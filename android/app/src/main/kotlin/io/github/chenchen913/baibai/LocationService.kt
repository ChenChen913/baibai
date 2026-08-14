package io.github.chenchen913.baibai

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 前台服务（四层防杀之第一、二层）：
 * - foregroundServiceType="location" + 常驻通知 → 系统最高优先级保护；
 * - START_STICKY → 被回收后自动重建，null intent 时静默恢复检查点继续记录；
 * - PARTIAL_WAKE_LOCK（L-8）：记录期间 CPU 不休眠，10s 检查点定时器在国产 ROM 深睡时不漂移。
 */
class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
        acquireWakeLock()
        Notifications.ensureChannel(this)
        startForeground(Notifications.NOTIF_ID, Notifications.build(this, RecorderHub.session.value))
        observeSession()
        RecorderHub.ensureSourceRunning()
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "baibai:recording").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // 最长 4 小时兜底
        }
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
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        RecorderHub.stopLocationSource()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
