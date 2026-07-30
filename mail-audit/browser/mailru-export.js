/*
 * mailru-export.js — выгрузка всех писем из веб-интерфейса Mail.ru (VK Mail)
 * в структурированный NDJSON прямо из консоли браузера.
 *
 * Как пользоваться:
 *   1. Открыть https://e.mail.ru и залогиниться.
 *   2. Открыть DevTools → Console.
 *   3. Вставить весь этот файл, Enter.
 *   4. Выполнить:  await MailAudit.run()
 *
 * Скрипт работает через тот же внутренний JSON API, что и сама веб-морда,
 * с вашими же cookie. Никуда наружу ничего не отправляется — только скачивание
 * файлов на ваш диск.
 *
 * Полезные команды:
 *   MailAudit.diagnose()        — что удалось найти (токен, ящик, папки)
 *   MailAudit.setToken('...')   — задать CSRF-токен вручную
 *   await MailAudit.run({ folders: [0], withBodies: true, bodyLimit: 500 })
 *   await MailAudit.download()  — заново скачать то, что уже лежит в IndexedDB
 *   await MailAudit.stats()     — сколько писем уже выгружено
 *   MailAudit.stop()            — мягко остановить текущую выгрузку
 *   await MailAudit.reset()     — стереть локальную базу и начать заново
 */

