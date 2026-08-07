# Удалённый доступ к кодинг-агентам с телефона: ccpocket + Tailscale

Документ по итогам аудита [K9i-0/ccpocket](https://github.com/K9i-0/ccpocket)
(проверялась версия бриджа `@ccpocket/bridge` **1.69.6**, коммит `46b2de3`).

Хост — Linux, доступ снаружи — через Tailscale.

---

## 1. Результат проверки безопасности

Бэкдоров не найдено. Ниже — что именно проверялось и что стоит учитывать.

### Чисто

| Проверка | Результат |
|---|---|
| `eval` / `new Function` / `atob` / длинные base64-блобы | нет ни в исходниках, ни в опубликованном `dist` |
| `postinstall` / `preinstall` хуки в npm-пакете | нет (`prepare` есть только в корневом workspace-package.json, в публикуемый пакет не входит) |
| Чтение `~/.ssh`, `~/.aws`, `.env`, keychain | нет |
| Отправка кода / диффов / файлов на сторонние серверы | нет |
| Исходящие адреса в опубликованном `dist` | только Firebase (`identitytoolkit`, `securetoken`), собственный релей `us-central1-ccpocket-ca33b.cloudfunctions.net/relay` и ссылки в текстах ошибок |
| Соответствие npm-пакета исходникам | набор URL в `dist` совпадает с `src`, обфускации нет |
| Цепочка поставки | публикация из GitHub Actions с `--provenance` (OIDC), sigstore-аттестация валидна, экшены запинены по SHA |
| Cloud Function релея | проверяет Firebase ID-token, `bridgeId` из тела запроса игнорируется (берётся UID из токена), FCM-токены хранятся как SHA-256, есть rate-limit и лимит 20 токенов на бридж, тело пуша **не** пишется в Firestore |
| Правила Firestore | доступ только к своей подколлекции токенов, остальное `deny` |
| mDNS | в TXT-записи публикуется только `auth: required\|none`, сам API-ключ не светится |
| Автор | один мейнтейнер (K9i), ~1800 коммитов, история без подозрительных бинарников |

### Что важно знать (это не бэкдоры, это дефолты и дизайн)

1. **По умолчанию бридж слушает `0.0.0.0` без аутентификации.**
   `packages/bridge/src/index.ts:32-33`: `BRIDGE_HOST` по умолчанию `0.0.0.0`,
   `BRIDGE_API_KEY` не задан. Проверка ключа (`websocket.ts:851-860`) выполняется
   только если ключ задан. То есть на дефолтах любой в той же сети (кафе,
   коворкинг, отель) может подключиться к WebSocket и запускать агента с вашими
   правами и вашими токенами. Лечится пунктом 3 этого гайда.

2. **HTTP-эндпоинты не закрыты API-ключом вообще.** `/health`, `/version`,
   `/usage`, `/doctor`, раздача картинок и `POST /api/gallery/upload`
   обрабатываются до WebSocket-слоя и ключ не проверяют (`index.ts:134-212`).
   `/doctor` отдаёт разведданные о машине (какие CLI стоят, есть ли креды —
   сами креды не отдаёт). Плюс `Access-Control-Allow-Origin: *`.

3. **У WebSocket нет проверки `Origin`.** Браузеры на WebSocket не применяют
   CORS, поэтому пока ключ не задан, любая открытая в браузере страница может
   подключиться к `ws://localhost:8765` и управлять агентом. Закрывается тем же
   API-ключом + биндом на Tailscale-адрес (тогда `localhost` не слушается).

4. **Пуш-релей включается сам, без спроса.** При старте бридж создаёт анонимный
   Firebase-аккаунт и пишет `~/.ccpocket/firebase-credentials.json`
   (`index.ts:66-75`). Флага «выключить» нет. В пуше на Google FCM уходит:
   имя сессии, имя проекта, тип события и **до 120 символов текста результата
   или ошибки агента** (`websocket.ts:7598-7643`). В приложении есть privacy mode
   — он заменяет это на обезличенный текст (`websocket.ts:7509-7510`).
   При этом `SECURITY.md` проекта утверждает «no data is sent to external
   servers» — это утверждение неточное.

5. **Документация про авторизацию Claude устарела.** `docs/auth-troubleshooting.md`
   всё ещё описывает вход через `/login`, тогда как код (`sdk-process.ts:374-392`)
   OAuth-подписку отклоняет и требует `ANTHROPIC_API_KEY`.

**Вывод:** код можно запускать, но не на дефолтных настройках. Дефолт
«0.0.0.0 без пароля» — главный риск, и он полностью снимается настройкой ниже.

---

## 2. Про подписку Claude

Коротко: **через ccpocket подписка Claude (Pro/Max) не заработает.**

Бридж для Claude использует Claude Agent SDK, и вход по подписке там отключён
намеренно (`packages/bridge/src/sdk-process.ts:374-392`) — автор ссылается на то,
что документация Anthropic требует от сторонних продуктов авторизацию по API-ключу.
Единственный поддерживаемый вариант для провайдера Claude — `ANTHROPIC_API_KEY`,
то есть оплата по токенам, отдельно от подписки. Обходить это подсовыванием
OAuth-токена в `ANTHROPIC_AUTH_TOKEN` не стоит: это ровно то ограничение, которое
разработчик выключил сознательно.

Легальные способы работать с телефона именно по подписке:

| Способ | Что даёт |
|---|---|
| **Claude Code в веб/приложении** (`claude.ai/code`) | Официальный доступ с телефона по подписке, ничего настраивать не надо. Запускает сессии в облачном контейнере с вашим репо. |
| **SSH + tmux до Linux-хоста, там обычный `claude`** | Это сам Claude Code, а не сторонний клиент, поэтому вход по подписке штатный. Полный доступ к локальным проектам. Настройка — в разделе 4. |
| **ccpocket с Codex** | Красивый мобильный UI с апрувами и диффами, работает на подписке ChatGPT через `~/.codex/auth.json`. |

Практичная комбинация: **SSH+tmux для Claude по подписке** и **ccpocket для Codex**,
обе точки входа — через один tailnet.

---

## 3. Настройка ccpocket на Linux (закрытый вариант)

### 3.1. Tailscale на хосте и на телефоне

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
tailscale ip -4          # запомните адрес 100.x.y.z
```

Тот же аккаунт — в приложении Tailscale на телефоне.

### 3.2. Зависимости

```bash
node --version           # нужен >= 20.18.1
npm i -g @openai/codex   # если работаете через Codex
codex login              # вход по подписке ChatGPT
```

### 3.3. Запуск с закрытыми настройками

Ключевой момент — **бинд на Tailscale-адрес, а не на `0.0.0.0`**. Тогда порт не
виден ни в LAN, ни на `localhost`, и незакрытые HTTP-эндпоинты становятся
доступны только внутри вашего tailnet.

```bash
TS_IP=$(tailscale ip -4 | head -1)
API_KEY=$(openssl rand -hex 24)
echo "API key: $API_KEY"

BRIDGE_HOST="$TS_IP" \
BRIDGE_API_KEY="$API_KEY" \
BRIDGE_ALLOWED_DIRS="$HOME/projects" \
BRIDGE_DISABLE_MDNS=1 \
BRIDGE_PUBLIC_WS_URL="ws://$TS_IP:8765" \
npx @ccpocket/bridge@latest
```

Что делает каждая переменная:

- `BRIDGE_HOST` — слушать только tailnet-интерфейс (снимает пункты 1-3 из аудита);
- `BRIDGE_API_KEY` — пароль на WebSocket, защита на случай, если в tailnet попадёт
  чужое устройство;
- `BRIDGE_ALLOWED_DIRS` — агент не выйдет за пределы этого каталога
  (по умолчанию был бы весь `$HOME`, включая `~/.ssh` и `~/.config`);
- `BRIDGE_DISABLE_MDNS=1` — не анонсировать себя в локальной сети;
- `BRIDGE_PUBLIC_WS_URL` — QR-код сразу с Tailscale-адресом.

В терминале появится QR — сканируйте приложением. Ключ уже вшит в QR
(`startup-info.ts:51-60`, ссылка вида `ccpocket://connect?url=...&token=...`),
руками вводить не нужно.

### 3.4. Автозапуск как systemd-сервис

```bash
BRIDGE_HOST="$TS_IP" \
BRIDGE_API_KEY="$API_KEY" \
BRIDGE_ALLOWED_DIRS="$HOME/projects" \
BRIDGE_DISABLE_MDNS=1 \
BRIDGE_PUBLIC_WS_URL="ws://$TS_IP:8765" \
npx @ccpocket/bridge@latest setup
```

Команда пишет `~/.config/systemd/user/*.service` с этими переменными, включает
`enable`, стартует и вызывает `loginctl enable-linger`, чтобы сервис жил без
активной SSH-сессии (`setup-systemd.ts:178-226`).

Проверка и логи:

```bash
systemctl --user status ccpocket-bridge
journalctl --user -u ccpocket-bridge -f
npx @ccpocket/bridge@latest doctor
curl -s "http://$TS_IP:8765/health"
```

Юнит создаётся с `Restart=on-failure` / `RestartSec=5`, так что если при загрузке
Tailscale-интерфейс поднимется позже бриджа, бинд отвалится и сервис
переподнимется сам.

### 3.5. После настройки — проверьте, что снаружи закрыто

```bash
ss -ltnp | grep 8765                       # должен быть только 100.x.y.z:8765
curl -s --max-time 3 http://<LAN-IP>:8765/health   # должно отвалиться
```

### 3.6. Гигиена

- В приложении включите **privacy mode**, если не хотите, чтобы фрагменты вывода
  агента уходили в пуши через FCM.
- Если пуши не нужны совсем — удалите `~/.ccpocket/firebase-credentials.json` и
  заблокируйте исходящие к `us-central1-ccpocket-ca33b.cloudfunctions.net`;
  штатного флага отключения нет, бридж просто напишет в лог, что релей недоступен.
- Держите права агента на апрувах (`default`), не переключайтесь на
  `bypassPermissions` / `danger-full-access` на телефоне — это снимает
  подтверждения на выполнение команд.
- Обновляйтесь через `@latest` и посматривайте в
  [CHANGELOG бриджа](https://github.com/K9i-0/ccpocket/blob/main/packages/bridge/CHANGELOG.md).

---

## 4. Claude по подписке с телефона: SSH + tmux

Работает параллельно с ccpocket, на том же tailnet.

На хосте:

```bash
sudo apt install -y openssh-server tmux
sudo systemctl enable --now ssh

# SSH только через tailnet
sudo tailscale up --ssh          # либо оставить обычный sshd, но закрыть порт в firewall

claude                            # один раз войти по подписке
```

На телефоне — любой SSH-клиент (Termius, Blink, а на Android — Termux):

```bash
ssh user@100.x.y.z
tmux new -A -s claude            # переподключение к той же сессии после обрыва связи
claude
```

`tmux new -A -s claude` возвращает вас в ту же сессию после потери сети —
агент продолжает работать, пока телефон в кармане.

---

## 5. Итоговая схема

```
Телефон (Tailscale)
   ├── ccpocket app ──ws──> Bridge на 100.x.y.z:8765 ──> Codex CLI (подписка ChatGPT)
   └── SSH-клиент  ──ssh──> tmux на хосте ──────────────> claude (подписка Claude)
```

Ничего не выставлено в интернет, оба канала живут внутри tailnet.
