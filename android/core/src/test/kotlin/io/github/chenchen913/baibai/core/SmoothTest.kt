package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.TrackPoint
import io.github.chenchen913.baibai.core.smooth.Smooth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 对应网页版 tests/smooth.test.ts（12 项，逐位对齐） */
class SmoothTest {

    private fun pt(lat: Double, lng: Double, t: Long, jump: Boolean? = null): TrackPoint =
        TrackPoint(t = t, pos = LatLng(lat, lng), acc = 5.0, seg = "s0", jump = jump)

    // ---------- jumpSplit ----------

    @Test
    fun `jumpSplit 无 jump 为单段`() {
        val segs = Smooth.jumpSplit(listOf(pt(0.0, 0.0, 0), pt(1.0, 1.0, 1), pt(2.0, 2.0, 2)))
        assertEquals(1, segs.size)
        assertEquals(3, segs[0].size)
    }

    @Test
    fun `jumpSplit 中间 jump 切两段，jump 点归后段`() {
        val segs = Smooth.jumpSplit(
            listOf(pt(0.0, 0.0, 0), pt(1.0, 1.0, 1), pt(2.0, 2.0, 2, jump = true), pt(3.0, 3.0, 3)),
        )
        assertEquals(2, segs.size)
        assertEquals(listOf(0.0, 1.0), segs[0].map { it.pos.lat })
        assertEquals(listOf(2.0, 3.0), segs[1].map { it.pos.lat })
    }

    @Test
    fun `jumpSplit 开头 jump 不产生空段`() {
        val segs = Smooth.jumpSplit(listOf(pt(0.0, 0.0, 0, jump = true), pt(1.0, 1.0, 1)))
        assertEquals(1, segs.size)
        assertEquals(2, segs[0].size)
    }

    // ---------- movingAverage ----------

    @Test
    fun `movingAverage 长度不变、保留元字段`() {
        val pts = listOf(pt(0.0, 0.0, 0), pt(1.0, 1.0, 1, jump = true), pt(2.0, 2.0, 2))
        val out = Smooth.movingAverage(pts)
        assertEquals(3, out.size)
        assertEquals(1L, out[1].t)
        assertEquals(true, out[1].jump)
    }

    @Test
    fun `movingAverage 共线等距点不变`() {
        val pts = listOf(
            pt(0.0, 0.0, 0),
            pt(0.001, 0.0, 1),
            pt(0.002, 0.0, 2),
            pt(0.003, 0.0, 3),
            pt(0.004, 0.0, 4),
        )
        val out = Smooth.movingAverage(pts)
        assertEquals(0.002, out[2].pos.lat, 1e-9)
    }

    @Test
    fun `movingAverage 端点保持原坐标，中间点取窗口均值`() {
        val pts = listOf(pt(0.0, 0.0, 0), pt(1.0, 0.0, 1), pt(2.0, 0.0, 2), pt(3.0, 0.0, 3), pt(4.0, 0.0, 4))
        val out = Smooth.movingAverage(pts)
        assertEquals(0.0, out[0].pos.lat)
        assertEquals(4.0, out[4].pos.lat)
        assertEquals(2.0, out[2].pos.lat, 1e-9)
    }

    // ---------- smoothTrack ----------

    @Test
    fun `smoothTrack 长度不变`() {
        val pts = listOf(pt(0.0, 0.0, 0), pt(1.0, 1.0, 1, jump = true), pt(2.0, 2.0, 2), pt(3.0, 3.0, 3))
        assertEquals(4, Smooth.smoothTrack(pts).size)
    }

    @Test
    fun `smoothTrack 窗口不跨 jump 段`() {
        val farA = pt(0.0, 0.0, 0)
        val farB = pt(1.0, 1.0, 1, jump = true)
        val farC = pt(2.0, 2.0, 2)
        val out = Smooth.smoothTrack(listOf(farA, farB, farC))
        assertEquals(LatLng(1.0, 1.0), out[1].pos)
        assertEquals(LatLng(0.0, 0.0), out[0].pos)
    }

    // ---------- douglasPeucker ----------

    @Test
    fun `douglasPeucker 直线仅保留两端点`() {
        val line = listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0), LatLng(2.0, 2.0), LatLng(3.0, 3.0))
        assertEquals(listOf(LatLng(0.0, 0.0), LatLng(3.0, 3.0)), Smooth.douglasPeucker(line, 2.0))
    }

    @Test
    fun `douglasPeucker eps 为 0 全保留`() {
        val line = listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.5), LatLng(2.0, 2.0))
        assertEquals(3, Smooth.douglasPeucker(line, 0.0).size)
    }

    @Test
    fun `douglasPeucker 直角拐点保留`() {
        val corner = listOf(LatLng(0.0, 0.0), LatLng(1.0, 0.0), LatLng(1.0, 1.0))
        assertEquals(corner, Smooth.douglasPeucker(corner, 1.0))
    }

    @Test
    fun `douglasPeucker 空与两点直通`() {
        assertEquals(emptyList<LatLng>(), Smooth.douglasPeucker(emptyList(), 2.0))
        val two = listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0))
        assertEquals(two, Smooth.douglasPeucker(two, 2.0))
        assertTrue(true)
    }
}
