/* 拜拜 Service Worker
   应用资源：运行时缓存 + 首页 network-first（发布新版本时递增 CACHE 版本号）
   地图瓦片：独立缓存 + 条数上限（LRU 淘汰），杜绝无限膨胀
*/
const CACHE = 'baibai-v5'; // P0：瓦片缓存域名扩展 + 预载通道，递增版本强制刷新 SW
const TILE_CACHE = 'baibai-tiles-v1';
const TILE_MAX = 2000; // P0：预载 z13~z16 双图层约 170 张，留足余量（LRU 淘汰）

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

// P0：缓存 key 规范化——高德子域数字归一（webrd0X/webst0X → *），预载与运行时共用缓存
const normTileUrl = (u) => u.replace(/(webrd|webst)0[1-4]\./g, '$10*.');

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
  const TILE_HOSTS = ['is.autonavi.com', 'tile.openstreetmap.org', 'tile.openstreetmap.fr'];
  const isTile = TILE_HOSTS.some((h) => url.hostname.includes(h));
  if (!sameOrigin && !isTile) return;

  if (isTile) {
    // 瓦片：cache-first + 限容缓存；离线未缓存时返回空占位（轨迹/标记在应用层不受影响）
    const cacheKey = normTileUrl(url.href);
    e.respondWith(
      caches.match(cacheKey).then(
        (hit) =>
          hit ||
          fetch(e.request)
            .then((res) => {
              if (res && res.status === 200) {
                const copy = res.clone();
                caches
                  .open(TILE_CACHE)
                  .then((c) => c.put(cacheKey, copy))
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

// P0：离线预载通道——页面发 baibai-preload{tiles:[...]}，SW 逐张拉取入库（no-cors，图源无 CORS 头）
self.addEventListener('message', (e) => {
  const d = e.data;
  if (!d || d.type !== 'baibai-preload' || !Array.isArray(d.tiles)) return;
  (async () => {
    let ok = 0;
    for (const t of d.tiles) {
      try {
        const res = await fetch(t, { mode: 'no-cors' });
        if (res.type !== 'error') {
          await caches.open(TILE_CACHE).then((c) => c.put(normTileUrl(t), res.clone()));
          ok += 1;
        }
      } catch {
        /* 单张失败继续 */
      }
    }
    trimTiles();
    if (e.source && e.source.postMessage) {
      e.source.postMessage({ type: 'baibai-preload-done', ok, total: d.tiles.length });
    }
  })();
});
