/** 旅行商问题求解器：Held-Karp 精确解（n≤16）+ 贪心/2-opt 启发式（更大规模） */

export interface TspResult {
  order: number[]; // 起点打头，不含闭合重复
  cost: number;
  exact: boolean;
}

export const EXACT_MAX = 16;

/** Held-Karp 动态规划（固定起点 0）：O(2^n · n²) */
function heldKarp(d: number[][], start: number): { order: number[]; cost: number } {
  const n = d.length;
  if (n === 1) return { order: [0], cost: 0 };
  if (n === 2) return { order: [0, 1], cost: d[0][1] * 2 };
  const full = (1 << n) - 1;
  const dp: number[][] = Array.from({ length: 1 << n }, () =>
    new Array<number>(n).fill(Infinity),
  );
  const parent: number[][] = Array.from({ length: 1 << n }, () =>
    new Array<number>(n).fill(-1),
  );
  dp[1 << start][start] = 0;
  for (let mask = 0; mask <= full; mask++) {
    if ((mask & (1 << start)) === 0) continue;
    for (let u = 0; u < n; u++) {
      if ((mask & (1 << u)) === 0) continue;
      const cur = dp[mask][u];
      if (cur === Infinity) continue;
      for (let v = 0; v < n; v++) {
        if (mask & (1 << v)) continue;
        const nm = mask | (1 << v);
        const nd = cur + d[u][v];
        if (nd < dp[nm][v]) {
          dp[nm][v] = nd;
          parent[nm][v] = u;
        }
      }
    }
  }
  let bestEnd = -1;
  let best = Infinity;
  for (let u = 0; u < n; u++) {
    if (u === start) continue;
    const nd = dp[full][u] + d[u][start];
    if (nd < best) {
      best = nd;
      bestEnd = u;
    }
  }
  const order: number[] = [];
  let mask = full;
  let u = bestEnd;
  while (u !== -1) {
    order.push(u);
    const pu = parent[mask][u];
    mask &= ~(1 << u);
    u = pu;
  }
  order.reverse();
  return { order, cost: best };
}

function pathCost(d: number[][], order: number[]): number {
  let c = 0;
  for (let i = 1; i < order.length; i++) c += d[order[i - 1]][order[i]];
  c += d[order[order.length - 1]][order[0]];
  return c;
}

/** 贪心最近邻（起点 0） */
function greedy(d: number[][], start: number): number[] {
  const n = d.length;
  const visited = new Array<boolean>(n).fill(false);
  const order = [start];
  visited[start] = true;
  let cur = start;
  for (let k = 1; k < n; k++) {
    let best = -1;
    let bestD = Infinity;
    for (let v = 0; v < n; v++) {
      if (!visited[v] && d[cur][v] < bestD) {
        bestD = d[cur][v];
        best = v;
      }
    }
    order.push(best);
    visited[best] = true;
    cur = best;
  }
  return order;
}

/** 2-opt 局部优化（起点不动） */
function twoOpt(d: number[][], order: number[]): number[] {
  let cur = order.slice();
  let improved = true;
  while (improved) {
    improved = false;
    for (let i = 1; i < cur.length - 1; i++) {
      for (let j = i + 1; j < cur.length; j++) {
        const rev = [
          ...cur.slice(0, i),
          ...cur.slice(i, j + 1).reverse(),
          ...cur.slice(j + 1),
        ];
        if (pathCost(d, rev) < pathCost(d, cur) - 1e-9) {
          cur = rev;
          improved = true;
        }
      }
    }
  }
  return cur;
}

/** 求解 TSP：n≤16 精确，否则启发式 */
export function solveTsp(d: number[][], start = 0): TspResult {
  const n = d.length;
  if (n <= EXACT_MAX) {
    const { order, cost } = heldKarp(d, start);
    return { order, cost, exact: true };
  }
  const order = twoOpt(d, greedy(d, start));
  return { order, cost: pathCost(d, order), exact: false };
}
