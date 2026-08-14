package io.github.chenchen913.baibai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.chenchen913.baibai.core.errors.GpsErrorKind
import io.github.chenchen913.baibai.core.model.Fix
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.SessionState
import io.github.chenchen913.baibai.core.state.FinishResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI

/** 测试定位源：按测试脚本派发 fix */
class FakeSource(private val home: LatLng) : LocationSource {
    private val buffer = ArrayDeque<Fix>()
    private var cb: LocationCallbacks? = null

    override val active: Boolean get() = cb != null
    override val lastFix: Fix? get() = buffer.lastOrNull()
    override fun recent(n: Int): List<Fix> = buffer.takeLast(n)

    override fun start(cb: LocationCallbacks) {
        this.cb = cb
    }

    override fun stop() {
        cb = null
    }

    fun push(pos: LatLng, acc: Double = 5.0) {
        val f = Fix(pos, acc)
        buffer.addLast(f)
        cb?.onFix(f)
    }
}

/** app 层集成测试（Robolectric，无模拟器）：对应网页版 main.ts 全链路 + 崩溃恢复 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecorderHubTest {

    companion object {
        val HOME = LatLng(31.0, 121.0)
        const val R = 6371000.0
        fun far(m: Double): LatLng = LatLng(HOME.lat + m / R * 180.0 / PI, HOME.lng)
    }

    private lateinit var fake: FakeSource

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RecorderHub.init(app)
        fake = FakeSource(HOME)
        RecorderHub.source = fake
        RecorderHub.useForegroundService = false // 本类聚焦 Hub 逻辑，服务在 LocationServiceTest 单独测
        RecorderHub.resetForTest(clearStore = true)
    }

    @Test
    fun `全链路：开始→暂停→继续→结束保存`() {
        RecorderHub.startPressed()
        // 攒 3 个 fix（或 3 秒）→ 中位数定 Home（首个 fix 噪声大，不再单点定 Home）
        fake.push(HOME)
        fake.push(HOME)
        fake.push(HOME)
        assertEquals(SessionState.WALKING, RecorderHub.recorder?.currentState)
        assertEquals(HOME, RecorderHub.recorder?.snapshot()?.home)
        assertEquals(0, RecorderHub.session.value?.points?.size)

        fake.push(far(50.0))
        fake.push(far(90.0))
        fake.push(far(105.0))
        RecorderHub.pausePressed() // 最近 3 个 fix 中位数 → 新户
        assertEquals(SessionState.PAUSED, RecorderHub.recorder?.currentState)
        assertEquals(1, RecorderHub.recorder?.snapshot()?.nodes?.size)

        RecorderHub.resumePressed()
        fake.push(HOME)
        val res = RecorderHub.finishPressed() // 中位数 far(90) 距 Home 90m → TooFar
        assertTrue(res is FinishResult.TooFar)
        RecorderHub.finishPressed(force = true)
        assertEquals(1, RecorderHub.store.listSessions().size)
        assertNull(RecorderHub.recorder)
    }

    @Test
    fun `检查点恢复：崩溃后续录`() {
        RecorderHub.startPressed()
        fake.push(HOME)
        fake.push(far(50.0))
        fake.push(far(90.0))
        fake.push(far(105.0))
        RecorderHub.pausePressed()
        RecorderHub.flushNow()
        val nodeCount = RecorderHub.recorder?.snapshot()?.nodes?.size ?: 0
        assertEquals(1, nodeCount)

        // 模拟崩溃：清运行时状态但保留存储
        RecorderHub.resetForTest(clearStore = false)
        assertNull(RecorderHub.recorder)

        RecorderHub.boot()
        assertTrue(RecorderHub.pendingRestore.value)
        RecorderHub.resumeCheckpoint()
        assertEquals(SessionState.PAUSED, RecorderHub.recorder?.currentState)
        assertEquals(nodeCount, RecorderHub.recorder?.snapshot()?.nodes?.size)

        // 清理：放弃本次（避免污染其它测试断言存储）
        RecorderHub.abandonCheckpoint()
    }

    @Test
    fun `GPS 错误映射到提示不崩溃`() {
        RecorderHub.handleGpsError(GpsErrorKind.DENIED)
        RecorderHub.handleGpsError(GpsErrorKind.TIMEOUT)
        // 无崩溃即通过（错误仅发送 toast 事件）
    }
}
