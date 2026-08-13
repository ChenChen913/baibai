import { describe, expect, it } from 'vitest';
import { RecorderState } from '../src/state.js';
import { buildEdges, projectToView, toSvgPath } from '../src/track.js';

const HOME = { lat: 31.0, lng: 121.0 };
const R = 6371000;
const far = (m: number, dir: 'n' | 'e' = 'n') => {
  if (dir === 'n') return { lat: HOME.lat + ((m / R) * 180) / Math.PI, lng: HOME.lng };
  return {
    lat: HOME.lat,
    lng: HOME.lng + ((m / R) * 180) / Math.PI / Math.cos((HOME.lat * Math.PI) / 180),
  };
};
const fix = (pos: { lat: number; lng: number }, acc = 5) => ({ pos, acc });
const T0 = 1_700_000_000_000;

/** 构造三段场景：home→A→B→home */
function threeStopSession() {
  const r = new RecorderState();
  r.start([fix(HOME)], T0);
  r.addPoint(HOME, 5, T0 + 500); // home→A 途中
  r.addPoint(far(30, 'n'), 5, T0 + 700);
  r.addPoint(far(60, 'n'), 5, T0 + 900);
  r.addPoint(far(90, 'n'), 5, T0 + 1100);
  r.pause([fix(far(100))], T0 + 2000); // A
  r.resume(T0 + 3000);
  r.addPoint(far(50, 'n'), 5, T0 + 3500); // A→B 途中
  r.pause([fix(far(300))], T0 + 5000); // B
  r.resume(T0 + 6000);
  r.addPoint(far(150, 'n'), 5, T0 + 6500); // B→home 途中
  r.finish([fix(HOME)], T0 + 8000);
  return r.snapshot;
}

describe('buildEdges', () => {
  it('三段场景：3 条边、时间窗过滤、距离为正', () => {
    const s = threeStopSession();
    const edges = buildEdges(s);
    expect(edges).toHaveLength(3);
    const [nA, nB] = s.nodes.map((n) => n.id);
    expect(edges.map((e) => `${e.fromId}→${e.toId}`)).toEqual([
      `home→${nA}`,
      `${nA}→${nB}`,
      `${nB}→home`,
    ]);
    // 时间窗 (depart, arrive]：home→A 应含 4 个途中点
    expect(edges[0].raw.map((p) => p.t)).toEqual([
      T0 + 500,
      T0 + 700,
      T0 + 900,
      T0 + 1100,
    ]);
    expect(edges[0].distM).toBeGreaterThan(0);
    // 最后一条边止于末点
    expect(edges[2].raw.map((p) => p.t)).toEqual([T0 + 6500]);
  });

  it('出行方式归属：进入该停靠点那次访问的 mode', () => {
    const r = new RecorderState();
    r.start([fix(HOME)], T0);
    r.addPoint(HOME, 5, T0 + 100);
    r.pause([fix(far(100))], T0 + 1000);
    r.resume(T0 + 2000);
    r.setMode('bike', T0 + 2500); // 骑车去 B
    r.addPoint(far(50), 5, T0 + 2600);
    r.pause([fix(far(300))], T0 + 3000);
    r.resume(T0 + 4000);
    r.addPoint(far(150, 'n'), 5, T0 + 4100); // 走回家
    r.finish([fix(HOME)], T0 + 5000);
    const edges = buildEdges(r.snapshot);
    expect(edges).toHaveLength(3);
    expect(edges[0].mode).toBe('walk'); // home→A
    expect(edges[1].mode).toBe('bike'); // A→B
    expect(edges[2].mode).toBe('walk'); // B→home（D19：到达 B 后自动回走路）
  });

  it('中途回 Home：多段循环天然成立', () => {
    const r = new RecorderState();
    r.start([fix(HOME)], T0);
    r.addPoint(HOME, 5, T0 + 100);
    r.pause([fix(far(100))], T0 + 1000); // A
    r.resume(T0 + 2000);
    r.addPoint(far(50), 5, T0 + 2100);
    r.pause([fix(far(5))], T0 + 3000); // 合并回 Home
    r.resume(T0 + 4000);
    r.addPoint(HOME, 5, T0 + 4100);
    r.pause([fix(far(200))], T0 + 5000); // C
    r.resume(T0 + 6000);
    r.addPoint(HOME, 5, T0 + 6100); // 走回家
    r.finish([fix(HOME)], T0 + 7000);
    const s = r.snapshot;
    const [nA, nC] = s.nodes.map((n) => n.id);
    const edges = buildEdges(s);
    expect(edges.map((e) => `${e.fromId}→${e.toId}`)).toEqual([
      `home→${nA}`,
      `${nA}→home`,
      `home→${nC}`,
      `${nC}→home`,
    ]);
  });

  it('未结束的会话不生成回 home 的尾边', () => {
    const r = new RecorderState();
    r.start([fix(HOME)], T0);
    r.addPoint(HOME, 5, T0 + 100);
    r.pause([fix(far(100))], T0 + 1000);
    r.resume(T0 + 2000);
    r.addPoint(far(50), 5, T0 + 2100);
    const edges = buildEdges(r.snapshot);
    expect(edges).toHaveLength(1);
    expect(edges[0].toId).not.toBe('home');
  });
});

describe('SVG 投影', () => {
  const pts = [
    { lat: 31.0, lng: 121.0 },
    { lat: 31.1, lng: 121.0 },
    { lat: 31.0, lng: 121.1 },
  ];

  it('所有点落在视口内', () => {
    const proj = projectToView(pts, 400, 300);
    for (const p of proj) {
      expect(p.x).toBeGreaterThanOrEqual(0);
      expect(p.x).toBeLessThanOrEqual(400);
      expect(p.y).toBeGreaterThanOrEqual(0);
      expect(p.y).toBeLessThanOrEqual(300);
    }
  });

  it('等比不变形：南北 1° 与东西 1° 同像素长度', () => {
    const b = projectToView(
      [
        { lat: 30, lng: 120 },
        { lat: 31, lng: 120 },
        { lat: 30, lng: 121 },
      ],
      400,
      400,
    );
    // 北在上：lat 31 的 y 小于 lat 30
    expect(b[1].y).toBeLessThan(b[0].y);
    const dy = Math.abs(b[1].y - b[0].y);
    const dx = Math.abs(b[2].x - b[0].x);
    expect(dx).toBeCloseTo(dy, 9);
  });

  it('toSvgPath 起笔 M 且点数一致', () => {
    const d = toSvgPath(pts, 400, 300);
    expect(d.startsWith('M')).toBe(true);
    const cmds = d.split('L');
    expect(cmds).toHaveLength(3);
  });

  it('空点集返回空串', () => {
    expect(toSvgPath([], 400, 300)).toBe('');
  });
});
