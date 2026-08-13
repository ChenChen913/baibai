/** 轨迹平滑与抽稀（M2 算法层，纯函数） */

import type { LatLng } from './geo.js';
import type { TrackPoint } from './state.js';

/** 按 jump 标记切段（jump 点归入后段开头） */
export function jumpSplit(pts: TrackPoint[]): TrackPoint[][] {
  const segs: TrackPoint[][] = [];
  let cur: TrackPoint[] = [];
  for (const p of pts) {
    if (p.jump && cur.length > 0) {
      segs.push(cur);
      cur = [];
    }
    cur.push(p);
  }
  if (cur.length > 0) segs.push(cur);
  return segs;
}

/** 段内滑动平均（窗口不跨段、跳过 jump 点；jump 点自身保持原坐标；长度不变） */
export function movingAverage(pts: TrackPoint[], w = 5): TrackPoint[] {
  const half = Math.floor(w / 2);
  return pts.map((p, i) => {
    if (p.jump) return { ...p }; // 跳变点保持原样
    const lo = Math.max(0, i - half);
    const hi = Math.min(pts.length - 1, i + half);
    let lat = 0;
    let lng = 0;
    let n = 0;
    for (let j = lo; j <= hi; j++) {
      if (pts[j].jump) continue;
      lat += pts[j].pos.lat;
      lng += pts[j].pos.lng;
      n += 1;
    }
    if (n === 0) return { ...p };
    return { ...p, pos: { lat: lat / n, lng: lng / n } };
  });
}

/** 平滑管线：切段 → 段内滑动平均 → 拍平（长度不变） */
export function smoothTrack(pts: TrackPoint[], w = 5): TrackPoint[] {
  return jumpSplit(pts).flatMap((seg) => movingAverage(seg, w));
}

const R = 6371000;

/** 等距圆柱平面近似（村庄尺度误差可忽略） */
function toXY(p: LatLng, ref: LatLng): { x: number; y: number } {
  const rad = Math.PI / 180;
  return {
    x: (p.lng - ref.lng) * rad * R * Math.cos(ref.lat * rad),
    y: (p.lat - ref.lat) * rad * R,
  };
}

/** 点到线段的最短距离（米，平面近似） */
function distToSegM(p: LatLng, a: LatLng, b: LatLng): number {
  const P = toXY(p, a);
  const B = toXY(b, a);
  const len2 = B.x * B.x + B.y * B.y;
  if (len2 === 0) return Math.hypot(P.x, P.y);
  let t = (P.x * B.x + P.y * B.y) / len2;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(P.x - t * B.x, P.y - t * B.y);
}

/** Douglas-Peucker 抽稀：首尾必保留 */
export function douglasPeucker(pts: LatLng[], epsM: number): LatLng[] {
  if (pts.length <= 2) return pts.slice();
  const first = pts[0];
  const last = pts[pts.length - 1];
  let maxD = -1;
  let maxI = -1;
  for (let i = 1; i < pts.length - 1; i++) {
    const d = distToSegM(pts[i], first, last);
    if (d > maxD) {
      maxD = d;
      maxI = i;
    }
  }
  if (maxD <= epsM) return [first, last];
  const left = douglasPeucker(pts.slice(0, maxI + 1), epsM);
  const right = douglasPeucker(pts.slice(maxI), epsM);
  return [...left.slice(0, -1), ...right];
}
