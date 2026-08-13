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

/** 段内滑动平均（窗口不跨段、跳过 jump 点；jump 点与段端点保持原坐标防轨迹被拉短；长度不变） */
export function movingAverage(pts: TrackPoint[], w = 5): TrackPoint[] {
  const half = Math.floor(w / 2);
  const n = pts.length;
  return pts.map((p, i) => {
    if (p.jump || i === 0 || i === n - 1) return { ...p }; // 端点/jump 点保持原样
    const lo = Math.max(0, i - half);
    const hi = Math.min(n - 1, i + half);
    let lat = 0;
    let lng = 0;
    let cnt = 0;
    for (let j = lo; j <= hi; j++) {
      if (pts[j].jump) continue;
      lat += pts[j].pos.lat;
      lng += pts[j].pos.lng;
      cnt += 1;
    }
    if (cnt === 0) return { ...p };
    return { ...p, pos: { lat: lat / cnt, lng: lng / cnt } };
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

function dpRange(pts: LatLng[], lo: number, hi: number, epsM: number): number[] {
  if (hi - lo <= 1) return [lo, hi];
  const first = pts[lo];
  const last = pts[hi];
  let maxD = -1;
  let maxI = -1;
  for (let i = lo + 1; i < hi; i++) {
    const d = distToSegM(pts[i], first, last);
    if (d > maxD) {
      maxD = d;
      maxI = i;
    }
  }
  if (maxD <= epsM) return [lo, hi];
  const left = dpRange(pts, lo, maxI, epsM);
  const right = dpRange(pts, maxI, hi, epsM);
  return [...left.slice(0, -1), ...right];
}

/** Douglas-Peucker 抽稀：返回保留点下标（首尾必保留；供回放对齐时间轴） */
export function douglasPeuckerKeep(pts: LatLng[], epsM: number): number[] {
  if (pts.length <= 2) return pts.map((_, i) => i);
  return dpRange(pts, 0, pts.length - 1, epsM);
}

/** Douglas-Peucker 抽稀：返回保留点坐标（首尾必保留） */
export function douglasPeucker(pts: LatLng[], epsM: number): LatLng[] {
  return douglasPeuckerKeep(pts, epsM).map((i) => pts[i]);
}
