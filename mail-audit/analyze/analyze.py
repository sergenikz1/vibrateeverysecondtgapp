#!/usr/bin/env python3
"""
analyze.py — офлайн-анализ выгрузки почты (NDJSON из mailru-export.js).

Ничего никуда не отправляет, зависимостей нет — только стандартная библиотека.

    python3 analyze.py mailru-export-*.ndjson --out ./out
    python3 analyze.py export.ndjson --me me@company.ru,boss@company.ru --out ./out

На выходе в --out:
    report.md            человекочитаемый отчёт
    report.html          то же + графики (открыть в браузере)
    messages.csv         плоская таблица всех писем
    counterparties.csv   по каждому контрагенту: объём, скорость ответа, доля инициатив
    senders.csv          топ отправителей входящих
    domains.csv          топ доменов
    monthly.csv          помесячная динамика
    threads.csv          цепочки: длина, участники, длительность
    unanswered.csv       входящие без ответа (рабочий бэклог)
    recurring.csv        повторяющиеся темы — кандидаты на автоматизацию
"""

import argparse
import csv
import html
import json
import os
import re
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone

# ────────────────────────────── загрузка ──────────────────────────────


def parse_date(value):
    if not value:
        return None
    try:
        text = value.replace("Z", "+00:00")
        dt = datetime.fromisoformat(text)
    except (ValueError, AttributeError):
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def load(paths):
    """Читает NDJSON-файлы, возвращает (сообщения, метаданные)."""
    messages, meta = [], {}
    seen_ids = set()
    for path in paths:
        with open(path, "r", encoding="utf-8") as fh:
            for line_no, line in enumerate(fh, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except json.JSONDecodeError:
                    print(f"  ! {os.path.basename(path)}:{line_no} — битая строка, пропускаю",
                          file=sys.stderr)
                    continue
                if rec.get("_meta"):
                    meta.update(rec)
                    continue
                mid = rec.get("id")
                if not mid or mid in seen_ids:
                    continue
                seen_ids.add(mid)
                rec["_dt"] = parse_date(rec.get("date"))
                messages.append(rec)
    messages = [m for m in messages if m["_dt"]]
    messages.sort(key=lambda m: m["_dt"])
    return messages, meta


# ────────────────────────── определение "своих" ──────────────────────────

SENT_FOLDER_RE = re.compile(r"отправл|sent|исходя", re.I)
DRAFT_FOLDER_RE = re.compile(r"чернов|draft", re.I)
SPAM_FOLDER_RE = re.compile(r"спам|spam|junk|корзин|trash|удал", re.I)


def addr_of(msg, field="from"):
    value = msg.get(field)
    if isinstance(value, dict):
        return (value.get("email") or "").lower()
    return ""


def recipients(msg):
    out = []
    for field in ("to", "cc"):
        for addr in msg.get(field) or []:
            email = (addr.get("email") or "").lower()
            if email:
                out.append(email)
    return out


def infer_own_addresses(messages, explicit):
    """Свои адреса: заданные явно, либо выведенные из папки «Отправленные»."""
    if explicit:
        return {a.strip().lower() for a in explicit.split(",") if a.strip()}

    counter = Counter()
    for msg in messages:
        folder = msg.get("folder_name") or ""
        if SENT_FOLDER_RE.search(folder):
            sender = addr_of(msg)
            if sender:
                counter[sender] += 1
    if counter:
        top = counter.most_common(1)[0][1]
        # берём все адреса, дающие хотя бы 5% исходящих — это ящик + алиасы
        return {a for a, n in counter.items() if n >= max(1, top * 0.05)}

    # запасной вариант: самый частый получатель входящих
    fallback = Counter()
    for msg in messages:
        for addr in recipients(msg):
            fallback[addr] += 1
    return {fallback.most_common(1)[0][0]} if fallback else set()


def classify_direction(msg, own):
    folder = msg.get("folder_name") or ""
    if DRAFT_FOLDER_RE.search(folder) or (msg.get("flags") or {}).get("draft"):
        return "draft"
    if SENT_FOLDER_RE.search(folder):
        return "out"
    sender = addr_of(msg)
    if sender and sender in own:
        return "out"
    return "in"


# ───────────────────────────── эвристики ─────────────────────────────

AUTOMATED_LOCAL_RE = re.compile(
    r"^(no-?reply|donotreply|do-not-reply|notification|notifications|noticias|"
    r"robot|mailer|mailer-daemon|bounce|bot|auto|automated|alerts?|digest|"
    r"news(letter)?s?|billing|invoice|receipts?|updates?|info|support|help|"
    r"service|system|admin|webmaster|postmaster)([._-]|$)",
    re.I,
)

REPLY_PREFIX_RE = re.compile(
    r"^\s*(re|re\[\d+\]|fwd?|fw|ответ|отв|пересылка|переслано)\s*[:\]]\s*", re.I
)


def normalize_subject(subject):
    text = subject or ""
    prev = None
    while prev != text:
        prev = text
        text = REPLY_PREFIX_RE.sub("", text)
    # даты — до чисел, иначе год «2026» съест числовая маска
    text = re.sub(r"\b\d{1,2}[./-]\d{1,2}(?:[./-]\d{2,4})?\b", " <date> ", text)
    text = re.sub(r"[#№]?\s*\b\d{3,}\b", " <num> ", text)      # номера заявок/счетов
    text = re.sub(r"\s+", " ", text).strip()
    return text.lower()


def domain_of(email):
    return email.split("@", 1)[1] if "@" in email else ""


def is_automated_sender(email, reply_rate, volume, use_reply_signal=True):
    """Робот или живой человек.

    Основной признак — маска адреса. Нулевая доля ответов — признак вторичный
    и годится, только если в выборке вообще есть исходящие: в выгрузке одной
    папки «Входящие» не отвечено вообще ничего, и без этой оговорки роботами
    оказались бы все.
    """
    local = email.split("@", 1)[0] if "@" in email else email
    if AUTOMATED_LOCAL_RE.match(local):
        return True
    return use_reply_signal and volume >= 5 and reply_rate == 0.0


def fmt_duration(seconds):
    if seconds is None:
        return "—"
    if seconds < 3600:
        return f"{seconds / 60:.0f} мин"
    if seconds < 86400:
        return f"{seconds / 3600:.1f} ч"
    return f"{seconds / 86400:.1f} дн"


def percentile(values, q):
    if not values:
        return None
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, int(round((len(ordered) - 1) * q))))
    return ordered[idx]


