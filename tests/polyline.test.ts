import { describe, expect, it } from 'vitest';
import { generateDemoSession } from '../src/demo.js';
import { lerpPolyline, resamplePolyline, routePolyline } from '../src/polyline.js';
import { optimizeSession, scorecard } from '../src/optimize.js';

describe('routePolyline', () => {
  it('闭合：首尾同点，点数 = 顺序长 + 1', () => {
    const s = generateDemoSession();
    const fly = optimizeSession(s).find((r) => r.mode === 'fly')!;
    const pts = routePolyline(s, fly.order);
    expect(pts).toHaveLength(fly.order.length + 1);
    expect(pts[0]).toEqual(pts[pts.length - 1]);
    expect(pts[0]).toEqual(s.home);
  });
});

describe('resamplePolyline', () => {
  it('重采样后首尾保持、点数正确', () => {
    const pts = [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
      { x: 10, y: 10 },
    ];
    const out = resamplePolyline(pts, 11);
    expect(out).toHaveLength(11);
    expect(out[0]).toEqual({ x: 0, y: 0 });
    expect(out[10]).toEqual({ x: 10, y: 10 });
    // 中间点沿折线等距：第 5 个（k=5）应在 (10, 0)（半程处，总长 20 → 10）
    expect(out[5].x).toBeCloseTo(10, 6);
    expect(out[5].y).toBeCloseTo(0, 6);
  });

  it('退化折线（零长度）不崩溃', () => {
    const out = resamplePolyline(
      [
        { x: 1, y: 1 },
        { x: 1, y: 1 },
      ],
      5,
    );
    expect(out).toHaveLength(5);
    expect(out.every((p) => p.x === 1 && p.y === 1)).toBe(true);
  });

  it('空与单点', () => {
    expect(resamplePolyline([], 5)).toEqual([]);
    // 单点/零长折线退化为 m 个重复点（保证 morph 两端等长）
    const out = resamplePolyline([{ x: 1, y: 2 }], 5);
    expect(out).toHaveLength(5);
    expect(out.every((p) => p.x === 1 && p.y === 2)).toBe(true);
  });
});

describe('lerpPolyline', () => {
  it('t=0 → a，t=1 → b，中间线性', () => {
    const a = [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
    ];
    const b = [
      { x: 0, y: 10 },
      { x: 10, y: 10 },
    ];
    expect(lerpPolyline(a, b, 0)).toEqual(a);
    expect(lerpPolyline(a, b, 1)).toEqual(b);
    expect(lerpPolyline(a, b, 0.5)[1]).toEqual({ x: 10, y: 5 });
  });
});

describe('scorecard', () => {
  it('demo 成绩单：口径正确、节省率为正', () => {
    const s = generateDemoSession();
    const routes = optimizeSession(s);
    const c = scorecard(s, routes);
    expect(c.actualDistM).toBeGreaterThan(0);
    expect(c.actualMoveSec).toBeGreaterThan(0);
    expect(c.actualTotalSec).toBeGreaterThanOrEqual(c.actualMoveSec); // 含停留
    expect(c.bikeDistM).toBeGreaterThan(0); // demo 含骑行段
    expect(c.timeOptSec).toBeGreaterThan(0);
    expect(c.distOptM).toBeGreaterThan(0);
    expect(c.flyOptM).toBeGreaterThan(0);
    // 绕路 demo：三线都应比实走更省
    expect(c.timeOptSec).toBeLessThan(c.actualMoveSec);
    expect(c.distOptM).toBeLessThan(c.actualDistM);
    expect(c.flyOptM).toBeLessThan(c.actualDistM);
    expect(c.savingsTimePct).toBeGreaterThan(0);
    expect(c.savingsDistPct).toBeGreaterThan(0);
    expect(c.savingsFlyPct).toBeGreaterThan(0);
    expect(c.savingsTimePct).toBeLessThanOrEqual(100);
  });

  it('空会话成绩单全零', () => {
    const s = {
      id: 'x',
      year: 2026,
      date: '2026-02-17',
      home: { lat: 31, lng: 121 },
      nodes: [],
      visits: [],
      points: [],
      state: 'FINISHED' as const,
      currentMode: 'walk' as const,
      finished: true,
      createdAt: 0,
      updatedAt: 0,
    };
    const c = scorecard(s, optimizeSession(s));
    expect(c.actualDistM).toBe(0);
    expect(c.actualMoveSec).toBe(0);
    expect(c.savingsTimePct).toBe(0);
  });
});
