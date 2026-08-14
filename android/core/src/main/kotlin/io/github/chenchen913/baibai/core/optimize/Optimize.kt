package io.github.chenchen913.baibai.core.optimize

import io.github.chenchen913.baibai.core.geo.Geo
import io.github.chenchen913.baibai.core.model.Constants
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.track.Track
import io.github.chenchen913.baibai.core.tsp.Tsp

/** 三线优化（对应网页版 optimize.ts：D8/D14/D15） */

const val DETOUR_FACTOR = 1.3 // 未知距离边 = 直线 × 1.3
const val SPEED_WALK_MS = 1.35 // 4.86 km/h
const val SPEED_BIKE_MS = 4.0 // 14.4 km/h

enum class RouteMode { FLY, WALK_DIST, WALK_TIME }

data class RouteEdge(val from: String, val to: String, val known: Boolean)

data class Route(
    val mode: RouteMode,
    val order: List<String>, // home 打头，不含闭合重复
    val cost: Double, // 米（FLY/WALK_DIST）或 秒（WALK_TIME）
    val exact: Boolean,
    val edges: List<RouteEdge>, // 含闭合边（最后一户→home）
)

private data class PairInfo(
    val minDist: Double? = null,
    val minTime: Long? = null,
    val mode: Mode? = null,
)

object Optimize {

    private fun pairKey(a: String, b: String): String = listOf(a, b).sorted().joinToString("|")

    private fun posOf(s: SessionData, id: String): LatLng =
        if (id == Constants.HOME_ID) s.home else s.nodes.first { it.id == id }.pos

    /** 会话 → 三条最优路线 */
    fun optimizeSession(s: SessionData): List<Route> {
        val ids = listOf(Constants.HOME_ID) + s.nodes.map { it.id }
        val n = ids.size
        if (n == 1) {
            fun empty(mode: RouteMode) = Route(mode, listOf(Constants.HOME_ID), 0.0, exact = true, edges = emptyList())
            return listOf(empty(RouteMode.FLY), empty(RouteMode.WALK_DIST), empty(RouteMode.WALK_TIME))
        }

        // 边聚合：同一对多次实走 → 距离/时间各自取 min（D15）
        val pairs = mutableMapOf<String, PairInfo>()
        for (e in Track.buildEdges(s)) {
            val k = pairKey(e.fromId, e.toId)
            val p = pairs[k] ?: PairInfo()
            val p2 = PairInfo(
                minDist = if (p.minDist == null || e.distM < p.minDist!!) e.distM else p.minDist,
                minTime = if (p.minTime == null || (e.arriveT - e.departT) < p.minTime!!) e.arriveT - e.departT else p.minTime,
                mode = if (p.minDist == null || e.distM < p.minDist!!) e.mode else p.mode,
            )
            pairs[k] = p2
        }

        val fly = Array(n) { DoubleArray(n) }
        val dist = Array(n) { DoubleArray(n) }
        val time = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val geo = Geo.haversineM(posOf(s, ids[i]), posOf(s, ids[j]))
                val p = pairs[pairKey(ids[i], ids[j])]
                fly[i][j] = geo
                fly[j][i] = geo
                dist[i][j] = p?.minDist ?: geo * DETOUR_FACTOR
                dist[j][i] = dist[i][j]
                time[i][j] = if (p != null && p.minTime != null) p.minTime / 1000.0 else dist[i][j] / SPEED_WALK_MS
                time[j][i] = time[i][j]
            }
        }

        fun mk(mode: RouteMode, m: Array<DoubleArray>): Route {
            val res = Tsp.solveTsp(m, 0)
            return Route(
                mode = mode,
                order = res.order.map { ids[it] },
                cost = res.cost,
                exact = res.exact,
                edges = res.order.mapIndexed { k, v ->
                    val u = res.order[(k + res.order.size - 1) % res.order.size]
                    RouteEdge(from = ids[u], to = ids[v], known = pairs.containsKey(pairKey(ids[u], ids[v])))
                },
            )
        }

        return listOf(mk(RouteMode.FLY, fly), mk(RouteMode.WALK_DIST, dist), mk(RouteMode.WALK_TIME, time))
    }

    /** 成绩单（F-14）：对比口径均为"路上时间/距离"，不含户内停留 */
    data class Scorecard(
        val actualDistM: Double,
        val actualMoveSec: Double,
        val actualTotalSec: Double,
        val bikeDistM: Double,
        val timeOptSec: Double,
        val distOptM: Double,
        val flyOptM: Double,
        val savingsTimePct: Double,
        val savingsDistPct: Double,
        val savingsFlyPct: Double,
    )

    fun scorecard(s: SessionData, routes: List<Route>): Scorecard {
        val edges = Track.buildEdges(s)
        val actualDistM = edges.sumOf { it.distM }
        val actualMoveSec = edges.sumOf { (it.arriveT - it.departT) / 1000.0 }
        val first = s.points.firstOrNull()?.t ?: s.createdAt
        val last = s.points.lastOrNull()?.t ?: s.updatedAt
        val actualTotalSec = maxOf(0.0, (last - first) / 1000.0)
        val bikeDistM = edges.filter { it.mode == Mode.BIKE }.sumOf { it.distM }
        fun cost(mode: RouteMode) = routes.first { it.mode == mode }.cost
        fun pct(opt: Double, actual: Double) = if (actual > 0) maxOf(0.0, (1 - opt / actual) * 100) else 0.0
        val timeOptSec = cost(RouteMode.WALK_TIME)
        val distOptM = cost(RouteMode.WALK_DIST)
        val flyOptM = cost(RouteMode.FLY)
        return Scorecard(
            actualDistM = actualDistM,
            actualMoveSec = actualMoveSec,
            actualTotalSec = actualTotalSec,
            bikeDistM = bikeDistM,
            timeOptSec = timeOptSec,
            distOptM = distOptM,
            flyOptM = flyOptM,
            savingsTimePct = pct(timeOptSec, actualMoveSec),
            savingsDistPct = pct(distOptM, actualDistM),
            savingsFlyPct = pct(flyOptM, actualDistM),
        )
    }
}
