#!/usr/bin/env python3
"""
classify_llm.py — раскладывает письма по бизнес-процессам с помощью Claude.

Шаг 1 (discover): берём случайную выборку тем и просим модель вывести таксономию
процессов, которые реально живут в этом ящике. Никаких заранее вшитых категорий —
у каждой компании они свои.

Шаг 2 (classify): прогоняем все входящие пачками через эту таксономию и получаем
на каждое письмо: процесс, срочность, можно ли автоматизировать, нужен ли человек.

Шаг 3 (report): сводка — какой процент потока какой процесс съедает и сколько
из этого механическая работа.

    export ANTHROPIC_API_KEY=sk-ant-...
    pip install anthropic

    python3 classify_llm.py export.ndjson --out ./out                # всё сразу
    python3 classify_llm.py export.ndjson --out ./out --step discover
    python3 classify_llm.py export.ndjson --out ./out --step classify --limit 2000

Прогон устойчив к обрывам: результаты пишутся построчно в classified.ndjson,
повторный запуск дообрабатывает только оставшееся.

Внимание: письма уходят в Claude API. Если в ящике есть данные, которые нельзя
отдавать наружу, — сначала отфильтруйте выгрузку.
"""

import argparse
import json
import os
import random
import sys
import threading
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed

try:
    import anthropic
except ImportError:
    print("Нужен пакет anthropic:  pip install anthropic", file=sys.stderr)
    raise SystemExit(1)

MODEL = "claude-opus-5"
MAX_TOKENS = 16000

# ───────────────────────────── схемы ответа ─────────────────────────────

TAXONOMY_SCHEMA = {
    "type": "object",
    "properties": {
        "categories": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "description": {"type": "string"},
                    "typical_subjects": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["name", "description", "typical_subjects"],
                "additionalProperties": False,
            },
        },
        "notes": {"type": "string"},
    },
    "required": ["categories", "notes"],
    "additionalProperties": False,
}

CLASSIFY_SCHEMA = {
    "type": "object",
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "i": {"type": "integer"},
                    "category": {"type": "string"},
                    "intent": {"type": "string"},
                    "urgency": {"type": "string", "enum": ["low", "medium", "high"]},
                    "automatable": {
                        "type": "string",
                        "enum": ["no", "partial", "full"],
                    },
                    "needs_human_judgment": {"type": "boolean"},
                },
                "required": ["i", "category", "intent", "urgency",
                             "automatable", "needs_human_judgment"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["items"],
    "additionalProperties": False,
}

# ───────────────────────────── промпты ─────────────────────────────

DISCOVER_SYSTEM = """\
Ты помогаешь владельцу компании разобраться, из чего состоит поток его рабочей почты.

Тебе дадут список тем писем и отправителей. Выведи таксономию бизнес-процессов,
которые за этими письмами стоят.

Требования к таксономии:
- 8–16 категорий, взаимоисключающих настолько, насколько это возможно;
- названия на русском, по смыслу процесса, а не по формулировке темы
  («Согласование договоров», а не «Re: договор»);
- обязательно отдельно вынеси автоматические уведомления и рассылки, если они есть;
- добавь категорию «Прочее» последней;
- description — одно предложение о том, что попадает в категорию и что не попадает.

Не придумывай процессы, которых в данных не видно."""

CLASSIFY_SYSTEM_TEMPLATE = """\
Ты классифицируешь рабочую почту компании по заранее заданным бизнес-процессам.

ТАКСОНОМИЯ (используй ТОЛЬКО эти названия категорий, дословно):
{taxonomy}

Для каждого письма верни:
- i — порядковый номер письма из входных данных (обязательно тот же);
- category — точное название категории из таксономии;
- intent — что от получателя хотят, 3–7 слов, на русском;
- urgency — low / medium / high, исходя из того, что произойдёт при задержке ответа
  на неделю: ничего (low), неудобство (medium), потеря денег или клиента (high);
- automatable — можно ли обработать письмо без участия человека:
  full   — ответ полностью выводится из шаблона или данных системы,
  partial— черновик готовит система, человек проверяет и отправляет,
  no     — нужно решение, переговоры или экспертное суждение;
- needs_human_judgment — true, если требуется решение, которое нельзя свести к правилу.

Отвечай по одному объекту на каждое входное письмо, ровно столько же элементов,
сколько было на входе. Не пропускай и не объединяй."""


