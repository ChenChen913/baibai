/** 轨迹分段（M2 算法层）+ SVG 视口投影（M2/M3 共用，纯函数） */

import type { LatLng } from './geo.js';
import { haversineM } from './geo.js';
import type { Mode, SessionData, TrackPoint } from './state.js';
import { smoothTrack } from './smooth.js';

export interface Edge {
  fromId: string;
  toId: string;
  mode: Mode;
  departT: number;
  arriveT: number;
  raw: TrackPoint[];
  smoothed: TrackPoint[];
  distM: number; // 平滑链逐段 haversine 求和
}

interface Stop {
  nodeId: string;
  t: number; // 窗口终点（到访时刻）
  departT: number; // 离开时刻（行程时间 = 下一停靠点 t − 本停靠点 departT）
  mode: Mode;
}

/** 会话 → 边序列（home→…→home；中途回 Home 多段循环天然成立） */
export function buildEdges(s: SessionData): Edge[] {
  // 首停靠点取首点前 1ms，保证第一条边包含第一个轨迹点
  const t0 = (s.points[0]?.t ?? s.createdAt) - 1;
  const tEnd = s.points[s.points.length - 1]?.t ?? s.updatedAt;
  const ordered = [...s.visits].sort((a, b) => a.arriveT - b.arriveT);

  const stops: Stop[] = [
    { nodeId: 'home', t: t0, departT: t0, mode: ordered[0]?.mode ?? s.currentMode },
  ];
  for (const v of ordered) {
    stops.push({
      nodeId: v.nodeId,
      t: v.arriveT,
      departT: v.leaveT ?? v.arriveT,
      mode: v.mode,
    });
  }
  if (s.finished) {
    stops.push({ nodeId: 'home', t: tEnd, departT: tEnd, mode: s.currentMode });
  }

  const edges: Edge[] = [];
  for (let i = 0; i + 1 < stops.length; i++) {
    const a = stops[i];
    const b = stops[i + 1];
    if (b.t <= a.t) continue;
    // E-15：排除跳变点（它们会入库供回顾页展示/剔除），否则一个 900m 的坏点
    // 会让实走距离虚增上千公里级的误差（demo 场景实测虚增 1652m / +86%）
    const raw = s.points.filter((p) => !p.jump && p.t > a.t && p.t <= b.t);
    if (raw.length === 0) continue;
    const smoothed = smoothTrack(raw);
    let distM = 0;
    for (let k = 1; k < smoothed.length; k++) {
      distM += haversineM(smoothed[k - 1].pos, smoothed[k].pos);
    }
    edges.push({
      fromId: a.nodeId,
      toId: b.nodeId,
      mode: b.mode, // 进入该停靠点的出行方式（D19）
      departT: a.departT, // 行程时间基准：上一户的离开时刻
      arriveT: b.t,
      raw,
      smoothed,
      distM,
    });
  }
  return edges;
}

export interface Bounds {
  minLat: number;
  maxLat: number;
  minLng: number;
  maxLng: number;
}

export const MIN_VIEW_SPAN_M = 60; // R7：回放/回顾投影最小跨度——静止小点团不得被放大充满视口

/**
 * 统一投影参考点集（E-1 修复）：家 + 全部户 + 全部轨迹点。
 *
 * 为什么需要：回放轨迹来自 buildPlan（内部按"轨迹点"算 bounds），而节点标记/最优路线
 * 来自 projectToView([home, ...nodes])（按"节点"算 bounds）。两组点的包围盒不同时，
 * 同一物理点在两套投影下坐标不同 → 叠加显示错位（实测最大 220px / 480px 画布，
 * morph 动画首末点错位、三线对比失去可比性）。
 *
 * 约定：同一个会话的所有视图（回放轨迹 / 节点标记 / 最优三线 / morph 重采样）
 * 必须传同一个 ref，坐标才严格对齐。
 */
export function viewRefPoints(s: SessionData): LatLng[] {
  const out: LatLng[] = [s.home];
  for (const n of s.nodes) out.push(n.pos);
  // E-15：跳变点不参与投影参考——一个 900m 外的坏点会把包围盒撑大，
  // 导致整幅图被缩小、看起来"什么都没画"
  for (const p of s.points) if (!p.jump) out.push(p.pos);
  return out;
}

export function boundsOf(pts: LatLng[]): Bounds | null {
  if (pts.length === 0) return null;
  let minLat = Infinity;
  let maxLat = -Infinity;
  let minLng = Infinity;
  let maxLng = -Infinity;
  for (const p of pts) {
    if (p.lat < minLat) minLat = p.lat;
    if (p.lat > maxLat) maxLat = p.lat;
    if (p.lng < minLng) minLng = p.lng;
    if (p.lng > maxLng) maxLng = p.lng;
  }
  return { minLat, maxLat, minLng, maxLng };
}

/**
 * 等比投影到视口（北在上、留白 10%），全部点落在 [0,w]×[0,h] 内。
 * R7：跨度下限 MIN_VIEW_SPAN_M（60m）——静止小点团（几米抖动范围）不得被放大充满视口；
 * 仅在任一方向跨度低于下限时以实际中心扩展，大跨度场景投影结果与旧版逐位一致。
 * E-1：ref 传"统一参考点集"（viewRefPoints）时，本函数只负责投影 pts，
 * bounds 一律按 ref 计算——同会话多图层叠加必须传同一个 ref。
 */
export function projectToView(
  pts: LatLng[],
  w: number,
  h: number,
  pad = 0.1,
  ref?: LatLng[],
): { x: number; y: number }[] {
  const b = boundsOf(ref && ref.length > 0 ? ref : pts);
  if (!b) return [];
  // 米→度换算（等距圆柱近似）：纬度 1° ≈ 111320m；经度 1° ≈ 111320·cos(纬度)
  const cLat = (b.maxLat + b.minLat) / 2;
  const cLng = (b.maxLng + b.minLng) / 2;
  const rad = Math.PI / 180;
  const minSpanLat = MIN_VIEW_SPAN_M / 111320;
  const minSpanLng = MIN_VIEW_SPAN_M / (111320 * Math.cos(cLat * rad));
  let spanLat = Math.max(b.maxLat - b.minLat, 1e-9);
  let spanLng = Math.max(b.maxLng - b.minLng, 1e-9);
  let minLat = b.minLat;
  let maxLat = b.maxLat;
  let minLng = b.minLng;
  if (spanLat < minSpanLat || spanLng < minSpanLng) {
    // 小点团：以实际 bounds 中心扩展跨度（点团居中），不再放大到充满视口
    spanLat = Math.max(spanLat, minSpanLat);
    spanLng = Math.max(spanLng, minSpanLng);
    minLat = cLat - spanLat / 2;
    maxLat = cLat + spanLat / 2;
    minLng = cLng - spanLng / 2;
  }
  const usableW = w * (1 - 2 * pad);
  const usableH = h * (1 - 2 * pad);
  const s = Math.min(usableW / spanLng, usableH / spanLat);
  const drawW = spanLng * s;
  const drawH = spanLat * s;
  const ox = (w - drawW) / 2;
  const oy = (h - drawH) / 2;
  return pts.map((p) => ({
    x: ox + (p.lng - minLng) * s,
    y: oy + (maxLat - p.lat) * s,
  }));
}

export function toSvgPath(pts: LatLng[], w: number, h: number, pad = 0.1): string {
  const proj = projectToView(pts, w, h, pad);
  return proj
    .map(
      (p, i) =>
        `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`,
    )
    .join(' ');
}
