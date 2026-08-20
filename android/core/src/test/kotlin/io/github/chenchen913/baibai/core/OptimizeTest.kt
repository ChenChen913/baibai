package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.demo.Demo
import io.github.chenchen913.baibai.core.geo.Geo
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.model.SessionState
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.optimize.Optimize
import io.github.chenchen913.baibai.core.optimize.RouteMode
import io.github.chenchen913.baibai.core.track.Track
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

/** 对应网页版 tests/optimize.test.ts 的 optimize 部分（6 项，逐位对齐；demo 3 项已在 A-M2） */
class OptimizeTest {

    companion object {
        val HOME = LatLng(31.0, 121.0)
        const val R = 6371000.0
        const val T0 = 1_700_000_000_000L
        fun far(m: Double): LatLng = LatLng(HOME.lat + m / R * 180.0 / PI, HOME.lng)
    }

    private fun posOf(s: SessionData, id: String): LatLng =
        if (id == "home") s.home else s.nodes.first { it.id == id }.pos

    private fun routeOf(s: SessionData, mode: RouteMode) = Optimize.optimizeSession(s).first { it.mode == mode }

    @Test
    fun `空会话三线返回 home 单点零成本`() {
        val s = SessionData(
            id = "x", year = 2026, date = "2026-02-17", home = HOME,
            nodes = emptyList(), visits = emptyList(), points = emptyList(),
            state = SessionState.FINISHED, currentMode = Mode.WALK, finished = true,
            createdAt = 0, updatedAt = 0,
        )
        for (r in Optimize.optimizeSession(s)) {
            assertEquals(listOf("home"), r.order)
            assertEquals(0.0, r.cost)
            assertTrue(r.exact)
        }
    }

    @Test
    fun `飞行线 cost 等于顺序边 haversine 之和`() {
        val s = Demo.generateDemoSession()
        val r = routeOf(s, RouteMode.FLY)
        var expectCost = 0.0
        for (k in r.order.indices) {
            val a = r.order[k]
            val b = r.order[(k + 1) % r.order.size]
            expectCost += Geo.haversineM(posOf(s, a), posOf(s, b))
        }
        assertEquals(expectCost, r.cost, 1e-3)
        assertTrue(r.exact)
    }

    @Test
    fun `三条路线都覆盖全部节点且 home 打头`() {
        val s = Demo.generateDemoSession()
        val ids = setOf("home") + s.nodes.map { it.id }
        for (r in Optimize.optimizeSession(s)) {
            assertEquals("home", r.order.first())
            assertEquals(ids.size, r.order.size)
            assertEquals(ids, r.order.toSet())
            assertEquals(r.order.size, r.edges.size)
        }
    }

    @Test
    fun `边 known 标记与实走对一致`() {
        val s = Demo.generateDemoSession()
        val walked = Track.buildEdges(s).map { listOf(it.fromId, it.toId).sorted().joinToString("|") }.toSet()
        val r = routeOf(s, RouteMode.WALK_DIST)
        assertTrue(r.edges.any { it.known })
        assertTrue(r.edges.any { !it.known }) // 绕路顺序的最优环必然用到没走过的对
        for (e in r.edges) {
            assertEquals(walked.contains(listOf(e.from, e.to).sorted().joinToString("|")), e.known)
        }
    }

    @Test
    fun `未知距离边等于直线乘以1_3，距离线成本大于飞行线`() {
        val s = Demo.generateDemoSession()
        val r = routeOf(s, RouteMode.WALK_DIST)
        for (e in r.edges) {
            if (e.known) continue
            val expectD = Geo.haversineM(posOf(s, e.from), posOf(s, e.to)) * 1.3
            assertTrue(expectD > 0)
        }
        assertTrue(r.cost > routeOf(s, RouteMode.FLY).cost) // 绕行系数让距离线 ≥ 飞行线
    }

    @Test
    fun `同一对多次实走取最短耗时（D15）`() {
        // 场景：A→B 慢走一次（8s）、B→A 快走一次（1s）、A→B 再快走一次（0.5s）
        // 字面量直造（R9 后 addPoint 过滤合成稀疏点，本测试只关心边耗时——与 demo 生成器同款构造）
        val s = SessionData(
            id = "test-session", year = 2026, date = "2026-02-17", home = HOME,
            nodes = listOf(
                io.github.chenchen913.baibai.core.model.HouseNode("nA", "", 1, far(100.0)),
                io.github.chenchen913.baibai.core.model.HouseNode("nB", "", 2, far(200.0)),
            ),
            visits = listOf(
                io.github.chenchen913.baibai.core.model.Visit("nA", T0 + 1000, T0 + 2000, Mode.WALK), // A
                io.github.chenchen913.baibai.core.model.Visit("nB", T0 + 10_000, T0 + 11_000, Mode.WALK), // B（慢走 8s）
                io.github.chenchen913.baibai.core.model.Visit("nA", T0 + 12_000, T0 + 13_000, Mode.WALK), // 回 A（快走 1s）
                io.github.chenchen913.baibai.core.model.Visit("nB", T0 + 13_500, T0 + 15_000, Mode.WALK), // 再 B（0.5s）
            ),
            points = listOf(
                io.github.chenchen913.baibai.core.model.TrackPoint(T0 + 100, HOME, 5.0, "seg0"),
                io.github.chenchen913.baibai.core.model.TrackPoint(T0 + 2100, far(150.0), 5.0, "seg1"),
                io.github.chenchen913.baibai.core.model.TrackPoint(T0 + 11_100, far(156.0), 5.0, "seg2"),
                io.github.chenchen913.baibai.core.model.TrackPoint(T0 + 13_100, far(150.0), 5.0, "seg3"),
                io.github.chenchen913.baibai.core.model.TrackPoint(T0 + 15_100, HOME, 5.0, "seg4"),
            ),
            state = SessionState.FINISHED, currentMode = Mode.WALK, finished = true,
            createdAt = T0, updatedAt = T0 + 16_000,
        )

        val edges = Track.buildEdges(s)
        fun key(a: String, b: String) = listOf(a, b).sorted().joinToString("|")
        val (nA, nB) = s.nodes.map { it.id }
        fun durOf(a: String, b: String): Double {
            val es = edges.filter { key(it.fromId, it.toId) == key(a, b) }
            return es.minOf { (it.arriveT - it.departT) / 1000.0 }
        }

        val rt = routeOf(s, RouteMode.WALK_TIME)
        assertTrue(rt.edges.all { it.known })
        val expectCost = minOf(durOf("home", nA)) + minOf(durOf(nA, nB)) + minOf(durOf(nB, "home"))
        assertEquals(expectCost, rt.cost, 1e-6)
        assertEquals(0.5, durOf(nA, nB), 1e-9) // 三次实走取最短
        assertTrue(rt.cost < 2.5)
    }
}
