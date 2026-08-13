/** 地理基础运算（纯函数，M1 算法层） */

export interface LatLng {
  lat: number;
  lng: number;
}

/** 一次 GPS 定位（含精度） */
export interface Fix {
  pos: LatLng;
  acc: number; // 精度（米）
}

const R = 6371000; // 地球半径（米）

/** 球面距离（米），haversine 公式 */
export function haversineM(a: LatLng, b: LatLng): number {
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

/** 坐标分量中位数（抗单点跳变；空数组返回 null） */
export function medianPos(ps: LatLng[]): LatLng | null {
  if (ps.length === 0) return null;
  const mid = Math.ceil(ps.length / 2) - 1; // 奇数取正中，偶数取中偏前
  const lats = ps.map((p) => p.lat).sort((x, y) => x - y);
  const lngs = ps.map((p) => p.lng).sort((x, y) => x - y);
  return { lat: lats[mid], lng: lngs[mid] };
}

export interface Located {
  id: string;
  pos: LatLng;
}

/** 找最近节点；调用方按 ≤thresholdM 决定合并还是新建 */
export function nearest(
  pos: LatLng,
  located: Located[],
): { node: Located | null; distM: number } {
  let best: Located | null = null;
  let bestD = Infinity;
  for (const n of located) {
    const d = haversineM(pos, n.pos);
    if (d < bestD) {
      bestD = d;
      best = n;
    }
  }
  return { node: best, distM: bestD };
}
