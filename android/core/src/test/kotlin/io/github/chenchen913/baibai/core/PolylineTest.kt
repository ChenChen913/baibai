package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.demo.Demo
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.SessionState
import io.github.chenchen913.baibai.core.optimize.Optimize
import io.github.chenchen913.baibai.core.optimize.RouteMode
import io.github.chenchen913.baibai.core.polyline.Polyline
import io.github.chenchen913.baibai.core.track.XY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 对应网页版 tests/polyline.test.ts（7 项，逐位对齐） */
class PolylineTest {

    @Test
    fun `routePolyline 闭合：首尾同点，点数等于顺序加1`() {
        val s = Demo.generateDemoSession()
        val fly = Optimize.optimizeSession(s).first { it.mode == RouteMode.FLY }
        val pts = Polyline.routePolyline(s, fly.order)
        assertEquals(fly.order.size + 1, pts.size)
        assertEquals(pts.first(), pts.last())
        assertEquals(s.home, pts.first())
    }

    @Test
    fun `resamplePolyline 首尾保持、点数正确、等距中点`() {
        val pts = listOf(XY(0.0, 0.0), XY(10.0, 0.0), XY(10.0, 10.0))
        val out = Polyline.resamplePolyline(pts, 11)
        assertEquals(11, out.size)
        assertEquals(XY(0.0, 0.0), out.first())
        assertEquals(XY(10.0, 10.0), out.last())
        assertEquals(10.0, out[5].x, 1e-6) // 总长 20 → 半程处 (10,0)
        assertEquals(0.0, out[5].y, 1e-6)
    }

    @Test
    fun `resamplePolyline 退化折线不崩溃`() {
        val out = Polyline.resamplePolyline(listOf(XY(1.0, 1.0), XY(1.0, 1.0)), 5)
        assertEquals(5, out.size)
        assertTrue(out.all { it.x == 1.0 && it.y == 1.0 })
    }

    @Test
    fun `resamplePolyline 空与单点`() {
        assertEquals(emptyList<XY>(), Polyline.resamplePolyline(emptyList(), 5))
        val out = Polyline.resamplePolyline(listOf(XY(1.0, 2.0)), 5)
        assertEquals(5, out.size)
        assertTrue(out.all { it.x == 1.0 && it.y == 2.0 })
    }

    @Test
    fun `lerpPolyline t=0 到 a、t=1 到 b、中间线性`() {
        val a = listOf(XY(0.0, 0.0), XY(10.0, 0.0))
        val b = listOf(XY(0.0, 10.0), XY(10.0, 10.0))
        assertEquals(a, Polyline.lerpPolyline(a, b, 0.0))
        assertEquals(b, Polyline.lerpPolyline(a, b, 1.0))
        assertEquals(XY(10.0, 5.0), Polyline.lerpPolyline(a, b, 0.5)[1])
    }

    @Test
    fun `scorecard demo 口径正确、节省率为正`() {
        val s = Demo.generateDemoSession()
        val routes = Optimize.optimizeSession(s)
        val c = Optimize.scorecard(s, routes)
        assertTrue(c.actualDistM > 0)
        assertTrue(c.actualMoveSec > 0)
        assertTrue(c.actualTotalSec >= c.actualMoveSec) // 含停留
        assertTrue(c.bikeDistM > 0) // demo 含骑行段
        assertTrue(c.timeOptSec > 0)
        assertTrue(c.distOptM > 0)
        assertTrue(c.flyOptM > 0)
        assertTrue(c.timeOptSec < c.actualMoveSec)
        assertTrue(c.distOptM < c.actualDistM)
        assertTrue(c.flyOptM < c.actualDistM)
        assertTrue(c.savingsTimePct in 0.0..100.0)
        assertTrue(c.savingsDistPct > 0)
        assertTrue(c.savingsFlyPct > 0)
    }

    @Test
    fun `scorecard 空会话全零`() {
        val s = io.github.chenchen913.baibai.core.model.SessionData(
            id = "x", year = 2026, date = "2026-02-17", home = LatLng(31.0, 121.0),
            nodes = emptyList(), visits = emptyList(), points = emptyList(),
            state = SessionState.FINISHED, currentMode = Mode.WALK, finished = true,
            createdAt = 0, updatedAt = 0,
        )
        val c = Optimize.scorecard(s, Optimize.optimizeSession(s))
        assertEquals(0.0, c.actualDistM)
        assertEquals(0.0, c.actualMoveSec)
        assertEquals(0.0, c.savingsTimePct)
    }
}
