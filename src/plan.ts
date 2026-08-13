/** 跨年便利纯函数（M4）：今年清单、漏访匹配、套名候选（D17/D18/F-9） */

import type { LatLng } from './geo.js';
import { haversineM } from './geo.js';
import type { HouseNode, SessionData } from './state.js';

export interface PlanItem {
  name: string; // 可为空，UI 显示"户N"兜底
  pos: LatLng | null; // null=手动添加（无坐标，不参与自动匹配，仅在漏访区提醒）
}

export interface Plan {
  year: number;
  items: PlanItem[];
  createdAt: number;
  updatedAt: number;
}

export const PLAN_MATCH_M = 10; // 与 D10 节点合并阈值一致

/** 从往年会话生成今年清单（F-1） */
export function planFromSession(
  prev: SessionData,
  year: number,
  now = Date.now(),
): Plan {
  return {
    year,
    createdAt: now,
    updatedAt: now,
    items: prev.nodes.map((n) => ({ name: n.name, pos: n.pos })),
  };
}

export interface MatchResult {
  visited: { item: PlanItem; nodeId: string }[];
  missing: PlanItem[]; // 疑似漏访
}

/** 贪心一对一匹配：item×node 距离升序，≤threshold 配对，每方最多一次（D18 回顾页对比） */
export function matchPlan(
  s: SessionData,
  plan: Plan,
  threshold = PLAN_MATCH_M,
): MatchResult {
  const cands: { i: number; j: number; d: number }[] = [];
  plan.items.forEach((item, i) => {
    const pos = item.pos;
    if (!pos) return; // 无坐标项不参与自动匹配
    s.nodes.forEach((n, j) => {
      cands.push({ i, j, d: haversineM(pos, n.pos) });
    });
  });
  cands.sort((a, b) => a.d - b.d);
  const usedItem = new Set<number>();
  const usedNode = new Set<number>();
  const visited: { item: PlanItem; nodeId: string }[] = [];
  for (const c of cands) {
    if (c.d > threshold) break;
    if (usedItem.has(c.i) || usedNode.has(c.j)) continue;
    usedItem.add(c.i);
    usedNode.add(c.j);
    visited.push({ item: plan.items[c.i], nodeId: s.nodes[c.j].id });
  }
  const missing = plan.items.filter((_, i) => !usedItem.has(i));
  return { visited, missing };
}

export interface NameCandidate {
  name: string;
  distM: number;
}

/** 套名候选：去年**有名字**的户按距离升序取前 top 个（D17） */
export function nameCandidates(
  nodePos: LatLng,
  prevNodes: HouseNode[],
  top = 3,
): NameCandidate[] {
  return prevNodes
    .filter((n) => n.name.trim() !== '')
    .map((n) => ({ name: n.name, distM: haversineM(nodePos, n.pos) }))
    .sort((a, b) => a.distM - b.distM)
    .slice(0, top);
}