# ───────────────────────────── аналитика ─────────────────────────────


def build_threads(messages):
    threads = defaultdict(list)
    for msg in messages:
        key = msg.get("thread_id") or ("subj:" + (normalize_subject(msg.get("subject")) or msg["id"]))
        threads[str(key)].append(msg)
    for items in threads.values():
        items.sort(key=lambda m: m["_dt"])
    return threads


def analyse(messages, own, stale_days):
    now = max(m["_dt"] for m in messages)
    for msg in messages:
        msg["_dir"] = classify_direction(msg, own)
        msg["_spam"] = bool(SPAM_FOLDER_RE.search(msg.get("folder_name") or ""))

    live = [m for m in messages if not m["_spam"] and m["_dir"] != "draft"]
    incoming = [m for m in live if m["_dir"] == "in"]
    outgoing = [m for m in live if m["_dir"] == "out"]

    threads = build_threads(live)

    # время ответа: для каждого входящего — ближайшее исходящее в той же цепочке
    reply_times = []                       # секунды, все ответы
    per_partner_replies = defaultdict(list)
    unanswered = []

    for items in threads.values():
        for idx, msg in enumerate(items):
            if msg["_dir"] != "in":
                continue
            nxt = next((m for m in items[idx + 1:] if m["_dir"] == "out"), None)
            partner = addr_of(msg)
            if nxt:
                delta = (nxt["_dt"] - msg["_dt"]).total_seconds()
                if 0 <= delta <= 90 * 86400:
                    reply_times.append(delta)
                    if partner:
                        per_partner_replies[partner].append(delta)
            elif (now - msg["_dt"]).total_seconds() > stale_days * 86400:
                unanswered.append(msg)

    # контрагенты
    partners = defaultdict(lambda: {
        "in": 0, "out": 0, "first": None, "last": None,
        "subjects": Counter(), "attachments": 0,
    })
    for msg in incoming:
        addr = addr_of(msg)
        if not addr or addr in own:
            continue
        rec = partners[addr]
        rec["in"] += 1
        rec["first"] = min(rec["first"] or msg["_dt"], msg["_dt"])
        rec["last"] = max(rec["last"] or msg["_dt"], msg["_dt"])
        rec["subjects"][normalize_subject(msg.get("subject"))] += 1
        rec["attachments"] += 1 if msg.get("has_attachments") else 0
    for msg in outgoing:
        for addr in recipients(msg):
            if addr in own:
                continue
            rec = partners[addr]
            rec["out"] += 1
            rec["first"] = min(rec["first"] or msg["_dt"], msg["_dt"])
            rec["last"] = max(rec["last"] or msg["_dt"], msg["_dt"])

    # если исходящих в выгрузке почти нет, доля ответов ничего не значит
    use_reply_signal = len(outgoing) >= 20 and len(outgoing) >= 0.02 * len(incoming)
    for addr, rec in partners.items():
        deltas = per_partner_replies.get(addr, [])
        rec["reply_median"] = statistics.median(deltas) if deltas else None
        rec["reply_rate"] = (rec["out"] / rec["in"]) if rec["in"] else 0.0
        rec["automated"] = is_automated_sender(
            addr, rec["reply_rate"], rec["in"], use_reply_signal
        )

    # временные разрезы
    by_month = Counter()
    by_month_dir = defaultdict(lambda: {"in": 0, "out": 0})
    by_weekday = Counter()
    by_hour = Counter()
    after_hours = 0
    for msg in live:
        key = msg["_dt"].strftime("%Y-%m")
        by_month[key] += 1
        by_month_dir[key][msg["_dir"]] += 1
        by_weekday[msg["_dt"].weekday()] += 1
        by_hour[msg["_dt"].hour] += 1
        if msg["_dt"].weekday() >= 5 or msg["_dt"].hour < 8 or msg["_dt"].hour >= 20:
            after_hours += 1

    # повторяющиеся темы (кандидаты на шаблон/автоматизацию)
    recurring = Counter()
    recurring_sample = {}
    for msg in incoming:
        norm = normalize_subject(msg.get("subject"))
        if len(norm) < 6:
            continue
        recurring[norm] += 1
        recurring_sample.setdefault(norm, msg.get("subject") or "")

    automated_in = sum(1 for m in incoming
                       if partners.get(addr_of(m), {}).get("automated"))

    return {
        "now": now,
        "messages": live,
        "incoming": incoming,
        "outgoing": outgoing,
        "threads": threads,
        "partners": partners,
        "reply_times": reply_times,
        "unanswered": unanswered,
        "by_month": by_month,
        "by_month_dir": by_month_dir,
        "by_weekday": by_weekday,
        "by_hour": by_hour,
        "after_hours": after_hours,
        "recurring": recurring,
        "recurring_sample": recurring_sample,
        "automated_in": automated_in,
        "spam": [m for m in messages if m["_spam"]],
        "drafts": [m for m in messages if m["_dir"] == "draft"],
    }


