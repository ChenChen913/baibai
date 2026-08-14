package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.model.Fix
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.playback.Playback
import io.github.chenchen913.baibai.core.state.RecorderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos

/** 对应网页版 tests/playback.test.ts（7 项，逐位对齐） */
class PlaybackTest {

    companion object {
        val HOME = LatLng(31.0, 121.0)
        const val R = 6371000.0
        const val T0 = 1_700_000_000_000L

        fun at(mN: Double, mE: Double): LatLng = LatLng(
            HOME.lat + mN / R * 180.0 / PI,
            HOME.lng + mE / R * 180.0 / PI / cos(HOME.lat * PI / 180.0),
        )

        fun fix(pos: LatLng, acc: Double = 5.0) = Fix(pos, acc)

        /** 带拐角的轨迹：home → A → B → home */
        fun cornerSession(): SessionData {
            val r = RecorderState.fresh()
            r.start(listOf(fix(HOME)), T0)
            r.addPoint(at(0.0, 0.0), 5.0, T0 + 500)
            r.addPoint(at(30.0, 0.0), 5.0, T0 + 700)
            r.addPoint(at(60.0, 40.0), 5.0, T0 + 900) // 拐点
            r.addPoint(at(90.0, 40.0), 5.0, T0 + 1100)
            r.pause(listOf(fix(at(100.0, 40.0))), T0 + 2000) // A
            r.resume(T0 + 3000)
            r.addPoint(at(200.0, 40.0), 5.0, T0 + 3500)
            r.pause(listOf(fix(at(300.0, 40.0))), T0 + 5000) // B
            r.resume(T0 + 6000)
            r.addPoint(at(150.0, 20.0), 5.0, T0 + 6500)
            r.finish(listOf(fix(HOME)), T0 + 8000)
            return r.snapshot()
        }
    }

    // ---------- buildPlan ----------

    @Test
    fun `buildPlan 抽稀后首尾保留、时间轴连续`() {
        val plan = Playback.buildPlan(cornerSession(), 480.0, 560.0)
        assertTrue(plan.pts.size >= 2)
        assertEquals(T0 + 500, plan.pts.first().t)
        assertEquals(T0 + 6500, plan.pts.last().t)
        assertEquals(6000L, plan.totalMs)
        for (i in 1 until plan.pts.size) {
            assertTrue(plan.pts[i].t > plan.pts[i - 1].t)
        }
    }

    @Test
    fun `buildPlan 拐点被保留`() {
        val plan = Playback.buildPlan(cornerSession(), 480.0, 560.0)
        assertTrue(plan.pts.size > 2)
    }

    @Test
    fun `buildPlan 空会话返回空计划`() {
        val plan = Playback.buildPlan(RecorderState.fresh().snapshot(), 480.0, 560.0)
        assertEquals(emptyList<io.github.chenchen913.baibai.core.playback.PlaybackPoint>(), plan.pts)
        assertEquals(0L, plan.totalMs)
    }

    // ---------- positionAt ----------

    @Test
    fun `positionAt 起点终点越界夹取`() {
        val plan = Playback.buildPlan(cornerSession(), 480.0, 560.0)
        val first = plan.pts.first()
        val last = plan.pts.last()
        assertEquals(io.github.chenchen913.baibai.core.track.XY(first.x, first.y), Playback.positionAt(plan, 0))
        assertEquals(io.github.chenchen913.baibai.core.track.XY(last.x, last.y), Playback.positionAt(plan, plan.totalMs))
        assertEquals(
            io.github.chenchen913.baibai.core.track.XY(last.x, last.y),
            Playback.positionAt(plan, plan.totalMs + 9999),
        )
        assertEquals(io.github.chenchen913.baibai.core.track.XY(first.x, first.y), Playback.positionAt(plan, -5))
    }

    @Test
    fun `positionAt 段间线性插值`() {
        val plan = Playback.buildPlan(cornerSession(), 480.0, 560.0)
        val a = plan.pts[0]
        val b = plan.pts[1]
        val mid = Playback.positionAt(plan, (b.t - a.t) / 2)!!
        assertEquals((a.x + b.x) / 2, mid.x, 1e-6)
        assertEquals((a.y + b.y) / 2, mid.y, 1e-6)
    }

    @Test
    fun `positionAt 空计划返回 null`() {
        assertNull(Playback.positionAt(io.github.chenchen913.baibai.core.playback.PlaybackPlan(emptyList(), 0), 0))
    }

    // ---------- fractionAt ----------

    @Test
    fun `fractionAt 0 到 1 夹取`() {
        val plan = Playback.buildPlan(cornerSession(), 480.0, 560.0)
        assertEquals(0.0, Playback.fractionAt(plan, 0))
        assertEquals(1.0, Playback.fractionAt(plan, plan.totalMs))
        assertEquals(1.0, Playback.fractionAt(plan, plan.totalMs * 2))
        assertEquals(0.0, Playback.fractionAt(io.github.chenchen913.baibai.core.playback.PlaybackPlan(emptyList(), 0), 10))
    }
}
