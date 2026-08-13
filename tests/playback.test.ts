import { describe, expect, it } from 'vitest';
import { RecorderState } from '../src/state.js';
import { buildPlan, fractionAt, positionAt } from '../src/playback.js';

const HOME = { lat: 31.0, lng: 121.0 };
const R = 6371000;
const at = (mN: number, mE: number) => ({
  lat: HOME.lat + ((mN / R) * 180) / Math.PI,
  lng: HOME.lng + ((mE / R) * 180) / Math.PI / Math.cos((HOME.lat * Math.PI) / 180),
});
const fix = (pos: { lat: number; lng: number }, acc = 5) => ({ pos, acc });
const T0 = 1_700_000_000_000;

/** 带拐角的轨迹：home → A → B → home */
function cornerSession() {
  const r = new RecorderState();
  r.start([fix(HOME)], T0);
  r.addPoint(at(0, 0), 5, T0 + 500);
  r.addPoint(at(30, 0), 5, T0 + 700);
  r.addPoint(at(60, 40), 5, T0 + 900); // 拐点
  r.addPoint(at(90, 40), 5, T0 + 1100);
  r.pause([fix(at(100, 40))], T0 + 2000); // A
  r.resume(T0 + 3000);
  r.addPoint(at(200, 40), 5, T0 + 3500);
  r.pause([fix(at(300, 40))], T0 + 5000); // B
  r.resume(T0 + 6000);
  r.addPoint(at(150, 20), 5, T0 + 6500);
  r.finish([fix(HOME)], T0 + 8000);
  return r.snapshot;
}

describe('buildPlan', () => {
  it('抽稀后首尾保留、时间轴连续', () => {
    const plan = buildPlan(cornerSession(), 480, 560);
    expect(plan.pts.length).toBeGreaterThanOrEqual(2);
    expect(plan.pts[0].t).toBe(T0 + 500);
    expect(plan.pts[plan.pts.length - 1].t).toBe(T0 + 6500);
    expect(plan.totalMs).toBe(6000);
    // 时间单调递增
    for (let i = 1; i < plan.pts.length; i++) {
      expect(plan.pts[i].t).toBeGreaterThan(plan.pts[i - 1].t);
    }
  });

  it('拐点被保留（非直线退化）', () => {
    const plan = buildPlan(cornerSession(), 480, 560);
    expect(plan.pts.length).toBeGreaterThan(2);
  });

  it('空会话返回空计划', () => {
    const r = new RecorderState();
    const plan = buildPlan(r.snapshot, 480, 560);
    expect(plan.pts).toEqual([]);
    expect(plan.totalMs).toBe(0);
  });
});

describe('positionAt', () => {
  it('起点/终点/越界夹取', () => {
    const plan = buildPlan(cornerSession(), 480, 560);
    const first = plan.pts[0];
    const last = plan.pts[plan.pts.length - 1];
    expect(positionAt(plan, 0)).toEqual({ x: first.x, y: first.y });
    expect(positionAt(plan, plan.totalMs)).toEqual({ x: last.x, y: last.y });
    expect(positionAt(plan, plan.totalMs + 9999)).toEqual({ x: last.x, y: last.y });
    expect(positionAt(plan, -5)).toEqual({ x: first.x, y: first.y });
  });

  it('段间线性插值', () => {
    const plan = buildPlan(cornerSession(), 480, 560);
    const a = plan.pts[0];
    const b = plan.pts[1];
    const mid = positionAt(plan, (b.t - a.t) / 2);
    expect(mid!.x).toBeCloseTo((a.x + b.x) / 2, 6);
    expect(mid!.y).toBeCloseTo((a.y + b.y) / 2, 6);
  });

  it('空计划返回 null', () => {
    expect(positionAt({ pts: [], totalMs: 0 }, 0)).toBeNull();
  });
});

describe('fractionAt', () => {
  it('0..1 夹取', () => {
    const plan = buildPlan(cornerSession(), 480, 560);
    expect(fractionAt(plan, 0)).toBe(0);
    expect(fractionAt(plan, plan.totalMs)).toBe(1);
    expect(fractionAt(plan, plan.totalMs * 2)).toBe(1);
    expect(fractionAt({ pts: [], totalMs: 0 }, 10)).toBe(0);
  });
});
