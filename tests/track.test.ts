import { describe, expect, it } from 'vitest';
import { RecorderState, type Mode, type SessionData } from '../src/state.js';
import type { LatLng } from '../src/geo.js';
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

/**
 * 字面量直造会话数据（R9 后 addPoint 有平滑窗口+连续确认过滤，合成稀疏点会被过滤，
 * 本文件测的是分段/投影等下游逻辑，不测入库过滤——与 demo.ts 同款构造方式）
 */
function makeSession(
  nodes: Array<{ id: string; pos: LatLng }>,
  visits: Array<{ nodeId: string; arriveT: number; leaveT: number; mode: Mode }>,
  points: Array<{ t: number; pos: LatLng }>,
): SessionData {
  // 段 id 按离开时刻递增（与状态机 resume 的 segCounter 语义一致）
  const segOf = (t: number): string => {
    let seg = 0;
    for (const v of visits) if (t > v.leaveT) seg += 1;
    return `seg${seg}`;
  };
  return {
    id: 'test-session',
    year: 2026,
    date: '2026-02-17',
    home: HOME,
    nodes: nodes.map((n, i) => ({ id: n.id, name: '', autoNo: i + 1, pos: n.pos })),
    visits: visits.map((v) => ({
      nodeId: v.nodeId,
      arriveT: v.arriveT,
      leaveT: v.leaveT,
      mode: v.mode,
    })),
    points: points.map((p) => ({ t: p.t, pos: p.pos, acc: 5, seg: segOf(p.t) })),
    state: 'FINISHED',
    currentMode: 'walk',
    finished: true,
    createdAt: T0,
    updatedAt: T0 + 10_000,
  };
}

/** 构造三段场景：home→A→B→home */
function threeStopSession() {
  return makeSession(
    [
      { id: 'nA', pos: far(100) }, // A
      { id: 'nB', pos: far(300) }, // B
    ],
    [
      { nodeId: 'nA', arriveT: T0 + 2000, leaveT: T0 + 3000, mode: 'walk' },
      { nodeId: 'nB', arriveT: T0 + 5000, leaveT: T0 + 6000, mode: 'walk' },
    ],
    [
      { t: T0 + 500, pos: HOME }, // home→A 途中
      { t: T0 + 700, pos: far(30, 'n') },
      { t: T0 + 900, pos: far(60, 'n') },
      { t: T0 + 1100, pos: far(90, 'n') },
      { t: T0 + 3500, pos: far(50, 'n') }, // A→B 途中
      { t: T0 + 6500, pos: far(150, 'n') }, // B→home 途中
    ],
  );
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
    const s = makeSession(
      [
        { id: 'nA', pos: far(100) },
        { id: 'nB', pos: far(300) },
      ],
      [
        { nodeId: 'nA', arriveT: T0 + 1000, leaveT: T0 + 2000, mode: 'walk' },
        { nodeId: 'nB', arriveT: T0 + 3000, leaveT: T0 + 4000, mode: 'bike' }, // 骑车去 B
      ],
      [
        { t: T0 + 100, pos: HOME },
        { t: T0 + 2600, pos: far(50) },
        { t: T0 + 4100, pos: far(150, 'n') }, // 走回家
      ],
    );
    const edges = buildEdges(s);
    expect(edges).toHaveLength(3);
    expect(edges[0].mode).toBe('walk'); // home→A
    expect(edges[1].mode).toBe('bike'); // A→B
    expect(edges[2].mode).toBe('walk'); // B→home（D19：到达 B 后自动回走路）
  });

  it('中途回 Home：多段循环天然成立', () => {
    const s = makeSession(
      [
        { id: 'nA', pos: far(100) },
        { id: 'nC', pos: far(200) },
      ],
      [
        { nodeId: 'nA', arriveT: T0 + 1000, leaveT: T0 + 2000, mode: 'walk' }, // A
        { nodeId: 'home', arriveT: T0 + 3000, leaveT: T0 + 4000, mode: 'walk' }, // 合并回 Home
        { nodeId: 'nC', arriveT: T0 + 5000, leaveT: T0 + 6000, mode: 'walk' }, // C
      ],
      [
        { t: T0 + 100, pos: HOME },
        { t: T0 + 2100, pos: far(50) },
        { t: T0 + 4100, pos: HOME },
        { t: T0 + 6100, pos: far(6) }, // 走回家（门口 6m 处）
      ],
    );
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

  it('R7：静止小点团不放大充满视口（最小跨度 60m）', () => {
    // 坐板凳 2 分钟的抖动团：纬度方向 0~3m。旧版投影会把 3m 跨度放大到全屏画成一团乱线
    const pts = [far(0), far(2), far(1), far(3)];
    const proj = projectToView(pts, 400, 300);
    const xs = proj.map((p) => p.x);
    const ys = proj.map((p) => p.y);
    // 实际跨度 3m / 最小跨度 60m → 像素跨度 ≈ usableH 的 5%（12px），留足余量断言 ≤40px
    expect(Math.max(...xs) - Math.min(...xs)).toBeLessThanOrEqual(40);
    expect(Math.max(...ys) - Math.min(...ys)).toBeLessThanOrEqual(40);
    // 点团居中：质心在视口中心附近（不偏到角落）
    const cx = xs.reduce((a, b) => a + b, 0) / xs.length;
    const cy = ys.reduce((a, b) => a + b, 0) / ys.length;
    expect(Math.abs(cx - 200)).toBeLessThanOrEqual(20);
    expect(Math.abs(cy - 150)).toBeLessThanOrEqual(20);
  });
});
