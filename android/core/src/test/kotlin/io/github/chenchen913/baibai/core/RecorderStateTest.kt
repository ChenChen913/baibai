package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.model.Constants
import io.github.chenchen913.baibai.core.model.Fix
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.SessionState
import io.github.chenchen913.baibai.core.state.FinishResult
import io.github.chenchen913.baibai.core.state.RecorderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.roundToInt

/** 对应网页版 tests/state.test.ts（25 项，逐位对齐） */
class RecorderStateTest {

    companion object {
        val HOME = LatLng(31.0, 121.0)
        const val R = 6371000.0
        const val T0 = 1_700_000_000_000L

        fun far(m: Double): LatLng = LatLng(
            HOME.lat + m / R * 180.0 / PI,
            HOME.lng,
        )

        fun fix(pos: LatLng, acc: Double = 5.0) = Fix(pos, acc)
        fun fixes(pos: LatLng, acc: Double = 5.0): List<Fix> = listOf(fix(pos, acc))

        fun started(): RecorderState {
            val r = RecorderState.fresh()
            r.start(fixes(HOME), T0)
            return r
        }
    }

    // ---------- 状态机转移 ----------

    @Test
    fun `完整闭环：开始→暂停→继续→暂停→结束`() {
        val r = started()
        val a = r.pause(fixes(far(100.0)), T0 + 1000)
        assertEquals(1, a.autoNo)
        r.resume(T0 + 2000)
        val b = r.pause(fixes(far(300.0)), T0 + 3000)
        assertEquals(2, b.autoNo)
        assertEquals(FinishResult.Ok, r.finish(fixes(HOME), T0 + 4000))
        assertEquals(SessionState.FINISHED, r.currentState)
        assertTrue(r.snapshot().finished)
    }

    @Test
    fun `非法转移被拒绝`() {
        val r = RecorderState.fresh()
        assertThrows(IllegalStateException::class.java) { r.pause(fixes(HOME), T0) }
        assertThrows(IllegalStateException::class.java) { r.resume(T0) }
        assertThrows(IllegalStateException::class.java) { r.finish(fixes(HOME), T0) }
        r.start(fixes(HOME), T0)
        assertThrows(IllegalStateException::class.java) { r.start(fixes(HOME), T0 + 1) }
    }

    @Test
    fun `SPEC 修正：WALKING 状态可直接结束`() {
        val r = started()
        r.pause(fixes(far(100.0)), T0 + 1000)
        r.resume(T0 + 2000)
        assertEquals(FinishResult.Ok, r.finish(fixes(HOME), T0 + 3000))
    }

    @Test
    fun `无有效定位时 start 抛错，有 fallback 可用`() {
        val r1 = RecorderState.fresh()
        assertThrows(IllegalArgumentException::class.java) { r1.start(emptyList(), T0) }
        val r2 = RecorderState.fresh()
        r2.start(emptyList(), T0, fix(HOME))
        assertEquals(HOME, r2.snapshot().home)
    }

    @Test
    fun `无有效定位时 pause 抛错`() {
        val r = started()
        assertThrows(IllegalArgumentException::class.java) { r.pause(emptyList(), T0 + 1000) }
    }

    // ---------- 10m 合并（D10） ----------

    @Test
    fun `8m 内重复暂停合并到同一节点`() {
        val r = started()
        val a = r.pause(fixes(far(100.0)), T0 + 1000)
        r.resume(T0 + 2000)
        val b = r.pause(fixes(far(108.0)), T0 + 3000) // 与 A 距 8m
        assertEquals(a.id, b.id)
        assertEquals(1, r.snapshot().nodes.size)
        assertEquals(2, r.snapshot().visits.size)
    }

    @Test
    fun `边界 9_999m 合并 10_001m 新建`() {
        val r = started()
        val a = r.pause(fixes(far(100.0)), T0 + 1000)
        r.resume(T0 + 2000)
        val bNear = r.pause(fixes(far(100.0 + Constants.MERGE_THRESHOLD_M - 0.001)), T0 + 3000)
        assertEquals(a.id, bNear.id)
        r.resume(T0 + 4000)
        val cFar = r.pause(fixes(far(100.0 + Constants.MERGE_THRESHOLD_M + 0.001)), T0 + 5000)
        assertTrue(cFar.id != a.id)
        assertEquals(2, r.snapshot().nodes.size)
    }

