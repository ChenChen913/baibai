package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.model.Fix
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.model.SessionState
import io.github.chenchen913.baibai.core.model.Visit
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

        /**
         * 字面量直造会话数据（R9 后 addPoint 有平滑窗口+连续确认过滤，合成稀疏点会被过滤，
         * 本文件测的是分段/投影等下游逻辑，不测入库过滤——与 demo 生成器同款构造方式）
         */
        fun makeSession(
            nodes: List<Pair<String, LatLng>>, // id → pos（autoNo 按序 1..n）
            visits: List<Visit>,
            points: List<Pair<Long, LatLng>>, // t → pos（acc 固定 5，段 id 按离开时刻递增）
        ): SessionData {
            fun segOf(t: Long): String {
                var seg = 0
                for (v in visits) if (v.leaveT != null && t > v.leaveT!!) seg += 1
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
                visits = visits,
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

        /** 三段场景：home→A→B→home */
        fun threeStopSession(): SessionData = makeSession(
            nodes = listOf("nA" to far(100.0), "nB" to far(300.0)),
            visits = listOf(
                Visit("nA", T0 + 2000, T0 + 3000, Mode.WALK), // A
                Visit("nB", T0 + 5000, T0 + 6000, Mode.WALK), // B
            ),
            points = listOf(
                T0 + 500 to HOME, // home→A 途中
                T0 + 700 to far(30.0),
                T0 + 900 to far(60.0),
                T0 + 1100 to far(90.0),
                T0 + 3500 to far(50.0), // A→B 途中
                T0 + 6500 to far(150.0), // B→home 途中
            ),
        )
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
        val s = makeSession(
            nodes = listOf("nA" to far(100.0), "nB" to far(300.0)),
            visits = listOf(
                Visit("nA", T0 + 1000, T0 + 2000, Mode.WALK),
                Visit("nB", T0 + 3000, T0 + 4000, Mode.BIKE), // 骑车去 B
            ),
            points = listOf(
                T0 + 100 to HOME,
                T0 + 2600 to far(50.0),
                T0 + 4100 to far(150.0), // 走回家
            ),
        )
        val edges = Track.buildEdges(s)
        assertEquals(3, edges.size)
        assertEquals(Mode.WALK, edges[0].mode) // home→A
        assertEquals(Mode.BIKE, edges[1].mode) // A→B
        assertEquals(Mode.WALK, edges[2].mode) // B→home（D19：到达 B 后自动回走路）
    }

    @Test
    fun `buildEdges 中途回 Home：多段循环`() {
        val s = makeSession(
            nodes = listOf("nA" to far(100.0), "nC" to far(200.0)),
            visits = listOf(
                Visit("nA", T0 + 1000, T0 + 2000, Mode.WALK), // A
                Visit("home", T0 + 3000, T0 + 4000, Mode.WALK), // 合并回 Home
                Visit("nC", T0 + 5000, T0 + 6000, Mode.WALK), // C
            ),
            points = listOf(
                T0 + 100 to HOME,
                T0 + 2100 to far(50.0),
                T0 + 4100 to HOME,
                T0 + 6100 to far(6.0), // 走回家（门口 6m 处）
            ),
        )
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

    @Test
    fun `R7 静止小点团不放大充满视口（最小跨度 60m）`() {
        // 坐板凳 2 分钟的抖动团：纬度方向 0~3m。旧版投影会把 3m 跨度放大到全屏画成一团乱线
        val pts = listOf(far(0.0), far(2.0), far(1.0), far(3.0))
        val proj = Track.projectToView(pts, 400.0, 300.0)
        val xs = proj.map { it.x }
        val ys = proj.map { it.y }
        // 实际跨度 3m / 最小跨度 60m → 像素跨度 ≈ usableH 的 5%（12px），留足余量断言 ≤40px
        assertTrue((xs.max() - xs.min()) <= 40.0)
        assertTrue((ys.max() - ys.min()) <= 40.0)
        // 点团居中：质心在视口中心附近（不偏到角落）
        val cx = xs.average()
        val cy = ys.average()
        assertTrue(kotlin.math.abs(cx - 200.0) <= 20.0)
        assertTrue(kotlin.math.abs(cy - 150.0) <= 20.0)
    }
}
