package io.github.chenchen913.baibai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Web Mercator 瓦片数学（P0 离线预载）。
 * 与网页版 src/tiles.ts 同一套公式与同一组测试值——任何一侧改动必须同步另一侧（契约）。
 */
object TileMath {

    /** lng → 瓦片列号 x（z 级） */
    fun tileX(lng: Double, z: Int): Int =
        floor((lng + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    /** lat → 瓦片行号 y（z 级，北为上） */
    fun tileY(lat: Double, z: Int): Int {
        val rad = lat * PI / 180.0
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * (1 shl z)
        return floor(y).toInt().coerceIn(0, (1 shl z) - 1)
    }
    /** 高德瓦片 URL（style: "street" 矢量 / "sat" 卫星；sub 1~4） */
    fun url(style: String, x: Int, y: Int, z: Int, sub: Int = 1): String {
        val host = if (style == "sat") "webst0" + sub else "webrd0" + sub
        val query = if (style == "sat") "style=6" else "lang=zh_cn&size=1&scale=1&style=8"
        return "https://" + host + ".is.autonavi.com/appmaptile?" + query + "&x=" + x + "&y=" + y + "&z=" + z
    }

    /** 以 (centerLat,centerLng) 为中心、经纬度各扩展 dLat/dLng 的矩形内，z 级全部瓦片 */
    fun tilesIn(centerLat: Double, centerLng: Double, dLat: Double, dLng: Double, z: Int): List<Pair<Int, Int>> {
        val x0 = tileX(centerLng - dLng, z)
        val x1 = tileX(centerLng + dLng, z)
        val y0 = tileY(centerLat + dLat, z) // 北边（y 更小）
        val y1 = tileY(centerLat - dLat, z)
        val out = ArrayList<Pair<Int, Int>>()
        for (x in x0..x1) {
            for (y in y0..y1) {
                out.add(x to y)
            }
        }
        return out
    }

    /** 预载清单：Home ±0.02°（约 2.2km）× z13~z16（约 170 张、3.5MB），每级含普通+卫星 */
    fun preloadList(centerLat: Double, centerLng: Double): List<String> {
        val out = ArrayList<String>()
        for (z in 13..16) {
            for ((x, y) in tilesIn(centerLat, centerLng, 0.02, 0.02, z)) {
                out.add(url("street", x, y, z))
                out.add(url("sat", x, y, z))
            }
        }
        return out
    }

    /**
     * 缓存 key 规范化：只取 path+query（主机与子域数字不参与）。
     * 这样预载（固定子域）与运行时（任意子域）共用同一条缓存，断网时不会因子域不同而 miss。
     */
    fun cacheKey(url: String): String {
        val i = url.indexOf("/appmaptile")
        if (i < 0) {
            val j = url.indexOf("/", "https://".length)
            return if (j < 0) url else url.substring(j)
        }
        return url.substring(i)
    }
}

