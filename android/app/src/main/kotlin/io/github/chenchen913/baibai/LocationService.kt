package io.github.chenchen913.baibai

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import io.github.chenchen913.baibai.core.model.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 修 H：WakeLock 单次租期 10 分钟——到期前自动续租（拜年会话 3~6 小时，覆盖全程） */
private const val WAKE_LOCK_LEASE_MS = 10 * 60 * 1000L

/** 修 H：续租巡检间隔 1 分钟（判断租约将到期/已被系统超时释放，及时补租） */
private const val WAKE_LOCK_CHECK_MS = 60 * 1000L

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
    private var wakeLockJob: Job? = null // 修 H：WakeLock 续租协程

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
        // 修 G：只有记录中（WALKING）才重启定位源。旧版无条件 ensureSourceRunning()，
        // START_STICKY 重建时若会话处于 PAUSED 也会把定位重新拉起来，
        // 违反 SPEC §7「PAUSED 停定位」的省电语义
        if (RecorderHub.recorder?.currentState == SessionState.WALKING) {
            RecorderHub.ensureSourceRunning()
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "baibai:recording").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_LEASE_MS)
        }
        startWakeLockRenewal()
    }

    /**
     * 修 H：WakeLock 到期前自动续租。
     * 旧版 acquire(4h) 是硬上限，而拜年会话通常 3~6 小时：到期后没有任何续期逻辑，
     * 深睡时 10s 检查点定时器漂移（四层防杀的 L-8 失效）。
     * 取舍：不改用无超时的 acquire()——一旦 onDestroy 漏执行（异常路径）就是永久耗电；
     * 这里「10 分钟租期 + 每分钟巡检、到期前主动换租」：正常路径全程不断电，
     * 异常路径（进程被杀/协程被取消）锁最多 10 分钟后由系统自动释放，不会永久耗电。
     */
    private fun startWakeLockRenewal() {
        wakeLockJob?.cancel()
        wakeLockJob = scope.launch {
            var leaseEnd = System.currentTimeMillis() + WAKE_LOCK_LEASE_MS
            while (true) {
                delay(WAKE_LOCK_CHECK_MS)
                val wl = wakeLock ?: return@launch // onDestroy 已释放并置空 → 停止续租
                val now = System.currentTimeMillis()
                if (!wl.isHeld || now >= leaseEnd - WAKE_LOCK_CHECK_MS) {
                    // 先释放再申请（同一线程内连续两条语句，不产生可观察空窗）：
                    // 不依赖各 ROM 对「重复 acquire」的叠加/延长语义，租期确定可预期
                    runCatching {
                        if (wl.isHeld) wl.release()
                        wl.acquire(WAKE_LOCK_LEASE_MS)
                    }
                    leaseEnd = now + WAKE_LOCK_LEASE_MS
                }
            }
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
        wakeLockJob?.cancel() // 修 H：先停续租协程，避免刚释放又被续租回来
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        RecorderHub.stopLocationSource()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 修 I：自建的 scope 随 Service 一起取消（旧版只取消 observeJob，
        // 续租等其余协程会随 Service 泄漏）
        scope.cancel()
        super.onDestroy()
    }
}
