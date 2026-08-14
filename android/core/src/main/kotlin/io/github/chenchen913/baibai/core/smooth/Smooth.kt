package io.github.chenchen913.baibai.core.smooth

import io.github.chenchen913.baibai.core.model.Constants
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.TrackPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 轨迹平滑与抽稀（对应网页版 smooth.ts，语义逐位一致） */
object Smooth {

    /** 按 jump 标记切段（jump 点归入后段开头） */
    fun jumpSplit(pts: List<TrackPoint>): List<List<TrackPoint>> {
        val segs = mutableListOf<List<TrackPoint>>()
        var cur = mutableListOf<TrackPoint>()
        for (p in pts) {
            if (p.jump == true && cur.isNotEmpty()) {
                segs.add(cur)
                cur = mutableListOf()
            }
            cur.add(p)
        }
        if (cur.isNotEmpty()) segs.add(cur)
        return segs
    }

    /** 段内滑动平均（端点/jump 点保持原样；窗口跳过 jump 点；长度不变） */
    fun movingAverage(pts: List<TrackPoint>, w: Int = 5): List<TrackPoint> {
        val half = w / 2
        val n = pts.size
        return pts.mapIndexed { i, p ->
            if (p.jump == true || i == 0 || i == n - 1) {
                p.copy()
            } else {
                val lo = max(0, i - half)
                val hi = min(n - 1, i + half)
                var lat = 0.0
                var lng = 0.0
                var cnt = 0
                for (j in lo..hi) {
                    if (pts[j].jump == true) continue
                    lat += pts[j].pos.lat
                    lng += pts[j].pos.lng
                    cnt += 1
                }
                if (cnt == 0) p.copy() else p.copy(pos = LatLng(lat / cnt, lng / cnt))
            }
        }
    }

    /** 平滑管线：切段 → 段内滑动平均 → 拍平（长度不变） */
    fun smoothTrack(pts: List<TrackPoint>, w: Int = 5): List<TrackPoint> =
        jumpSplit(pts).flatMap { movingAverage(it, w) }

    private fun toXY(p: LatLng, ref: LatLng): Pair<Double, Double> {
        val rad = PI / 180.0
        val x = (p.lng - ref.lng) * rad * Constants.R * cos(ref.lat * rad)
        val y = (p.lat - ref.lat) * rad * Constants.R
        return x to y
    }

    /** 点到线段最短距离（米，平面近似） */
    private fun distToSegM(p: LatLng, a: LatLng, b: LatLng): Double {
        val (px, py) = toXY(p, a)
        val (bx, by) = toXY(b, a)
        val len2 = bx * bx + by * by
        if (len2 == 0.0) return hypot(px, py)
        var t = (px * bx + py * by) / len2
        t = max(0.0, min(1.0, t))
        return hypot(px - t * bx, py - t * by)
    }

    private fun dpRange(pts: List<LatLng>, lo: Int, hi: Int, epsM: Double): List<Int> {
        if (hi - lo <= 1) return listOf(lo, hi)
        val first = pts[lo]
        val last = pts[hi]
        var maxD = -1.0
        var maxI = -1
        for (i in lo + 1 until hi) {
            val d = distToSegM(pts[i], first, last)
            if (d > maxD) {
                maxD = d
                maxI = i
            }
        }
        if (maxD <= epsM) return listOf(lo, hi)
        val left = dpRange(pts, lo, maxI, epsM)
        val right = dpRange(pts, maxI, hi, epsM)
        return left.dropLast(1) + right
    }

    /** Douglas-Peucker 抽稀：返回保留点下标（首尾必保留；供回放对齐时间轴） */
    fun douglasPeuckerKeep(pts: List<LatLng>, epsM: Double): List<Int> {
        if (pts.size <= 2) return pts.indices.toList()
        return dpRange(pts, 0, pts.size - 1, epsM)
    }

    /** Douglas-Peucker 抽稀：返回保留点坐标（首尾必保留） */
    fun douglasPeucker(pts: List<LatLng>, epsM: Double): List<LatLng> =
        douglasPeuckerKeep(pts, epsM).map { pts[it] }
}
