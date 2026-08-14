package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.demo.Demo
import io.github.chenchen913.baibai.core.playback.Playback
import io.github.chenchen913.baibai.core.track.Track
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 对应网页版 tests/optimize.test.ts 的 demo 部分（3 项，逐位对齐） */
class DemoTest {

    @Test
    fun `结构合法：8 户 8 访、时间单调、时长大于 0`() {
        val s = Demo.generateDemoSession()
        assertEquals(8, s.nodes.size)
        assertEquals(8, s.visits.size)
        assertTrue(s.points.size > 100)
        for (i in 1 until s.points.size) {
            assertTrue(s.points[i].t >= s.points[i - 1].t)
        }
        for (v in s.visits) {
            assertTrue(v.leaveT!! > v.arriveT)
        }
        assertTrue(s.points.any { it.jump == true }) // 含 1 个跳变点
    }

    @Test
    fun `可被 buildPlan 与 buildEdges 消费`() {
        val s = Demo.generateDemoSession()
        val plan = Playback.buildPlan(s, 480.0, 560.0)
        assertTrue(plan.pts.size > 10)
        assertTrue(plan.totalMs > 0)
        assertTrue(Track.buildEdges(s).size > 0)
    }

    @Test
    fun `确定性：两次生成完全一致`() {
        assertEquals(Demo.generateDemoSession(), Demo.generateDemoSession())
    }
}