(function () {
  'use strict';

  // ───────────────────────────── конфиг ─────────────────────────────

  var DEFAULTS = {
    apiBase: 'https://e.mail.ru/api/v1/',
    pageSize: 200,        // писем за один запрос списка
    delayMs: 250,         // пауза между запросами (щадим бэкенд)
    maxPages: 20000,      // предохранитель от бесконечного цикла
    folders: null,        // null = все папки; иначе массив id, напр. [0, 500000]
    withBodies: false,    // тянуть ли полные тела писем (1 запрос на письмо!)
    bodyLimit: 300,       // максимум писем, для которых тянуть тело
    keepRaw: false,       // сохранять ли сырой ответ API рядом с нормализованным
    retries: 4,           // ретраи на запрос
    dbName: 'mail-audit',
    storeName: 'messages',
    chunkSize: 20000,     // писем на один файл при выгрузке
    verbose: true
  };

  var state = {
    token: null,
    email: null,
    running: false,
    abort: false,
    stats: { fetched: 0, saved: 0, bodies: 0, errors: 0, folders: 0 }
  };

  // ───────────────────────────── утилиты ─────────────────────────────

  function log() {
    if (!DEFAULTS.verbose) return;
    var args = ['%c[mail-audit]', 'color:#8b5cf6;font-weight:bold'].concat(
      Array.prototype.slice.call(arguments)
    );
    console.log.apply(console, args);
  }
  function warn() {
    var args = ['[mail-audit]'].concat(Array.prototype.slice.call(arguments));
    console.warn.apply(console, args);
  }
  function sleep(ms) {
    return new Promise(function (r) { setTimeout(r, ms); });
  }
  function isPlainObject(v) {
    return v !== null && typeof v === 'object' && !Array.isArray(v);
  }

  /** Достаёт первое непустое значение по списку путей вида 'a.b.c'. */
  function pick(obj, paths) {
    for (var i = 0; i < paths.length; i++) {
      var cur = obj;
      var parts = paths[i].split('.');
      var ok = true;
      for (var j = 0; j < parts.length; j++) {
        if (cur === null || cur === undefined) { ok = false; break; }
        cur = cur[parts[j]];
      }
      if (ok && cur !== undefined && cur !== null && cur !== '') return cur;
    }
    return undefined;
  }

  // ─────────────────────── поиск токена и адреса ───────────────────────
  //
  // Mail.ru подписывает запросы к API CSRF-токеном. Он лежит в разных местах
  // в зависимости от версии фронта, поэтому пробуем по очереди несколько
  // источников — от самого надёжного к самому грубому.

  var TOKEN_RE = /^[0-9a-f]{20,80}$/i;

  function tokenFromResourceTimings() {
    // Самый надёжный способ: браузер помнит полные URL уже выполненных
    // XHR-запросов самой почты, а в них есть и token, и email.
    var found = { token: null, email: null };
    var entries = [];
    try { entries = performance.getEntriesByType('resource') || []; } catch (e) { /* noop */ }
    for (var i = entries.length - 1; i >= 0; i--) {
      var name = entries[i].name || '';
      if (name.indexOf('/api/v1/') === -1) continue;
      var qs;
      try { qs = new URL(name).searchParams; } catch (e) { continue; }
      var t = qs.get('token');
      var e2 = qs.get('email');
      if (t && TOKEN_RE.test(t) && !found.token) found.token = t;
      if (e2 && e2.indexOf('@') > 0 && !found.email) found.email = e2;
      if (found.token && found.email) break;
    }
    return found;
  }

  function tokenFromGlobals() {
    var candidates = [
      'p.token', 'patron.token', '__PRELOADED_STATE__.token',
      '__INITIAL_STATE__.token', 'BUNDLE_INFO.token'
    ];
    for (var i = 0; i < candidates.length; i++) {
      var v = pick(window, [candidates[i]]);
      if (typeof v === 'string' && TOKEN_RE.test(v)) return v;
    }
    return null;
  }

  function tokenFromHtml() {
    var html = '';
    try { html = document.documentElement.innerHTML; } catch (e) { return null; }
    var m = html.match(/"(?:csrf_?)?token"\s*:\s*"([0-9a-f]{20,80})"/i);
    return m ? m[1] : null;
  }

  function emailFromPage() {
    var html = '';
    try { html = document.documentElement.innerHTML; } catch (e) { /* noop */ }
    var m = html.match(/"email"\s*:\s*"([^"@]+@[^"]+)"/);
    if (m) return m[1];
    var el = document.querySelector('[data-email], [title*="@"]');
    if (el) {
      var v = el.getAttribute('data-email') || el.getAttribute('title') || '';
      if (v.indexOf('@') > 0) return v.trim();
    }
    return null;
  }

  function discoverCredentials() {
    var fromTimings = tokenFromResourceTimings();
    state.token = state.token || fromTimings.token || tokenFromGlobals() || tokenFromHtml();
    state.email = state.email || fromTimings.email || emailFromPage();
    return { token: state.token, email: state.email };
  }

  // Перехватываем fetch/XHR: если токен не нашёлся статически, он появится,
  // как только пользователь кликнет по любой папке в интерфейсе.
  function installSniffer() {
    if (window.__mailAuditSniffer) return;
    window.__mailAuditSniffer = true;

    function absorb(url) {
      try {
        var u = new URL(url, location.origin);
        var t = u.searchParams.get('token');
        var e = u.searchParams.get('email');
        if (t && TOKEN_RE.test(t)) state.token = state.token || t;
        if (e && e.indexOf('@') > 0) state.email = state.email || e;
      } catch (err) { /* noop */ }
    }

    var origFetch = window.fetch;
    window.fetch = function (input) {
      absorb(typeof input === 'string' ? input : (input && input.url) || '');
      return origFetch.apply(this, arguments);
    };

    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      absorb(url);
      return origOpen.apply(this, arguments);
    };
  }

  // ───────────────────────────── HTTP слой ─────────────────────────────

  function buildUrl(path, params) {
    var url = new URL(DEFAULTS.apiBase + path);
    var p = Object.assign({ htmlencoded: false, _: Date.now() }, params || {});
    if (state.token) p.token = state.token;
    if (state.email) p.email = state.email;
    Object.keys(p).forEach(function (k) {
      var v = p[k];
      if (v === undefined || v === null) return;
      url.searchParams.set(k, typeof v === 'object' ? JSON.stringify(v) : String(v));
    });
    return url.toString();
  }

  /** GET к API с ретраями; при 4xx пробует POST (некоторые ручки только POST). */
  async function api(path, params) {
    var lastErr = null;
    for (var attempt = 0; attempt <= DEFAULTS.retries; attempt++) {
      if (attempt > 0) await sleep(Math.min(8000, 500 * Math.pow(2, attempt)));
      try {
        var res = await fetch(buildUrl(path, params), {
          credentials: 'include',
          headers: { 'Accept': 'application/json' }
        });
        if (res.status === 429 || res.status >= 500) {
          lastErr = new Error('HTTP ' + res.status + ' на ' + path);
          continue;
        }
        if (!res.ok) {
          // Иногда список отдаётся только по POST — пробуем один раз.
          var form = new URLSearchParams();
          var p2 = Object.assign({}, params || {});
          if (state.token) p2.token = state.token;
          if (state.email) p2.email = state.email;
          Object.keys(p2).forEach(function (k) {
            var v = p2[k];
            if (v === undefined || v === null) return;
            form.set(k, typeof v === 'object' ? JSON.stringify(v) : String(v));
          });
          var res2 = await fetch(DEFAULTS.apiBase + path, {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: form.toString()
          });
          if (!res2.ok) {
            lastErr = new Error('HTTP ' + res.status + '/' + res2.status + ' на ' + path);
            continue;
          }
          res = res2;
        }
        var json = await res.json();
        if (json && typeof json.status === 'number' && json.status >= 400) {
          lastErr = new Error('API status ' + json.status + ' на ' + path);
          continue;
        }
        return (json && json.body !== undefined) ? json.body : json;
      } catch (err) {
        lastErr = err;
      }
    }
    throw lastErr || new Error('Не удалось выполнить запрос ' + path);
  }

  // ─────────────────────────── IndexedDB ───────────────────────────
  //
  // Пишем письма в IndexedDB, а не только в память: выгрузка большого ящика
  // занимает минуты, и после случайного F5 не хочется начинать сначала.

  function openDb() {
    return new Promise(function (resolve, reject) {
      var req = indexedDB.open(DEFAULTS.dbName, 1);
      req.onupgradeneeded = function () {
        var db = req.result;
        if (!db.objectStoreNames.contains(DEFAULTS.storeName)) {
          db.createObjectStore(DEFAULTS.storeName, { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains('meta')) {
          db.createObjectStore('meta', { keyPath: 'key' });
        }
      };
      req.onsuccess = function () { resolve(req.result); };
      req.onerror = function () { reject(req.error); };
    });
  }

  async function dbPutMany(records) {
    if (!records.length) return;
    var db = await openDb();
    await new Promise(function (resolve, reject) {
      var tx = db.transaction(DEFAULTS.storeName, 'readwrite');
      var store = tx.objectStore(DEFAULTS.storeName);
      records.forEach(function (r) { store.put(r); });
      tx.oncomplete = resolve;
      tx.onerror = function () { reject(tx.error); };
    });
    db.close();
  }

  async function dbGetAll() {
    var db = await openDb();
    var out = await new Promise(function (resolve, reject) {
      var tx = db.transaction(DEFAULTS.storeName, 'readonly');
      var req = tx.objectStore(DEFAULTS.storeName).getAll();
      req.onsuccess = function () { resolve(req.result || []); };
      req.onerror = function () { reject(req.error); };
    });
    db.close();
    return out;
  }

  async function dbCount() {
    var db = await openDb();
    var n = await new Promise(function (resolve, reject) {
      var tx = db.transaction(DEFAULTS.storeName, 'readonly');
      var req = tx.objectStore(DEFAULTS.storeName).count();
      req.onsuccess = function () { resolve(req.result); };
      req.onerror = function () { reject(req.error); };
    });
    db.close();
    return n;
  }

  async function dbClear() {
    var db = await openDb();
    await new Promise(function (resolve, reject) {
      var tx = db.transaction(DEFAULTS.storeName, 'readwrite');
      tx.objectStore(DEFAULTS.storeName).clear();
      tx.oncomplete = resolve;
      tx.onerror = function () { reject(tx.error); };
    });
    db.close();
  }

  // ────────────────────── нормализация письма ──────────────────────
  //
  // Форма ответа Mail.ru менялась между версиями фронта, поэтому вытаскиваем
  // поля по списку возможных путей, а не по одному фиксированному.

  function normAddr(raw) {
    if (!raw) return null;
    if (typeof raw === 'string') {
      var m = raw.match(/<([^>]+)>/);
      var addr = (m ? m[1] : raw).trim().toLowerCase();
      var nm = m ? raw.slice(0, m.index).replace(/["']/g, '').trim() : '';
      return { name: nm, email: addr };
    }
    var email = pick(raw, ['email', 'address', 'addr']) || '';
    return {
      name: String(pick(raw, ['name', 'display_name']) || '').trim(),
      email: String(email).trim().toLowerCase()
    };
  }

  function normAddrList(raw) {
    if (!raw) return [];
    if (Array.isArray(raw)) return raw.map(normAddr).filter(Boolean);
    if (typeof raw === 'string') {
      return raw.split(/[,;]/).map(function (s) { return normAddr(s.trim()); })
        .filter(function (a) { return a && a.email; });
    }
    if (isPlainObject(raw)) {
      var a = normAddr(raw);
      return a && a.email ? [a] : [];
    }
    return [];
  }

  function toIso(v) {
    if (v === undefined || v === null || v === '') return null;
    if (typeof v === 'number') {
      var ms = v < 1e12 ? v * 1000 : v;   // секунды или миллисекунды
      return new Date(ms).toISOString();
    }
    var d = new Date(v);
    return isNaN(d.getTime()) ? null : d.toISOString();
  }

  function normalize(raw, folderMeta) {
    var flags = raw.flags || {};
    var id = String(pick(raw, ['id', 'uidl', 'message_id', 'msg_id']) || '');
    if (!id) return null;

    var msg = {
      id: id,
      thread_id: pick(raw, ['thread_id', 'threadId', 'thread.id']) || null,
      folder_id: raw.folder !== undefined ? raw.folder : (folderMeta && folderMeta.id),
      folder_name: (folderMeta && folderMeta.name) || null,
      date: toIso(pick(raw, ['date', 'time', 'timestamp', 'received_date'])),
      subject: String(pick(raw, ['subject', 'title']) || ''),
      snippet: String(pick(raw, ['snippet', 'preview', 'short_body', 'text']) || ''),
      from: normAddr(pick(raw, ['from', 'sender'])),
      to: normAddrList(raw.to),
      cc: normAddrList(raw.cc),
      bcc: normAddrList(raw.bcc),
      reply_to: normAddrList(raw.reply_to || raw.replyTo),
      size: pick(raw, ['size', 'bytes']) || null,
      has_attachments: Boolean(
        pick(raw, ['attaches.has', 'attaches.total', 'has_attach', 'attachments_count'])
      ),
      attachments: [],
      flags: {
        read: !(flags.unread === true || raw.unread === true),
        flagged: Boolean(flags.flagged || raw.flagged),
        draft: Boolean(flags.draft || raw.draft),
        answered: Boolean(flags.answered || raw.answered),
        forwarded: Boolean(flags.forwarded || raw.forwarded)
      },
      body_text: null
    };

    var atts = raw.attaches && raw.attaches.list;
    if (Array.isArray(atts)) {
      msg.attachments = atts.map(function (a) {
        return {
          name: String(pick(a, ['name', 'filename']) || ''),
          size: pick(a, ['size', 'bytes']) || null,
          type: pick(a, ['content_type', 'type', 'mime']) || null
        };
      });
      msg.has_attachments = msg.has_attachments || atts.length > 0;
    }

    if (DEFAULTS.keepRaw) msg._raw = raw;
    return msg;
  }

  // ─────────────────────────── обход папок ───────────────────────────

  function extractList(body, keys) {
    if (Array.isArray(body)) return body;
    if (!isPlainObject(body)) return [];
    for (var i = 0; i < keys.length; i++) {
      if (Array.isArray(body[keys[i]])) return body[keys[i]];
    }
    // на всякий случай — первый попавшийся массив объектов
    var vals = Object.keys(body).map(function (k) { return body[k]; });
    for (var j = 0; j < vals.length; j++) {
      if (Array.isArray(vals[j]) && isPlainObject(vals[j][0])) return vals[j];
    }
    return [];
  }

  async function listFolders() {
    var body = await api('folders', {});
    var raw = extractList(body, ['folders', 'list', 'items']);
    return raw.map(function (f) {
      return {
        id: pick(f, ['id', 'folder_id', 'folder']),
        name: String(pick(f, ['name', 'title', 'full_name']) || ('folder ' + f.id)),
        total: pick(f, ['messages_total', 'messages.total', 'count']) || null
      };
    }).filter(function (f) { return f.id !== undefined && f.id !== null; });
  }

  async function fetchFolderPage(folderId, offset) {
    var params = {
      folder: folderId,
      limit: DEFAULTS.pageSize,
      offset: offset,
      sort: { type: 'date', order: 'desc' }
    };
    var body = await api('messages', params);
    return extractList(body, ['messages', 'list', 'items', 'threads']);
  }

  async function fetchBody(id) {
    var body = await api('messages/message', { id: id, ids: [id] });
    var m = Array.isArray(body) ? body[0] : (body && (body.message || body));
    if (!m) return null;
    var text = pick(m, ['body.text', 'body.plain', 'text', 'plain', 'body']);
    if (isPlainObject(text)) text = pick(text, ['text', 'plain', 'html']);
    if (typeof text !== 'string') return null;
    // грубо чистим html, если пришёл он
    return text
      .replace(/<style[\s\S]*?<\/style>/gi, ' ')
      .replace(/<script[\s\S]*?<\/script>/gi, ' ')
      .replace(/<[^>]+>/g, ' ')
      .replace(/&nbsp;/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  // ───────────────────────── выгрузка файлов ─────────────────────────

  function saveBlob(blob, filename) {
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 5000);
  }

  function toNdjson(records) {
    return records.map(function (r) { return JSON.stringify(r); }).join('\n') + '\n';
  }

  async function download() {
    var all = await dbGetAll();
    if (!all.length) {
      warn('В локальной базе пусто — сначала запустите MailAudit.run()');
      return 0;
    }
    all.sort(function (a, b) { return (a.date || '') < (b.date || '') ? -1 : 1; });

    var stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
    var meta = {
      _meta: true,
      account: state.email,
      exported_at: new Date().toISOString(),
      total: all.length,
      source: 'e.mail.ru'
    };

    var chunks = Math.max(1, Math.ceil(all.length / DEFAULTS.chunkSize));
    for (var i = 0; i < chunks; i++) {
      var slice = all.slice(i * DEFAULTS.chunkSize, (i + 1) * DEFAULTS.chunkSize);
      var head = i === 0 ? JSON.stringify(meta) + '\n' : '';
      var name = chunks === 1
        ? 'mailru-export-' + stamp + '.ndjson'
        : 'mailru-export-' + stamp + '-part' + (i + 1) + '.ndjson';
      saveBlob(new Blob([head + toNdjson(slice)], { type: 'application/x-ndjson' }), name);
      await sleep(400); // браузер не любит пачку одновременных скачиваний
    }
    log('Скачано писем:', all.length, 'файлов:', chunks);
    return all.length;
  }

  // ───────────────────────────── основной цикл ─────────────────────────────

  async function run(options) {
    if (state.running) { warn('Выгрузка уже идёт. MailAudit.stop() чтобы прервать.'); return; }
    Object.assign(DEFAULTS, options || {});

    installSniffer();
    discoverCredentials();

    if (!state.token) {
      warn(
        'Не удалось найти CSRF-токен.\n' +
        'Кликните по любой папке в интерфейсе почты и повторите MailAudit.run(),\n' +
        'либо задайте вручную: MailAudit.setToken("<token>")\n' +
        '(DevTools → Network → любой запрос к /api/v1/ → параметр token).'
      );
      return;
    }
    log('Токен найден, ящик:', state.email || '(не определён)');

    state.running = true;
    state.abort = false;
    state.stats = { fetched: 0, saved: 0, bodies: 0, errors: 0, folders: 0 };

    try {
      var folders = await listFolders();
      if (DEFAULTS.folders) {
        var want = DEFAULTS.folders.map(String);
        folders = folders.filter(function (f) { return want.indexOf(String(f.id)) !== -1; });
      }
      log('Папок к обработке:', folders.length, folders.map(function (f) { return f.name; }));

      var seen = Object.create(null);
      (await dbGetAll()).forEach(function (m) { seen[m.id] = true; });
      var bodiesLeft = DEFAULTS.withBodies ? DEFAULTS.bodyLimit : 0;

      for (var fi = 0; fi < folders.length; fi++) {
        if (state.abort) break;
        var folder = folders[fi];
        var offset = 0;
        var pages = 0;
        log('→ Папка «' + folder.name + '» (id=' + folder.id + ')');

        while (!state.abort && pages < DEFAULTS.maxPages) {
          var rawList;
          try {
            rawList = await fetchFolderPage(folder.id, offset);
          } catch (err) {
            state.stats.errors++;
            warn('Ошибка на папке', folder.name, 'offset', offset, err.message);
            break;
          }
          if (!rawList.length) break;

          var batch = [];
          for (var i = 0; i < rawList.length; i++) {
            var msg = normalize(rawList[i], folder);
            if (!msg) continue;
            state.stats.fetched++;
            if (seen[msg.id]) continue;
            seen[msg.id] = true;
            batch.push(msg);
          }

          if (bodiesLeft > 0) {
            for (var b = 0; b < batch.length && bodiesLeft > 0; b++) {
              try {
                batch[b].body_text = await fetchBody(batch[b].id);
                if (batch[b].body_text) { state.stats.bodies++; bodiesLeft--; }
              } catch (err) { state.stats.errors++; }
              await sleep(DEFAULTS.delayMs);
            }
          }

          await dbPutMany(batch);
          state.stats.saved += batch.length;
          offset += rawList.length;
          pages++;
          if (pages % 5 === 0 || rawList.length < DEFAULTS.pageSize) {
            log('   ' + folder.name + ': ' + offset + ' просмотрено, всего сохранено ' + state.stats.saved);
          }
          if (rawList.length < DEFAULTS.pageSize) break;
          await sleep(DEFAULTS.delayMs);
        }
        state.stats.folders++;
      }

      log('Готово.', JSON.stringify(state.stats));
      await download();
      return state.stats;
    } finally {
      state.running = false;
    }
  }

  // ───────────────────────────── публичный API ─────────────────────────────

  var MailAudit = {
    config: DEFAULTS,
    run: run,
    download: download,
    stop: function () { state.abort = true; log('Останавливаюсь после текущей страницы…'); },
    setToken: function (t) { state.token = t; log('Токен задан вручную.'); return t; },
    setEmail: function (e) { state.email = e; return e; },
    reset: async function () { await dbClear(); log('Локальная база очищена.'); },
    stats: async function () {
      return { inDb: await dbCount(), session: state.stats, token: Boolean(state.token), email: state.email };
    },
    diagnose: async function () {
      installSniffer();
      var creds = discoverCredentials();
      var out = {
        token: creds.token ? creds.token.slice(0, 8) + '…' : null,
        email: creds.email,
        apiBase: DEFAULTS.apiBase,
        inDb: await dbCount(),
        folders: null,
        error: null
      };
      if (creds.token) {
        try { out.folders = await listFolders(); } catch (e) { out.error = e.message; }
      }
      console.table(out.folders || []);
      return out;
    }
  };

  window.MailAudit = MailAudit;
  installSniffer();
  discoverCredentials();
  log(
    'Загружено. Запуск:  await MailAudit.run()\n' +
    'Диагностика:        await MailAudit.diagnose()\n' +
    'Токен ' + (state.token ? 'найден ✓' : 'НЕ найден — кликните по папке и повторите') +
    (state.email ? (', ящик: ' + state.email) : '')
  );
})();
