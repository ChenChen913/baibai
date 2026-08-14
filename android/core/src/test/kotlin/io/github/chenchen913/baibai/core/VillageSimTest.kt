package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.model.Fix
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.optimize.Optimize
import io.github.chenchen913.baibai.core.playback.Playback
import io.github.chenchen913.baibai.core.state.RecorderState
import io.github.chenchen913.baibai.core.track.Track
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** 对应网页版 tests/village-sim.test.ts（2 项）：潍坊市昌乐县模拟村 15/20 户实地仿真 */
class VillageSimTest {

    companion object {
        /** 潍坊市昌乐县某村（模拟坐标，村庄中心） */
        val VILLAGE = LatLng(36.7532, 118.961)
        const val R = 6371000.0
        const val JIT_M = 4.0 // GPS 抖动 σ（米）
        const val FIX_JIT_M = 3.0 // 暂停点定位抖动 σ（米）
        const val T0 = 1_767_830_400_000L // 2027-02-06 08:00（2027 大年初一）
        const val SPEED_WALK = 1.35
        const val SPEED_BIKE = 4.0

        fun toPos(lat0: Double, lng0: Double, dN: Double, dE: Double): LatLng = LatLng(
            lat0 + dN / R * 180.0 / PI,
            lng0 + dE / R * 180.0 / PI / cos(lat0 * PI / 180.0),
        )

        fun lcg(seed: Int): () -> Double {
            var s = seed.toLong() and 0xFFFFFFFFL
            return {
                s = (s * 1664525L + 1013904223L) and 0xFFFFFFFFL
                s.toDouble() / 4294967296.0
            }
        }

        fun gauss(rnd: () -> Double, sigma: Double): Double {
            val u = max(1e-9, 1 - rnd())
            val v = rnd()
            return sqrt(-2 * ln(u)) * cos(2 * PI * v) * sigma
        }

        data class House(val pos: LatLng, val dN: Double, val dE: Double)

        fun makeHouses(rnd: () -> Double, n: Int): List<House> {
            val houses = mutableListOf<House>()
            var tries = 0
            while (houses.size < n && tries < n * 500) {
                tries += 1
                val dN = 60.0 + rnd() * 880.0
                val dE = 60.0 + rnd() * 680.0
                val pos = toPos(VILLAGE.lat, VILLAGE.lng, dN, dE)
                if (houses.all { io.github.chenchen913.baibai.core.geo.Geo.haversineM(it.pos, pos) >= 18.0 }) {
                    houses.add(House(pos, dN, dE))
                }
            }
            assertEquals(n, houses.size)
            return houses
        }

        /** 蛇形扫描顺序：刻意绕路的拜访路线 */
        fun serpentine(houses: List<House>): List<House> {
            fun band(h: House) = Math.round(h.dE / 100.0).toInt()
            return houses.sortedWith(
                compareBy({ band(it) }, { if (band(it) % 2 == 0) it.dN else -it.dN }),
            )
        }

        /** 全链路仿真（与网页版逻辑一致） */
        fun simulate(n: Int): SessionData {
            val rnd = lcg(20260217 + n)
            val houses = makeHouses(rnd, n)
            val order = serpentine(houses)
            val r = RecorderState.fresh()
            var t = T0
            r.start(listOf(Fix(VILLAGE, 5.0)), t)

            fun jit(): Double = gauss(rnd, JIT_M / R * 180.0 / PI)
            fun fixJit(): Double = gauss(rnd, FIX_JIT_M / R * 180.0 / PI)
            var prev = VILLAGE
            val bikeSeg = n / 2

            fun walkSegment(a: LatLng, b: LatLng, speed: Double, detour: Double = 1.2) {
                val dist = io.github.chenchen913.baibai.core.geo.Geo.haversineM(a, b) * detour
                val steps = max(14, Math.ceil(dist / 3.0).toInt())
                for (k in 1..steps) {
                    val f = k.toDouble() / steps
                    t += (dist / speed * 1000.0 / steps).toLong()
                    r.addPoint(
                        LatLng(
                            a.lat + (b.lat - a.lat) * f + jit(),
                            a.lng + (b.lng - a.lng) * f + jit() / cos(VILLAGE.lat * PI / 180.0),
                        ),
                        5.0,
                        t,
                    )
                }
            }

            for (i in order.indices) {
                val h = order[i]
                if (i == bikeSeg) r.setMode(io.github.chenchen913.baibai.core.model.Mode.BIKE, t)
                val speed = if (i == bikeSeg) SPEED_BIKE else SPEED_WALK
                walkSegment(prev, h.pos, speed)
                r.pause(
                    List(3) {
                        Fix(
                            LatLng(
                                h.pos.lat + fixJit(),
                                h.pos.lng + fixJit() / cos(VILLAGE.lat * PI / 180.0),
                            ),
                            5.0,
                        )
                    },
                    t,
                )
                t += (5 + (i % 3) * 5) * 60_000L
                r.resume(t)
                prev = h.pos
            }

            // 一次回访（第 2 户）
            val back = order[1]
            walkSegment(prev, back.pos, SPEED_WALK)
            r.pause(
                List(3) {
                    Fix(
                        LatLng(
                            back.pos.lat + fixJit(),
                            back.pos.lng + fixJit() / cos(VILLAGE.lat * PI / 180.0),
                        ),
                        5.0,
                    )
                },
                t,
            )
            t += 8 * 60_000L
            r.resume(t)

            walkSegment(back.pos, VILLAGE, SPEED_WALK)
            assertEquals(io.github.chenchen913.baibai.core.state.FinishResult.Ok, r.finish(listOf(Fix(VILLAGE, 5.0)), t))
            return r.snapshot()
        }

        fun report(label: String, s: SessionData) {
            val edges = Track.buildEdges(s)
            val actualDist = edges.sumOf { it.distM }
            val actualMove = edges.sumOf { (it.arriveT - it.departT) / 1000.0 }
            val t0 = System.nanoTime()
            val routes = Optimize.optimizeSession(s)
            val ms = (System.nanoTime() - t0) / 1e6
            val c = Optimize.scorecard(s, routes)
            val timeRoute = routes.first { it.mode == io.github.chenchen913.baibai.core.optimize.RouteMode.WALK_TIME }
            val plan = Playback.buildPlan(s, 480.0, 560.0)
            println("=== $label ===")
            println("户数 ${s.nodes.size} · 到访 ${s.visits.size} 次（含 1 次回访合并）· 轨迹点 ${s.points.size}（回放抽稀后 ${plan.pts.size}）")
            println("实走距离 ${"%.2f".format(actualDist / 1000)} km · 路上时间 ${(actualMove / 60).toInt()} 分钟 · 全天 ${(c.actualTotalSec / 60).toInt()} 分钟（含拜年停留）")
            println("时间最优 ${(c.timeOptSec / 60).toInt()} 分钟（省 ${"%.1f".format(c.savingsTimePct)}%）")
            println("距离最优 ${"%.2f".format(c.distOptM / 1000)} km（省 ${"%.1f".format(c.savingsDistPct)}%）")
            println("飞行最优 ${"%.2f".format(c.flyOptM / 1000)} km（少走 ${"%.1f".format(c.savingsFlyPct)}%）")
            println("求解 ${if (timeRoute.exact) "Held-Karp 精确解" else "贪心+2-opt 启发式"} · 三线总耗时 ${"%.1f".format(ms)} ms")
        }
    }

