package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.model.Fix
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.state.RecorderState
import io.github.chenchen913.baibai.core.track.Track
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

/** 对应网页版 tests/track.test.ts（8 项，逐位对齐） */
class TrackTest {

    companion object {
        val HOME = LatLng(31.0, 121.0)
        const val R = 6371000.0
        const val T0 = 1_700_000_000_000L

        fun far(m: Double, dir: Char = 'n'): LatLng = if (dir == 'n') {
            LatLng(HOME.lat + m / R * 180.0 / PI, HOME.lng)
        } else {
            LatLng(HOME.lat, HOME.lng + m / R * 180.0 / PI / kotlin.math.cos(HOME.lat * PI / 180.0))
        }

        fun fix(pos: LatLng, acc: Double = 5.0) = Fix(pos, acc)

        /** 三段场景：home→A→B→home */
        fun threeStopSession(): SessionData {
            val r = RecorderState.fresh()
            r.start(listOf(fix(HOME)), T0)
            r.addPoint(HOME, 5.0, T0 + 500)
            r.addPoint(far(30.0), 5.0, T0 + 700)
            r.addPoint(far(60.0), 5.0, T0 + 900)
            r.addPoint(far(90.0), 5.0, T0 + 1100)
            r.pause(listOf(fix(far(100.0))), T0 + 2000) // A
            r.resume(T0 + 3000)
            r.addPoint(far(50.0), 5.0, T0 + 3500) // A→B 途中
            r.pause(listOf(fix(far(300.0))), T0 + 5000) // B
            r.resume(T0 + 6000)
            r.addPoint(far(150.0), 5.0, T0 + 6500) // B→home 途中
            r.finish(listOf(fix(HOME)), T0 + 8000)
            return r.snapshot()
        }
    }

    // ---------- buildEdges ----------

    @Test
    fun `buildEdges 三段场景：3 条边、时间窗过滤、距离为正`() {
        val s = threeStopSession()
        val edges = Track.buildEdges(s)
        assertEquals(3, edges.size)
        val (nA, nB) = s.nodes.map { it.id }
        assertEquals(listOf("home→$nA", "$nA→$nB", "$nB→home"), edges.map { "${it.fromId}→${it.toId}" })
        assertEquals(listOf(T0 + 500, T0 + 700, T0 + 900, T0 + 1100), edges[0].raw.map { it.t })
        assertTrue(edges[0].distM > 0)
        assertEquals(listOf(T0 + 6500), edges[2].raw.map { it.t })
    }

    @Test
    fun `buildEdges 出行方式归属`() {
        val r = RecorderState.fresh()
        r.start(listOf(fix(HOME)), T0)
        r.addPoint(HOME, 5.0, T0 + 100)
        r.pause(listOf(fix(far(100.0))), T0 + 1000)
        r.resume(T0 + 2000)
        r.setMode(Mode.BIKE, T0 + 2500)
        r.addPoint(far(50.0), 5.0, T0 + 2600)
        r.pause(listOf(fix(far(300.0))), T0 + 3000)
        r.resume(T0 + 4000)
        r.addPoint(far(150.0), 5.0, T0 + 4100)
        r.finish(listOf(fix(HOME)), T0 + 5000)
        val edges = Track.buildEdges(r.snapshot())
        assertEquals(3, edges.size)
        assertEquals(Mode.WALK, edges[0].mode) // home→A
        assertEquals(Mode.BIKE, edges[1].mode) // A→B
        assertEquals(Mode.WALK, edges[2].mode) // B→home（D19：到达 B 后自动回走路）
    }

    @Test
    fun `buildEdges 中途回 Home：多段循环`() {
        val r = RecorderState.fresh()
        r.start(listOf(fix(HOME)), T0)
        r.addPoint(HOME, 5.0, T0 + 100)
        r.pause(listOf(fix(far(100.0))), T0 + 1000) // A
        r.resume(T0 + 2000)
        r.addPoint(far(50.0), 5.0, T0 + 2100)
        r.pause(listOf(fix(far(5.0))), T0 + 3000) // 合并回 Home
        r.resume(T0 + 4000)
        r.addPoint(HOME, 5.0, T0 + 4100)
        r.pause(listOf(fix(far(200.0))), T0 + 5000) // C
        r.resume(T0 + 6000)
        r.addPoint(HOME, 5.0, T0 + 6100)
        r.finish(listOf(fix(HOME)), T0 + 7000)
        val s = r.snapshot()
        val (nA, nC) = s.nodes.map { it.id }
        assertEquals(
            listOf("home→$nA", "$nA→home", "home→$nC", "$nC→home"),
            Track.buildEdges(s).map { "${it.fromId}→${it.toId}" },
        )
    }

    @Test
    fun `buildEdges 未结束的会话不生成尾边`() {
        val r = RecorderState.fresh()
        r.start(listOf(fix(HOME)), T0)
        r.addPoint(HOME, 5.0, T0 + 100)
        r.pause(listOf(fix(far(100.0))), T0 + 1000)
        r.resume(T0 + 2000)
        r.addPoint(far(50.0), 5.0, T0 + 2100)
        val edges = Track.buildEdges(r.snapshot())
        assertEquals(1, edges.size)
        assertTrue(edges[0].toId != "home")
    }

    // ---------- SVG 投影 ----------

    private val pts = listOf(LatLng(31.0, 121.0), LatLng(31.1, 121.0), LatLng(31.0, 121.1))

    @Test
    fun `projectToView 所有点落在视口内`() {
        val proj = Track.projectToView(pts, 400.0, 300.0)
        for (p in proj) {
            assertTrue(p.x >= 0 && p.x <= 400.0)
            assertTrue(p.y >= 0 && p.y <= 300.0)
        }
    }

    @Test
    fun `projectToView 等比不变形、北在上`() {
        val b = Track.projectToView(
            listOf(LatLng(30.0, 120.0), LatLng(31.0, 120.0), LatLng(30.0, 121.0)),
            400.0,
            400.0,
        )
        assertTrue(b[1].y < b[0].y) // 北在上
        val dy = kotlin.math.abs(b[1].y - b[0].y)
        val dx = kotlin.math.abs(b[2].x - b[0].x)
        assertEquals(dy, dx, 1e-9)
    }

    @Test
    fun `toSvgPath 起笔 M 且点数一致`() {
        val d = Track.toSvgPath(pts, 400.0, 300.0)
        assertTrue(d.startsWith("M"))
        assertEquals(3, d.split("L").size)
    }

    @Test
    fun `toSvgPath 空点集返回空串`() {
        assertEquals("", Track.toSvgPath(emptyList(), 400.0, 300.0))
    }
}
