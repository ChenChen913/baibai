/**
 * 投影坐标系统一回归测试（E-1 修复）
 *
 * 背景（体检实测）：回放轨迹走 buildPlan（按"轨迹点"算 bounds），节点标记/最优线走
 * projectToView([home, ...nodes])（按"节点"算 bounds）。两组点包围盒不同 → 同一物理点
 * 在两套投影下坐标不同：demo 数据实测轨迹端点 vs home 标记错位 220.10px（画布仅 480px 宽），
 * morph 动画首末点错位 220.10 / 214.70px，三线对比失去可比性。
 *
 * 修复：所有视图传同一个 ref（viewRefPoints），bounds 一律按 ref 计算。
 * 本测试锁死该契约，防止回归。
 */
import { describe, expect, it } from 'vitest';
import { generateDemoSession } from '../src/demo.js';
import { buildPlan } from '../src/playback.js';
import { buildEdges, projectToView, viewRefPoints } from '../src/track.js';
import { douglasPeuckerKeep } from '../src/smooth.js';
import { analyzeSync, morphSync } from '../src/compute.js';
import { DP_EPS_M } from '../src/playback.js';
import type { SessionData } from '../src/state.js';
import type { LatLng } from '../src/geo.js';

const W = 480;
const H = 560;
const dist = (a: { x: number; y: number }, b: { x: number; y: number }): number =>
  Math.hypot(a.x - b.x, a.y - b.y);

describe('viewRefPoints（统一投影参考点集）', () => {
  it('包含 home + 全部户 + 全部"非跳变"轨迹点', () => {
    const s = generateDemoSession();
    const ref = viewRefPoints(s);
    // E-15：跳变点不参与投影参考（900m 外的坏点会把包围盒撑大、整幅图被缩小）
    const real = s.points.filter((p) => !p.jump);
    expect(ref).toHaveLength(1 + s.nodes.length + real.length);
    expect(ref[0]).toEqual(s.home);
    expect(ref[1]).toEqual(s.nodes[0].pos);
    expect(ref[ref.length - 1]).toEqual(real[real.length - 1].pos);
  });
});

describe('projectToView 的 ref 语义', () => {
  it('传同一 ref 时，不同点集落在同一坐标系（可叠加）', () => {
    const s = generateDemoSession();
    const ref = viewRefPoints(s);
    const all = [s.home, ...s.nodes.map((n) => n.pos)];
    const viaAll = projectToView(all, W, H, 0.1, ref);
    // 只投影其中一个子集，结果必须与"整体投影"里的对应项逐位一致
    const viaSubset = projectToView([all[3]], W, H, 0.1, ref);
    expect(dist(viaSubset[0], viaAll[3])).toBeLessThan(1e-9);
  });

  it('省略 ref 时行为与旧版一致（向后兼容）', () => {
    const s = generateDemoSession();
    const pts = [s.home, ...s.nodes.map((n) => n.pos)];
    const a = projectToView(pts, W, H);
    const b = projectToView(pts, W, H, 0.1, undefined);
    expect(a).toEqual(b);
  });

  it('ref 为空数组时退回按 pts 计算（不崩、不返回空）', () => {
    const s = generateDemoSession();
    const pts = [s.home, ...s.nodes.map((n) => n.pos)];
    const a = projectToView(pts, W, H, 0.1, []);
    expect(a).toEqual(projectToView(pts, W, H));
  });
});

