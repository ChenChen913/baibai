package io.github.chenchen913.baibai

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 前台服务（四层防杀之第一、二层）：
 * - foregroundServiceType="location" + 常驻通知 → 系统最高优先级保护；
 * - START_STICKY → 被回收后自动重建，null intent 时静默恢复检查点继续记录。
 */
class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RecorderHub.init(application)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // START_STICKY 重建：静默恢复（用户可能不在前台，不弹窗）
            RecorderHub.autoResumeFromCheckpoint()
        }
        Notifications.ensureChannel(this)
        startForeground(Notifications.NOTIF_ID, Notifications.build(this, RecorderHub.session.value))
        observeSession()
        RecorderHub.ensureSourceRunning()
        return START_STICKY
    }

    private fun observeSession() {
        observeJob?.cancel()
        observeJob = scope.launch {
            RecorderHub.session.collect { s ->
                val nm = getSystemService(NotificationManager::class.java)
                runCatching { nm.notify(Notifications.NOTIF_ID, Notifications.build(this@LocationService, s)) }
            }
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        RecorderHub.stopLocationSource()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
