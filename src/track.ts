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
  t: number;
  mode: Mode;
}

/** 会话 → 边序列（home→…→home；中途回 Home 多段循环天然成立） */
export function buildEdges(s: SessionData): Edge[] {
  // 首停靠点取首点前 1ms，保证第一条边包含第一个轨迹点
  const t0 = (s.points[0]?.t ?? s.createdAt) - 1;
  const tEnd = s.points[s.points.length - 1]?.t ?? s.updatedAt;
  const ordered = [...s.visits].sort((a, b) => a.arriveT - b.arriveT);

  const stops: Stop[] = [
    { nodeId: 'home', t: t0, mode: ordered[0]?.mode ?? s.currentMode },
  ];
  for (const v of ordered) {
    stops.push({ nodeId: v.nodeId, t: v.arriveT, mode: v.mode });
  }
  if (s.finished) {
    stops.push({ nodeId: 'home', t: tEnd, mode: s.currentMode });
  }

  const edges: Edge[] = [];
  for (let i = 0; i + 1 < stops.length; i++) {
    const a = stops[i];
    const b = stops[i + 1];
    if (b.t <= a.t) continue;
    const raw = s.points.filter((p) => p.t > a.t && p.t <= b.t);
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
      departT: a.t,
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

/** 等比投影到视口（北在上、留白 10%），全部点落在 [0,w]×[0,h] 内 */
export function projectToView(
  pts: LatLng[],
  w: number,
  h: number,
  pad = 0.1,
): { x: number; y: number }[] {
  const b = boundsOf(pts);
  if (!b) return [];
  const spanLat = Math.max(b.maxLat - b.minLat, 1e-9);
  const spanLng = Math.max(b.maxLng - b.minLng, 1e-9);
  const usableW = w * (1 - 2 * pad);
  const usableH = h * (1 - 2 * pad);
  const s = Math.min(usableW / spanLng, usableH / spanLat);
  const drawW = spanLng * s;
  const drawH = spanLat * s;
  const ox = (w - drawW) / 2;
  const oy = (h - drawH) / 2;
  return pts.map((p) => ({
    x: ox + (p.lng - b.minLng) * s,
    y: oy + (b.maxLat - p.lat) * s,
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