# ───────────────────────────── ввод/вывод ─────────────────────────────


def load_messages(paths):
    out, seen = [], set()
    for path in paths:
        with open(path, "r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if rec.get("_meta"):
                    continue
                mid = rec.get("id")
                if not mid or mid in seen:
                    continue
                seen.add(mid)
                out.append(rec)
    return out


def sender(msg):
    frm = msg.get("from") or {}
    return (frm.get("email") or "").lower()


def compact(msg, index):
    """Минимальное представление письма для модели — без лишних токенов."""
    body = (msg.get("snippet") or msg.get("body_text") or "")[:280]
    return {
        "i": index,
        "from": sender(msg),
        "subject": (msg.get("subject") or "")[:180],
        "snippet": body,
    }


def load_done(path):
    done = set()
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as fh:
            for line in fh:
                try:
                    done.add(json.loads(line)["id"])
                except (json.JSONDecodeError, KeyError):
                    continue
    return done


# ───────────────────────────── вызовы Claude ─────────────────────────────


def call_claude(client, model, system, user_text, schema, effort="medium"):
    """Один запрос со строгой JSON-схемой. Возвращает распарсенный объект."""
    response = client.messages.create(
        model=model,
        max_tokens=MAX_TOKENS,
        system=system,
        output_config={"effort": effort, "format": {"type": "json_schema", "schema": schema}},
        messages=[{"role": "user", "content": user_text}],
    )
    if response.stop_reason == "refusal":
        details = getattr(response, "stop_details", None)
        raise RuntimeError(f"Модель отклонила запрос: {getattr(details, 'category', 'n/a')}")
    if response.stop_reason == "max_tokens":
        raise RuntimeError("Ответ обрезан по max_tokens — уменьшите --batch-size")
    text = next((b.text for b in response.content if b.type == "text"), "")
    return json.loads(text), response.usage


def discover_taxonomy(client, model, messages, sample_size):
    sample = random.sample(messages, min(sample_size, len(messages)))
    lines = []
    for msg in sample:
        subj = (msg.get("subject") or "").strip()[:140]
        if subj:
            lines.append(f"{sender(msg)} | {subj}")

    top_senders = Counter(sender(m) for m in messages if sender(m)).most_common(30)
    senders_block = "\n".join(f"{addr} — {n} писем" for addr, n in top_senders)

    user = (
        f"Всего писем в ящике: {len(messages)}.\n\n"
        f"Самые активные отправители:\n{senders_block}\n\n"
        f"Случайная выборка тем ({len(lines)} шт.):\n" + "\n".join(lines)
    )
    data, usage = call_claude(
        client, model,
        system=DISCOVER_SYSTEM,
        user_text=user,
        schema=TAXONOMY_SCHEMA,
        effort="high",
    )
    return data, usage


def classify_batch(client, model, taxonomy_text, batch, lock, out_fh, counters):
    payload = [compact(msg, idx) for idx, msg in enumerate(batch)]
    # Стабильный префикс системного промпта кэшируется — платим за таксономию один раз.
    system = [{
        "type": "text",
        "text": CLASSIFY_SYSTEM_TEMPLATE.format(taxonomy=taxonomy_text),
        "cache_control": {"type": "ephemeral"},
    }]
    user = "Письма для классификации:\n" + json.dumps(payload, ensure_ascii=False, indent=None)

    data, usage = call_claude(client, model, system, user, CLASSIFY_SCHEMA, effort="low")

    by_index = {item["i"]: item for item in data.get("items", [])}
    written = 0
    with lock:
        for idx, msg in enumerate(batch):
            item = by_index.get(idx)
            if not item:
                counters["missing"] += 1
                continue
            record = {
                "id": msg.get("id"),
                "date": msg.get("date"),
                "from": sender(msg),
                "subject": msg.get("subject"),
                "category": item["category"],
                "intent": item["intent"],
                "urgency": item["urgency"],
                "automatable": item["automatable"],
                "needs_human_judgment": item["needs_human_judgment"],
            }
            out_fh.write(json.dumps(record, ensure_ascii=False) + "\n")
            written += 1
        out_fh.flush()
        counters["done"] += written
        counters["in_tokens"] += usage.input_tokens
        counters["cached"] += getattr(usage, "cache_read_input_tokens", 0) or 0
        counters["out_tokens"] += usage.output_tokens
        print(f"  обработано {counters['done']}/{counters['total']} "
              f"(кэш: {counters['cached']} токенов)", flush=True)
    return written


# ───────────────────────────── сводка ─────────────────────────────


def build_report(out_dir, classified_path, taxonomy):
    rows = []
    with open(classified_path, "r", encoding="utf-8") as fh:
        for line in fh:
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    if not rows:
        print("Нечего сводить — classified.ndjson пуст.", file=sys.stderr)
        return

    total = len(rows)
    by_cat = Counter(r["category"] for r in rows)
    auto_by_cat = defaultdict(Counter)
    urgency_by_cat = defaultdict(Counter)
    senders_by_cat = defaultdict(Counter)
    for r in rows:
        auto_by_cat[r["category"]][r["automatable"]] += 1
        urgency_by_cat[r["category"]][r["urgency"]] += 1
        senders_by_cat[r["category"]][r["from"]] += 1

    weight = {"full": 1.0, "partial": 0.5, "no": 0.0}
    saved = sum(weight[r["automatable"]] for r in rows)

    lines = ["# Процессы в почте (классификация LLM)\n"]
    lines.append(f"Писем разобрано: **{total}**\n")
    lines.append(f"Потенциал автоматизации: **{saved / total * 100:.0f}%** потока "
                 f"(«full» = 1.0, «partial» = 0.5).\n")

    lines.append("## Процессы по объёму\n")
    lines.append("| Процесс | Писем | Доля | Автоматизируемо | Высокая срочность |")
    lines.append("|---|---|---|---|---|")
    for cat, count in by_cat.most_common():
        a = auto_by_cat[cat]
        auto_share = (a["full"] + 0.5 * a["partial"]) / count
        lines.append(
            f"| {cat} | {count} | {count / total * 100:.1f}% | "
            f"{auto_share * 100:.0f}% | {urgency_by_cat[cat]['high']} |"
        )
    lines.append("")

    lines.append("## Где искать выигрыш\n")
    ranked = sorted(
        by_cat.items(),
        key=lambda kv: (auto_by_cat[kv[0]]["full"] + 0.5 * auto_by_cat[kv[0]]["partial"]),
        reverse=True,
    )
    for cat, count in ranked[:6]:
        a = auto_by_cat[cat]
        gain = a["full"] + 0.5 * a["partial"]
        if gain < 3:
            continue
        top = ", ".join(f"{addr} ({n})" for addr, n in senders_by_cat[cat].most_common(3))
        lines.append(
            f"- **{cat}** — {count} писем, из них {a['full']} полностью и "
            f"{a['partial']} частично автоматизируемы (≈{gain:.0f} писем работы). "
            f"Основные источники: {top}."
        )
    lines.append("")

    lines.append("## Таксономия\n")
    for cat in taxonomy.get("categories", []):
        lines.append(f"- **{cat['name']}** — {cat['description']}")
    if taxonomy.get("notes"):
        lines.append(f"\n> {taxonomy['notes']}")
    lines.append("")

    path = os.path.join(out_dir, "processes.md")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print(f"Сводка: {path}")


# ───────────────────────────── точка входа ─────────────────────────────


def main():
    parser = argparse.ArgumentParser(description="Классификация писем по процессам через Claude")
    parser.add_argument("inputs", nargs="+", help="файлы .ndjson из mailru-export.js")
    parser.add_argument("--out", default="./out", help="каталог результатов")
    parser.add_argument("--step", choices=["discover", "classify", "report", "all"],
                        default="all")
    parser.add_argument("--model", default=MODEL)
    parser.add_argument("--sample", type=int, default=400,
                        help="размер выборки для построения таксономии")
    parser.add_argument("--batch-size", type=int, default=40, help="писем в одном запросе")
    parser.add_argument("--workers", type=int, default=4, help="параллельных запросов")
    parser.add_argument("--limit", type=int, default=0, help="сколько писем классифицировать (0 = все)")
    parser.add_argument("--incoming-only", action="store_true", default=True,
                        help="только входящие (по умолчанию да)")
    parser.add_argument("--all-messages", dest="incoming_only", action="store_false",
                        help="классифицировать и исходящие тоже")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    taxonomy_path = os.path.join(args.out, "taxonomy.json")
    classified_path = os.path.join(args.out, "classified.ndjson")

    messages = load_messages(args.inputs)
    if args.incoming_only:
        messages = [m for m in messages
                    if "отправл" not in (m.get("folder_name") or "").lower()
                    and "sent" not in (m.get("folder_name") or "").lower()]
    if not messages:
        print("Писем не найдено.", file=sys.stderr)
        return 1
    print(f"Писем на входе: {len(messages)}")

    client = anthropic.Anthropic()

    # ─── шаг 1: таксономия ───
    if args.step in ("discover", "all"):
        print("Строю таксономию процессов…")
        taxonomy, usage = discover_taxonomy(client, args.model, messages, args.sample)
        with open(taxonomy_path, "w", encoding="utf-8") as fh:
            json.dump(taxonomy, fh, ensure_ascii=False, indent=2)
        print(f"Категорий получилось: {len(taxonomy['categories'])} → {taxonomy_path}")
        for cat in taxonomy["categories"]:
            print(f"  · {cat['name']}")

    if args.step in ("classify", "report", "all"):
        if not os.path.exists(taxonomy_path):
            print("Нет taxonomy.json — сначала --step discover", file=sys.stderr)
            return 1
        with open(taxonomy_path, "r", encoding="utf-8") as fh:
            taxonomy = json.load(fh)

    # ─── шаг 2: классификация ───
    if args.step in ("classify", "all"):
        taxonomy_text = "\n".join(
            f"- {c['name']}: {c['description']}" for c in taxonomy["categories"]
        )
        done = load_done(classified_path)
        todo = [m for m in messages if m.get("id") not in done]
        if args.limit:
            todo = todo[:args.limit]
        print(f"К классификации: {len(todo)} (уже готово: {len(done)})")

        if todo:
            batches = [todo[i:i + args.batch_size]
                       for i in range(0, len(todo), args.batch_size)]
            lock = threading.Lock()
            counters = {"done": 0, "total": len(todo), "missing": 0,
                        "in_tokens": 0, "out_tokens": 0, "cached": 0}

            with open(classified_path, "a", encoding="utf-8") as out_fh:
                with ThreadPoolExecutor(max_workers=args.workers) as pool:
                    futures = [
                        pool.submit(classify_batch, client, args.model, taxonomy_text,
                                    batch, lock, out_fh, counters)
                        for batch in batches
                    ]
                    for future in as_completed(futures):
                        try:
                            future.result()
                        except Exception as err:      # пачка упала — остальные идут дальше
                            print(f"  ! пачка не обработана: {err}", file=sys.stderr)

            print(f"Готово. Токенов: вход {counters['in_tokens']} "
                  f"(из кэша {counters['cached']}), выход {counters['out_tokens']}. "
                  f"Пропущено моделью: {counters['missing']}")

    # ─── шаг 3: сводка ───
    if args.step in ("report", "all"):
        build_report(args.out, classified_path, taxonomy)

    return 0


if __name__ == "__main__":
    sys.exit(main())
