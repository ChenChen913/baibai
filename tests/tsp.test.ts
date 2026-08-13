import { describe, expect, it } from 'vitest';
import { solveTsp, EXACT_MAX } from '../src/tsp.js';

/** 全排列暴力解（n≤8 校验用） */
function bruteForce(d: number[][]): number {
  const n = d.length;
  const rest = [...Array(n - 1).keys()].map((i) => i + 1);
  let best = Infinity;
  const perm = (arr: number[], k: number): void => {
    if (k === arr.length) {
      let c = d[0][arr[0]];
      for (let i = 1; i < arr.length; i++) c += d[arr[i - 1]][arr[i]];
      c += d[arr[arr.length - 1]][0];
      if (c < best) best = c;
      return;
    }
    for (let i = k; i < arr.length; i++) {
      [arr[k], arr[i]] = [arr[i], arr[k]];
      perm(arr, k + 1);
      [arr[k], arr[i]] = [arr[i], arr[k]];
    }
  };
  perm(rest, 0);
  return best;
}

function randDist(n: number, seed: number): number[][] {
  // 对称欧氏近似（随机点）
  let s = seed;
  const rnd = () => {
    s = (s * 1103515245 + 12345) & 0x7fffffff;
    return s / 0x7fffffff;
  };
  const pts = Array.from({ length: n }, () => ({ x: rnd() * 100, y: rnd() * 100 }));
  const d = pts.map((a) =>
    pts.map((b) => Math.hypot(a.x - b.x, a.y - b.y)),
  );
  return d;
}

function isPermutationOfAll(order: number[], n: number): boolean {
  return (
    order.length === n &&
    order[0] === 0 &&
    new Set(order).size === n &&
    order.every((v) => v >= 0 && v < n)
  );
}

describe('solveTsp', () => {
  it('n=1 与 n=2 平凡情形', () => {
    expect(solveTsp([[0]])).toEqual({ order: [0], cost: 0, exact: true });
    expect(solveTsp([[0, 5], [5, 0]])).toEqual({
      order: [0, 1],
      cost: 10,
      exact: true,
    });
  });

  it('n=6 随机 10 组：精确解等于全排列暴力解', () => {
    for (let seed = 1; seed <= 10; seed++) {
      const d = randDist(6, seed);
      const res = solveTsp(d);
      expect(res.exact).toBe(true);
      expect(isPermutationOfAll(res.order, 6)).toBe(true);
      expect(res.cost).toBeCloseTo(bruteForce(d), 6);
    }
  });

  it('n=16 边界仍为精确解且排列合法', () => {
    const d = randDist(16, 42);
    const res = solveTsp(d);
    expect(res.exact).toBe(true);
    expect(isPermutationOfAll(res.order, 16)).toBe(true);
  });

  it('n>16 走启发式：exact=false 且排列合法', () => {
    const n = EXACT_MAX + 2;
    const d = randDist(n, 7);
    const res = solveTsp(d);
    expect(res.exact).toBe(false);
    expect(isPermutationOfAll(res.order, n)).toBe(true);
    expect(res.cost).toBeGreaterThan(0);
  });
});
