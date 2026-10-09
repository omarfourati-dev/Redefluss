// Redefluss service worker – scope "/".
// Caches only the app shell (index, hashed JS/CSS under /_app/immutable/, icons) so the installed app starts fast.
// Never touched: /api (sentences, corrections, tokens, audio), /healthz, /metrics, anything but GET.
// scripts/stamp-sw.mjs fills in BUILD and ASSETS after `vite build`: every deploy is a new worker ("Neu laden").
const BUILD = '__BUILD__';
const ASSETS = [/*__ASSETS__*/];
const SHELL = 'redefluss-shell-' + BUILD;
const ICONS = ['icon-192.png', 'icon-512.png', 'maskable-512.png'].map((f) => '/icons/' + f);
const PASS = ['/api', '/healthz', '/metrics'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(SHELL).then((c) => c.addAll(['/', ...ASSETS, ...ICONS])));
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const name of await caches.keys()) if (name.startsWith('redefluss-shell-') && name !== SHELL) await caches.delete(name);
    await self.clients.claim();
  })());
});

self.addEventListener('message', (event) => { if (event.data === 'skip-waiting') self.skipWaiting(); });

self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== self.location.origin) return;
  if (PASS.some((p) => url.pathname === p || url.pathname.startsWith(p + '/'))) return;
  if (req.mode === 'navigate') event.respondWith(networkFirst(req));
  else if (url.pathname.startsWith('/_app/immutable/') || ICONS.includes(url.pathname)) event.respondWith(cacheFirst(req));
});

async function networkFirst(req) {
  const cache = await caches.open(SHELL);
  try {
    const res = await fetch(req);
    if (res.ok && (res.headers.get('Content-Type') || '').includes('text/html')) await cache.put('/', res.clone());
    return res;
  } catch (err) {
    const cached = await cache.match('/');
    if (cached) return cached;
    throw err;
  }
}

async function cacheFirst(req) {
  const cache = await caches.open(SHELL);
  const hit = await cache.match(req);
  if (hit) return hit;
  const res = await fetch(req);
  if (res.ok) await cache.put(req, res.clone());
  return res;
}
