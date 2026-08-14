/** Web Mercator 瓦片数学（P0 离线预载）。
 * 与安卓 TileMath.kt 同一套公式与同一组测试值——任何一侧改动必须同步另一侧（契约）。 */

export function tileX(lng: number, z: number): number {
  const n = 1 << z;
  return Math.min(Math.max(Math.floor(((lng + 180) / 360) * n), 0), n - 1);
}

export function tileY(lat: number, z: number): number {
  const n = 1 << z;
  const rad = (lat * Math.PI) / 180;
  const y = ((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2) * n;
  return Math.min(Math.max(Math.floor(y), 0), n - 1);
}

/** 高德瓦片 URL（style: street 矢量 / sat 卫星；sub 1~4） */
export function tileUrl(style: 'street' | 'sat', x: number, y: number, z: number, sub = 1): string {
  const host = style === 'sat' ? 'webst0' + sub : 'webrd0' + sub;
  const query = style === 'sat' ? 'style=6' : 'lang=zh_cn&size=1&scale=1&style=8';
  return 'https://' + host + '.is.autonavi.com/appmaptile?' + query + '&x=' + x + '&y=' + y + '&z=' + z;
}
/** 以 (centerLat,centerLng) 为中心、经纬度各扩展 dLat/dLng 的矩形内，z 级全部瓦片 */
export function tilesIn(
  centerLat: number,
  centerLng: number,
  dLat: number,
  dLng: number,
  z: number,
): [number, number][] {
  const x0 = tileX(centerLng - dLng, z);
  const x1 = tileX(centerLng + dLng, z);
  const y0 = tileY(centerLat + dLat, z); // 北边（y 更小）
  const y1 = tileY(centerLat - dLat, z);
  const out: [number, number][] = [];
  for (let x = x0; x <= x1; x++) {
    for (let y = y0; y <= y1; y++) out.push([x, y]);
  }
  return out;
}

/** 预载清单：Home ±0.02°（约 2.2km）× z13~z16（约 170 张、3.5MB；z16 足以辨认村巷），每级含普通+卫星 */
export function preloadTileList(lat: number, lng: number): string[] {
  const out: string[] = [];
  for (let z = 13; z <= 16; z++) {
    for (const [x, y] of tilesIn(lat, lng, 0.02, 0.02, z)) {
      out.push(tileUrl('street', x, y, z));
      out.push(tileUrl('sat', x, y, z));
    }
  }
  return out;
}
/**
 * 缓存 key 规范化（SW 用）：把高德瓦片子域数字归一（webrd0X/webst0X → *），
 * 预载与运行时请求共用同一条缓存，断网时不会因子域不同而 miss。
 */
export function normalizeTileUrl(url: string): string {
  return url.replace(/(webrd|webst)0[1-4]\./g, '$10*.');
}


