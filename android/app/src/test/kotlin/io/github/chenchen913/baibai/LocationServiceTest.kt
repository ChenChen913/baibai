package io.github.chenchen913.baibai

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** 前台服务测试（四层防杀之第一、二层）：常驻通知 + START_STICKY 静默恢复 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationServiceTest {

    companion object {
        val HOME = LatLng(31.0, 121.0)
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RecorderHub.init(app)
        RecorderHub.useForegroundService = true
        RecorderHub.resetForTest(clearStore = true)
    }

    @Test
    fun `启动服务后发布常驻通知`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val controller = Robolectric.buildService(LocationService::class.java, Intent(ctx, LocationService::class.java))
            .create()
            .startCommand(0, 0)
        val service = controller.get()
        val nm = service.getSystemService(NotificationManager::class.java)
        val shadow = Shadows.shadowOf(nm)
        assertTrue(shadow.allNotifications.isNotEmpty())
        assertTrue(shadow.allNotifications.any { it.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 })
        controller.destroy()
    }

    @Test
    fun `START_STICKY 重建后静默恢复检查点`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RecorderHub.source = FakeSource(HOME)
        // 造一个 WALKING 中的未完成检查点
        RecorderHub.startPressed()
        (RecorderHub.source as FakeSource).push(HOME)
        RecorderHub.flushNow()
        assertEquals(SessionState.WALKING, RecorderHub.recorder?.currentState)
        assertTrue(RecorderHub.store.loadActive() != null)

        // 模拟进程被杀：清运行时状态但保留存储
        RecorderHub.resetForTest(clearStore = false)
        assertNull(RecorderHub.recorder)

        // 服务以 null intent 重建（START_STICKY 语义）
        val controller = Robolectric.buildService(LocationService::class.java)
            .create()
            .startCommand(0, 0)
        assertEquals(SessionState.WALKING, RecorderHub.recorder?.currentState)
        assertEquals(0, RecorderHub.recorder?.snapshot()?.points?.size ?: -1) // 恢复时尚未产生新轨迹点

        controller.destroy()
        RecorderHub.abandonCheckpoint()
    }
}
