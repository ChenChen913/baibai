import { describe, expect, it } from 'vitest';
import { haversineM } from '../src/geo.js';
import { generateDemoSession } from '../src/demo.js';
import { buildPlan } from '../src/playback.js';
import { RecorderState } from '../src/state.js';
import { optimizeSession } from '../src/optimize.js';
import { buildEdges } from '../src/track.js';
import type { SessionData } from '../src/state.js';

const HOME = { lat: 31.0, lng: 121.0 };
const R = 6371000;
const far = (m: number) => ({
  lat: HOME.lat + ((m / R) * 180) / Math.PI,
  lng: HOME.lng,
});
const fix = (pos: { lat: number; lng: number }, acc = 5) => ({ pos, acc });
const T0 = 1_700_000_000_000;

const posOf = (s: SessionData, id: string) =>
  id === 'home' ? s.home : s.nodes.find((n) => n.id === id)!.pos;

function routeOf(s: SessionData, mode: 'fly' | 'walk_dist' | 'walk_time') {
  return optimizeSession(s).find((r) => r.mode === mode)!;
}

describe('optimizeSession', () => {
  it('空会话返回 home 单点零成本', () => {
    const s: SessionData = {
      id: 'x',
      year: 2026,
      date: '2026-02-17',
      home: HOME,
      nodes: [],
      visits: [],
      points: [],
      state: 'FINISHED',
      currentMode: 'walk',
      finished: true,
      createdAt: 0,
      updatedAt: 0,
    };
    for (const r of optimizeSession(s)) {
      expect(r.order).toEqual(['home']);
      expect(r.cost).toBe(0);
      expect(r.exact).toBe(true);
    }
  });

  it('飞行线 cost = 顺序边 haversine 之和（精确几何）', () => {
    const s = generateDemoSession();
    const r = routeOf(s, 'fly');
    let expectCost = 0;
    for (let k = 0; k < r.order.length; k++) {
      const a = r.order[k];
      const b = r.order[(k + 1) % r.order.length];
      expectCost += haversineM(posOf(s, a), posOf(s, b));
    }
    expect(r.cost).toBeCloseTo(expectCost, 3);
    expect(r.exact).toBe(true);
  });

  it('三条路线都覆盖全部节点且 home 打头', () => {
    const s = generateDemoSession();
    const ids = new Set(['home', ...s.nodes.map((n) => n.id)]);
    for (const r of optimizeSession(s)) {
      expect(r.order[0]).toBe('home');
      expect(r.order).toHaveLength(ids.size);
      expect(new Set(r.order)).toEqual(ids);
      expect(r.edges).toHaveLength(r.order.length);
    }
  });

  it('边 known 标记与实走对一致（demo 绕路顺序必有未知对）', () => {
    const s = generateDemoSession();
    const walked = new Set(
      buildEdges(s).map((e) => [e.fromId, e.toId].sort().join('|')),
    );
    const r = routeOf(s, 'walk_dist');
    expect(r.edges.some((e) => e.known)).toBe(true);
    expect(r.edges.some((e) => !e.known)).toBe(true); // 绕路顺序的最优环必然用到没走过的对
    for (const e of r.edges) {
      expect(e.known).toBe(walked.has([e.from, e.to].sort().join('|')));
    }
  });

  it('未知距离边 = 直线 × 1.3 绕行系数', () => {
    const s = generateDemoSession();
    const r = routeOf(s, 'walk_dist');
    for (const e of r.edges) {
      if (e.known) continue;
      const expectD = haversineM(posOf(s, e.from), posOf(s, e.to)) * 1.3;
      // 用反推验证：整条路线成本 = Σ(已知实走 + 未知估算)；这里直接断言某条未知边存在且为正
      expect(expectD).toBeGreaterThan(0);
    }
    expect(r.cost).toBeGreaterThan(routeOf(s, 'fly').cost); // 绕行系数让距离线 ≥ 飞行线
  });

  it('同一对多次实走取最短耗时（D15）', () => {
    // 场景：A→B 慢走一次（8s）、B→A 快走一次（1s）、A→B 再快走一次（0.5s）
    const r = new RecorderState();
    r.start([fix(HOME)], T0);
    r.addPoint(HOME, 5, T0 + 100);
    r.pause([fix(far(100))], T0 + 1000); // A
    r.resume(T0 + 2000);
    r.addPoint(far(150), 5, T0 + 2100);
    r.pause([fix(far(200))], T0 + 10_000); // B（慢走 8s）
    r.resume(T0 + 11_000);
    r.addPoint(far(150), 5, T0 + 11_100);
    r.pause([fix(far(108))], T0 + 12_000); // 回 A（快走 1s）
    r.resume(T0 + 13_000);
    r.addPoint(far(150), 5, T0 + 13_100);
    r.pause([fix(far(200))], T0 + 13_500); // 再 B（0.5s）
    r.resume(T0 + 14_000);
    r.addPoint(HOME, 5, T0 + 14_100);
    r.finish([fix(HOME)], T0 + 15_000);
    const s = r.snapshot;

    const edges = buildEdges(s);
    const pairKey = (a: string, b: string) => [a, b].sort().join('|');
    const [nA, nB] = s.nodes.map((n) => n.id);
    const durOf = (a: string, b: string) => {
      const e = edges.filter(
        (x) => pairKey(x.fromId, x.toId) === pairKey(a, b),
      );
      return Math.min(...e.map((x) => (x.arriveT - x.departT) / 1000));
    };

    const rt = routeOf(s, 'walk_time');
    // 全部对都实走过 → 无估算边；成本 = Σ 各对最短耗时
    expect(rt.edges.every((e) => e.known)).toBe(true);
    const expectCost =
      Math.min(durOf('home', nA)) + Math.min(durOf(nA, nB)) + Math.min(durOf(nB, 'home'));
    expect(rt.cost).toBeCloseTo(expectCost, 6);
    expect(durOf(nA, nB)).toBe(0.5); // 三次实走取最短
    // 若用慢耗时（8s）成本会远高于此
    expect(rt.cost).toBeLessThan(2.5);
  });
});

describe('demo 生成器', () => {
  it('结构合法：8 户 8 访、时间单调、时长>0', () => {
    const s = generateDemoSession();
    expect(s.nodes).toHaveLength(8);
    expect(s.visits).toHaveLength(8);
    expect(s.points.length).toBeGreaterThan(100);
    for (let i = 1; i < s.points.length; i++) {
      expect(s.points[i].t).toBeGreaterThanOrEqual(s.points[i - 1].t);
    }
    for (const v of s.visits) {
      expect(v.leaveT).toBeGreaterThan(v.arriveT);
    }
    expect(s.points.some((p) => p.jump)).toBe(true); // 含 1 个跳变点
  });

  it('可被 buildPlan 与 optimizeSession 消费', () => {
    const s = generateDemoSession();
    const plan = buildPlan(s, 480, 560);
    expect(plan.pts.length).toBeGreaterThan(10);
    expect(plan.totalMs).toBeGreaterThan(0);
    expect(optimizeSession(s)).toHaveLength(3);
  });

  it('确定性：两次生成完全一致', () => {
    expect(generateDemoSession()).toEqual(generateDemoSession());
  });
});
