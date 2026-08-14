package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.SessionState
import io.github.chenchen913.baibai.core.state.RecorderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.PI

/**
 * 并发冒烟（网页版 _infer_lock 教训）：
 * 定位线程式写入 + UI 线程式读取同时进行，不允许非预期异常、不允许状态损坏。
 */
class ConcurrencySmokeTest {

    companion object {
        val HOME = LatLng(31.0, 121.0)
        const val R = 6371000.0
        const val T0 = 1_700_000_000_000L
        fun fix(pos: LatLng) = io.github.chenchen913.baibai.core.model.Fix(pos, 5.0)
        fun far(m: Double): LatLng = LatLng(HOME.lat + m / R * 180.0 / PI, HOME.lng)
    }

    @Test
    fun `两线程并发操作状态机`() {
        val r = RecorderState.fresh()
        val stop = AtomicBoolean(false)
        val unexpected = CopyOnWriteArrayList<Throwable>()

        val writer = thread(name = "writer") {
            try {
                var i = 0
                while (!stop.get() && i < 50_000) {
                    try {
                        when (i % 6) {
                            0 -> r.start(listOf(fix(HOME)), T0 + i)
                            1 -> r.pause(listOf(fix(far(100.0))), T0 + i)
                            2 -> r.resume(T0 + i)
                            3 -> r.pause(listOf(fix(far(200.0))), T0 + i)
                            4 -> r.finish(listOf(fix(HOME)), T0 + i, force = true)
                            else -> r.undo()
                        }
                    } catch (e: IllegalStateException) {
                        /* 状态转移不可用——并发下允许，恰好是防御目标 */
                    }
                    i += 1
                }
            } catch (t: Throwable) {
                unexpected.add(t)
            }
        }

        val reader = thread(name = "reader") {
            try {
                var i = 0
                while (!stop.get() && i < 50_000) {
                    r.snapshot()
                    r.checkpoint()
                    i += 1
                }
            } catch (t: Throwable) {
                unexpected.add(t)
            }
        }

        Thread.sleep(1500)
        stop.set(true)
        writer.join(10_000)
        reader.join(10_000)
        assertFalse(writer.isAlive, "writer 线程应已结束")
        assertFalse(reader.isAlive, "reader 线程应已结束")
        assertEquals(emptyList<Throwable>(), unexpected.toList())

        val s = r.snapshot()
        assertTrue(s.state in listOf(SessionState.IDLE, SessionState.WALKING, SessionState.PAUSED, SessionState.FINISHED))
        // 数据不变量：节点数与访问数一致、编号连续从 1 起
        val nums = s.nodes.map { it.autoNo }.sorted()
        assertEquals(nums, (1..s.nodes.size).toList())
    }
}
