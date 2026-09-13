package io.github.chenchen913.baibai.core.track

import io.github.chenchen913.baibai.core.geo.Geo
import io.github.chenchen913.baibai.core.model.Constants
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.model.TrackPoint
import io.github.chenchen913.baibai.core.smooth.Smooth
import kotlin.math.max
import kotlin.math.min

data class Edge(
    val fromId: String,
    val toId: String,
    val mode: Mode,
    val departT: Long,
    val arriveT: Long,
    val raw: List<TrackPoint>,
    val smoothed: List<TrackPoint>,
    val distM: Double,
)

data class Bounds(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
)

data class XY(val x: Double, val y: Double)

/** 轨迹分段 + SVG 视口投影（对应网页版 track.ts；toSvgPath 保留作对照，Compose Canvas 用 projectToView 坐标） */
object Track {

    private data class Stop(val nodeId: String, val t: Long, val departT: Long, val mode: Mode)

    /** 会话 → 边序列（home→…→home；中途回 Home 多段循环天然成立） */
    fun buildEdges(s: SessionData): List<Edge> {
        val t0 = (s.points.firstOrNull()?.t ?: s.createdAt) - 1
        val tEnd = s.points.lastOrNull()?.t ?: s.updatedAt
        val ordered = s.visits.sortedBy { it.arriveT }

        val stops = mutableListOf(
            Stop(Constants.HOME_ID, t0, t0, ordered.firstOrNull()?.mode ?: s.currentMode),
        )
        for (v in ordered) {
            stops.add(Stop(v.nodeId, v.arriveT, v.leaveT ?: v.arriveT, v.mode))
        }
        if (s.finished) {
            stops.add(Stop(Constants.HOME_ID, tEnd, tEnd, s.currentMode))
        }

        val edges = mutableListOf<Edge>()
        for (i in 0 until stops.size - 1) {
            val a = stops[i]
            val b = stops[i + 1]
            if (b.t <= a.t) continue
            // E-15：排除跳变点（它们会入库供回顾页展示/剔除），否则一个 900m 的坏点
            // 会让实走距离虚增（demo 场景实测虚增 1652m / +86%；与网页版 track.ts 一致）
            val raw = s.points.filter { it.jump != true && it.t > a.t && it.t <= b.t }
            if (raw.isEmpty()) continue
            val smoothed = Smooth.smoothTrack(raw)
            var distM = 0.0
            for (k in 1 until smoothed.size) {
                distM += Geo.haversineM(smoothed[k - 1].pos, smoothed[k].pos)
            }
            edges.add(
                Edge(
                    fromId = a.nodeId,
                    toId = b.nodeId,
                    mode = b.mode, // 进入该停靠点的出行方式（D19）
                    departT = a.departT, // 行程时间基准：上一户的离开时刻
                    arriveT = b.t,
                    raw = raw,
                    smoothed = smoothed,
                    distM = distM,
                ),
            )
        }
        return edges
    }

    fun boundsOf(pts: List<LatLng>): Bounds? {
        if (pts.isEmpty()) return null
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        for (p in pts) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lng < minLng) minLng = p.lng
            if (p.lng > maxLng) maxLng = p.lng
        }
        return Bounds(minLat, maxLat, minLng, maxLng)
    }

    /**
     * 统一投影参考点集（E-1 修复，与网页版 track.ts `viewRefPoints` 对应）：家 + 全部户 + 全部轨迹点。
     *
     * 为什么需要：回放轨迹来自 Playback.buildPlan（内部按"轨迹点"算 bounds），而节点标记/最优路线
     * 来自 projectToView([home, ...nodes])（按"节点"算 bounds）。两组点包围盒不同时，同一物理点
     * 在两套投影下坐标不同 → 叠加显示错位（实测最大 220px / 480px 画布，morph 首末点错位）。
     *
     * 约定：同一会话的所有视图（回放轨迹 / 节点标记 / 最优三线 / morph 重采样）必须传同一个 ref。
     */
    fun viewRefPoints(s: SessionData): List<LatLng> {
        val out = ArrayList<LatLng>(s.nodes.size + s.points.size + 1)
        out.add(s.home)
        for (n in s.nodes) out.add(n.pos)
        // E-15：跳变点不参与投影参考——一个 900m 外的坏点会把包围盒撑大，
        // 整幅图被缩小、看起来"什么都没画"（与网页版 track.ts 一致）
        for (p in s.points) if (p.jump != true) out.add(p.pos)
        return out
    }

    /**
     * 等比投影到视口（北在上、留白 10%），全部点落在 [0,w]×[0,h] 内。
     * R7：跨度下限 MIN_VIEW_SPAN_M（60m）——静止小点团（几米抖动范围）不得被放大充满视口；
     * 仅在任一方向跨度低于下限时以实际中心扩展，大跨度场景投影结果与旧版逐位一致（与网页版 track.ts 同步）。
     * E-1：ref 传"统一参考点集"（viewRefPoints）时只投影 pts，bounds 一律按 ref 计算。
     */
    fun projectToView(
        pts: List<LatLng>,
        w: Double,
        h: Double,
        pad: Double = 0.1,
        ref: List<LatLng>? = null,
    ): List<XY> {
        val b = boundsOf(if (ref != null && ref.isNotEmpty()) ref else pts) ?: return emptyList()
        // 米→度换算（等距圆柱近似）：纬度 1° ≈ 111320m；经度 1° ≈ 111320·cos(纬度)
        val cLat = (b.maxLat + b.minLat) / 2
        val cLng = (b.maxLng + b.minLng) / 2
        val rad = Math.PI / 180
        val minSpanLat = Constants.MIN_VIEW_SPAN_M / 111320.0
        val minSpanLng = Constants.MIN_VIEW_SPAN_M / (111320.0 * kotlin.math.cos(cLat * rad))
        var spanLat = max(b.maxLat - b.minLat, 1e-9)
        var spanLng = max(b.maxLng - b.minLng, 1e-9)
        var minLat = b.minLat
        var maxLat = b.maxLat
        var minLng = b.minLng
        if (spanLat < minSpanLat || spanLng < minSpanLng) {
            // 小点团：以实际 bounds 中心扩展跨度（点团居中），不再放大到充满视口
            spanLat = max(spanLat, minSpanLat)
            spanLng = max(spanLng, minSpanLng)
            minLat = cLat - spanLat / 2
            maxLat = cLat + spanLat / 2
            minLng = cLng - spanLng / 2
        }
        val usableW = w * (1 - 2 * pad)
        val usableH = h * (1 - 2 * pad)
        val s = min(usableW / spanLng, usableH / spanLat)
        val drawW = spanLng * s
        val drawH = spanLat * s
        val ox = (w - drawW) / 2
        val oy = (h - drawH) / 2
        return pts.map { p ->
            XY(
                x = ox + (p.lng - minLng) * s,
                y = oy + (maxLat - p.lat) * s,
            )
        }
    }

    fun toSvgPath(pts: List<LatLng>, w: Double, h: Double, pad: Double = 0.1): String {
        val proj = projectToView(pts, w, h, pad)
        return proj.mapIndexed { i, p ->
            val cmd = if (i == 0) "M" else "L"
            "$cmd${"%.1f".format(p.x)},${"%.1f".format(p.y)}"
        }.joinToString(" ")
    }
}
