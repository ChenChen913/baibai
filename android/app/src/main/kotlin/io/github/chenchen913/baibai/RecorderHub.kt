package io.github.chenchen913.baibai

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.chenchen913.baibai.core.errors.GpsErrorKind
import io.github.chenchen913.baibai.core.model.Fix
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.state.FinishResult
import io.github.chenchen913.baibai.core.state.RecorderState
import io.github.chenchen913.baibai.core.store.JsonStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 全局记录中枢（对应网页版 main.ts 的职责）。
 * 定位回调线程与 UI 线程都经过这里；RecorderState 自带 @Synchronized 保证并发安全。
 * A-M1 第 2 步会把定位迁入前台服务（锁屏持续），本类接口不变。
 */
object RecorderHub {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _session = MutableStateFlow<SessionData?>(null)
    val session: StateFlow<SessionData?> = _session

    private val _waiting = MutableStateFlow(false) // 开始拜年后等待首个定位
    val waiting: StateFlow<Boolean> = _waiting

    private val _gpsAcc = MutableStateFlow<Double?>(null)
    val gpsAcc: StateFlow<Double?> = _gpsAcc

    private val _pendingRestore = MutableStateFlow(false)
    val pendingRestore: StateFlow<Boolean> = _pendingRestore

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: MutableSharedFlow<String> = _messages

    private val _finishTooFar = MutableStateFlow<Double?>(null)
    val finishTooFar: StateFlow<Double?> = _finishTooFar

    // P6/D19 R3：震动 + 提示音（默认开启，可关）
    private val _feedbackOn = MutableStateFlow(true)
    val feedbackOn: StateFlow<Boolean> = _feedbackOn

    // 用户选定的拜年日期（默认今天；记录页徽章可改）
    private val _bizDate = MutableStateFlow(LocalDate.now())
    val bizDate: StateFlow<LocalDate> = _bizDate

    lateinit var store: JsonStore
        private set
    var source: LocationSource? = null // 测试可注入 FakeSource
    var recorder: RecorderState? = null
        private set
    var useForegroundService = true // 测试可关闭（避免测试里嵌套启动服务）

    private var context: Context? = null
    private var watchdogJob: Job? = null
    private var flushTimerStarted = false
    private var lastSessionEmit = 0L // M-5：定位 fix 的 session 快照节流（5s）

    // 首个定位缓冲：攒 3 个 fix（或 3 秒）取中位数定 Home——单个 fix 噪声大（±10m+）
    private val pendingFixes = mutableListOf<Fix>()
    private var pendingStartAt = 0L

