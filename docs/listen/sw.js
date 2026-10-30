// Book Driver web player: streams audio from Google Drive for the page's <audio> element.
//
// A media element can't send an Authorization header, so <audio src="stream/<fileId>?size=N">
// is answered here instead: we forward the browser's Range request to Drive with the user's
// access token and hand back a 206 the element can seek in. The token lives only in memory.

const VERSION = 2;
let token = null;

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('message', (e) => {
  const m = e.data || {};
  if (m.type === 'token') token = m.token;
  if (m.type === 'signout') token = null;
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  const at = url.pathname.indexOf('/stream/');
  if (at < 0 || url.origin !== self.location.origin) return;
  const fileId = decodeURIComponent(url.pathname.slice(at + '/stream/'.length));
  const size = Number(url.searchParams.get('size')) || null;
  e.respondWith(stream(fileId, size, e.request));
});

async function askForToken() {
  // The worker may have been restarted and lost the token: ask an open page for it.
  const pages = await self.clients.matchAll({ type: 'window' });
  for (const page of pages) {
    const t = await new Promise((resolve) => {
      const ch = new MessageChannel();
      ch.port1.onmessage = (ev) => resolve(ev.data && ev.data.token);
      page.postMessage({ type: 'need-token' }, [ch.port2]);
      setTimeout(() => resolve(null), 2000);
    });
    if (t) return t;
  }
  return null;
}

async function stream(fileId, size, request) {
  if (!token) token = await askForToken();
  if (!token) return new Response('Not signed in', { status: 401 });

  const m = /^bytes=(\d+)-(\d*)$/.exec(request.headers.get('Range') || '');
  const start = m ? Number(m[1]) : 0;
  const end = m && m[2] ? Number(m[2]) : (size ? size - 1 : null);
  const range = end != null ? `bytes=${start}-${end}` : `bytes=${start}-`;

  let res;
  try {
    res = await fetch(`https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}?alt=media`, {
      headers: { Authorization: `Bearer ${token}`, Range: range },
    });
  } catch (err) {
    return new Response('Network error', { status: 503 });
  }
  if (res.status === 401) {
    token = null;
    notifyPages({ type: 'auth-expired' });
  }
  if (!res.ok) return new Response(await res.text(), { status: res.status });

  // Drive's CORS response doesn't expose Content-Range, so rebuild the headers we know.
  const headers = new Headers();
  headers.set('Content-Type', res.headers.get('Content-Type') || 'audio/mp4');
  headers.set('Accept-Ranges', 'bytes');
  if (res.status === 206 && size) {
    const last = end != null ? end : size - 1;
    headers.set('Content-Range', `bytes ${start}-${last}/${size}`);
    headers.set('Content-Length', String(last - start + 1));
  } else {
    const len = res.headers.get('Content-Length');
    if (len) headers.set('Content-Length', len);
  }
  return new Response(res.body, { status: res.status === 206 ? 206 : 200, headers });
}

async function notifyPages(msg) {
  for (const page of await self.clients.matchAll({ type: 'window' })) page.postMessage(msg);
}
