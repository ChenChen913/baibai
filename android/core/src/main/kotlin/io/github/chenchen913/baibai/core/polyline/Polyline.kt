package io.github.chenchen913.baibai.core.polyline

import io.github.chenchen913.baibai.core.model.Constants
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.track.XY
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 折线工具（对应网页版 polyline.ts：压轴 morph 数学） */
object Polyline {

    /** 路线（含闭合回 home）的直线折线 */
    fun routePolyline(s: SessionData, order: List<String>): List<LatLng> {
        fun posOf(id: String): LatLng =
            if (id == Constants.HOME_ID) s.home else s.nodes.first { it.id == id }.pos
        val pts = order.map { posOf(it) }.toMutableList()
        pts.add(posOf(order.first()))
        return pts
    }

    /** 按弧长均匀重采样为 m 个点（首尾必含；退化折线退化为重复点） */
    fun resamplePolyline(pts: List<XY>, m: Int): List<XY> {
        if (pts.isEmpty()) return emptyList()
        if (m <= 1) return listOf(pts.first())
        val segLens = DoubleArray(pts.size - 1)
        var total = 0.0
        for (i in 1 until pts.size) {
            val l = hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
            segLens[i - 1] = l
            total += l
        }
        if (total == 0.0) return List(m) { pts.first() }
        val out = mutableListOf(pts.first())
        var seg = 0
        var acc = 0.0
        for (k in 1 until m - 1) {
            val target = total * k / (m - 1)
            while (seg < segLens.size && acc + segLens[seg] < target) {
                acc += segLens[seg]
                seg += 1
            }
            val si = min(seg, segLens.size - 1)
            val l = if (segLens[si] > 0) segLens[si] else 1.0
            var f = (target - acc) / l
            f = max(0.0, min(1.0, f))
            val a = pts[min(si, pts.size - 1)]
            val b = pts[min(si + 1, pts.size - 1)]
            out.add(XY(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f))
        }
        out.add(pts.last())
        return out
    }

    /** 两组同长折线逐点线性插值（t∈[0,1]；morph 动画核心） */
    fun lerpPolyline(a: List<XY>, b: List<XY>, t: Double): List<XY> {
        val n = min(a.size, b.size)
        return List(n) { i -> XY(a[i].x + (b[i].x - a[i].x) * t, a[i].y + (b[i].y - a[i].y) * t) }
    }
}