# ───────────────────────────── экспорт CSV ─────────────────────────────


def write_csv(path, header, rows):
    with open(path, "w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.writer(fh, delimiter=";")
        writer.writerow(header)
        writer.writerows(rows)


def export_csvs(out_dir, data, own):
    msgs = data["messages"]

    write_csv(
        os.path.join(out_dir, "messages.csv"),
        ["id", "date", "direction", "folder", "from", "to", "subject",
         "attachments", "read", "thread_id"],
        [[
            m["id"], m["_dt"].isoformat(), m["_dir"], m.get("folder_name") or "",
            addr_of(m), "|".join(recipients(m)), (m.get("subject") or "")[:300],
            "1" if m.get("has_attachments") else "0",
            "1" if (m.get("flags") or {}).get("read") else "0",
            m.get("thread_id") or "",
        ] for m in msgs],
    )

    partners = sorted(data["partners"].items(),
                      key=lambda kv: kv[1]["in"] + kv[1]["out"], reverse=True)
    write_csv(
        os.path.join(out_dir, "counterparties.csv"),
        ["email", "domain", "incoming", "outgoing", "reply_rate",
         "reply_median_hours", "first_contact", "last_contact", "automated"],
        [[
            addr, domain_of(addr), rec["in"], rec["out"], f"{rec['reply_rate']:.2f}",
            f"{rec['reply_median'] / 3600:.1f}" if rec["reply_median"] else "",
            rec["first"].date().isoformat() if rec["first"] else "",
            rec["last"].date().isoformat() if rec["last"] else "",
            "1" if rec["automated"] else "0",
        ] for addr, rec in partners],
    )

    senders = Counter(addr_of(m) for m in data["incoming"] if addr_of(m))
    write_csv(
        os.path.join(out_dir, "senders.csv"),
        ["email", "incoming", "share_pct"],
        [[addr, n, f"{n * 100 / max(1, len(data['incoming'])):.2f}"]
         for addr, n in senders.most_common()],
    )

    domains = Counter(domain_of(addr_of(m)) for m in data["incoming"] if addr_of(m))
    write_csv(
        os.path.join(out_dir, "domains.csv"),
        ["domain", "incoming"],
        [[d, n] for d, n in domains.most_common() if d],
    )

    write_csv(
        os.path.join(out_dir, "monthly.csv"),
        ["month", "total", "incoming", "outgoing"],
        [[month, data["by_month"][month], data["by_month_dir"][month]["in"],
          data["by_month_dir"][month]["out"]]
         for month in sorted(data["by_month"])],
    )

    thread_rows = []
    for key, items in data["threads"].items():
        span = (items[-1]["_dt"] - items[0]["_dt"]).total_seconds()
        thread_rows.append([
            key, len(items),
            sum(1 for m in items if m["_dir"] == "in"),
            sum(1 for m in items if m["_dir"] == "out"),
            f"{span / 86400:.1f}",
            (items[0].get("subject") or "")[:200],
            items[0]["_dt"].date().isoformat(),
            items[-1]["_dt"].date().isoformat(),
        ])
    thread_rows.sort(key=lambda r: r[1], reverse=True)
    write_csv(
        os.path.join(out_dir, "threads.csv"),
        ["thread_key", "messages", "incoming", "outgoing", "span_days",
         "subject", "started", "ended"],
        thread_rows,
    )

    write_csv(
        os.path.join(out_dir, "unanswered.csv"),
        ["date", "days_waiting", "from", "subject", "folder"],
        [[
            m["_dt"].date().isoformat(),
            f"{(data['now'] - m['_dt']).total_seconds() / 86400:.0f}",
            addr_of(m), (m.get("subject") or "")[:200], m.get("folder_name") or "",
        ] for m in sorted(data["unanswered"], key=lambda m: m["_dt"])],
    )

    write_csv(
        os.path.join(out_dir, "recurring.csv"),
        ["normalized_subject", "count", "example"],
        [[norm, n, data["recurring_sample"].get(norm, "")[:200]]
         for norm, n in data["recurring"].most_common(500) if n >= 3],
    )


# ───────────────────────────── отчёты ─────────────────────────────


def build_summary(data, own, meta):
    msgs = data["messages"]
    first, last = msgs[0]["_dt"], msgs[-1]["_dt"]
    days = max(1, (last - first).days)
    reply = data["reply_times"]
    active_partners = {a: r for a, r in data["partners"].items() if not r["automated"]}

    return {
        "account": meta.get("account") or ", ".join(sorted(own)) or "—",
        "own": sorted(own),
        "total": len(msgs),
        "incoming": len(data["incoming"]),
        "outgoing": len(data["outgoing"]),
        "drafts": len(data["drafts"]),
        "spam": len(data["spam"]),
        "first": first,
        "last": last,
        "days": days,
        "per_day": len(msgs) / days,
        "in_per_day": len(data["incoming"]) / days,
        "threads": len(data["threads"]),
        "partners_total": len(data["partners"]),
        "partners_human": len(active_partners),
        "domains": len({domain_of(a) for a in data["partners"] if domain_of(a)}),
        "reply_median": statistics.median(reply) if reply else None,
        "reply_p90": percentile(reply, 0.9),
        "reply_count": len(reply),
        "answered_share": len(reply) / max(1, len(data["incoming"])),
        "unanswered": len(data["unanswered"]),
        "automated_in": data["automated_in"],
        "automated_share": data["automated_in"] / max(1, len(data["incoming"])),
        "after_hours": data["after_hours"],
        "after_hours_share": data["after_hours"] / max(1, len(msgs)),
        "attachments": sum(1 for m in msgs if m.get("has_attachments")),
    }


WEEKDAYS = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"]


def top_partners(data, n=25):
    rows = [(a, r) for a, r in data["partners"].items() if not r["automated"]]
    rows.sort(key=lambda kv: kv[1]["in"] + kv[1]["out"], reverse=True)
    return rows[:n]


def write_markdown(path, data, summary):
    lines = []
    add = lines.append

    add("# Аудит почты\n")
    add(f"**Ящик:** {summary['account']}  ")
    add(f"**Период:** {summary['first'].date()} — {summary['last'].date()} "
        f"({summary['days']} дней)  ")
    add(f"**Писем в выборке:** {summary['total']:,}".replace(",", " ") + "\n")

    add("## 1. Что вообще происходит\n")
    add("| Метрика | Значение |")
    add("|---|---|")
    add(f"| Всего писем | {summary['total']} |")
    add(f"| Входящих | {summary['incoming']} ({summary['in_per_day']:.1f} в день) |")
    add(f"| Исходящих | {summary['outgoing']} |")
    add(f"| Черновиков | {summary['drafts']} |")
    add(f"| В спаме/корзине (исключены из анализа) | {summary['spam']} |")
    add(f"| Цепочек (тредов) | {summary['threads']} |")
    add(f"| Контрагентов всего | {summary['partners_total']} |")
    add(f"| Из них живых людей (не рассылки) | {summary['partners_human']} |")
    add(f"| Доменов | {summary['domains']} |")
    add(f"| Писем с вложениями | {summary['attachments']} |")
    add("")

    add("## 2. Скорость реакции\n")
    add("| Метрика | Значение |")
    add("|---|---|")
    add(f"| Медиана времени ответа | {fmt_duration(summary['reply_median'])} |")
    add(f"| 90-й перцентиль | {fmt_duration(summary['reply_p90'])} |")
    add(f"| Доля входящих, на которые ответили | {summary['answered_share'] * 100:.0f}% |")
    add(f"| Входящих без ответа (бэклог) | {summary['unanswered']} |")
    add(f"| Писем вне рабочего времени | {summary['after_hours']} "
        f"({summary['after_hours_share'] * 100:.0f}%) |")
    add("")

    add("## 3. Структура нагрузки\n")
    add(f"- Автоматических рассылок и уведомлений среди входящих: "
        f"**{summary['automated_in']}** ({summary['automated_share'] * 100:.0f}%). "
        f"Это шум, который можно увести в отдельную папку/дайджест.")
    add(f"- Реальный человеческий поток: "
        f"**{summary['incoming'] - summary['automated_in']}** входящих "
        f"от {summary['partners_human']} корреспондентов.")
    add("")

    add("### Помесячно\n")
    add("| Месяц | Всего | Входящих | Исходящих |")
    add("|---|---|---|---|")
    for month in sorted(data["by_month"]):
        d = data["by_month_dir"][month]
        add(f"| {month} | {data['by_month'][month]} | {d['in']} | {d['out']} |")
    add("")

    add("### По дням недели\n")
    add("| День | Писем |")
    add("|---|---|")
    for i in range(7):
        add(f"| {WEEKDAYS[i]} | {data['by_weekday'].get(i, 0)} |")
    add("")

    add("### По часам\n")
    add("| Час | Писем |")
    add("|---|---|")
    for hour in range(24):
        add(f"| {hour:02d}:00 | {data['by_hour'].get(hour, 0)} |")
    add("")

    add("## 4. Ключевые контрагенты\n")
    add("| Адрес | Входящих | Исходящих | Медиана ответа | Последний контакт |")
    add("|---|---|---|---|---|")
    for addr, rec in top_partners(data):
        add(f"| {addr} | {rec['in']} | {rec['out']} | "
            f"{fmt_duration(rec['reply_median'])} | "
            f"{rec['last'].date() if rec['last'] else '—'} |")
    add("")

    add("## 5. Повторяющиеся темы — кандидаты на автоматизацию\n")
    add("Одинаковые по смыслу письма, приходящие снова и снова. "
        "Каждая такая строка — потенциальный шаблон ответа, форма или интеграция.\n")
    add("| Тема (нормализованная) | Раз |")
    add("|---|---|")
    for norm, count in data["recurring"].most_common(30):
        if count < 3:
            continue
        add(f"| {norm[:110]} | {count} |")
    add("")

    add("## 6. Бэклог: входящие без ответа\n")
    if not data["unanswered"]:
        add("Пусто — всё отвечено.\n")
    else:
        add("| Дата | Ждёт, дней | От кого | Тема |")
        add("|---|---|---|---|")
        stale = sorted(data["unanswered"], key=lambda m: m["_dt"])[:50]
        for msg in stale:
            waiting = (data["now"] - msg["_dt"]).total_seconds() / 86400
            add(f"| {msg['_dt'].date()} | {waiting:.0f} | {addr_of(msg)} | "
                f"{(msg.get('subject') or '')[:80]} |")
        add("")

    add("## 7. Куда смотреть дальше\n")
    add("1. `recurring.csv` — самые частые повторы. Если тема встречается "
        "десятки раз, её обрабатывает человек, а должен обрабатывать шаблон или бот.")
    add("2. `counterparties.csv` — сортируйте по `incoming`: топ-10 адресов "
        "обычно дают половину нагрузки. Для них имеет смысл отдельный канал.")
    add("3. `unanswered.csv` — это буквально список невыполненных обязательств.")
    add("4. `threads.csv` — длинные цепочки означают, что вопрос не решается "
        "с первого раза: либо не хватает данных в первом письме, либо нужен другой канал.")
    add("5. Запустите `classify_llm.py`, чтобы разложить письма по бизнес-процессам "
        "и оценить, какая доля потока автоматизируема.\n")

    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))