    @Test
    fun `低精度 fix 不参与中位数，节点标 lowAcc`() {
        val r = started()
        val n = r.pause(
            listOf(
                Fix(far(100.0), 80.0),
                Fix(far(200.0), 90.0), // 全部低精度 → 用原始最后一点
            ),
            T0 + 1000,
        )
        assertEquals(true, n.lowAcc)
        assertEquals(far(200.0), n.pos)
    }

    @Test
    fun `高精度优先：低精度点被过滤后取高精度中位数`() {
        val r = started()
        val n = r.pause(
            listOf(
                Fix(far(100.0), 8.0),
                Fix(far(120.0), 90.0), // 被过滤
                Fix(far(110.0), 9.0),
            ),
            T0 + 1000,
        )
        assertNull(n.lowAcc)
        assertEquals(far(100.0), n.pos) // 8/9 精度两点取中偏前
    }

    @Test
    fun `5m 内暂停合并到 Home（D20 中途回家）`() {
        val r = started()
        val n = r.pause(fixes(far(5.0)), T0 + 1000)
        assertEquals(Constants.HOME_ID, n.id)
        assertEquals(0, r.snapshot().nodes.size)
        assertEquals(Constants.HOME_ID, r.snapshot().visits[0].nodeId)
    }

    // ---------- 自动编号（D11） ----------

    @Test
    fun `按拜访顺序编号，不含 Home`() {
        val r = started()
        assertEquals(1, r.pause(fixes(far(100.0)), T0 + 1).autoNo)
        r.resume(T0 + 2)
        assertEquals(2, r.pause(fixes(far(300.0)), T0 + 3).autoNo)
        r.resume(T0 + 4)
        assertEquals(3, r.pause(fixes(far(500.0)), T0 + 5).autoNo)
    }

    @Test
    fun `撤销后新建节点复用编号`() {
        val r = started()
        r.pause(fixes(far(100.0)), T0 + 1)
        r.resume(T0 + 2)
        r.pause(fixes(far(300.0)), T0 + 3) // 2 号
        r.undo() // 撤销 2 号
        r.pause(fixes(far(400.0)), T0 + 4)
        assertEquals(listOf(1, 2), r.snapshot().nodes.map { it.autoNo })
    }

    // ---------- 撤销（D19 R2） ----------

    @Test
    fun `撤销链回溯到待机`() {
        val r = started()
        r.pause(fixes(far(100.0)), T0 + 1000) // A
        r.resume(T0 + 2000)
        r.pause(fixes(far(300.0)), T0 + 3000) // B
        assertTrue(r.undo()) // 撤 B
        assertEquals(SessionState.WALKING, r.currentState)
        assertEquals(1, r.snapshot().nodes.size)
        assertTrue(r.undo()) // 撤 continue
        assertEquals(SessionState.PAUSED, r.currentState)
        assertNull(r.snapshot().visits[0].leaveT)
        assertTrue(r.undo()) // 撤 A
        assertEquals(SessionState.WALKING, r.currentState)
        assertEquals(0, r.snapshot().nodes.size)
        assertTrue(r.undo()) // 撤开始
        assertEquals(SessionState.IDLE, r.currentState)
        assertEquals(0, r.snapshot().nodes.size)
        assertEquals(0, r.snapshot().visits.size)
    }

    @Test
    fun `撤销"合并到已有节点"的暂停：只删访问，不删节点`() {
        val r = started()
        val a = r.pause(fixes(far(100.0)), T0 + 1000)
        r.resume(T0 + 2000)
        r.pause(fixes(far(108.0)), T0 + 3000) // 合并到 A
        assertEquals(2, r.snapshot().visits.size)
        r.undo()
        assertEquals(1, r.snapshot().visits.size)
        assertEquals(1, r.snapshot().nodes.size)
        assertEquals(a.id, r.snapshot().nodes[0].id)
    }

