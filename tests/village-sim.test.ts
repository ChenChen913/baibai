import { describe, expect, it } from 'vitest';
import { RecorderState, type SessionData } from '../src/state.js';
import { haversineM, type LatLng } from '../src/geo.js';
import { buildEdges } from '../src/track.js';
import { buildPlan } from '../src/playback.js';
import { optimizeSession, scorecard } from '../src/optimize.js';

/** 潍坊市昌乐县某村（模拟坐标，村庄中心） */
const VILLAGE: LatLng = { lat: 36.7532, lng: 118.961 };
const R = 6371000;
const JIT_M = 4; // GPS 抖动 σ（米）
const FIX_JIT_M = 3; // 暂停点定位抖动 σ（米）

const toPos = (lat0: number, lng0: number, dN: number, dE: number): LatLng => ({
  lat: lat0 + ((dN / R) * 180) / Math.PI,
  lng: lng0 + ((dE / R) * 180) / Math.PI / Math.cos((lat0 * Math.PI) / 180),
});

function lcg(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

function gauss(rnd: () => number, sigma: number): number {
  const u = Math.max(1e-9, 1 - rnd());
  const v = rnd();
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v) * sigma;
}

interface House {
  pos: LatLng;
  dN: number;
  dE: number;
}

/** 生成 n 户：散布在 ~950m×720m 范围，两两间距 ≥18m（10m 合并阈值的安全边际） */
function makeHouses(rnd: () => number, n: number): House[] {
  const houses: House[] = [];
  let tries = 0;
  while (houses.length < n && tries < n * 500) {
    tries += 1;
    const dN = 60 + rnd() * 880;
    const dE = 60 + rnd() * 680;
    const pos = toPos(VILLAGE.lat, VILLAGE.lng, dN, dE);
    if (houses.every((h) => haversineM(h.pos, pos) >= 18)) {
      houses.push({ pos, dN, dE });
    }
  }
  expect(houses, '生成的户数应等于 n').toHaveLength(n);
  return houses;
}

/** 蛇形扫描顺序：刻意绕路的拜访路线（按 100m 纵带扫描，带内方向交替） */
function serpentine(houses: House[]): House[] {
  const band = (h: House) => Math.round(h.dE / 100);
  return [...houses].sort((a, b) => {
    if (band(a) !== band(b)) return band(a) - band(b);
    return band(a) % 2 === 0 ? a.dN - b.dN : b.dN - a.dN;
  });
}

const T0 = new Date('2027-02-06T08:00:00+08:00').getTime(); // 2027 大年初一
const SPEED_WALK = 1.35;
const SPEED_BIKE = 4.0;

/** 全链路仿真：真实状态机走完一天（GPS 抖动 + 停留 + 一段骑行 + 一次回访 + 回家） */
function simulate(n: number): SessionData {
  const rnd = lcg(20260217 + n);
  const houses = makeHouses(rnd, n);
  const order = serpentine(houses);
  const r = new RecorderState();
  let t = T0;
  r.start([{ pos: VILLAGE, acc: 5 }], t);

  const jit = () => gauss(rnd, (JIT_M / R) * (180 / Math.PI));
  const fixJit = () => gauss(rnd, (FIX_JIT_M / R) * (180 / Math.PI));
  let prev = VILLAGE;
  const bikeSeg = Math.floor(n / 2); // 第 bikeSeg 段骑行

  for (let i = 0; i < order.length; i++) {
    const h = order[i];
    if (i === bikeSeg) r.setMode('bike', t);
    const speed = i === bikeSeg ? SPEED_BIKE : SPEED_WALK;
    const dist = haversineM(prev, h.pos) * 1.2; // 村路绕行 20%
    const steps = Math.max(14, Math.ceil(dist / 3));
    for (let k = 1; k <= steps; k++) {
      const f = k / steps;
      t += ((dist / speed) * 1000) / steps;
      r.addPoint(
        {
          lat: prev.lat + (h.pos.lat - prev.lat) * f + jit(),
          lng:
            prev.lng +
            (h.pos.lng - prev.lng) * f +
            jit() / Math.cos((VILLAGE.lat * Math.PI) / 180),
        },
        5,
        t,
      );
    }
    // 到户：暂停（3 个抖动 fix）
    r.pause(
      [0, 1, 2].map(() => ({
        pos: {
          lat: h.pos.lat + fixJit(),
          lng: h.pos.lng + fixJit() / Math.cos((VILLAGE.lat * Math.PI) / 180),
        },
        acc: 5,
      })),
      t,
    );
    t += (5 + (i % 3) * 5) * 60_000; // 停留 5~15 分钟
    r.resume(t);
    prev = h.pos;
  }

  // 一次回访（第 2 户）：应在 10m 内合并，不产生新节点
  const back = order[1];
  {
    const dist = haversineM(prev, back.pos) * 1.2;
    const steps = Math.max(14, Math.ceil(dist / 3));
    for (let k = 1; k <= steps; k++) {
      const f = k / steps;
      t += ((dist / SPEED_WALK) * 1000) / steps;
      r.addPoint(
        {
          lat: prev.lat + (back.pos.lat - prev.lat) * f + jit(),
          lng:
            prev.lng +
            (back.pos.lng - prev.lng) * f +
            jit() / Math.cos((VILLAGE.lat * Math.PI) / 180),
        },
        5,
        t,
      );
    }
    r.pause(
      [0, 1, 2].map(() => ({
        pos: {
          lat: back.pos.lat + fixJit(),
          lng: back.pos.lng + fixJit() / Math.cos((VILLAGE.lat * Math.PI) / 180),
        },
        acc: 5,
      })),
      t,
    );
    t += 8 * 60_000;
    r.resume(t);
  }

  // 回家
  {
    const dist = haversineM(back.pos, VILLAGE) * 1.2;
    const steps = Math.max(14, Math.ceil(dist / 3));
    for (let k = 1; k <= steps; k++) {
      const f = k / steps;
      t += ((dist / SPEED_WALK) * 1000) / steps;
      r.addPoint(
        {
          lat: back.pos.lat + (VILLAGE.lat - back.pos.lat) * f + jit(),
          lng:
            back.pos.lng +
            (VILLAGE.lng - back.pos.lng) * f +
            jit() / Math.cos((VILLAGE.lat * Math.PI) / 180),
        },
        5,
        t,
      );
    }
  }
  expect(r.finish([{ pos: VILLAGE, acc: 5 }], t)).toEqual({ ok: true });
  return r.snapshot;
}

