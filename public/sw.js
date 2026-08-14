/* 拜拜 Service Worker
   应用资源：运行时缓存 + 首页 network-first（发布新版本时递增 CACHE 版本号）
   地图瓦片：独立缓存 + 条数上限（LRU 淘汰），杜绝无限膨胀
*/
const CACHE = 'baibai-v4';
const TILE_CACHE = 'baibai-tiles-v1';
const TILE_MAX = 600;

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(['./'])));
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((k) => k !== CACHE && k !== TILE_CACHE)
            .map((k) => caches.delete(k)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

/** 瓦片缓存超限时淘汰最旧条目 */
async function trimTiles(): Promise<void> {
  const c = await caches.open(TILE_CACHE);
  const keys = await c.keys();
  let overflow = keys.length - TILE_MAX;
  for (const k of keys) {
    if (overflow <= 0) break;
    await c.delete(k);
    overflow--;
  }
}

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  const sameOrigin = url.origin === location.origin;
  const isTile = url.hostname.includes('tile.openstreetmap.org');
  if (!sameOrigin && !isTile) return;

  if (isTile) {
    // 瓦片：cache-first + 限容缓存；离线未缓存时返回空占位（轨迹/标记在应用层不受影响）
    e.respondWith(
      caches.match(e.request).then(
        (hit) =>
          hit ||
          fetch(e.request)
            .then((res) => {
              if (res && res.status === 200) {
                const copy = res.clone();
                caches
                  .open(TILE_CACHE)
                  .then((c) => c.put(e.request, copy))
                  .then(() => trimTiles())
                  .catch(() => {});
              }
              return res;
            })
            .catch(() =>
              new Response('', {
                status: 200,
                headers: { 'Content-Type': 'image/png' },
              }),
            ),
      ),
    );
    return;
  }

  // 自家资源：导航请求 network-first（保证新版本及时生效），其余 cache-first
  if (e.request.mode === 'navigate') {
    e.respondWith(
      fetch(e.request)
        .then((res) => {
          if (res && res.status === 200) {
            const copy = res.clone();
            caches
              .open(CACHE)
              .then((c) => c.put(e.request, copy))
              .catch(() => {});
          }
          return res;
        })
        .catch(() => caches.match(e.request).then((hit) => hit || caches.match('./'))),
    );
    return;
  }

  e.respondWith(
    caches.match(e.request).then(
      (hit) =>
        hit ||
        fetch(e.request)
          .then((res) => {
            if (res && res.status === 200) {
              const copy = res.clone();
              caches
                .open(CACHE)
                .then((c) => c.put(e.request, copy))
                .catch(() => {});
            }
            return res;
          })
          .catch(() => Response.error()),
    ),
  );
});
