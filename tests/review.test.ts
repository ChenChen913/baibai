import { describe, expect, it } from 'vitest';
import { RecorderState } from '../src/state.js';
import {
  mergeNodes,
  removePoint,
  renameNode,
  renumberNodes,
  splitVisit,
} from '../src/review.js';

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

/** 两户场景：A（拜访两次，相距 8m 合并），B（拜访一次） */
function twoNodeSession() {
  const r = new RecorderState();
  r.start([fix(HOME)], T0);
  r.addPoint(HOME, 5, T0 + 100);
  r.pause([fix(far(100))], T0 + 1000); // A 第 1 次
  r.resume(T0 + 2000);
  r.addPoint(far(104), 5, T0 + 2100); // 靠近 A 的点（供拆分取坐标）
  r.pause([fix(far(108))], T0 + 3000); // A 第 2 次（8m 内合并）
  r.resume(T0 + 4000);
  r.addPoint(far(200), 5, T0 + 4100);
  r.pause([fix(far(300))], T0 + 5000); // B
  r.resume(T0 + 6000);
  r.addPoint(HOME, 5, T0 + 6100);
  r.finish([fix(HOME)], T0 + 7000);
  return r.snapshot;
}

describe('renameNode', () => {
  it('改名生效', () => {
    const s = twoNodeSession();
    const id = s.nodes[0].id;
    const out = renameNode(s, id, '大伯家');
    expect(out.nodes.find((n) => n.id === id)!.name).toBe('大伯家');
    expect(out.visits).toEqual(s.visits); // 其他数据不动
  });

  it('home 不可改名', () => {
    const s = twoNodeSession();
    expect(renameNode(s, 'home', 'X')).toBe(s);
  });
});

describe('mergeNodes', () => {
  it('drop 的访问并入 keep，节点删除，空名继承 drop 名', () => {
    const s = twoNodeSession();
    const [a, b] = s.nodes.map((n) => n.id);
    const named = renameNode(s, b, '二叔家');
    const out = mergeNodes(named, a, b);
    expect(out.nodes).toHaveLength(1);
    expect(out.nodes[0].id).toBe(a);
    expect(out.visits.filter((v) => v.nodeId === a)).toHaveLength(3); // A 2 次 + B 1 次
    expect(out.visits.some((v) => v.nodeId === b)).toBe(false);
    expect(out.nodes[0].name).toBe('二叔家'); // keep 原名空 → 继承 drop 名
    const out2 = mergeNodes(named, b, a); // 反向：keep=B（有名字）
    expect(out2.nodes[0].name).toBe('二叔家');
  });

  it('不存在的 id 原样返回', () => {
    const s = twoNodeSession();
    expect(mergeNodes(s, 'x', 'y')).toBe(s);
  });
});

describe('splitVisit', () => {
  it('拆出第二次到访为新户，坐标取 arriveT 前最近点', () => {
    const s = twoNodeSession();
    const visitIdx = s.visits.findIndex(
      (v) => v.nodeId === s.nodes[0].id && v.arriveT === T0 + 3000,
    );
    const out = splitVisit(s, visitIdx);
    expect(out.nodes).toHaveLength(3);
    const newNode = out.nodes.find((n) => n.autoNo === 2);
    expect(newNode).toBeTruthy();
    expect(newNode!.pos).toEqual(far(104)); // T0+2100 的点
    expect(out.visits[visitIdx].nodeId).toBe(newNode!.id);
    // 编号重排：A(1) 新户(2) B(3)
    expect(out.nodes.map((n) => n.autoNo).sort((x, y) => x - y)).toEqual([1, 2, 3]);
  });

  it('home 访问不可拆', () => {
    const r = new RecorderState();
    r.start([fix(HOME)], T0);
    r.addPoint(HOME, 5, T0 + 100);
    r.pause([fix(far(5))], T0 + 1000); // 合并回 Home
    r.resume(T0 + 2000);
    r.addPoint(far(50), 5, T0 + 2100);
    r.pause([fix(far(100))], T0 + 3000);
    const s = r.snapshot;
    expect(s.visits[0].nodeId).toBe('home');
    expect(splitVisit(s, 0)).toBe(s);
  });

  it('无轨迹点可借时原样返回', () => {
    const r = new RecorderState();
    r.start([fix(HOME)], T0);
    r.pause([fix(far(100))], T0 + 1000); // 没有 addPoint
    const s = r.snapshot;
    expect(splitVisit(s, 0)).toBe(s);
  });
});

describe('removePoint', () => {
  it('按时间戳剔除单个点', () => {
    const s = twoNodeSession();
    const t = s.points[1].t;
    const out = removePoint(s, t);
    expect(out.points).toHaveLength(s.points.length - 1);
    expect(out.points.some((p) => p.t === t)).toBe(false);
  });
});

describe('renumberNodes', () => {
  it('按首次到访顺序编号，未到访节点排后', () => {
    const s = twoNodeSession();
    const [a, b] = s.nodes.map((n) => n.id);
    const out = renumberNodes({ ...s, nodes: [{ ...s.nodes[0], id: a }, { ...s.nodes[1], id: b }] });
    // A 首访在前 → 1，B → 2
    expect(out.nodes.find((n) => n.id === a)!.autoNo).toBe(1);
    expect(out.nodes.find((n) => n.id === b)!.autoNo).toBe(2);
  });
});