    @Test
    fun `昌乐县 15 户：无误合并、回访合并、三线节省为正、精确解、性能达标`() {
        val s = simulate(15)
        assertEquals(15, s.nodes.size) // 无 10m 误合并
        assertEquals(16, s.visits.size) // 15 户 + 1 回访（合并）
        assertTrue(Track.buildEdges(s).size >= 16)
        val t0 = System.nanoTime()
        val routes = Optimize.optimizeSession(s)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(routes.all { it.exact }) // 16 节点 ≤ 精确解上限
        assertTrue(ms < 2000)
        val c = Optimize.scorecard(s, routes)
        assertTrue(c.savingsTimePct > 0)
        assertTrue(c.savingsDistPct > 0)
        assertTrue(c.savingsFlyPct > 0)
        report("昌乐县模拟村 · 15 户", s)
    }

    @Test
    fun `昌乐县 20 户：无误合并、启发式求解、性能达标、跳变点鲁棒`() {
        val s = simulate(20)
        assertEquals(20, s.nodes.size)
        assertEquals(21, s.visits.size)
        val t0 = System.nanoTime()
        val routes = Optimize.optimizeSession(s)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(routes.all { !it.exact }) // 21 节点 > 16 → 启发式
        assertTrue(ms < 2000)
        val c = Optimize.scorecard(s, routes)
        assertTrue(c.savingsTimePct > 0)
        report("昌乐县模拟村 · 20 户", s)

        // 跳变点鲁棒：插入一个跳变点后全管线仍正常
        val withJump = s.copy(points = s.points.toMutableList().also { list ->
            val mid = list.size / 2
            list.add(
                mid,
                io.github.chenchen913.baibai.core.model.TrackPoint(
                    t = list[mid].t + 1,
                    pos = toPos(VILLAGE.lat, VILLAGE.lng, 700.0, -500.0),
                    acc = 5.0,
                    seg = "segX",
                    jump = true,
                ),
            )
            list.sortBy { it.t }
        })
        Track.buildEdges(withJump)
        Playback.buildPlan(withJump, 480.0, 560.0)
        Optimize.optimizeSession(withJump)
    }
}
