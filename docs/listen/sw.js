// Book Driver web player: streams audio from Google Drive for the page's <audio> element.
//
// A media element can't send an Authorization header, so <audio src="stream/<fileId>"> is
// answered here instead: we forward the browser's Range request to Drive with the user's
// access token and hand back a 206 the element can seek in. The token lives only in memory.

let token = null;
const sizes = new Map(); // fileId -> total bytes, for building Content-Range ourselves

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('message', (e) => {
  const m = e.data || {};
  if (m.type === 'token') token = m.token;
  if (m.type === 'size') sizes.set(m.fileId, m.size);
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  const at = url.pathname.indexOf('/stream/');
  if (at < 0) return;
  const fileId = decodeURIComponent(url.pathname.slice(at + '/stream/'.length));
  e.respondWith(stream(fileId, e.request));
});

async function askForToken() {
  // The worker may have been restarted and lost the token: ask an open page for it.
  const pages = await self.clients.matchAll({ type: 'window' });
  if (!pages.length) return null;
  return new Promise((resolve) => {
    const ch = new MessageChannel();
    ch.port1.onmessage = (ev) => resolve(ev.data && ev.data.token);
    pages[0].postMessage({ type: 'need-token' }, [ch.port2]);
    setTimeout(() => resolve(null), 3000);
  });
}

async function stream(fileId, request) {
  if (!token) token = await askForToken();
  if (!token) return new Response('Not signed in', { status: 401 });

  const size = sizes.get(fileId);
  let range = request.headers.get('Range') || 'bytes=0-';
  const m = /^bytes=(\d+)-(\d*)$/.exec(range);
  let start = m ? Number(m[1]) : 0;
  let end = m && m[2] ? Number(m[2]) : (size ? size - 1 : null);
  range = end != null ? `bytes=${start}-${end}` : `bytes=${start}-`;

  const res = await fetch(`https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}?alt=media`, {
    headers: { Authorization: `Bearer ${token}`, Range: range },
  });
  if (res.status === 401) token = null;
  if (!res.ok) return new Response(await res.text(), { status: res.status });

  // Drive's CORS response may not expose Content-Range, so rebuild the headers we know.
  const headers = new Headers();
  headers.set('Content-Type', res.headers.get('Content-Type') || 'audio/mp4');
  headers.set('Accept-Ranges', 'bytes');
  const len = res.headers.get('Content-Length');
  if (res.status === 206 && size) {
    const last = end != null ? end : size - 1;
    headers.set('Content-Range', `bytes ${start}-${last}/${size}`);
    headers.set('Content-Length', String(last - start + 1));
  } else if (len) {
    headers.set('Content-Length', len);
  }
  return new Response(res.body, { status: res.status === 206 ? 206 : 200, headers });
}
