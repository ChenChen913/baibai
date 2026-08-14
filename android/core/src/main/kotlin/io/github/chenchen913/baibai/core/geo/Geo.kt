package io.github.chenchen913.baibai.core.geo

import io.github.chenchen913.baibai.core.model.Constants
import io.github.chenchen913.baibai.core.model.LatLng
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 地理基础运算（对应网页版 geo.ts，语义逐位一致） */
object Geo {

    /** 球面距离（米），haversine 公式 */
    fun haversineM(a: LatLng, b: LatLng): Double {
        fun toRad(d: Double) = d * PI / 180.0
        val dLat = toRad(b.lat - a.lat)
        val dLng = toRad(b.lng - a.lng)
        val s =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(toRad(a.lat)) * cos(toRad(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * Constants.R * asin(sqrt(s))
    }

    /** 坐标分量中位数（抗单点跳变；空列表返回 null；偶数取中偏前） */
    fun medianPos(ps: List<LatLng>): LatLng? {
        if (ps.isEmpty()) return null
        val mid = ceil(ps.size / 2.0).toInt() - 1
        val lats = ps.map { it.lat }.sorted()
        val lngs = ps.map { it.lng }.sorted()
        return LatLng(lats[mid], lngs[mid])
    }

    data class Located(val id: String, val pos: LatLng)

    data class Nearest(val node: Located?, val distM: Double)

    /** 找最近节点；调用方按 ≤thresholdM 决定合并还是新建 */
    fun nearest(pos: LatLng, located: List<Located>): Nearest {
        var best: Located? = null
        var bestD = Double.POSITIVE_INFINITY
        for (n in located) {
            val d = haversineM(pos, n.pos)
            if (d < bestD) {
                bestD = d
                best = n
            }
        }
        return Nearest(best, bestD)
    }
}
