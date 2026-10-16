// App shell from cache so installed web apps open instantly. Build files only:
// API is cookie-auth + no-store, web app is online-only.

// Prepended by the service-worker plugin (vite.config.ts); file must stay import-free.
declare const PRECACHE: string[]
declare const VERSION: string

const sw = self as unknown as ServiceWorkerGlobalScope
const CACHE = `shell-${VERSION}`
const precached = new Set(PRECACHE)
const NETWORK_ONLY = ['/api/', '/healthz', '/readyz']

sw.addEventListener('install', (event) => {
  // no-cache: index.html and the root files may sit stale in the HTTP cache.
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(PRECACHE.map((u) => new Request(u, { cache: 'no-cache' })))))
  // Don't wait for windows to close: next load gets this build. Safe with no lazy JS chunks;
  // an open old page keeps its JS and at worst misses an unloaded font subset.
  void sw.skipWaiting()
})

sw.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => sw.clients.claim()),
  )
})

sw.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET') return
  const url = new URL(req.url)
  if (url.origin !== sw.location.origin) return
  if (NETWORK_ONLY.some((p) => url.pathname === p || url.pathname.startsWith(p))) return

  // Mirrors the server's SPA fallback: extensionless paths are app routes.
  if (req.mode === 'navigate' && !/\.[^/]*$/.test(url.pathname)) {
    event.respondWith(fromCache('/index.html', req))
    return
  }
  if (precached.has(url.pathname)) event.respondWith(fromCache(url.pathname, req))
})

async function fromCache(key: string, req: Request): Promise<Response> {
  const hit = await caches.match(key, { cacheName: CACHE })
  return hit ?? fetch(req)
}
