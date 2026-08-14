package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.tsp.Tsp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

/** 对应网页版 tests/tsp.test.ts（4 项，逐位对齐） */
class TspTest {

    /** 全排列暴力解（n≤8 校验用） */
    private fun bruteForce(d: Array<DoubleArray>): Double {
        val n = d.size
        val rest = (1 until n).toMutableList()
        var best = Double.POSITIVE_INFINITY
        fun perm(arr: MutableList<Int>, k: Int) {
            if (k == arr.size) {
                var c = d[0][arr[0]]
                for (i in 1 until arr.size) c += d[arr[i - 1]][arr[i]]
                c += d[arr.last()][0]
                if (c < best) best = c
                return
            }
            for (i in k until arr.size) {
                val tmp = arr[k]; arr[k] = arr[i]; arr[i] = tmp
                perm(arr, k + 1)
                val tmp2 = arr[k]; arr[k] = arr[i]; arr[i] = tmp2
            }
        }
        perm(rest, 0)
        return best
    }

    /** 对称欧氏近似（随机点，种子确定性） */
    private fun randDist(n: Int, seed: Int): Array<DoubleArray> {
        var s = seed
        fun rnd(): Double {
            s = (s * 1103515245 + 12345) and 0x7fffffff
            return s / 0x7fffffff.toDouble()
        }
        val pts = List(n) { Pair(rnd() * 100, rnd() * 100) }
        return Array(n) { i -> DoubleArray(n) { j -> hypot(pts[i].first - pts[j].first, pts[i].second - pts[j].second) } }
    }

    private fun isPermutationOfAll(order: List<Int>, n: Int): Boolean =
        order.size == n && order[0] == 0 && order.toSet().size == n && order.all { it in 0 until n }

    @Test
    fun `n=1 与 n=2 平凡情形`() {
        assertEquals(Tsp.Result(listOf(0), 0.0, true), Tsp.solveTsp(arrayOf(doubleArrayOf(0.0))))
        assertEquals(
            Tsp.Result(listOf(0, 1), 10.0, true),
            Tsp.solveTsp(arrayOf(doubleArrayOf(0.0, 5.0), doubleArrayOf(5.0, 0.0))),
        )
    }

    @Test
    fun `n=6 随机 10 组：精确解等于全排列暴力解`() {
        for (seed in 1..10) {
            val d = randDist(6, seed)
            val res = Tsp.solveTsp(d)
            assertTrue(res.exact)
            assertTrue(isPermutationOfAll(res.order, 6))
            assertEquals(bruteForce(d), res.cost, 1e-6)
        }
    }

    @Test
    fun `n=16 边界仍为精确解且排列合法`() {
        val d = randDist(16, 42)
        val res = Tsp.solveTsp(d)
        assertTrue(res.exact)
        assertTrue(isPermutationOfAll(res.order, 16))
    }

    @Test
    fun `n 大于 16 走启发式：exact 为 false 且排列合法`() {
        val n = Tsp.EXACT_MAX + 2
        val d = randDist(n, 7)
        val res = Tsp.solveTsp(d)
        assertTrue(!res.exact)
        assertTrue(isPermutationOfAll(res.order, n))
        assertTrue(res.cost > 0)
    }
}
