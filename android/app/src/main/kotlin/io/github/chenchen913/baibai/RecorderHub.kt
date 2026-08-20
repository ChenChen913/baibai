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
import io.github.chenchen913.baibai.core.model.Constants
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

    // 首个定位缓冲：攒 3 个高精度 GPS fix（或 10 秒超时）→ 中位数定 Home。
    // 网络点坐标系不可靠（国产 ROM 返回 GCJ-02）：混入会把 Home 定错坐标系，
    // 与后续 GPS 轨迹点相距 300~600m——人不动也画出一条长直线（R6 真机根因）；
    // 故就绪条件与 Home 采样都优先 GPS 点，仅 10s 内拿不到 GPS 时才用网络点兜底（室内）
    private val pendingFixes = mutableListOf<Fix>()
    private var pendingStartAt = 0L

    // R9（定位只做一次）：最近一次 fix 到达时刻——再次点「开始」时若缓存够新（10 分钟内）
    // 直接复用 source.lastFix 定 Home，不再重新攒 fix 等待。
    // 用户主诉：结束一次后再点「开始」又要重新等定位；拜年场景两次开始间隔通常只有几分钟。
    // 只复用 GPS 点（src=="net" 的 GCJ-02 网络点坐标系不可靠，R6 教训）
    private var lastFixArrivedAt = 0L

    companion object {
        private const val FIX_REUSE_MS = 10 * 60 * 1000L
    }

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
        // R9（定位只做一次）：10 分钟内有 GPS 定位 → 直接复用定 Home，秒开记录，不再重新等待
        val cached = source?.lastFix?.takeIf { it.src != "net" }
        if (cached != null && lastFixArrivedAt > 0 && nowMs() - lastFixArrivedAt <= FIX_REUSE_MS) {
            val fresh = RecorderState.fresh(_bizDate.value, pendingStartAt)
            try {
                fresh.start(listOf(cached), nowMs(), cached)
            } catch (e: Exception) {
                emit(e.message ?: "定位启动失败")
                return
            }
            recorder = fresh
            _waiting.value = false
            watchdogJob?.cancel()
            vibrate()
            beep()
            flushNow()
            _session.value = fresh.snapshot()
            // 复用只省「定 Home 的等待」；WALKING 的轨迹记录仍需立即恢复持续定位
            ensureSourceRunning()
            emit("定位已复用，开始记录！到一户按「暂停」")
            return
        }
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

    /**
     * R7：结束保存成功并跳转回顾页后调用——清掉 FINISHED 快照，记录页恢复「开始拜年」按钮。
     * 真机主诉：拜完一次年回记录页，主按钮永远消失（一天可拜多次年、一年拜十次年）。
     * 网页版无此问题（complete() 置 recorder=null，render(null) 即 IDLE）；
     * 安卓 _session 残留 FINISHED 快照导致 RecordScreen 按 FINISHED 分支不渲染按钮，此处对齐网页版语义。
     */
    fun consumeFinishedSession() {
        if (recorder == null && _session.value?.state == io.github.chenchen913.baibai.core.model.SessionState.FINISHED) {
            _session.value = null
        }
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
        lastFixArrivedAt = nowMs() // R9：记录 fix 到达时刻（供下次「开始」判断缓存时效）
        val r = recorder
        if (r == null && _waiting.value) {
            // 攒 3 个高精度 GPS fix（或 10 秒超时）→ 中位数定 Home；
            // src=="net" 的网络点坐标系不可靠（GCJ-02），不计入就绪数、不参与 Home 中位数，
            // 仅当 10s 内拿不到任何 GPS 点时才作为室内兜底使用
            pendingFixes.add(f)
            val t = nowMs()
            val gpsFixes = pendingFixes.filter { it.src != "net" }
            val goodGps = gpsFixes.count { it.acc <= Constants.GOOD_ACC_M }
            val ready = goodGps >= 3 || (pendingFixes.isNotEmpty() && t - pendingStartAt >= 10_000)
            if (ready) {
                val homeFixes = if (gpsFixes.isNotEmpty()) gpsFixes else pendingFixes
                val fresh = RecorderState.fresh(_bizDate.value, pendingStartAt) // R9：用时从点击「开始」起算（含等定位阶段）
                try {
                    fresh.start(homeFixes.toList(), t, f)
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
        // R9：等定位期间也计时（从点击「开始」那刻起）——用户主诉"点开始后用时一直是 0"，
        // 根因是旧版 waiting 阶段返回 0、且会话 createdAt 从拿到定位才起算，室内等 30 秒全程 00:00:00
        val s = _session.value
            ?: return if (_waiting.value) (nowMs() - pendingStartAt).coerceAtLeast(0) else 0
        // t0 用会话创建时刻（= 点击「开始」时刻，applyFix 创建时显式传入），不用 points[0].t——
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

    /** 仅测试用：清空运行时状态（可选保留存储，用于"崩溃恢复"场景模拟）。
     *  clearStore=true 同时清检查点与历史会话——RecorderHub 是跨用例单例、
     *  store 指向首用例的 filesDir（Robolectric 每用例换新 Application 但不删旧目录），
     *  不清 sessions 会跨用例残留，listSessions 断言依赖用例执行顺序。 */
    fun resetForTest(clearStore: Boolean) {
        recorder = null
        pendingFixes.clear()
        _waiting.value = false
        _pendingRestore.value = false
        _session.value = null
        _gpsAcc.value = null
        lastFixArrivedAt = 0 // R9：缓存定位时效一并重置，防跨用例污染
        watchdogJob?.cancel()
        if (clearStore) {
            runCatching {
                store.clearActive()
                store.clearSessions()
            }
        }
    }
}
