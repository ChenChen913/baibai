/** 三线优化（M3）：飞行/距离/时间 三条最优路线（D8/D14/D15） */

import type { LatLng } from './geo.js';
import { haversineM } from './geo.js';
import type { Mode, SessionData } from './state.js';
import { buildEdges } from './track.js';
import { solveTsp } from './tsp.js';

export const DETOUR_FACTOR = 1.3; // 未知距离边 = 直线 × 1.3
export const SPEED_WALK_MS = 1.35; // 4.86 km/h
export const SPEED_BIKE_MS = 4.0; // 14.4 km/h

export interface RouteEdge {
  from: string;
  to: string;
  known: boolean; // 该对是否有当日实走数据（UI：实线/虚线）
}

export interface Route {
  mode: 'fly' | 'walk_dist' | 'walk_time';
  order: string[]; // home 打头，不含闭合重复
  cost: number; // 米（fly/walk_dist）或 秒（walk_time）
  exact: boolean;
  edges: RouteEdge[]; // 含闭合边（最后一户→home）
}

interface PairInfo {
  minDist: number | null;
  minTime: number | null;
  mode: Mode | null;
}

const pairKey = (a: string, b: string): string => [a, b].sort().join('|');

function posOf(s: SessionData, id: string): LatLng {
  if (id === 'home') return s.home;
  const n = s.nodes.find((x) => x.id === id);
  if (!n) throw new Error(`unknown node: ${id}`);
  return n.pos;
}

/** 会话 → 三条最优路线 */
export function optimizeSession(s: SessionData): Route[] {
  const ids = ['home', ...s.nodes.map((n) => n.id)];
  const n = ids.length;
  if (n === 1) {
    const empty = (mode: Route['mode']): Route => ({
      mode,
      order: ['home'],
      cost: 0,
      exact: true,
      edges: [],
    });
    return [empty('fly'), empty('walk_dist'), empty('walk_time')];
  }

  // 边聚合：同一对多次实走 → 距离/时间各自取 min（D15）
  const pairs = new Map<string, PairInfo>();
  for (const e of buildEdges(s)) {
    const k = pairKey(e.fromId, e.toId);
    const p = pairs.get(k) ?? { minDist: null, minTime: null, mode: null };
    if (p.minDist === null || e.distM < p.minDist) {
      p.minDist = e.distM;
      p.mode = e.mode;
    }
    const timeMs = e.arriveT - e.departT;
    if (p.minTime === null || timeMs < p.minTime) p.minTime = timeMs;
    pairs.set(k, p);
  }

  const fly: number[][] = [];
  const dist: number[][] = [];
  const time: number[][] = [];
  for (let i = 0; i < n; i++) {
    fly.push(new Array<number>(n).fill(0));
    dist.push(new Array<number>(n).fill(0));
    time.push(new Array<number>(n).fill(0));
  }
  for (let i = 0; i < n; i++) {
    for (let j = i + 1; j < n; j++) {
      const geo = haversineM(posOf(s, ids[i]), posOf(s, ids[j]));
      const p = pairs.get(pairKey(ids[i], ids[j]));
      fly[i][j] = fly[j][i] = geo;
      dist[i][j] = dist[j][i] = p?.minDist ?? geo * DETOUR_FACTOR;
      time[i][j] = time[j][i] =
        p && p.minTime !== null
          ? p.minTime / 1000
          : dist[i][j] / SPEED_WALK_MS; // 未知边一律按步行
    }
  }

  const mk = (
    mode: Route['mode'],
    m: number[][],
  ): Route => {
    const res = solveTsp(m, 0);
    return {
      mode,
      order: res.order.map((i) => ids[i]),
      cost: res.cost,
      exact: res.exact,
      edges: res.order.map((v, k) => {
        const u = res.order[(k + res.order.length - 1) % res.order.length];
        return {
          from: ids[u],
          to: ids[v],
          known: pairs.has(pairKey(ids[u], ids[v])),
        };
      }),
    };
  };

  return [mk('fly', fly), mk('walk_dist', dist), mk('walk_time', time)];
}

/** 成绩单（F-14）：实走 vs 时间最优 vs 飞行最优。对比口径均为"路上时间/距离"，不含户内停留 */
export interface Scorecard {
  actualDistM: number;
  actualMoveSec: number; // 路上时间（Σ 边行程，不含停留）
  actualTotalSec: number; // 全天总时长（含停留，仅展示）
  bikeDistM: number;
  timeOptSec: number;
  distOptM: number;
  flyOptM: number;
  savingsTimePct: number; // 时间最优节省（路上时间口径）
  savingsDistPct: number; // 距离最优节省
  savingsFlyPct: number; // 若能飞
}

export function scorecard(s: SessionData, routes: Route[]): Scorecard {
  const edges = buildEdges(s);
  const actualDistM = edges.reduce((x, e) => x + e.distM, 0);
  const actualMoveSec = edges.reduce(
    (x, e) => x + (e.arriveT - e.departT) / 1000,
    0,
  );
  const first = s.points[0]?.t ?? s.createdAt;
  const last = s.points[s.points.length - 1]?.t ?? s.updatedAt;
  const actualTotalSec = Math.max(0, (last - first) / 1000);
  const bikeDistM = edges
    .filter((e) => e.mode === 'bike')
    .reduce((x, e) => x + e.distM, 0);
  const get = (mode: Route['mode']) => routes.find((r) => r.mode === mode)!.cost;
  const pct = (opt: number, actual: number): number =>
    actual > 0 ? Math.max(0, (1 - opt / actual) * 100) : 0;
  const timeOptSec = get('walk_time');
  const distOptM = get('walk_dist');
  const flyOptM = get('fly');
  return {
    actualDistM,
    actualMoveSec,
    actualTotalSec,
    bikeDistM,
    timeOptSec,
    distOptM,
    flyOptM,
    savingsTimePct: pct(timeOptSec, actualMoveSec),
    savingsDistPct: pct(distOptM, actualDistM),
    savingsFlyPct: pct(flyOptM, actualDistM),
  };
}
