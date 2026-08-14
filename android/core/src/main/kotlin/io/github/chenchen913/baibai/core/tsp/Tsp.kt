package io.github.chenchen913.baibai.core.tsp

/** 旅行商问题求解器（对应网页版 tsp.ts）：Held-Karp 精确解（n≤16）+ 贪心/2-opt 启发式（更大规模） */
object Tsp {

    const val EXACT_MAX = 16

    data class Result(val order: List<Int>, val cost: Double, val exact: Boolean)

    /** Held-Karp 动态规划（固定起点 0）：O(2^n · n²) */
    private fun heldKarp(d: Array<DoubleArray>, start: Int): Pair<List<Int>, Double> {
        val n = d.size
        if (n == 1) return listOf(0) to 0.0
        if (n == 2) return listOf(0, 1) to d[0][1] * 2
        val full = (1 shl n) - 1
        val dp = Array(1 shl n) { DoubleArray(n) { Double.POSITIVE_INFINITY } }
        val parent = Array(1 shl n) { IntArray(n) { -1 } }
        dp[1 shl start][start] = 0.0
        for (mask in 0..full) {
            if ((mask and (1 shl start)) == 0) continue
            for (u in 0 until n) {
                if ((mask and (1 shl u)) == 0) continue
                val cur = dp[mask][u]
                if (cur == Double.POSITIVE_INFINITY) continue
                for (v in 0 until n) {
                    if ((mask and (1 shl v)) != 0) continue
                    val nm = mask or (1 shl v)
                    val nd = cur + d[u][v]
                    if (nd < dp[nm][v]) {
                        dp[nm][v] = nd
                        parent[nm][v] = u
                    }
                }
            }
        }
        var bestEnd = -1
        var best = Double.POSITIVE_INFINITY
        for (u in 0 until n) {
            if (u == start) continue
            val nd = dp[full][u] + d[u][start]
            if (nd < best) {
                best = nd
                bestEnd = u
            }
        }
        val order = mutableListOf<Int>()
        var mask = full
        var u = bestEnd
        while (u != -1) {
            order.add(u)
            val pu = parent[mask][u]
            mask = mask and (1 shl u).inv()
            u = pu
        }
        order.reverse()
        return order to best
    }

    private fun pathCost(d: Array<DoubleArray>, order: List<Int>): Double {
        var c = 0.0
        for (i in 1 until order.size) c += d[order[i - 1]][order[i]]
        c += d[order.last()][order[0]]
        return c
    }

    /** 贪心最近邻（起点 0） */
    private fun greedy(d: Array<DoubleArray>, start: Int): List<Int> {
        val n = d.size
        val visited = BooleanArray(n)
        val order = mutableListOf(start)
        visited[start] = true
        var cur = start
        repeat(n - 1) {
            var bestV = -1
            var bestD = Double.POSITIVE_INFINITY
            for (v in 0 until n) {
                if (!visited[v] && d[cur][v] < bestD) {
                    bestD = d[cur][v]
                    bestV = v
                }
            }
            order.add(bestV)
            visited[bestV] = true
            cur = bestV
        }
        return order
    }

    /** 2-opt 局部优化（起点不动） */
    private fun twoOpt(d: Array<DoubleArray>, order: List<Int>): List<Int> {
        var cur = order.toMutableList()
        var improved = true
        while (improved) {
            improved = false
            for (i in 1 until cur.size - 1) {
                for (j in i + 1 until cur.size) {
                    val rev = ArrayList<Int>(cur.size)
                    rev.addAll(cur.subList(0, i))
                    rev.addAll(cur.subList(i, j + 1).reversed())
                    rev.addAll(cur.subList(j + 1, cur.size))
                    if (pathCost(d, rev) < pathCost(d, cur) - 1e-9) {
                        cur = rev
                        improved = true
                    }
                }
            }
        }
        return cur
    }

    /** 求解 TSP：n≤16 精确，否则启发式 */
    fun solveTsp(d: Array<DoubleArray>, start: Int = 0): Result {
        val n = d.size
        if (n <= EXACT_MAX) {
            val (order, cost) = heldKarp(d, start)
            return Result(order, cost, exact = true)
        }
        val order = twoOpt(d, greedy(d, start))
        return Result(order, pathCost(d, order), exact = false)
    }
}
