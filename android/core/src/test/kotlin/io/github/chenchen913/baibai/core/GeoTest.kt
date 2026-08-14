package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.geo.Geo
import io.github.chenchen913.baibai.core.model.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** 对应网页版 tests/geo.test.ts（9 项，逐位对齐） */
class GeoTest {

    companion object {
        const val R = 6371000.0
        fun latDeg(m: Double): Double = m / R * 180.0 / PI
        fun lngDeg(m: Double, lat: Double): Double = m / R * 180.0 / PI / cos(lat * PI / 180.0)
    }

    @Test
    fun `同点距离为 0`() {
        val p = LatLng(31.23, 121.47)
        assertEquals(0.0, Geo.haversineM(p, p))
    }

    @Test
    fun `纬度方向 100m 约为 100m`() {
        val a = LatLng(31.0, 121.0)
        val b = LatLng(31.0 + latDeg(100.0), 121.0)
        assertEquals(100.0, Geo.haversineM(a, b), 0.5)
    }

    @Test
    fun `经度方向 100m（31N）约为 100m`() {
        val a = LatLng(31.0, 121.0)
        val b = LatLng(31.0, 121.0 + lngDeg(100.0, 31.0))
        assertEquals(100.0, Geo.haversineM(a, b), 0.5)
    }

    @Test
    fun `往返精度 10m 构造点回算误差小于 1e-6`() {
        val a = LatLng(31.0, 121.0)
        val p10 = LatLng(31.0 + latDeg(10.0), 121.0)
        assertTrue(abs(Geo.haversineM(a, p10) - 10.0) < 1e-6)
    }

    @Test
    fun `medianPos 三点取中间点`() {
        val p = Geo.medianPos(
            listOf(
                LatLng(31.2, 121.4),
                LatLng(31.0, 121.0),
                LatLng(31.1, 121.2),
            ),
        )
        assertEquals(LatLng(31.1, 121.2), p)
    }

    @Test
    fun `medianPos 两点取中偏前`() {
        val p = Geo.medianPos(
            listOf(
                LatLng(31.5, 121.5),
                LatLng(31.0, 121.0),
            ),
        )
        assertEquals(LatLng(31.0, 121.0), p)
    }

    @Test
    fun `medianPos 空数组返回 null`() {
        assertNull(Geo.medianPos(emptyList()))
    }

    @Test
    fun `nearest 找到最近节点`() {
        val home = Geo.Located("home", LatLng(31.0, 121.0))
        val n1 = Geo.Located("n1", LatLng(31.0 + latDeg(120.0), 121.0)) // 120m 北
        val r = Geo.nearest(LatLng(31.0 + latDeg(115.0), 121.0), listOf(home, n1))
        assertEquals("n1", r.node?.id)
        assertTrue(r.distM < 30.0)
    }

    @Test
    fun `nearest 空列表返回 null 与 Infinity`() {
        val r = Geo.nearest(LatLng(0.0, 0.0), emptyList())
        assertNull(r.node)
        assertEquals(Double.POSITIVE_INFINITY, r.distM)
    }
}
