import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  matchPlan,
  nameCandidates,
  planFromSession,
  type Plan,
} from '../src/plan.js';
import { generateDemoSession } from '../src/demo.js';
import {
  clearPlan,
  loadPlan,
  savePlan,
} from '../src/db.js';
import type { SessionData } from '../src/state.js';

const HOME = { lat: 31.0, lng: 121.0 };
const R = 6371000;
const far = (m: number) => ({
  lat: HOME.lat + ((m / R) * 180) / Math.PI,
  lng: HOME.lng,
});

function mkSession(nodes: { id: string; name: string; pos: { lat: number; lng: number } }[]): SessionData {
  return {
    id: 's-test',
    year: 2027,
    date: '2027-02-06',
    home: HOME,
    nodes: nodes.map((n, i) => ({ ...n, autoNo: i + 1 })),
    visits: [],
    points: [],
    state: 'FINISHED',
    currentMode: 'walk',
    finished: true,
    createdAt: 0,
    updatedAt: 0,
  };
}

describe('planFromSession', () => {
  it('从去年会话生成清单：节点数与名字原样', () => {
    const prev = mkSession([
      { id: 'a', name: '大伯家', pos: far(100) },
      { id: 'b', name: '', pos: far(200) },
    ]);
    const plan = planFromSession(prev, 2027);
    expect(plan.year).toBe(2027);
    expect(plan.items).toHaveLength(2);
    expect(plan.items[0]).toEqual({ name: '大伯家', pos: far(100) });
    expect(plan.items[1].name).toBe('');
  });
});

describe('matchPlan', () => {
  const plan: Plan = {
    year: 2027,
    createdAt: 0,
    updatedAt: 0,
    items: [
      { name: '大伯家', pos: far(100) },
      { name: '二叔家', pos: far(300) },
      { name: '三舅家', pos: far(500) },
    ],
  };

  it('全部到访：三对三', () => {
    const s = mkSession([
      { id: 'n1', name: '', pos: far(103) },
      { id: 'n2', name: '', pos: far(298) },
      { id: 'n3', name: '', pos: far(502) },
    ]);
    const r = matchPlan(s, plan);
    expect(r.visited).toHaveLength(3);
    expect(r.missing).toHaveLength(0);
  });

  it('漏访：一户没去', () => {
    const s = mkSession([
      { id: 'n1', name: '', pos: far(103) },
      { id: 'n2', name: '', pos: far(298) },
    ]);
    const r = matchPlan(s, plan);
    expect(r.visited).toHaveLength(2);
    expect(r.missing.map((m) => m.name)).toEqual(['三舅家']);
  });

  it('一对一贪心：两 item 争同一 node，只配最近者', () => {
    const s = mkSession([{ id: 'n1', name: '', pos: far(105) }]);
    const two = { ...plan, items: [
      { name: 'A', pos: far(102) },
      { name: 'B', pos: far(108) },
    ] };
    const r = matchPlan(s, two);
    expect(r.visited).toHaveLength(1);
    expect(r.visited[0].item.name).toBe('A'); // 距离更近者胜
    expect(r.missing.map((m) => m.name)).toEqual(['B']);
  });

  it('10m 边界：9.999 配、10.001 缺', () => {
    const edge = { ...plan, items: [
      { name: 'A', pos: far(9.999) },
      { name: 'B', pos: far(10.001) },
    ] };
    const s = mkSession([{ id: 'n1', name: '', pos: far(0) }]);
    const r = matchPlan(s, edge);
    expect(r.visited.map((v) => v.item.name)).toEqual(['A']);
    expect(r.missing.map((m) => m.name)).toEqual(['B']);
  });

  it('空清单与空会话', () => {
    const empty: Plan = { year: 2027, createdAt: 0, updatedAt: 0, items: [] };
    expect(matchPlan(mkSession([{ id: 'n', name: '', pos: far(10) }]), empty)).toEqual({ visited: [], missing: [] });
    const s = mkSession([]);
    expect(matchPlan(s, plan).missing).toHaveLength(3);
  });

  it('无坐标项不参与自动匹配，恒为 missing（手动核对）', () => {
    const s = mkSession([{ id: 'n1', name: '', pos: far(100) }]);
    const withManual: Plan = {
      ...plan,
      items: [
        { name: '大伯家', pos: far(100) },
        { name: '新搬来的张叔家', pos: null },
      ],
    };
    const r = matchPlan(s, withManual);
    expect(r.visited.map((v) => v.item.name)).toEqual(['大伯家']);
    expect(r.missing.map((m) => m.name)).toEqual(['新搬来的张叔家']);
  });
});

describe('nameCandidates', () => {
  it('按距离升序、空名过滤、top 截断', () => {
    const prev = generateDemoSession();
    const cands = nameCandidates(far(200), prev.nodes, 3);
    expect(cands).toHaveLength(3);
    expect(cands[0].distM).toBeLessThanOrEqual(cands[1].distM);
    expect(cands[1].distM).toBeLessThanOrEqual(cands[2].distM);
    expect(cands.every((c) => c.name.trim() !== '')).toBe(true);
  });
});

describe('db plans（M4）', () => {
  beforeEach(async () => {
    await clearPlan(2027);
    await clearPlan(2026);
  });

  it('保存/读取/覆盖往返', async () => {
    const p: Plan = {
      year: 2027,
      createdAt: 1,
      updatedAt: 2,
      items: [{ name: '大伯家', pos: far(100) }],
    };
    await savePlan(p);
    expect(await loadPlan(2027)).toEqual(p);
    const p2 = { ...p, items: [] };
    await savePlan(p2);
    expect((await loadPlan(2027))!.items).toHaveLength(0);
  });

  it('清除后读不到', async () => {
    await savePlan({ year: 2027, createdAt: 0, updatedAt: 0, items: [] });
    await clearPlan(2027);
    expect(await loadPlan(2027)).toBeUndefined();
  });
});