def svg_bars(pairs, width=760, height=200, color="#7c5cff"):
    """Простая столбчатая диаграмма без внешних библиотек."""
    if not pairs:
        return ""
    top = max(v for _, v in pairs) or 1
    n = len(pairs)
    pad_left, pad_bottom = 4, 22
    slot = (width - pad_left * 2) / n
    bar_w = max(2.0, slot * 0.7)
    parts = [f'<svg viewBox="0 0 {width} {height}" role="img" preserveAspectRatio="none">']
    for i, (label, value) in enumerate(pairs):
        h = (value / top) * (height - pad_bottom - 6)
        x = pad_left + i * slot + (slot - bar_w) / 2
        y = height - pad_bottom - h
        parts.append(
            f'<rect x="{x:.1f}" y="{y:.1f}" width="{bar_w:.1f}" height="{h:.1f}" '
            f'rx="2" fill="{color}"><title>{html.escape(str(label))}: {value}</title></rect>'
        )
        if n <= 26 or i % max(1, n // 12) == 0:
            parts.append(
                f'<text x="{x + bar_w / 2:.1f}" y="{height - 7}" font-size="10" '
                f'text-anchor="middle" fill="currentColor" opacity="0.6">'
                f'{html.escape(str(label))}</text>'
            )
    parts.append("</svg>")
    return "".join(parts)


def write_html(path, data, summary):
    def esc(v):
        return html.escape(str(v))

    def kpi(label, value, hint=""):
        return (f'<div class="kpi"><div class="kpi-v">{esc(value)}</div>'
                f'<div class="kpi-l">{esc(label)}</div>'
                f'{f"<div class=hint>{esc(hint)}</div>" if hint else ""}</div>')

    months = sorted(data["by_month"])
    month_pairs = [(m[2:], data["by_month"][m]) for m in months]
    hour_pairs = [(f"{h:02d}", data["by_hour"].get(h, 0)) for h in range(24)]
    weekday_pairs = [(WEEKDAYS[i], data["by_weekday"].get(i, 0)) for i in range(7)]

    partner_rows = "".join(
        f"<tr><td>{esc(a)}</td><td class=n>{r['in']}</td><td class=n>{r['out']}</td>"
        f"<td class=n>{esc(fmt_duration(r['reply_median']))}</td>"
        f"<td class=n>{esc(r['last'].date() if r['last'] else '—')}</td></tr>"
        for a, r in top_partners(data, 25)
    )
    recurring_rows = "".join(
        f"<tr><td>{esc(norm[:120])}</td><td class=n>{c}</td></tr>"
        for norm, c in data["recurring"].most_common(25) if c >= 3
    )
    unanswered_rows = "".join(
        f"<tr><td>{esc(m['_dt'].date())}</td>"
        f"<td class=n>{(data['now'] - m['_dt']).total_seconds() / 86400:.0f}</td>"
        f"<td>{esc(addr_of(m))}</td><td>{esc((m.get('subject') or '')[:90])}</td></tr>"
        for m in sorted(data["unanswered"], key=lambda m: m["_dt"])[:40]
    ) or "<tr><td colspan=4>Пусто — всё отвечено.</td></tr>"

    doc = f"""<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Аудит почты — {esc(summary['account'])}</title>
<style>
  :root {{ --bg:#ffffff; --fg:#16151a; --muted:#6b6a75; --line:#e6e4ee; --card:#f7f6fb; --accent:#7c5cff; }}
  @media (prefers-color-scheme: dark) {{
    :root {{ --bg:#111015; --fg:#eceaf3; --muted:#9a97a8; --line:#2a2833; --card:#1a1922; --accent:#9d86ff; }}
  }}
  :root[data-theme="dark"] {{ --bg:#111015; --fg:#eceaf3; --muted:#9a97a8; --line:#2a2833; --card:#1a1922; --accent:#9d86ff; }}
  :root[data-theme="light"] {{ --bg:#ffffff; --fg:#16151a; --muted:#6b6a75; --line:#e6e4ee; --card:#f7f6fb; --accent:#7c5cff; }}
  body {{ background:var(--bg); color:var(--fg); font:15px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
         margin:0; padding:32px 20px 80px; }}
  .wrap {{ max-width:900px; margin:0 auto; }}
  h1 {{ font-size:26px; margin:0 0 4px; letter-spacing:-.02em; }}
  h2 {{ font-size:17px; margin:40px 0 12px; letter-spacing:-.01em; }}
  .sub {{ color:var(--muted); margin-bottom:28px; }}
  .kpis {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:10px; }}
  .kpi {{ background:var(--card); border:1px solid var(--line); border-radius:10px; padding:14px 16px; }}
  .kpi-v {{ font-size:24px; font-weight:600; letter-spacing:-.02em; }}
  .kpi-l {{ color:var(--muted); font-size:13px; margin-top:2px; }}
  .hint {{ color:var(--muted); font-size:11px; margin-top:4px; opacity:.8; }}
  .chart {{ background:var(--card); border:1px solid var(--line); border-radius:10px;
            padding:12px; color:var(--fg); overflow-x:auto; }}
  .chart svg {{ display:block; width:100%; height:200px; min-width:320px; }}
  .scroll {{ overflow-x:auto; }}
  table {{ border-collapse:collapse; width:100%; font-size:13.5px; }}
  th,td {{ text-align:left; padding:7px 10px; border-bottom:1px solid var(--line); }}
  th {{ color:var(--muted); font-weight:500; font-size:12px; text-transform:uppercase; letter-spacing:.04em; }}
  td.n {{ text-align:right; font-variant-numeric:tabular-nums; white-space:nowrap; }}
  code {{ background:var(--card); padding:1px 5px; border-radius:4px; font-size:12.5px; }}
</style></head><body><div class="wrap">

<h1>Аудит почты</h1>
<div class="sub">{esc(summary['account'])} · {esc(summary['first'].date())} — {esc(summary['last'].date())}
 · {summary['days']} дней</div>

<div class="kpis">
  {kpi('писем всего', summary['total'])}
  {kpi('входящих', summary['incoming'], f"{summary['in_per_day']:.1f} в день")}
  {kpi('исходящих', summary['outgoing'])}
  {kpi('медиана ответа', fmt_duration(summary['reply_median']), f"p90 {fmt_duration(summary['reply_p90'])}")}
  {kpi('отвечено', f"{summary['answered_share'] * 100:.0f}%")}
  {kpi('без ответа', summary['unanswered'], 'бэклог')}
  {kpi('рассылок', f"{summary['automated_share'] * 100:.0f}%", 'шум во входящих')}
  {kpi('вне рабочих часов', f"{summary['after_hours_share'] * 100:.0f}%")}
  {kpi('контрагентов', summary['partners_human'], 'без учёта роботов')}
  {kpi('цепочек', summary['threads'])}
</div>

<h2>Динамика по месяцам</h2>
<div class="chart">{svg_bars(month_pairs)}</div>

<h2>Распределение по часам суток</h2>
<div class="chart">{svg_bars(hour_pairs)}</div>

<h2>По дням недели</h2>
<div class="chart">{svg_bars(weekday_pairs)}</div>

<h2>Ключевые контрагенты</h2>
<div class="scroll"><table>
<tr><th>Адрес</th><th>Входящих</th><th>Исходящих</th><th>Медиана ответа</th><th>Последний контакт</th></tr>
{partner_rows}
</table></div>

<h2>Повторяющиеся темы</h2>
<p class="sub">Кандидаты на шаблон, форму или интеграцию — эти письма приходят снова и снова.</p>
<div class="scroll"><table>
<tr><th>Тема (нормализованная)</th><th>Раз</th></tr>
{recurring_rows}
</table></div>

<h2>Входящие без ответа</h2>
<div class="scroll"><table>
<tr><th>Дата</th><th>Ждёт, дней</th><th>От кого</th><th>Тема</th></tr>
{unanswered_rows}
</table></div>

<h2>Дальше</h2>
<p>Полные таблицы — в CSV рядом с этим файлом: <code>counterparties.csv</code>,
<code>recurring.csv</code>, <code>unanswered.csv</code>, <code>threads.csv</code>.
Разложить поток по бизнес-процессам — <code>classify_llm.py</code>.</p>

</div></body></html>"""

    with open(path, "w", encoding="utf-8") as fh:
        fh.write(doc)


# ───────────────────────────── точка входа ─────────────────────────────


def main():
    parser = argparse.ArgumentParser(description="Анализ выгрузки почты (NDJSON)")
    parser.add_argument("inputs", nargs="+", help="файлы .ndjson из mailru-export.js")
    parser.add_argument("--out", default="./out", help="каталог для отчётов")
    parser.add_argument("--me", default=None,
                        help="свои адреса через запятую (иначе определятся автоматически)")
    parser.add_argument("--stale-days", type=float, default=3.0,
                        help="через сколько дней входящее считается «без ответа»")
    args = parser.parse_args()

    messages, meta = load(args.inputs)
    if not messages:
        print("Не нашёл ни одного письма с корректной датой.", file=sys.stderr)
        return 1

    own = infer_own_addresses(messages, args.me)
    if not own:
        print("Не удалось определить собственные адреса — укажите --me me@example.ru",
              file=sys.stderr)
        return 1

    print(f"Писем загружено: {len(messages)}")
    print(f"Свои адреса: {', '.join(sorted(own))}")

    data = analyse(messages, own, args.stale_days)
    summary = build_summary(data, own, meta)

    os.makedirs(args.out, exist_ok=True)
    export_csvs(args.out, data, own)
    write_markdown(os.path.join(args.out, "report.md"), data, summary)
    write_html(os.path.join(args.out, "report.html"), data, summary)

    print(f"\nГотово. Отчёты в {os.path.abspath(args.out)}:")
    for name in sorted(os.listdir(args.out)):
        print("  " + name)
    print(f"\nКоротко: {summary['incoming']} входящих за {summary['days']} дней, "
          f"медиана ответа {fmt_duration(summary['reply_median'])}, "
          f"{summary['unanswered']} писем без ответа, "
          f"{summary['automated_share'] * 100:.0f}% входящих — рассылки.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