describe('buildPlan 使用 ref（E-1 回归防护）', () => {
  it('buildPlan 的投影结果 = 手写「DP 抽稀 + projectToView(ref)」', () => {
    const s = generateDemoSession();
    const ref = viewRefPoints(s);
    const plan = buildPlan(s, W, H, ref);

    // 复刻 buildPlan 内部管线（唯一权威口径）
    const raw: LatLng[] = buildEdges(s).flatMap((e) => e.smoothed.map((p) => p.pos));
    const keep = douglasPeuckerKeep(raw, DP_EPS_M);
    const expected = projectToView(
      keep.map((i) => raw[i]),
      W,
      H,
      0.1,
      ref,
    );

    expect(plan.pts).toHaveLength(expected.length);
    let maxDiff = 0;
    plan.pts.forEach((p, i) => {
      maxDiff = Math.max(maxDiff, dist(p, expected[i]));
    });
    expect(maxDiff).toBeLessThan(1e-9);
  });

  it('同一物理点在「节点投影」与「轨迹投影」下像素一致（<1px）', () => {
    // demo 轨迹的末点物理上就落在家门口（buildPlan 端点 = home）：
    // 它经轨迹投影得到的像素，必须与 home 经节点投影得到的像素重合
    const s = generateDemoSession();
    const ref = viewRefPoints(s);
    const homeViaNodes = projectToView([s.home], W, H, 0.1, ref)[0];
    const plan = buildPlan(s, W, H, ref);
    const trackEnd = plan.pts[plan.pts.length - 1];
    expect(dist(trackEnd, homeViaNodes)).toBeLessThan(1);
  });

  it('轨迹超出节点包围盒时（绕路/走错路），旧写法会错位（证明该参数必需）', () => {
    const s = generateDemoSession();
    // 构造"走到远处又回来"的轨迹点：它让轨迹包围盒超出节点包围盒，
    // 正是真实场景（绕路、走错路、村口折返）下两套坐标系分叉的成因
    const far: SessionData = structuredClone(s);
    const t0 = s.points[s.points.length - 1].t;
    far.points = [
      ...far.points,
      {
        t: t0 + 1000,
        pos: { lat: s.home.lat + 0.012, lng: s.home.lng + 0.012 },
        acc: 5,
        seg: 'segX',
      },
    ];
    const nodePts = [far.home, ...far.nodes.map((n) => n.pos)];
    const homeOld = projectToView(nodePts, W, H)[0]; // 旧写法：按节点算 bounds
    const homeNew = projectToView(nodePts, W, H, 0.1, viewRefPoints(far))[0]; // 新写法
    expect(dist(homeOld, homeNew)).toBeGreaterThan(10); // 旧写法错位显著
  });
});

describe('jump 点不污染统计（E-15 回归防护）', () => {
  it('demo 实走距离不再被跳变点虚增（修复前 3564m，真实约 1911m）', () => {
    const s = generateDemoSession();
    expect(s.points.some((p) => p.jump)).toBe(true); // demo 确实含 1 个跳变点
    const d = buildEdges(s).reduce((x, e) => x + e.distM, 0);
    expect(d).toBeGreaterThan(1500);
    expect(d).toBeLessThan(2500);
  });

  it('含/不含跳变点的实走距离一致（buildEdges 已排除 jump）', () => {
    const s = generateDemoSession();
    const stripped: SessionData = structuredClone(s);
    stripped.points = stripped.points.filter((p) => !p.jump);
    const dWith = buildEdges(s).reduce((x, e) => x + e.distM, 0);
    const dWithout = buildEdges(stripped).reduce((x, e) => x + e.distM, 0);
    expect(Math.abs(dWith - dWithout)).toBeLessThan(1e-6);
  });

  it('跳变点仍保留在会话里（回顾页可展示与剔除）', () => {
    const s = generateDemoSession();
    const jumps = s.points.filter((p) => p.jump);
    expect(jumps).toHaveLength(1);
    // 剔除后点数减一、距离不变（它本来就没参与统计）
    const after: SessionData = structuredClone(s);
    after.points = after.points.filter((p) => p.t !== jumps[0].t);
    expect(after.points).toHaveLength(s.points.length - 1);
  });
});

describe('morph 两端同坐标系（E-1 回归防护）', () => {
  it('demo：actualRes 与 flyRes 的 home 端点重合（修复前错位 220.10 / 214.70px）', () => {
    const s = generateDemoSession();
    const ar = analyzeSync(s);
    const mr = morphSync(s, ar.routes);
    const last = mr.actualRes.length - 1;
    // demo 的实走轨迹首点距 home 约 20m（真实位移，非投影误差），末点就在 home；
    // 因此末点必须与飞行路线的 home 端点重合
    expect(dist(mr.actualRes[last], mr.flyRes[last])).toBeLessThan(1);
    // 首点两者物理上不同（轨迹起点在 home 外 ~20m），但必须落在同一坐标系：
    // 用像素尺度反查——20m 在这个视口下约 5~30px，绝不能是修复前的 220px
    expect(dist(mr.actualRes[0], mr.flyRes[0])).toBeLessThan(40);
  });
});
