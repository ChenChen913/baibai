/** 回放引擎（纯计算：可测的插值数学；rAF 循环在 UI 层） */

import type { LatLng } from './geo.js';
import type { SessionData } from './state.js';
import { douglasPeuckerKeep } from './smooth.js';
import { buildEdges, projectToView } from './track.js';

export interface PlaybackPoint {
  x: number;
  y: number;
  t: number;
}

export interface PlaybackPlan {
  pts: PlaybackPoint[];
  totalMs: number;
}

/** 契约常量（数据格式 §9）：Douglas-Peucker 抽稀容差 2m */
export const DP_EPS_M = 2;

/** 会话 → 回放计划：各边平滑点合并 → DP 抽稀（保留时间轴）→ 投影视口 */
export function buildPlan(s: SessionData, w: number, h: number): PlaybackPlan {
  const edges = buildEdges(s);
  const raw: { pos: LatLng; t: number }[] = [];
  for (const e of edges) {
    for (const p of e.smoothed) raw.push({ pos: p.pos, t: p.t });
  }
  if (raw.length === 0) return { pts: [], totalMs: 0 };
  const keep = douglasPeuckerKeep(
    raw.map((r) => r.pos),
    DP_EPS_M,
  );
  const proj = projectToView(
    keep.map((i) => raw[i].pos),
    w,
    h,
  );
  const pts = keep.map((i, k) => ({
    x: proj[k].x,
    y: proj[k].y,
    t: raw[i].t,
  }));
  return {
    pts,
    totalMs: Math.max(0, pts[pts.length - 1].t - pts[0].t),
  };
}

/** 播放进度 ms → 光点位置（沿时间轴线性插值，越界夹到端点） */
export function positionAt(
  plan: PlaybackPlan,
  ms: number,
): { x: number; y: number } | null {
  const { pts } = plan;
  if (pts.length === 0) return null;
  const target = pts[0].t + ms;
  if (target <= pts[0].t) return { x: pts[0].x, y: pts[0].y };
  for (let i = 1; i < pts.length; i++) {
    if (target <= pts[i].t) {
      const a = pts[i - 1];
      const b = pts[i];
      const dt = b.t - a.t;
      const f = dt > 0 ? (target - a.t) / dt : 0;
      return { x: a.x + (b.x - a.x) * f, y: a.y + (b.y - a.y) * f };
    }
  }
  const last = pts[pts.length - 1];
  return { x: last.x, y: last.y };
}

export function fractionAt(plan: PlaybackPlan, ms: number): number {
  if (plan.totalMs <= 0) return 0;
  return Math.min(1, Math.max(0, ms / plan.totalMs));
}
