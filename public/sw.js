/* 拜拜 Service Worker：运行时缓存（首次在线访问全量入缓存，之后断网可跑）
   发布新版本时递增 CACHE 版本号，旧缓存会被 activate 清掉 */
const CACHE = 'baibai-v3';

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(['./'])));
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET') return;
  const sameOrigin = url.origin === location.origin;
  const isTile = url.hostname.includes('tile.openstreetmap.org');
  if (!sameOrigin && !isTile) return;

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
          .catch(() => {
            // 离线且未缓存：地图瓦片返回空占位（不炸地图），应用资源走预缓存
            if (isTile) {
              return new Response('', {
                status: 200,
                headers: { 'Content-Type': 'image/png' },
              });
            }
            return Response.error();
          }),
    ),
  );
});
