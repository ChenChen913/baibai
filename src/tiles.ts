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

/** 预载覆盖下限：Home 周边 ±0.02°（约 2.2km）——单点场景与旧版行为一致 */
export const PRELOAD_MIN_RADIUS_DEG = 0.02;
/** 预载张数上限：超限时优先保覆盖、逐级降低最高缩放（避免清单无限膨胀） */
export const PRELOAD_MAX_TILES = 800;
const PRELOAD_Z_MIN = 13;
const PRELOAD_Z_MAX = 16;

export interface PreloadCoverage {
  lat: number;
  lng: number;
  dLat: number;
  dLng: number;
}

/** 由实际点位算预载覆盖框：至少 ±0.02°，并留 10% 余量（户/清单点位于边缘时不出现缺口） */
export function preloadCoverage(
  points: { lat: number; lng: number }[],
): PreloadCoverage | null {
  if (points.length === 0) return null;
  let minLat = Infinity;
  let maxLat = -Infinity;
  let minLng = Infinity;
  let maxLng = -Infinity;
  for (const p of points) {
    if (p.lat < minLat) minLat = p.lat;
    if (p.lat > maxLat) maxLat = p.lat;
    if (p.lng < minLng) minLng = p.lng;
    if (p.lng > maxLng) maxLng = p.lng;
  }
  return {
    lat: (minLat + maxLat) / 2,
    lng: (minLng + maxLng) / 2,
    dLat: Math.max((maxLat - minLat) / 2, PRELOAD_MIN_RADIUS_DEG) * 1.1,
    dLng: Math.max((maxLng - minLng) / 2, PRELOAD_MIN_RADIUS_DEG) * 1.1,
  };
}

function coverageTiles(c: PreloadCoverage, zLo: number, zHi: number): string[] {
  const out: string[] = [];
  for (let z = zLo; z <= zHi; z++) {
    for (const [x, y] of tilesIn(c.lat, c.lng, c.dLat, c.dLng, z)) {
      out.push(tileUrl('street', x, y, z));
      out.push(tileUrl('sat', x, y, z));
    }
  }
  return out;
}

/** 单点预载清单：Home ±0.02°（约 2.2km）× z13~z16（约 170 张、3.5MB；z16 足以辨认村巷），每级含普通+卫星 */
export function preloadTileList(lat: number, lng: number): string[] {
  const c = preloadCoverage([{ lat, lng }]);
  if (!c) return [];
  return coverageTiles(c, PRELOAD_Z_MIN, PRELOAD_Z_MAX);
}

/** 某级缩放下覆盖框内的瓦片数（含普通+卫星两层），只算数量不生成 URL */
function tileCountAt(c: PreloadCoverage, z: number): number {
  const x0 = tileX(c.lng - c.dLng, z);
  const x1 = tileX(c.lng + c.dLng, z);
  const y0 = tileY(c.lat + c.dLat, z);
  const y1 = tileY(c.lat - c.dLat, z);
  return (x1 - x0 + 1) * (y1 - y0 + 1) * 2;
}

/**
 * 多点自适应预载：覆盖 Home + 已拜访户 + 今年清单坐标（走远不出界）。
 * 先按数量预算选最高缩放（保住覆盖范围，牺牲细节），极端分散数据再截断——
 * 绝不先生成巨大数组再丢弃（两点相隔 10° 时 z16 会达到百万级，直接把页面卡死）。
 */
export function preloadTileListFor(points: { lat: number; lng: number }[]): string[] {
  const c = preloadCoverage(points);
  if (!c) return [];
  let total = 0;
  let zHi = PRELOAD_Z_MIN;
  for (let z = PRELOAD_Z_MIN; z <= PRELOAD_Z_MAX; z++) {
    const n = tileCountAt(c, z);
    if (total + n > PRELOAD_MAX_TILES) break;
    total += n;
    zHi = z;
  }
  const list = coverageTiles(c, PRELOAD_Z_MIN, zHi);
  return list.length > PRELOAD_MAX_TILES
    ? list.slice(0, PRELOAD_MAX_TILES)
    : list;
}
/**
 * 缓存 key 规范化（SW 用）：把高德瓦片子域数字归一（webrd0X/webst0X → *），
 * 预载与运行时请求共用同一条缓存，断网时不会因子域不同而 miss。
 */
export function normalizeTileUrl(url: string): string {
  return url.replace(/(webrd|webst)0[1-4]\./g, '$10*.');
}


