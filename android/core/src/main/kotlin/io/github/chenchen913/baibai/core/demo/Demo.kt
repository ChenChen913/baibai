package io.github.chenchen913.baibai.core.demo

import io.github.chenchen913.baibai.core.geo.Geo
import io.github.chenchen913.baibai.core.model.HouseNode
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.model.SessionState
import io.github.chenchen913.baibai.core.model.TrackPoint
import io.github.chenchen913.baibai.core.model.Visit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 演示数据生成器（对应网页版 demo.ts，确定性输出：8 户、绕路实走、骑行段、跳变点） */
object Demo {

    private const val R = 6371000.0
    private const val SPEED_WALK_MS = 1.35
    private const val SPEED_BIKE_MS = 4.0

    private fun posAt(lat0: Double, lng0: Double, dN: Double, dE: Double): LatLng = LatLng(
        lat0 + dN / R * 180.0 / PI,
        lng0 + dE / R * 180.0 / PI / cos(lat0 * PI / 180.0),
    )

    private data class HouseSpec(val id: String, val name: String, val n: Double, val e: Double)

    private val HOUSES = listOf(
        HouseSpec("d1", "大伯家", 120.0, 60.0),
        HouseSpec("d2", "二叔家", 260.0, 40.0),
        HouseSpec("d3", "三舅家", 140.0, 300.0),
        HouseSpec("d4", "四姨家", 320.0, 260.0),
        HouseSpec("d5", "五伯家", 420.0, 140.0),
        HouseSpec("d6", "六婶家", 60.0, 380.0),
        HouseSpec("d7", "七哥家", 380.0, 400.0),
        HouseSpec("d8", "小卖部", 240.0, 180.0),
    )

    /** 实走顺序故意绕路：1→3→6→7→2→8→4→5 */
    private val VISIT_ORDER = listOf("d1", "d3", "d6", "d7", "d2", "d8", "d4", "d5")
    private val SEG_MODES = listOf(Mode.WALK, Mode.WALK, Mode.WALK, Mode.BIKE, Mode.BIKE, Mode.WALK, Mode.WALK, Mode.WALK)

    fun generateDemoSession(): SessionData {
        val home = LatLng(31.0, 121.0)
        val nodes = HOUSES.mapIndexed { i, h ->
            HouseNode(id = h.id, name = h.name, autoNo = i + 1, pos = posAt(home.lat, home.lng, h.n, h.e))
        }
        val byId = nodes.associateBy { it.id }

        // 2026-02-17（丙午年春节）08:00 出发
        val t0 = java.time.Instant.parse("2026-02-17T00:00:00Z").toEpochMilli()
        var t = t0
        val points = mutableListOf<TrackPoint>()
        val visits = mutableListOf<Visit>()
        var seg = 0

        fun walkSegment(a: LatLng, b: LatLng, speed: Double, detour: Double = 1.35) {
            val dist = Geo.haversineM(a, b) * detour
            val durMs = dist / speed * 1000.0
            val steps = maxOf(10, Math.ceil(dist / 20.0).toInt())
            for (k in 1..steps) {
                val f = k.toDouble() / steps
                val wig = sin(f * PI * 3) * 6.0
                t += (durMs / steps).toLong()
                points.add(
                    TrackPoint(
                        t = t,
                        pos = LatLng(
                            a.lat + (b.lat - a.lat) * f + wig / R * 180.0 / PI,
                            a.lng + (b.lng - a.lng) * f + wig / R * 180.0 / PI / cos(home.lat * PI / 180.0),
                        ),
                        acc = 5.0,
                        seg = "seg$seg",
                    ),
                )
            }
        }

        var prev = home
        for (i in VISIT_ORDER.indices) {
            val pos = byId.getValue(VISIT_ORDER[i]).pos
            val mode = SEG_MODES[i]
            walkSegment(prev, pos, if (mode == Mode.BIKE) SPEED_BIKE_MS else SPEED_WALK_MS)
            val arriveT = t
            t += (5 + (i % 3) * 5) * 60_000L
            visits.add(Visit(nodeId = VISIT_ORDER[i], arriveT = arriveT, leaveT = t, mode = mode))
            prev = pos
            seg += 1
        }
        walkSegment(prev, home, SPEED_WALK_MS)

        // 一个跳变点（演示"剔除异常"）
        val mid = points.size / 2
        points.add(
            mid,
            TrackPoint(
                t = points[mid].t + 1,
                pos = posAt(home.lat, home.lng, 800.0, -500.0),
                acc = 5.0,
                seg = "segX",
                jump = true,
            ),
        )
        points.sortBy { it.t }

        return SessionData(
            id = "demo-2026",
            year = 2026,
            date = "2026-02-17",
            home = home,
            nodes = nodes,
            visits = visits,
            points = points,
            state = SessionState.FINISHED,
            currentMode = Mode.WALK,
            finished = true,
            createdAt = t0,
            updatedAt = t,
        )
    }
}
