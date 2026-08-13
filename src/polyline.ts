/** 折线工具（M3 展示层纯函数：路线闭环折线、弧长重采样、插值——压轴 morph 用） */

import type { LatLng } from './geo.js';
import type { SessionData } from './state.js';

/** 路线（含闭合回 home）的直线折线 */
export function routePolyline(s: SessionData, order: string[]): LatLng[] {
  const posOf = (id: string): LatLng =>
    id === 'home' ? s.home : s.nodes.find((n) => n.id === id)!.pos;
  const pts = order.map(posOf);
  pts.push(posOf(order[0]));
  return pts;
}

export interface XY {
  x: number;
  y: number;
}

/** 按弧长均匀重采样为 m 个点（首尾必含；退化折线退化为重复点） */
export function resamplePolyline(pts: XY[], m: number): XY[] {
  if (pts.length === 0) return [];
  if (m <= 1) return [{ ...pts[0] }];
  const segLens: number[] = [];
  let total = 0;
  for (let i = 1; i < pts.length; i++) {
    const l = Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y);
    segLens.push(l);
    total += l;
  }
  if (total === 0) return Array.from({ length: m }, () => ({ ...pts[0] }));
  const out: XY[] = [{ ...pts[0] }];
  let seg = 0;
  let acc = 0;
  for (let k = 1; k < m - 1; k++) {
    const target = (total * k) / (m - 1);
    while (seg < segLens.length && acc + segLens[seg] < target) {
      acc += segLens[seg];
      seg += 1;
    }
    const si = Math.min(seg, segLens.length - 1);
    const l = segLens[si] || 1;
    let f = (target - acc) / l;
    f = Math.max(0, Math.min(1, f));
    const a = pts[Math.min(si, pts.length - 1)];
    const b = pts[Math.min(si + 1, pts.length - 1)];
    out.push({
      x: a.x + (b.x - a.x) * f,
      y: a.y + (b.y - a.y) * f,
    });
  }
  out.push({ ...pts[pts.length - 1] });
  return out;
}

/** 两组同长折线逐点线性插值（t∈[0,1]；morph 动画核心） */
export function lerpPolyline(a: XY[], b: XY[], t: number): XY[] {
  const n = Math.min(a.length, b.length);
  const out: XY[] = [];
  for (let i = 0; i < n; i++) {
    out.push({
      x: a[i].x + (b[i].x - a[i].x) * t,
      y: a[i].y + (b[i].y - a[i].y) * t,
    });
  }
  return out;
}
