package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.model.SessionState
import io.github.chenchen913.baibai.core.model.Visit
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

        /**
         * 字面量直造会话数据（R9 后 addPoint 有平滑窗口+连续确认过滤，合成稀疏点会被过滤，
         * 本文件测的是回放抽稀/插值，不测入库过滤——与 demo 生成器同款构造方式）
         */
        fun makeSession(
            nodes: List<Pair<String, LatLng>>,
            visits: List<Triple<String, Long, Long>>, // nodeId, arriveT, leaveT
            points: List<Pair<Long, LatLng>>, // t, pos
        ): SessionData {
            fun segOf(t: Long): String {
                var seg = 0
                for ((_, _, leaveT) in visits) if (t > leaveT) seg += 1
                return "seg$seg"
            }
            return SessionData(
                id = "test-session",
                year = 2026,
                date = "2026-02-17",
                home = HOME,
                nodes = nodes.mapIndexed { i, (id, pos) ->
                    io.github.chenchen913.baibai.core.model.HouseNode(id, "", i + 1, pos)
                },
                visits = visits.map { (nodeId, arriveT, leaveT) ->
                    Visit(nodeId, arriveT, leaveT, Mode.WALK)
                },
                points = points.map { (t, pos) ->
                    io.github.chenchen913.baibai.core.model.TrackPoint(t, pos, 5.0, segOf(t))
                },
                state = SessionState.FINISHED,
                currentMode = Mode.WALK,
                finished = true,
                createdAt = T0,
                updatedAt = T0 + 10_000,
            )
        }

        /** 带拐角的轨迹：home → A → B → home */
        fun cornerSession(): SessionData = makeSession(
            nodes = listOf("nA" to at(100.0, 40.0), "nB" to at(300.0, 40.0)),
            visits = listOf(
                Triple("nA", T0 + 2000, T0 + 3000), // A
                Triple("nB", T0 + 5000, T0 + 6000), // B
            ),
            points = listOf(
                T0 + 500 to at(0.0, 0.0),
                T0 + 700 to at(30.0, 0.0),
                T0 + 900 to at(60.0, 40.0), // 拐点
                T0 + 1100 to at(90.0, 40.0),
                T0 + 3500 to at(200.0, 40.0),
                T0 + 6500 to at(150.0, 20.0),
            ),
        )
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
