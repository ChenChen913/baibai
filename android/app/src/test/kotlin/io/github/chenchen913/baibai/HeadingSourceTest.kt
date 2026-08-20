package io.github.chenchen913.baibai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 朝向源单测（v1.0.10「探照灯」）。
 * computeHeadingDeg 是纯四元数数学（不碰 SensorManager native），JVM 直接跑。
 *
 * 测试姿态的四元数推导（East-North-Up 世界，设备自然方向竖屏：X右 Y顶 Z出屏）：
 * - 竖持面北 = 平放顶朝北绕世界东轴(X)转 +90°：q=(w=√2/2, v=(√2/2,0,0))
 * - 竖持面东 = 面北再绕世界天轴(Z)转 -90°（从上看顺时针）：q=(w=0.5, v=(0.5,-0.5,-0.5))
 * - 竖持面南 = 面北绕天轴转 180°：q=(w=0, v=(0,-√2/2,-√2/2))
 * 屏幕法线 Z 的世界方向：面北=南、面东=西、面南=北；视线=-Z → 北/东/南。
 */
class HeadingSourceTest {

    private val s2 = Math.sqrt(2.0) / 2.0

    @Test
    fun 竖持面北方位角为0() {
        val rv = floatArrayOf(s2.toFloat(), 0f, 0f, s2.toFloat())
        assertEquals(0.0, HeadingSource.computeHeadingDeg(rv, 0.0), 0.01)
    }

    @Test
    fun 竖持面东方位角为90() {
        val rv = floatArrayOf(0.5f, -0.5f, -0.5f, 0.5f)
        assertEquals(90.0, HeadingSource.computeHeadingDeg(rv, 0.0), 0.01)
    }

    @Test
    fun 竖持面南方位角为180() {
        val rv = floatArrayOf(0f, (-s2).toFloat(), (-s2).toFloat(), 0f)
        assertEquals(180.0, HeadingSource.computeHeadingDeg(rv, 0.0), 0.01)
    }

    @Test
    fun 磁偏角东偏为正() {
        val rv = floatArrayOf(s2.toFloat(), 0f, 0f, s2.toFloat()) // 面北
        assertEquals(7.5, HeadingSource.computeHeadingDeg(rv, 7.5), 0.01)
    }

    @Test
    fun 负磁偏角跨0折360() {
        val rv = floatArrayOf(0.5f, -0.5f, -0.5f, 0.5f) // 面东 90°
        // 90° - 95° = -5° → 355°
        assertEquals(355.0, HeadingSource.computeHeadingDeg(rv, -95.0), 0.01)
    }

    @Test
    fun 结果恒在0到360() {
        val samples = listOf(
            floatArrayOf(s2.toFloat(), 0f, 0f, s2.toFloat()),
            floatArrayOf(0.5f, -0.5f, -0.5f, 0.5f),
            floatArrayOf(0f, (-s2).toFloat(), (-s2).toFloat(), 0f),
            floatArrayOf(0f, 0f, 0f, 1f), // 单位姿态（平放顶朝北）
        )
        for (rv in samples) {
            for (d in listOf(-180.0, 0.0, 180.0)) {
                val h = HeadingSource.computeHeadingDeg(rv, d)
                assertTrue("应在 [0,360)：$h", h in 0.0..360.0)
            }
        }
    }

    @Test
    fun 不足4元返回NaN() {
        assertTrue(HeadingSource.computeHeadingDeg(floatArrayOf(0.1f, 0.2f, 0.3f), 0.0).isNaN())
    }

    @Test
    fun wrapDelta最短弧() {
        assertEquals(2.0, HeadingSource.wrapDelta(2.0), 1e-9) // 359°→1° 应顺转 2°
        assertEquals(2.0, HeadingSource.wrapDelta(-358.0), 1e-9)
        assertEquals(-2.0, HeadingSource.wrapDelta(358.0), 1e-9)
        assertEquals(180.0, Math.abs(HeadingSource.wrapDelta(180.0)), 1e-9) // 半圈方向任意
        assertEquals(90.0, HeadingSource.wrapDelta(-270.0), 1e-9)
    }
}