    @Test
    fun `撤销结束：回到结束前状态`() {
        val r = started()
        r.pause(fixes(far(100.0)), T0 + 1000)
        r.resume(T0 + 2000)
        assertEquals(FinishResult.Ok, r.finish(fixes(HOME), T0 + 3000))
        assertTrue(r.undo())
        assertEquals(SessionState.WALKING, r.currentState)
        assertTrue(!r.snapshot().finished)
    }

    @Test
    fun `无可撤销时返回 false`() {
        val r = RecorderState.fresh()
        assertTrue(!r.undo())
    }

    // ---------- Home 起止（D9/D10） ----------

    @Test
    fun `距 Home 15m 结束自动通过（FINISH_OK_M=20）`() {
        val r = started()
        r.pause(fixes(far(100.0)), T0 + 1000)
        r.resume(T0 + 2000)
        assertEquals(FinishResult.Ok, r.finish(fixes(far(15.0)), T0 + 3000))
        assertEquals(SessionState.FINISHED, r.currentState)
    }

    @Test
    fun `距 Home 500m 结束被拒并可强制`() {
        val r = started()
        r.pause(fixes(far(100.0)), T0 + 1000)
        r.resume(T0 + 2000)
        val res = r.finish(fixes(far(500.0)), T0 + 3000)
        assertTrue(res is FinishResult.TooFar)
        assertEquals(500.0, (res as FinishResult.TooFar).distM, 1.0)
        assertEquals(SessionState.WALKING, r.currentState)
        assertEquals(FinishResult.Ok, r.finish(fixes(far(500.0)), T0 + 4000, force = true))
        assertEquals(SessionState.FINISHED, r.currentState)
    }

    // ---------- 跳变防护（D22 最小版） ----------

    @Test
    fun `2s 内 500m 跳变标 jump，超时不算`() {
        val r = started()
        r.addPoint(HOME, 5.0, T0)
        val p1 = r.addPoint(far(500.0), 5.0, T0 + 1000)
        assertEquals(true, p1.jump)
        val p2 = r.addPoint(far(600.0), 5.0, T0 + 5000)
        assertNull(p2.jump)
        // R7：跳变点直接丢弃——返回值带标记，但 points 只含 HOME 与 far(600)
        assertEquals(2, r.snapshot().points.size)
    }

    @Test
    fun `非 WALKING 状态记点抛错`() {
        val r = RecorderState.fresh()
        assertThrows(IllegalStateException::class.java) { r.addPoint(HOME, 5.0, T0) }
    }

    // ---------- 静止过滤（R8 真机修复） ----------

    @Test
    fun `R8 平滑窗口：中位数稳定估计走够门槛才入库，入库点即中位数`() {
        val r = started()
        r.addPoint(HOME, 5.0, T0)
        r.addPoint(far(3.0), 5.0, T0 + 1000) // 3m 抖动：原始候选 <5m → 不入库
        r.addPoint(far(4.0), 5.0, T0 + 2000) // 窗口≥3：中位数 far(3)，距 HOME 3m → 不入库
        r.addPoint(far(6.0), 5.0, T0 + 3000) // 窗口 [0,3,4,6] 中位数仍 far(3) → 不入库
        r.addPoint(far(9.0), 5.0, T0 + 4000) // 窗口 [0,3,4,6,9] 中位数 far(4) → 仍不足 5m
        assertEquals(1, r.snapshot().points.size)
        r.addPoint(far(12.0), 5.0, T0 + 5000) // 窗口滑至 [3,4,6,9,12]：中位数 far(6) ≥5m → 入库
        assertEquals(2, r.snapshot().points.size)
        assertEquals(far(6.0), r.snapshot().points[1].pos) // 入库的是中位数（稳定估计）
        r.addPoint(far(15.0), 5.0, T0 + 6000) // 入库后窗口重置：原始候选 far(15) 距 far(6) 9m → 入库
        assertEquals(3, r.snapshot().points.size)
    }

    @Test
    fun `R8 坐着不动 2 分钟：抖动点全被过滤，轨迹不长线`() {
        // 真机主诉复现：静止时 GPS 每 2s 抖动 ±4m——旧版全收，回放拉出多条线段的复杂轨迹
        val r = started()
        r.addPoint(HOME, 5.0, T0)
        var t = T0
        for (i in 1..60) {
            t += 2000
            r.addPoint(far(kotlin.math.abs(kotlin.math.sin(i.toDouble())) * 4), 5.0, t)
        }
        assertEquals(1, r.snapshot().points.size)
    }