function report(label: string, s: SessionData): void {
  const edges = buildEdges(s);
  const actualDist = edges.reduce((x, e) => x + e.distM, 0);
  const actualMove = edges.reduce((x, e) => x + (e.arriveT - e.departT) / 1000, 0);
  const t0 = performance.now();
  const routes = optimizeSession(s);
  const ms = performance.now() - t0;
  const card = scorecard(s, routes);
  const timeRoute = routes.find((r) => r.mode === 'walk_time')!;
  const distRoute = routes.find((r) => r.mode === 'walk_dist')!;
  const flyRoute = routes.find((r) => r.mode === 'fly')!;
  const plan = buildPlan(s, 480, 560);

  console.log(`\n=== ${label} ===`);
  console.log(`户数 ${s.nodes.length} · 到访 ${s.visits.length} 次（含 1 次回访合并）· 轨迹点 ${s.points.length}（回放抽稀后 ${plan.pts.length}）`);
  console.log(`实走距离 ${(actualDist / 1000).toFixed(2)} km · 路上时间 ${Math.round(actualMove / 60)} 分钟 · 全天 ${Math.round((card.actualTotalSec) / 60)} 分钟（含拜年停留）`);
  console.log(`时间最优 ${Math.round(card.timeOptSec / 60)} 分钟（省 ${card.savingsTimePct.toFixed(1)}%）`);
  console.log(`距离最优 ${(card.distOptM / 1000).toFixed(2)} km（省 ${card.savingsDistPct.toFixed(1)}%）`);
  console.log(`飞行最优 ${(card.flyOptM / 1000).toFixed(2)} km（少走 ${card.savingsFlyPct.toFixed(1)}%）`);
  console.log(`求解 ${timeRoute.exact ? 'Held-Karp 精确解' : '贪心+2-opt 启发式'} · 三线总耗时 ${ms.toFixed(1)} ms`);
  console.log(`实走顺序 ${s.visits.map((v) => (v.nodeId === 'home' ? '家' : s.nodes.find((n) => n.id === v.nodeId)!.autoNo)).join('→')}`);
  console.log(`最优顺序 家→${timeRoute.order.slice(1).map((id) => s.nodes.find((n) => n.id === id)!.autoNo).join('→')}→家`);
  void distRoute;
  void flyRoute;
}

describe('昌乐县模拟村 · 实地仿真（15 户 / 20 户）', () => {
  it('15 户：无误合并、回访合并、三线节省为正、精确解、性能达标', () => {
    const s = simulate(15);
    expect(s.nodes).toHaveLength(15); // 无 10m 误合并
    expect(s.visits).toHaveLength(16); // 15 户 + 1 回访（合并）
    const edges = buildEdges(s);
    expect(edges.length).toBeGreaterThanOrEqual(16);
    const t0 = performance.now();
    const routes = optimizeSession(s);
    const ms = performance.now() - t0;
    expect(routes.every((r) => r.exact)).toBe(true); // 16 节点 ≤ 精确解上限
    expect(ms).toBeLessThan(2000);
    const card = scorecard(s, routes);
    expect(card.savingsTimePct).toBeGreaterThan(0);
    expect(card.savingsDistPct).toBeGreaterThan(0);
    expect(card.savingsFlyPct).toBeGreaterThan(0);
    report('昌乐县模拟村 · 15 户', s);
  });

  it('20 户：无误合并、启发式求解、性能达标、跳变点鲁棒', () => {
    const s = simulate(20);
    expect(s.nodes).toHaveLength(20);
    expect(s.visits).toHaveLength(21);
    const t0 = performance.now();
    const routes = optimizeSession(s);
    const ms = performance.now() - t0;
    expect(routes.every((r) => !r.exact)).toBe(true); // 21 节点 > 16 → 启发式
    expect(ms).toBeLessThan(2000);
    const card = scorecard(s, routes);
    expect(card.savingsTimePct).toBeGreaterThan(0);
    report('昌乐县模拟村 · 20 户', s);

    // 跳变点鲁棒：插入一个跳变点后全管线仍正常
    const withJump = structuredClone(s);
    const mid = Math.floor(withJump.points.length / 2);
    withJump.points.splice(mid, 0, {
      t: withJump.points[mid].t + 1,
      pos: toPos(VILLAGE.lat, VILLAGE.lng, 700, -500),
      acc: 5,
      seg: 'segX',
      jump: true,
    });
    withJump.points.sort((a, b) => a.t - b.t);
    expect(() => {
      buildEdges(withJump);
      buildPlan(withJump, 480, 560);
      optimizeSession(withJump);
    }).not.toThrow();
  });
});