    fun init(app: Application) {
        if (this::store.isInitialized) return
        context = app.applicationContext
        store = JsonStore(app.filesDir)
        source = SystemLocationSource(app.applicationContext)
        val prefs = app.applicationContext
            .getSharedPreferences("baibai_prefs", Context.MODE_PRIVATE)
        _feedbackOn.value = prefs.getBoolean("feedback_on", true)
        _bizDate.value = prefs.getString("biz_date", null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
        // D22：每 10s 检查点落盘
        if (!flushTimerStarted) {
            flushTimerStarted = true
            scope.launch {
                while (true) {
                    delay(10_000)
                    flushNow()
                }
            }
        }
    }

    /** 用户选择拜年日期（记录页徽章弹出日期选择器后回调） */
    fun setBizDate(d: LocalDate) {
        _bizDate.value = d
        context?.getSharedPreferences("baibai_prefs", Context.MODE_PRIVATE)
            ?.edit()?.putString("biz_date", d.toString())?.apply()
    }

    /** 全局轻提示入口（AppRoot 顶部胶囊） */
    fun toast(msg: String) = emit(msg)

    /** P6：切换震动/提示音开关 */
    fun setFeedback(on: Boolean) {
        _feedbackOn.value = on
        context?.getSharedPreferences("baibai_prefs", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("feedback_on", on)?.apply()
    }

    /** 启动检查：发现未完成检查点 → 弹"继续/放弃"。H-2：已在记录的进程内（如旋转重建）不再重复弹窗 */
    fun boot() {
        if (recorder != null) return
        val ck = runCatching { store.loadActive() }.getOrNull()
        if (ck != null && !ck.session.finished) {
            _pendingRestore.value = true
            _session.value = ck.session
        }
    }

    fun resumeCheckpoint() {
        val ck = runCatching { store.loadActive() }.getOrNull() ?: return
        recorder = RecorderState.restore(ck)
        _session.value = recorder?.snapshot()
        _pendingRestore.value = false
        _gpsAcc.value = source?.lastFix?.acc
        if (recorder?.currentState == io.github.chenchen913.baibai.core.model.SessionState.WALKING) {
            ensureSourceRunning()
        }
    }

    fun abandonCheckpoint() {
        runCatching { store.clearActive() }
        _pendingRestore.value = false
        _session.value = null
    }

    /** 供 LocationService 调用：确保定位源运行（幂等） */
    fun ensureSourceRunning() {
        val src = source ?: return
        if (src.active) return
        src.start(
            object : LocationCallbacks {
                override fun onFix(f: Fix) = applyFix(f)
                override fun onError(kind: GpsErrorKind, message: String) = handleGpsError(kind)
            },
        )
    }

    /** 供 LocationService 调用：停定位源 */
    fun stopLocationSource() {
        source?.stop()
    }

    /** START_STICKY 重启后的静默恢复（无弹窗——用户可能不在前台） */
    fun autoResumeFromCheckpoint() {
        if (recorder != null) return
        val ck = runCatching { store.loadActive() }.getOrNull() ?: return
        if (ck.session.finished) return
        recorder = RecorderState.restore(ck)
        _session.value = recorder?.snapshot()
        if (recorder?.currentState == io.github.chenchen913.baibai.core.model.SessionState.WALKING) {
            ensureSourceRunning()
        }
    }

    // ---------- 按钮动作（UI 线程） ----------

    fun startPressed() {
        if (recorder != null) return
        pendingFixes.clear()
        pendingStartAt = nowMs()
        startKeepAliveService()
        _waiting.value = true
        ensureSourceRunning()
        vibrate()
        beep()
        emit("正在获取定位…")
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(30_000)
            if (_waiting.value && recorder == null) {
                emit("还没拿到定位：请检查定位权限、是否在室内")
            }
        }
    }

    fun pausePressed() {
        val r = recorder ?: return
        try {
            r.pause(source?.recent(10) ?: emptyList(), nowMs())
            stopLocationSource() // SPEC §7：PAUSED 停定位（省电 + 防屋内漂移）
            vibrate()
            beep()
            flushNow()
            _session.value = r.snapshot()
        } catch (e: Exception) { // M-7：除非法状态外，无定位/空 visits 等也不崩溃
            emit(e.message ?: "操作失败")
        }
    }

    fun resumePressed() {
        val r = recorder ?: return
        try {
            r.resume(nowMs())
            ensureSourceRunning()
            vibrate()
            beep()
            flushNow()
            _session.value = r.snapshot()
        } catch (e: Exception) { // M-7
            emit(e.message ?: "操作失败") // 双击等非法转移不再崩溃
        }
    }

    fun undoPressed() {
        val r = recorder ?: return
        if (r.undo()) {
            flushNow()
            _session.value = r.snapshot()
        } else {
            emit("没有可撤销的操作")
        }
    }

    /** 返回 TooFar 时由 UI 弹确认，确认后带 force=true 再调 */
    fun finishPressed(force: Boolean = false): FinishResult? {
        val r = recorder ?: return null
        val res = try {
            r.finish(source?.recent(10) ?: emptyList(), nowMs(), force)
        } catch (e: IllegalStateException) {
            emit(e.message ?: "操作失败") // 双击等非法转移不再崩溃
            return null
        }
        if (res is FinishResult.Ok) {
            _finishTooFar.value = null
            stopLocationSource()
            stopKeepAliveService()
            val saved = r.snapshot()
            val saveOk = runCatching { store.saveSession(saved) }.isSuccess
            if (saveOk) {
                runCatching { store.clearActive() }
                recorder = null
                _waiting.value = false
                watchdogJob?.cancel()
                vibrate()
                beep()
                _session.value = saved
                emit("已保存本次拜年 🎉")
            } else {
                // C-1 修复：保存失败绝不删检查点、绝不假装成功。
                // 磁盘上的检查点仍是结束前的未完成状态（本函数不再 flush 结束态），
                // 清空内存 recorder 后由「继续/放弃」弹窗恢复，用户可清理空间后重试结束。
                recorder = null
                _waiting.value = false
                watchdogJob?.cancel()
                _session.value = null
                _pendingRestore.value = true
                emit("保存失败：本场记录已保留为未完成记录，请清理存储空间后重试结束")
            }
        } else if (res is FinishResult.TooFar) {
            _finishTooFar.value = res.distM // UI 弹"距 Home X 米，仍要结束？"
        }
        return res
    }

    fun dismissFinishTooFar() {
        _finishTooFar.value = null
    }

    fun setMode(mode: Mode) {
        val r = recorder ?: return
        r.setMode(mode, nowMs())
        flushNow()
        _session.value = r.snapshot()
        emit(if (mode == Mode.BIKE) "下一段将骑行前往，到户自动回走路" else "已切回步行")
    }

    // ---------- 定位回调（源线程） ----------

    @Synchronized
    fun applyFix(f: Fix) {
        _gpsAcc.value = f.acc
        val r = recorder
        if (r == null && _waiting.value) {
            // 攒 3 个 fix（或 3 秒）→ 中位数定 Home；单个 fix 噪声太大
            pendingFixes.add(f)
            val t = nowMs()
            if (pendingFixes.size >= 3 || t - pendingStartAt >= 3000) {
                val fresh = RecorderState.fresh(_bizDate.value)
                try {
                    fresh.start(pendingFixes.toList(), t, f)
                } catch (e: Exception) {
                    pendingFixes.clear()
                    emit(e.message ?: "定位启动失败")
                    return
                }
                recorder = fresh
                _waiting.value = false
                watchdogJob?.cancel()
                vibrate()
                flushNow()
                _session.value = fresh.snapshot()
                pendingFixes.clear()
                emit("开始记录！到一户按「暂停」")
            }
            return
        }
        if (r != null) {
            try {
                val t = nowMs()
                r.addPoint(f.pos, f.acc, t)
                // M-5：每秒 fix 不再整树重组——session 快照节流到 5s（状态/户数变化由各动作单独发射）
                if (t - lastSessionEmit >= 5000) {
                    lastSessionEmit = t
                    _session.value = r.snapshot()
                }
            } catch (_: IllegalStateException) {
                /* 非 WALKING 状态，忽略 */
            }
        }
    }

    fun handleGpsError(kind: GpsErrorKind) {
        emit(
            when (kind) {
                GpsErrorKind.UNSUPPORTED -> "此设备不支持定位"
                GpsErrorKind.DENIED -> "定位权限被拒绝：请在系统设置中允许定位后重试"
                GpsErrorKind.UNAVAILABLE -> "无法获取定位：请确认系统定位服务已开启、人在开阔处"
                GpsErrorKind.TIMEOUT -> "定位超时：请到室外开阔处重试"
            },
        )
    }

    fun flushNow() {
        val r = recorder ?: return
        runCatching { store.saveActive(r.checkpoint()) }
    }

    fun elapsedMs(): Long {
        val s = _session.value ?: return 0
        // t0 用会话创建时刻（首个定位时刻），不用 points[0].t——
        // 首点迟到会让计时倒退（实机复现：定位稀疏时时间回跳/停滞）
        return (nowMs() - s.createdAt).coerceAtLeast(0)
    }

    // ---------- 内部 ----------

    private fun startKeepAliveService() {
        if (!useForegroundService) return
        val c = context ?: return
        runCatching {
            val i = android.content.Intent(c, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
    }

    private fun stopKeepAliveService() {
        if (!useForegroundService) return
        val c = context ?: return
        runCatching { c.stopService(android.content.Intent(c, LocationService::class.java)) }
    }

    private fun vibrate() {
        if (!_feedbackOn.value) return
        val c = context ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE),
                )
            } else {
                @Suppress("DEPRECATION")
                val v = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    /** P6：轻提示音（50ms 短哔，跟随开关） */
    private fun beep() {
        if (!_feedbackOn.value) return
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
            scope.launch {
                delay(150)
                runCatching { tg.release() }
            }
        }
    }

    private fun emit(msg: String) {
        scope.launch { _messages.emit(msg) }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    // ---------- 测试辅助 ----------

    /** 仅测试用：清空运行时状态（可选保留存储，用于"崩溃恢复"场景模拟） */
    fun resetForTest(clearStore: Boolean) {
        recorder = null
        pendingFixes.clear()
        _waiting.value = false
        _pendingRestore.value = false
        _session.value = null
        _gpsAcc.value = null
        watchdogJob?.cancel()
        if (clearStore) {
            runCatching { store.clearActive() }
        }
    }
}