    @Test
    fun `R8 室内静止（±15m 振荡、精度 25m）：门槛随精度抬高，零入库`() {
        // 真机主诉第二轮：坐 1~2 分钟仍拉出小段偏移——R7 固定 5m 门槛挡不住室内大抖动；
        // R8 门槛 = min(max(5, acc), 30) = 25m，±15m 振荡全滤
        val r = started()
        r.addPoint(HOME, 25.0, T0)
        var t = T0
        for (i in 1..60) {
            t += 2000
            r.addPoint(if (i % 2 == 0) far(15.0) else far(-15.0), 25.0, t)
        }
        assertEquals(1, r.snapshot().points.size)
    }

    @Test
    fun `R8 静止后真实走动（每 fix 3m）：轨迹正常记录不被误杀`() {
        val r = started()
        r.addPoint(HOME, 5.0, T0)
        r.addPoint(far(3.0), 5.0, T0 + 1000) // 静止抖动
        r.addPoint(far(4.0), 5.0, T0 + 2000)
        var t = T0 + 2000
        var m = 7.0
        while (m <= 40.0) {
            t += 2000
            r.addPoint(far(m), 5.0, t) // 起步：每 2s 前进 3m
            m += 3.0
        }
        val pts = r.snapshot().points
        assertTrue(pts.size >= 3)
        assertTrue(io.github.chenchen913.baibai.core.geo.Geo.haversineM(HOME, pts.last().pos) > 25) // 末点已远离 Home
    }

    // ---------- 出行方式（D19 R1） ----------

    @Test
    fun `骑车段访问记为 bike，到户自动回走路`() {
        val r = started()
        r.setMode(Mode.BIKE, T0 + 500)
        r.pause(fixes(far(100.0)), T0 + 1000)
        assertEquals(Mode.BIKE, r.snapshot().visits[0].mode)
        assertEquals(Mode.WALK, r.snapshot().currentMode) // D19：到户自动回走路
        r.resume(T0 + 2000)
        r.pause(fixes(far(300.0)), T0 + 3000)
        assertEquals(Mode.WALK, r.snapshot().visits[1].mode)
    }

    @Test
    fun `撤销暂停时还原出行方式`() {
        val r = started()
        r.setMode(Mode.BIKE, T0 + 500)
        r.pause(fixes(far(100.0)), T0 + 1000)
        assertEquals(Mode.WALK, r.snapshot().currentMode)
        r.undo()
        assertEquals(Mode.BIKE, r.snapshot().currentMode) // 回到"仍在骑行前往"的状态
    }

    // ---------- 检查点与恢复（D22） ----------

    @Test
    fun `检查点序列化往返一致`() {
        val r = started()
        r.pause(fixes(far(100.0)), T0 + 1000)
        r.resume(T0 + 2000)
        r.addPoint(far(50.0), 5.0, T0 + 2100)
        val ck = r.checkpoint()
        val json = io.github.chenchen913.baibai.core.store.JsonStore.defaultJson()
        val str = json.encodeToString(
            io.github.chenchen913.baibai.core.model.Checkpoint.serializer(),
            ck,
        )
        val ck2 = json.decodeFromString(
            io.github.chenchen913.baibai.core.model.Checkpoint.serializer(),
            str,
        )
        assertEquals(ck, ck2)
        assertEquals(ck.session, RecorderState.restore(ck2).snapshot())
    }

    @Test
    fun `恢复后可继续记录且撤销历史可用`() {
        val r = started()
        r.pause(fixes(far(100.0)), T0 + 1000)
        val r2 = RecorderState.restore(r.checkpoint())
        assertEquals(SessionState.PAUSED, r2.currentState)
        r2.resume(T0 + 2000)
        assertEquals(SessionState.WALKING, r2.currentState)
        assertTrue(r2.undo()) // 恢复后仍可撤销（动作历史随检查点保存）
        assertEquals(SessionState.PAUSED, r2.currentState)
    }
}
