/*
 * Book Driver web player.
 *
 * Reads the library (library.json, cover-<id>.img) and listening progress (progress.json)
 * that the Android app keeps in the user's hidden Drive app folder, plays books through
 * sw.js (which adds the Drive auth to the audio element's Range requests), and writes
 * progress back the same way the app does: newest change per book wins.
 *
 * No server: Google sign-in in the browser, Drive straight from the page.
 */
'use strict';

const BD = (() => {
  const CLIENT_ID = '1007063070283-jcrvnps2mf5inu5gd5bd5fktnm86fh8f.apps.googleusercontent.com';
  const SCOPES = 'https://www.googleapis.com/auth/drive.file https://www.googleapis.com/auth/drive.appdata';
  const API = 'https://www.googleapis.com/drive/v3';
  const UPLOAD = 'https://www.googleapis.com/upload/drive/v3';
  const SAVE_EVERY_MS = 30_000;
  const SPEEDS = [0.8, 1.0, 1.2, 1.5, 1.8, 2.0];
  const SLEEPS = [[15, '15 min'], [30, '30 min'], [45, '45 min'], [60, '1 hour'], ['chapter', 'End of chapter'], [0, 'Off']];
  const PALETTE = ['#C2410C', '#2F5D8A', '#6B3FA0', '#2E7D5B', '#7A2E3A', '#8A6A12', '#1F6F7A'];

  const $ = (id) => document.getElementById(id);
  const audio = $('audio');
  const store = {
    get(k, d) { try { const v = localStorage.getItem('bd.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem('bd.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
    del(k) { try { localStorage.removeItem('bd.' + k); } catch { /* ignore */ } },
  };

  // ---- state -------------------------------------------------------------------------
  let token = null, tokenExp = 0, tokenAt = 0, tokenClient = null, pendingToken = null;
  let unsynced = false;     // progress we couldn't write to Drive yet (session expired)
  let email = store.get('email', null);
  let books = [];           // ready books from library.json
  let progress = {};        // bookId -> {trackIndex, positionMs, updatedAt, lastPlayedAt, finished}
  let files = {};           // appData file name -> id
  const covers = {};        // bookId -> Promise<objectURL|null>
  let cur = null;           // { book, track }
  let pendingSeek = null;   // seconds to seek to once the track's metadata is in
  let speed = store.get('speed', 1.0);
  let filter = 'all';
  let sleep = { mode: 'off', remaining: 0, chapterEnd: 0 };
  let pausedAt = 0, lastSaved = 0, lastTick = 0, dragging = false, view = 'welcome';

  // ---- helpers -----------------------------------------------------------------------
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  function clock(ms) {
    const s = Math.max(0, Math.floor(ms / 1000)), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60;
    return h ? `${h}:${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}` : `${m}:${String(s % 60).padStart(2, '0')}`;
  }
  function duration(ms) {
    if (ms <= 0) return '—';
    const min = Math.floor(ms / 60000), h = Math.floor(min / 60);
    return h ? `${h}h ${min % 60}m` : `${min % 60}m`;
  }
  const prettyTrackName = (name, i) =>
    name.replace(/\.[A-Za-z0-9]{2,4}$/, '').replace(/^\s*\d+[\s._-]*/, '').trim() || `Part ${i + 1}`;
  function toast(msg, ms = 3500) {
    const t = $('toast'); t.textContent = msg; t.hidden = false;
    clearTimeout(toast.timer); toast.timer = setTimeout(() => { t.hidden = true; }, ms);
  }
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };

  // ---- book maths (mirrors the app's Book model) -------------------------------------
  const trackOffset = (b, i) => b.tracks.slice(0, Math.max(0, i)).reduce((s, t) => s + (t.durationMs || 0), 0);
  const bookDuration = (b) => b.tracks.reduce((s, t) => s + (t.durationMs || 0), 0);
  const chaptersOf = (b) => (b.chapters && b.chapters.length) ? b.chapters
    : b.tracks.map((t, i) => ({ title: prettyTrackName(t.name, i), trackIndex: i, startMs: 0 }));
  const chapterStart = (b, i) => { const c = chaptersOf(b)[i]; return c ? trackOffset(b, c.trackIndex) + c.startMs : 0; };
  const chapterEnd = (b, i) => (i + 1 < chaptersOf(b).length ? chapterStart(b, i + 1) : bookDuration(b));
  function chapterAt(b, g) {
    const chs = chaptersOf(b); let found = 0;
    for (let i = 0; i < chs.length; i++) { if (chapterStart(b, i) <= g + 250) found = i; else break; }
    return found;
  }
  function locate(b, g) {
    let rest = Math.max(0, g);
    for (let i = 0; i < b.tracks.length; i++) {
      const d = b.tracks[i].durationMs || 0;
      if (d <= 0 || rest < d || i === b.tracks.length - 1) return [i, d > 0 ? Math.min(rest, d) : rest];
      rest -= d;
    }
    return [0, 0];
  }
  const globalNow = () => cur ? trackOffset(cur.book, cur.track) + (pendingSeek != null ? pendingSeek : audio.currentTime) * 1000 : 0;
  const progressOf = (b) => {
    const p = progress[b.id]; const d = bookDuration(b);
    if (!p || d <= 0) return 0; if (p.finished) return 1;
    return clamp((trackOffset(b, p.trackIndex) + p.positionMs) / d, 0, 1);
  };
  const subtitle = (b) => [b.author, b.series && (b.series + (b.seriesIndex ? `, Book ${b.seriesIndex}` : ''))].filter(Boolean).join(' · ');

  // ---- covers ------------------------------------------------------------------------
  function javaHash(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0; return h; }
  function paletteIndex(key, n) {
    let x = javaHash(key);
    x ^= x >>> 16; x = Math.imul(x, 0x45d9f3b); x ^= x >>> 16; x = Math.imul(x, 0x45d9f3b); x ^= x >>> 16;
    return ((x % n) + n) % n;
  }
  function soften(hex) {
    const n = parseInt(hex.slice(1), 16), mix = (c) => Math.round(c + (255 - c) * 0.78);
    return `rgb(${mix(n >> 16)}, ${mix((n >> 8) & 255)}, ${mix(n & 255)})`;
  }
  function generatedCover(b) {
    const bg = PALETTE[paletteIndex(b.title.toLowerCase() + '|' + (b.seriesIndex || ''), PALETTE.length)];
    const g = el('div', 'gen'); g.style.background = bg;
    const idx = b.seriesIndex ? String(b.seriesIndex).padStart(2, '0') : null;
    const label = b.series ? b.series.toUpperCase() + (idx ? ' · ' + idx : '') : idx ? 'BOOK ' + idx : (b.author || '').toUpperCase();
    const l = el('div', 'lbl', label); l.style.color = soften(bg);
    const t = el('div', 'ttl', b.title.toUpperCase());
    g.append(l, t);
    requestAnimationFrame(() => fitTitle(g, t));
    return g;
  }
  function fitTitle(box, t) {
    // Largest size where no word breaks mid-word and the title fits (like the app).
    const w = box.clientWidth; if (!w) return;
    let f = w * 0.17;
    for (let i = 0; i < 14; i++) {
      t.style.fontSize = f + 'px';
      if (t.scrollWidth <= t.clientWidth + 1 && t.scrollHeight <= box.clientHeight * 0.75) break;
      f *= 0.92;
    }
  }
  function coverEl(b) {
    const c = el('div', 'cover');
    c.append(generatedCover(b));
    coverUrl(b).then((url) => {
      if (!url) return;
      const img = new Image(); img.alt = ''; img.src = url;
      img.onload = () => { c.replaceChildren(img); };
    });
    return c;
  }
  function coverUrl(b) {
    if (covers[b.id]) return covers[b.id];
    const id = files[`cover-${b.id}.img`];
    covers[b.id] = !id ? Promise.resolve(null)
      : api(`/files/${id}?alt=media`).then((r) => r.blob()).then((blob) => URL.createObjectURL(blob)).catch(() => null);
    return covers[b.id];
  }

  // ---- Google sign-in ----------------------------------------------------------------
  function gsiLoaded() {
    tokenClient = google.accounts.oauth2.initTokenClient({
      client_id: CLIENT_ID, scope: SCOPES,
      callback: (resp) => {
        const p = pendingToken; pendingToken = null;
        if (resp.error) { p && p.reject(new Error(resp.error)); return; }
        if (!SCOPES.split(' ').every((s) => google.accounts.oauth2.hasGrantedAllScopes(resp, s))) {
          p && p.reject(new Error('Book Driver needs both Drive permissions to find your library.'));
          return;
        }
        token = resp.access_token;
        tokenAt = Date.now();
        tokenExp = tokenAt + resp.expires_in * 1000;
        toWorker({ type: 'token', token });
        setSyncWarning(false);
        if (unsynced && cur) saveProgress(); // send what we couldn't while signed out
        p && p.resolve(token);
      },
      error_callback: (err) => { const p = pendingToken; pendingToken = null; p && p.reject(new Error(err && err.type === 'popup_closed' ? 'Sign-in was closed.' : 'Sign-in popup was blocked.')); },
    });
    if (email) $('signin').textContent = 'Continue as ' + email;
  }
  function gsiFailed() { $('welcomeNote').textContent = 'Couldn’t load Google sign-in. An ad blocker or privacy extension may be blocking accounts.google.com.'; }

  function requestToken() {
    return new Promise((resolve, reject) => {
      if (!tokenClient) { reject(new Error('Google sign-in is still loading.')); return; }
      pendingToken = { resolve, reject };
      tokenClient.requestAccessToken({ prompt: '', login_hint: email || undefined });
    });
  }
  /** Fresh token, refreshing when it's close to expiring. Call at the start of a click (popups need one). */
  function ensureToken() {
    if (token && Date.now() < tokenExp - 10 * 60_000) return Promise.resolve(token);
    return requestToken();
  }

  // Google's browser sign-in lasts an hour and can only be renewed from a tap or key press
  // (it may open a popup). So renew on any interaction once the token is half an hour old,
  // which keeps a listening session syncing as long as you touch the page now and then.
  function renewOnInteraction() {
    if (!email || !tokenClient || pendingToken) return;
    if (token && Date.now() - tokenAt < 30 * 60_000) return;
    requestToken().catch(() => {});
  }
  document.addEventListener('pointerdown', renewOnInteraction, true);
  document.addEventListener('keydown', renewOnInteraction, true);

  function setSyncWarning(on) {
    const s = $('pStatus');
    if (on) {
      s.textContent = 'Not syncing: your Google sign-in expired. Tap anywhere to reconnect, or your phone won’t see where you are.';
      s.hidden = false;
      $('miniSub').dataset.warn = '1';
      document.title = '⚠ Not syncing · Book Driver';
    } else {
      if (s.textContent.startsWith('Not syncing')) { s.textContent = ''; s.hidden = true; }
      delete $('miniSub').dataset.warn;
      if (cur) document.title = cur.book.title + ' · Book Driver';
    }
  }

  class AuthError extends Error {}
  async function api(path, opts = {}) {
    if (!token) throw new AuthError('signed out');
    const r = await fetch((opts.upload ? UPLOAD : API) + path, {
      method: opts.method || 'GET',
      headers: { Authorization: 'Bearer ' + token, ...(opts.headers || {}) },
      body: opts.body, keepalive: !!opts.keepalive,
    });
    if (r.status === 401) { token = null; throw new AuthError('expired'); }
    if (!r.ok) throw new Error(`Drive ${r.status}: ${(await r.text()).slice(0, 200)}`);
    return r;
  }

  // ---- service worker ----------------------------------------------------------------
  const swReady = ('serviceWorker' in navigator)
    ? navigator.serviceWorker.register('sw.js').then(() => navigator.serviceWorker.ready).catch(() => null)
    : Promise.resolve(null);
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.addEventListener('message', (e) => {
      const m = e.data || {};
      if (m.type === 'need-token' && e.ports[0]) e.ports[0].postMessage({ token });
      if (m.type === 'auth-expired') { token = null; setSyncWarning(true); }
    });
  }
  async function toWorker(msg) {
    const reg = await swReady; if (!reg) return;
    (navigator.serviceWorker.controller || reg.active)?.postMessage(msg);
  }

  // ---- loading & syncing -------------------------------------------------------------
  function mergeProgress(a, b) {
    const out = { ...a };
    for (const [id, p] of Object.entries(b || {})) {
      if (id.startsWith('folder:')) continue;
      if (!out[id] || (p.updatedAt || 0) > (out[id].updatedAt || 0)) out[id] = p;
    }
    for (const id of Object.keys(out)) if (id.startsWith('folder:')) delete out[id];
    return out;
  }

  async function loadAll() {
    const list = await (await api('/files?spaces=appDataFolder&pageSize=1000&fields=files(id,name)')).json();
    files = Object.fromEntries(list.files.map((f) => [f.name, f.id]));
    const [lib, prog] = await Promise.all([
      files['library.json'] ? api(`/files/${files['library.json']}?alt=media`).then((r) => r.json()).catch(() => null) : null,
      files['progress.json'] ? api(`/files/${files['progress.json']}?alt=media`).then((r) => r.json()).catch(() => null) : null,
    ]);
    books = lib ? Object.values(lib.books || {}).map((e) => e && e.book).filter((b) => b && b.status === 'READY' && b.tracks && b.tracks.length) : [];
    progress = mergeProgress(progress, prog && prog.books);
    store.set('progress', progress);
    return { hasLibrary: !!lib };
  }

  /** Pull progress from Drive; if the loaded book is paused and another device got further, follow it. */
  async function pull() {
    if (!token || Date.now() > tokenExp - 60_000) return;
    try {
      const before = cur && progress[cur.book.id];
      await loadAll();
      renderLibrary();
      const now = cur && progress[cur.book.id];
      if (cur && audio.paused && now && (!before || now.updatedAt > before.updatedAt)) {
        const there = trackOffset(cur.book, now.trackIndex) + now.positionMs;
        if (Math.abs(there - globalNow()) > 2000) { seekGlobal(there, false); toast('Picked up where you left off on another device'); }
      }
    } catch (e) { if (!(e instanceof AuthError)) console.warn('pull failed', e); }
  }

  async function saveProgress({ keepalive = false } = {}) {
    if (!cur) return;
    if (!token || Date.now() > tokenExp) {
      // Keep the position locally and flag it; it goes to Drive after the next reconnect.
      const t = Date.now(), [track, pos] = locate(cur.book, globalNow());
      progress[cur.book.id] = { trackIndex: track, positionMs: Math.round(pos), updatedAt: t, lastPlayedAt: t, finished: false };
      store.set('progress', progress);
      lastSaved = t; unsynced = true; token = null;
      setSyncWarning(true);
      return;
    }
    const t = Date.now();
    const g = globalNow();
    const [track, pos] = locate(cur.book, g);
    progress[cur.book.id] = { trackIndex: track, positionMs: Math.round(pos), updatedAt: t, lastPlayedAt: t, finished: false };
    store.set('progress', progress);
    lastSaved = t;
    try {
      // Merge with Drive first so a book another device saved meanwhile isn't overwritten.
      // (On page close there's no time for that; we merge with what we last saw.)
      if (!keepalive && files['progress.json']) {
        const remote = await (await api(`/files/${files['progress.json']}?alt=media`)).json();
        progress = mergeProgress(progress, remote.books);
      }
      const body = JSON.stringify({ version: 2, books: progress });
      if (files['progress.json']) {
        await api(`/files/${files['progress.json']}?uploadType=media`, { upload: true, method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body, keepalive });
      } else {
        const boundary = 'bd' + Math.random().toString(36).slice(2);
        const multipart = `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n` +
          JSON.stringify({ name: 'progress.json', parents: ['appDataFolder'] }) +
          `\r\n--${boundary}\r\nContent-Type: application/json\r\n\r\n${body}\r\n--${boundary}--`;
        const r = await api('/files?uploadType=multipart&fields=id', { upload: true, method: 'POST', headers: { 'Content-Type': `multipart/related; boundary=${boundary}` }, body: multipart });
        files['progress.json'] = (await r.json()).id;
      }
      unsynced = false;
    } catch (e) {
      unsynced = true;
      if (e instanceof AuthError || !token) setSyncWarning(true);
      console.warn('save failed', e);
    }
  }

  // ---- views -------------------------------------------------------------------------
  function show(v) {
    view = v;
    for (const id of ['welcome', 'library', 'player', 'chapters']) $(id).hidden = id !== v;
    $('mini').hidden = !(cur && v === 'library');
    $('account').hidden = v === 'welcome';
    if (v === 'chapters') renderChapters();
    window.scrollTo(0, 0);
    // Covers rendered while their view was hidden had no size to fit the title to.
    requestAnimationFrame(() => $(v).querySelectorAll('.gen').forEach((g) => fitTitle(g, g.querySelector('.ttl'))));
  }

  function renderLibrary() {
    const sorted = [...books].sort((a, b) => (progress[b.id]?.lastPlayedAt || b.addedAt || 0) - (progress[a.id]?.lastPlayedAt || a.addedAt || 0));
    const cont = sorted.find((b) => { const p = progress[b.id]; return p && !p.finished && p.lastPlayedAt > 0; });
    const box = $('continue'); box.replaceChildren();
    if (cont) {
      const p = progress[cont.id], g = trackOffset(cont, p.trackIndex) + p.positionMs;
      const card = el('div', 'continue'); card.tabIndex = 0;
      const body = el('div', 'c-body');
      const row = el('div', 'c-row');
      const line = el('div', 'bar-line'); line.style.flex = '1'; const fill = el('i'); fill.style.width = (progressOf(cont) * 100) + '%'; line.append(fill);
      const btn = el('button', 'round-play'); btn.setAttribute('aria-label', 'Play ' + cont.title);
      btn.innerHTML = '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z" class="fill"/></svg>';
      row.append(line, btn);
      body.append(el('span', 'c-over', 'Continue'), el('h2', null, cont.title),
        el('span', 'c-sub', `${chaptersOf(cont)[chapterAt(cont, g)]?.title || ''} · ${duration(bookDuration(cont) - g)} left`), row);
      card.append(coverEl(cont), body);
      card.onclick = () => act(() => openBook(cont, true));
      box.append(card);
    }
    const grid = $('grid'); grid.replaceChildren();
    const shown = sorted.filter((b) => {
      const p = progress[b.id], started = p && (p.positionMs > 0 || p.trackIndex > 0);
      return filter === 'all' || (filter === 'finished' && p?.finished) || (filter === 'progress' && started && !p.finished) || (filter === 'new' && !started && !p?.finished);
    });
    for (const b of shown) {
      const tile = el('button', 'tile-book'); const p = progress[b.id];
      tile.append(coverEl(b), el('span', 't', b.title));
      if (p?.finished) tile.append(el('span', 'done', '✓ Finished'));
      else if (!p || (p.positionMs === 0 && p.trackIndex === 0)) tile.append(el('span', 'new', 'NEW'));
      else { const line = el('div', 'bar-line'); const f = el('i'); f.style.width = (progressOf(b) * 100) + '%'; line.append(f); tile.append(line); }
      tile.onclick = () => act(() => openBook(b, true));
      grid.append(tile);
    }
    $('empty').hidden = shown.length > 0;
    if (!shown.length) $('empty').textContent = books.length ? 'Nothing here yet.' : 'No books yet. Add some in the Book Driver app.';
  }

  function renderPlayerStatic() {
    const b = cur.book;
    $('pCover').replaceChildren(coverEl(b));
    $('pTitle').textContent = b.title;
    $('pSub').textContent = subtitle(b);
    $('miniCover').replaceChildren(coverEl(b));
    $('miniTitle').textContent = b.title;
    document.title = b.title + ' · Book Driver';
    if ('mediaSession' in navigator) {
      coverUrl(b).then((url) => {
        navigator.mediaSession.metadata = new MediaMetadata({
          title: b.title, artist: b.author || '', album: b.series || '',
          artwork: url ? [{ src: url, sizes: '512x512' }] : [{ src: '../images/icon.png', sizes: '160x160', type: 'image/png' }],
        });
      });
    }
  }

  function renderChapters() {
    if (!cur) return;
    const b = cur.book, chs = chaptersOf(b), g = globalNow(), at = chapterAt(b, g);
    $('chSub').textContent = `${b.title} · ${chs.length} chapters · ${duration(bookDuration(b))}`;
    const list = $('chList'); list.replaceChildren();
    chs.forEach((c, i) => {
      const li = el('li', i < at ? 'past' : i === at ? 'cur' : '');
      const len = chapterEnd(b, i) - chapterStart(b, i);
      li.append(el('span', 'n', String(i + 1)), el('span', 'ttl', c.title),
        el('span', 'd', i === at ? '-' + clock(chapterEnd(b, i) - g) : clock(len)));
      li.onclick = () => act(() => { seekGlobal(chapterStart(b, i), true); if (audio.paused) play(); show('player'); });
      list.append(li);
      if (i === at) requestAnimationFrame(() => li.scrollIntoView({ block: 'center' }));
    });
  }

  function showStatus(msg) {
    if (!msg && $('miniSub').dataset.warn) return; // keep the "not syncing" notice until reconnected
    const s = $('pStatus'); s.textContent = msg || ''; s.hidden = !msg;
  }

  // ---- playback ----------------------------------------------------------------------
  function openBook(b, autoplay) {
    if (cur && cur.book.id === b.id) { if (autoplay && audio.paused) play(); show('player'); return; }
    if (cur) saveProgress();
    const p = progress[b.id];
    const start = p && !p.finished ? [clamp(p.trackIndex, 0, b.tracks.length - 1), p.positionMs] : [0, 0];
    if (p && p.finished) progress[b.id] = { ...p, finished: false, trackIndex: 0, positionMs: 0 };
    cur = { book: b, track: -1 };
    pausedAt = 0;
    sleepOff();
    renderPlayerStatic();
    loadTrack(start[0], start[1], autoplay);
    show('player');
  }

  function loadTrack(i, posMs, autoplay) {
    const t = cur.book.tracks[i];
    cur.track = i;
    pendingSeek = posMs / 1000;
    showStatus('');
    audio.src = `stream/${encodeURIComponent(t.fileId)}?size=${t.size || 0}`;
    audio.defaultPlaybackRate = speed;
    audio.playbackRate = speed;
    if (autoplay) audio.play().catch((e) => { if (e.name !== 'AbortError') showStatus('Couldn’t start playback: ' + e.message); });
    else audio.load();
  }

  function play() {
    if (!cur) return;
    // Smart rewind, like the app: back a little after a pause, more after a long break.
    if (pausedAt) {
      const gap = Date.now() - pausedAt;
      const back = gap < 5000 ? 0 : gap < 60_000 ? 3000 : gap < 3_600_000 ? 10_000 : 20_000;
      if (back) audio.currentTime = Math.max(0, audio.currentTime - back / 1000);
      pausedAt = 0;
    }
    if (audio.error || !audio.src) { const g = globalNow(); const [ti, pos] = locate(cur.book, g); loadTrack(ti, pos, true); return; }
    audio.play().catch((e) => { if (e.name !== 'AbortError') showStatus('Couldn’t start playback: ' + e.message); });
  }
  const pause = () => audio.pause();
  const togglePlay = () => (audio.paused ? play() : pause());

  function seekGlobal(g, keepPlaying) {
    const b = cur.book;
    g = clamp(g, 0, Math.max(0, bookDuration(b) - 500));
    const [ti, pos] = locate(b, g);
    if (ti === cur.track && pendingSeek == null && audio.readyState >= 1) audio.currentTime = pos / 1000;
    else if (ti === cur.track) pendingSeek = pos / 1000;
    else loadTrack(ti, pos, keepPlaying ?? !audio.paused);
    tick(true);
  }
  const skip = (ms) => cur && seekGlobal(globalNow() + ms);
  function nextChapter() {
    if (!cur) return;
    const i = chapterAt(cur.book, globalNow());
    if (i + 1 < chaptersOf(cur.book).length) seekGlobal(chapterStart(cur.book, i + 1));
  }
  function prevChapter() {
    if (!cur) return;
    const g = globalNow(), i = chapterAt(cur.book, g), start = chapterStart(cur.book, i);
    seekGlobal(g - start > 3000 || i === 0 ? start : chapterStart(cur.book, i - 1));
  }

  audio.addEventListener('loadedmetadata', () => {
    audio.playbackRate = speed;
    if (pendingSeek != null) { audio.currentTime = pendingSeek; pendingSeek = null; }
  });
  audio.addEventListener('play', () => { updatePlayIcons(); if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing'; });
  audio.addEventListener('playing', () => { updatePlayIcons(); showStatus(''); });
  audio.addEventListener('waiting', updatePlayIcons);
  audio.addEventListener('pause', () => {
    pausedAt = Date.now();
    updatePlayIcons();
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';
    saveProgress();
  });
  audio.addEventListener('ended', () => {
    if (cur && cur.track + 1 < cur.book.tracks.length) { loadTrack(cur.track + 1, 0, true); return; }
    if (cur) {
      const t = Date.now();
      progress[cur.book.id] = { trackIndex: 0, positionMs: 0, updatedAt: t, lastPlayedAt: t, finished: true };
      saveProgress().then(renderLibrary);
      toast('Finished “' + cur.book.title + '”');
    }
  });
  audio.addEventListener('error', () => {
    if (!cur) return;
    showStatus(token ? 'Playback stopped (couldn’t read the file). Tap play to try again.' : 'Your Google session expired. Tap play to continue.');
    updatePlayIcons();
  });

  function updatePlayIcons() {
    const playing = !audio.paused;
    const loading = playing && audio.readyState < 3;
    const icon = playing
      ? '<path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" class="fill"/>'
      : '<path d="M8 5v14l11-7z" class="fill"/>';
    $('playIcon').innerHTML = loading ? '<path d="M12 3a9 9 0 1 0 9 9"/>' : icon;
    $('playBtn').classList.toggle('loading', loading);
    $('playBtn').setAttribute('aria-label', playing ? 'Pause' : 'Play');
    $('miniIcon').innerHTML = icon;
    $('miniPlay').setAttribute('aria-label', playing ? 'Pause' : 'Play');
  }

  // ---- the 4×/s tick: UI, sleep timer, autosave --------------------------------------
  function tick(force) {
    const now = Date.now(), dt = lastTick ? now - lastTick : 0; lastTick = now;
    if (!cur) return;
    const b = cur.book, g = globalNow(), i = chapterAt(b, g);
    const s = chapterStart(b, i), e = chapterEnd(b, i), len = Math.max(1, e - s);
    if (!dragging) $('seek').value = String(Math.round(((g - s) / len) * 1000));
    $('tIn').textContent = clock(g - s);
    $('tLeft').textContent = '-' + clock(e - g);
    const total = bookDuration(b);
    $('tBook').textContent = `Book: ${duration(g)} of ${duration(total)} · ${total ? Math.floor(g * 100 / total) : 0}%`;
    $('pChapter').querySelector('span').textContent = chaptersOf(b)[i]?.title || '';
    $('chVal').textContent = `${i + 1}/${chaptersOf(b).length}`;
    $('miniSub').textContent = $('miniSub').dataset.warn ? 'Not syncing · tap to reconnect' : `${chaptersOf(b)[i]?.title || ''} · ${clock(g)}`;
    if (view === 'chapters' && force) renderChapters();

    // Sleep timer counts only while playing, fades over the last 10 s.
    if (!audio.paused && sleep.mode === 'minutes' && dt) {
      sleep.remaining -= dt;
      audio.volume = clamp(sleep.remaining / 10_000, 0, 1);
      if (sleep.remaining <= 0) { pause(); sleepOff(); toast('Sleep timer: paused'); }
    }
    if (!audio.paused && sleep.mode === 'chapter' && g >= sleep.chapterEnd - 300) { pause(); sleepOff(); toast('Sleep timer: end of chapter'); }
    renderSleepTile();

    if (!audio.paused && now - lastSaved > SAVE_EVERY_MS) saveProgress();
    if ('mediaSession' in navigator && audio.duration && isFinite(audio.duration) && (force || now % 5000 < 300)) {
      try { navigator.mediaSession.setPositionState({ duration: audio.duration, position: Math.min(audio.currentTime, audio.duration), playbackRate: audio.playbackRate }); } catch { /* ignore */ }
    }
  }
  setInterval(tick, 250);

  // ---- speed & sleep -----------------------------------------------------------------
  function setSpeed(v) {
    speed = Math.round(clamp(v, 0.5, 3) * 20) / 20;
    audio.playbackRate = speed; audio.defaultPlaybackRate = speed;
    store.set('speed', speed);
    renderSheet();
  }
  const speedText = (v) => (Number.isInteger(v) ? v.toFixed(1) : String(v)) + '×';
  function sleepOff() { sleep = { mode: 'off', remaining: 0, chapterEnd: 0 }; audio.volume = 1; renderSheet(); }
  function setSleep(v) {
    if (v === 0) sleepOff();
    else if (v === 'chapter') { sleep = { mode: 'chapter', remaining: 0, chapterEnd: cur ? chapterEnd(cur.book, chapterAt(cur.book, globalNow())) : 0, chosen: v }; audio.volume = 1; }
    else { sleep = { mode: 'minutes', remaining: v * 60_000, chosen: v }; audio.volume = 1; }
    renderSheet();
  }
  function renderSleepTile() {
    const v = $('sleepVal');
    v.textContent = sleep.mode === 'minutes' ? clock(sleep.remaining) : sleep.mode === 'chapter' ? 'Chapter' : 'Off';
    v.classList.toggle('on', sleep.mode !== 'off');
  }
  function renderSheet() {
    $('speedVal').textContent = speedText(speed);
    $('spBig').textContent = speed.toFixed(2) + '×';
    const pre = $('spPresets'); pre.replaceChildren();
    for (const s of SPEEDS) { const b = el('button', Math.abs(s - speed) < 0.001 ? 'on' : '', s.toFixed(1)); b.onclick = () => setSpeed(s); pre.append(b); }
    const so = $('sleepOpts'); so.replaceChildren();
    for (const [v, label] of SLEEPS) {
      const on = (v === 0 && sleep.mode === 'off') || (v !== 0 && sleep.chosen === v && sleep.mode !== 'off');
      const b = el('button', on ? 'on' : '', label); b.onclick = () => setSleep(v); so.append(b);
    }
    renderSleepTile();
  }

  // ---- wiring ------------------------------------------------------------------------
  /** Run a user action with a fresh token (refreshing inside the click, where popups are allowed). */
  function act(fn) {
    ensureToken().then(() => fn()).catch((e) => toast(e.message || String(e)));
  }

  async function signIn() {
    try {
      await requestToken();
      if (!email) {
        const me = await (await api('/about?fields=user(emailAddress)')).json();
        email = me.user.emailAddress; store.set('email', email);
      }
      $('account').textContent = (email || '?')[0].toUpperCase();
      $('menuEmail').textContent = email;
      progress = mergeProgress(store.get('progress', {}), {});
      const { hasLibrary } = await loadAll();
      renderLibrary();
      show('library');
      if (!hasLibrary) {
        $('empty').hidden = false;
        $('empty').textContent = 'Your library isn’t in Drive yet. Update the Book Driver app to 0.3.0 or newer and open it once, then refresh.';
      }
    } catch (e) { toast(e.message || String(e), 6000); }
  }

  function signOut() {
    // Forget the session in this browser only. Revoking would also cut off the app.
    if (cur) saveProgress({ keepalive: true });
    audio.pause(); audio.removeAttribute('src'); audio.load();
    token = null; tokenExp = 0; cur = null; books = []; progress = {};
    toWorker({ type: 'signout' });
    store.del('email'); store.del('progress'); email = null;
    $('signin').textContent = 'Sign in with Google';
    $('menu').hidden = true;
    show('welcome');
  }

  $('signin').onclick = signIn;
  $('account').onclick = (e) => { e.stopPropagation(); $('menu').hidden = !$('menu').hidden; };
  document.addEventListener('click', (e) => { if (!$('menu').hidden && !$('menu').contains(e.target)) $('menu').hidden = true; });
  $('refresh').onclick = () => act(async () => { $('menu').hidden = true; await loadAll(); renderLibrary(); toast('Library refreshed'); });
  $('signout').onclick = signOut;
  document.querySelectorAll('.chip').forEach((c) => c.onclick = () => {
    filter = c.dataset.filter;
    document.querySelectorAll('.chip').forEach((x) => x.classList.toggle('on', x === c));
    renderLibrary();
  });

  $('closePlayer').onclick = () => { show('library'); renderLibrary(); };
  $('playBtn').onclick = () => act(togglePlay);
  $('miniPlay').onclick = () => act(togglePlay);
  $('miniOpen').onclick = () => show('player');
  $('back10').onclick = () => act(() => skip(-10_000));
  $('fwd30').onclick = () => act(() => skip(30_000));
  $('prevCh').onclick = () => act(prevChapter);
  $('nextCh').onclick = () => act(nextChapter);
  $('pChapter').onclick = () => show('chapters');
  $('chTile').onclick = () => show('chapters');
  $('closeChapters').onclick = () => show('player');
  $('speedTile').onclick = $('sleepTile').onclick = () => { renderSheet(); $('sheet').hidden = false; };
  $('sheetDone').onclick = () => { $('sheet').hidden = true; };
  $('sheet').onclick = (e) => { if (e.target === $('sheet')) $('sheet').hidden = true; };
  $('spDown').onclick = () => setSpeed(speed - 0.05);
  $('spUp').onclick = () => setSpeed(speed + 0.05);

  const seek = $('seek');
  seek.addEventListener('input', () => {
    if (!cur) return; dragging = true;
    const b = cur.book, i = chapterAt(b, globalNow()), s = chapterStart(b, i), e = chapterEnd(b, i);
    $('tIn').textContent = clock((e - s) * seek.value / 1000);
  });
  seek.addEventListener('change', () => act(() => {
    const b = cur.book, i = chapterAt(b, globalNow()), s = chapterStart(b, i), e = chapterEnd(b, i);
    dragging = false;
    seekGlobal(s + (e - s) * seek.value / 1000);
  }));

  document.addEventListener('keydown', (e) => {
    if (!cur || e.target.matches('input, textarea, button') || e.metaKey || e.ctrlKey || e.altKey) return;
    if (e.code === 'Space') { e.preventDefault(); act(togglePlay); }
    if (e.code === 'ArrowLeft') act(() => skip(-10_000));
    if (e.code === 'ArrowRight') act(() => skip(30_000));
  });

  if ('mediaSession' in navigator) {
    const ms = navigator.mediaSession;
    const set = (a, f) => { try { ms.setActionHandler(a, f); } catch { /* unsupported */ } };
    set('play', () => play());
    set('pause', () => pause());
    set('seekbackward', () => skip(-10_000));
    set('seekforward', () => skip(30_000));
    set('previoustrack', prevChapter);
    set('nexttrack', nextChapter);
    set('seekto', (d) => { if (cur && d.seekTime != null) seekGlobal(trackOffset(cur.book, cur.track) + d.seekTime * 1000); });
  }

  // Save when the tab is hidden or closed; pick up other devices' progress when it's back.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') { if (cur && (!audio.paused || Date.now() - lastSaved > 2000)) saveProgress({ keepalive: true }); }
    else pull();
  });
  window.addEventListener('pagehide', () => { if (cur && !audio.paused) saveProgress({ keepalive: true }); });
  window.addEventListener('focus', () => pull());

  renderSheet();
  const api_ = { gsiLoaded, gsiFailed };
  // Hooks for the local end-to-end test only.
  if (location.hostname === 'localhost') api_.__test = { expireToken() { tokenExp = Date.now() - 1; tokenAt -= 3_600_000; }, save: () => saveProgress() };
  return api_;
})();
