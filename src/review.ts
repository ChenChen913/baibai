/** 回顾页收拾操作（纯函数：输入 SessionData 输出新 SessionData，便于测试与持久化） */

import { GOOD_ACC_M, newId, type SessionData } from './state.js';

/** 改名（home 不可改名） */
export function renameNode(s: SessionData, nodeId: string, name: string): SessionData {
  if (nodeId === 'home') return s;
  return {
    ...s,
    nodes: s.nodes.map((n) => (n.id === nodeId ? { ...n, name } : n)),
  };
}

/** 合并两户：drop 的访问并入 keep，keep 名优先（空则继承 drop 名） */
export function mergeNodes(s: SessionData, keepId: string, dropId: string): SessionData {
  const keep = s.nodes.find((n) => n.id === keepId);
  const drop = s.nodes.find((n) => n.id === dropId);
  if (!keep || !drop || keepId === dropId) return s;
  const merged: SessionData = {
    ...s,
    nodes: s.nodes
      .filter((n) => n.id !== dropId)
      .map((n) =>
        n.id === keepId ? { ...n, name: n.name || drop.name } : n,
      ),
    visits: s.visits.map((v) =>
      v.nodeId === dropId ? { ...v, nodeId: keepId } : v,
    ),
  };
  return renumberNodes(merged);
}

/** 拆分某次访问为独立新户：新户坐标 = arriveT 之前最近的一个轨迹点 */
export function splitVisit(s: SessionData, visitIdx: number): SessionData {
  const v = s.visits[visitIdx];
  if (!v || v.nodeId === 'home') return s;
  let bestT = -Infinity;
  let bestPos: { lat: number; lng: number } | null = null;
  let bestAcc = 99;
  for (const p of s.points) {
    if (p.t <= v.arriveT && p.t > bestT) {
      bestT = p.t;
      bestPos = p.pos;
      bestAcc = p.acc;
    }
  }
  if (!bestPos) return s;
  const node = {
    id: newId('n'),
    name: '',
    autoNo: s.nodes.length + 1,
    pos: bestPos,
    ...(bestAcc > GOOD_ACC_M ? { lowAcc: true } : {}),
  };
  const splitted: SessionData = {
    ...s,
    nodes: [...s.nodes, node],
    visits: s.visits.map((vv, i) =>
      i === visitIdx ? { ...vv, nodeId: node.id } : vv,
    ),
  };
  return renumberNodes(splitted);
}

/** 剔除一个轨迹点（用于异常跳变点） */
export function removePoint(s: SessionData, t: number): SessionData {
  return { ...s, points: s.points.filter((p) => p.t !== t) };
}

/** 按首次到访顺序重排 autoNo（合并/拆分后保持编号直观） */
export function renumberNodes(s: SessionData): SessionData {
  const order: string[] = [];
  for (const v of [...s.visits].sort((a, b) => a.arriveT - b.arriveT)) {
    if (v.nodeId !== 'home' && !order.includes(v.nodeId)) order.push(v.nodeId);
  }
  for (const n of s.nodes) {
    if (!order.includes(n.id)) order.push(n.id);
  }
  const no = new Map(order.map((id, i) => [id, i + 1]));
  return {
    ...s,
    nodes: s.nodes.map((n) => ({ ...n, autoNo: no.get(n.id) ?? n.autoNo })),
  };
}
