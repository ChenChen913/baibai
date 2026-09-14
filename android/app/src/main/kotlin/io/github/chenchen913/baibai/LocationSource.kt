package io.github.chenchen913.baibai

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import io.github.chenchen913.baibai.core.debug.RawLocationInfo
import io.github.chenchen913.baibai.core.errors.GpsErrorKind
import io.github.chenchen913.baibai.core.model.Fix
import io.github.chenchen913.baibai.core.model.LatLng

/** 定位源抽象（为高德/RTK 预留；A-M1 用系统 LocationManager 免 Key） */
interface LocationCallbacks {
    fun onFix(f: Fix)
    fun onError(kind: GpsErrorKind, message: String)
}

interface LocationSource {
    val active: Boolean
    val lastFix: Fix?
    fun recent(n: Int): List<Fix>
    fun start(cb: LocationCallbacks)
    fun stop()
}

/**
 * 系统 LocationManager（GPS 优先，1s 间隔；零 Key 零依赖，先跑通全链路）
 *
 * 双源坐标系网关（定位不准根因修复，R6 加固）：
 * 国产 ROM 的网络定位（WiFi/基站）由高德/百度服务提供，返回 GCJ-02 坐标；
 * GPS 返回 WGS-84。两源混入同一缓冲会导致：
 * - 首定 Home 用网络点（GPS 冷启动慢）→ Home 坐标系错，后续 GPS 点与 Home 相距 300~600m；
 * - 轨迹混入两套坐标系的点 → 人不动也画出一条长直线（真机 v1.0.3 复现）。
 * 策略（R6）：GPS 到过一次后网络点**永久**拒绝（旧 8s 宽限窗口有漏洞：首个 GPS 点到达前、
 * GPS 静默超窗后的网络点仍会混入）；无 GPS 时网络点仅放行供 gpsAcc 显示/室内兜底提示，
 * 不再参与 Home 定界（修 C 后 Home 一律只由 GPS 点中位数确定，见 RecorderHub.applyFix）。
 */
class SystemLocationSource(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis, // 可注入时钟（测试用）
) : LocationSource {

    private val buffer = ArrayDeque<Fix>()
    private var last: Fix? = null
    private var running = false
    private var cb: LocationCallbacks? = null

    /** 修 B：已成功注册的 provider 集合。
     *  running 由"是否有源注册成功"决定（任一成功即视为可用），stop() 据此无条件摘除监听——
     *  旧版 running 写在两次 requestLocationUpdates 之后：GPS 注册成功而网络注册抛异常时 running 仍为 false，
     *  stop() 首行 `if (!running) return` 直接早退 → lm.removeUpdates(listener) 永不执行，
     *  GPS 监听器永久泄漏（耗电），且 active 恒 false 导致反复重注册。 */
    private val registered = mutableSetOf<String>()

    /** 本次运行是否到过 GPS 点；到过一次后网络点（GCJ-02）永久拒绝——
     * 旧「8s 宽限窗口」有致命漏洞：首个 GPS 点到达**前**的网络点照样进缓冲，
     * GPS 静默超窗后网络点又混回来，两套坐标系的点各聚一团 → 人不动也画出长直线（真机复现） */
    @Volatile
    private var everGotGps = false

    /** 定位实验：原始 Location 旁路回调。默认 null（关闭时不产生任何开销）。
     *  在源侧网关判断**之前**调用，因此被网关丢弃的点也能被记录。 */
    var debugSink: ((RawLocationInfo) -> Unit)? = null

    override val active: Boolean get() = running
    override val lastFix: Fix? get() = last

    override fun recent(n: Int): List<Fix> = buffer.takeLast(n)

    private val listener = object : LocationListener {
        // 定位实验：isFromMockProvider() 在 API 31 起被标记废弃（改用 isMock()），
        // 但我们仍读旧 API 以兼容 minSdk 26 —— 用函数级 @Suppress 收掉废弃告警，
        // 不能把 @Suppress 内联写在表达式里（Kotlin 语法不支持）
        @Suppress("DEPRECATION")
        override fun onLocationChanged(loc: Location) {
            val isGps = loc.provider == LocationManager.GPS_PROVIDER
            // 定位实验（GPS Debug）：原始定位旁路。位置必须在 isGps 之后、网关判断之前——
            // 网关的 return（everGotGps / NET_MAX_ACC_M）之前调用，被丢弃的点才能记录到。
            // 该调用只把数据交给 GpsDebugRecorder，不改动下面任何网关逻辑。
            debugSink?.invoke(
                RawLocationInfo(
                    receivedAtMs = clock(),
                    fixTimeMs = loc.time,
                    elapsedRealtimeNanos = loc.elapsedRealtimeNanos,
                    lat = loc.latitude,
                    lng = loc.longitude,
                    accuracy = loc.accuracy.toDouble(),
                    provider = loc.provider ?: "unknown",
                    src = if (isGps) "gps" else "net",
                    speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                    bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                    altitudeM = if (loc.hasAltitude()) loc.altitude else null,
                    isMock = loc.isFromMockProvider(),
                ),
            )
            if (isGps) {
                if (!everGotGps) {
                    everGotGps = true
                    // 首个 GPS 点：清掉此前已进缓冲的网络点（GCJ-02），
                    // 保证 Home 定界/暂停/结束/轨迹用的缓冲坐标系纯净（全 WGS-84）
                    buffer.removeAll { it.src == "net" }
                }
            } else {
                // 网络点网关：GPS 到过一次即永久拒绝（防 GCJ-02 混入 WGS-84）；
                // 基站粗定位（acc > 300m）无参考价值，直接丢弃
                if (everGotGps) return
                if (loc.accuracy > NET_MAX_ACC_M) return
            }
            val f = Fix(
                LatLng(loc.latitude, loc.longitude),
                loc.accuracy.toDouble(),
                if (isGps) "gps" else "net",
            )
            last = f
            buffer.addLast(f)
            while (buffer.size > 16) buffer.removeFirst()
            cb?.onFix(f)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    override fun start(cb: LocationCallbacks) {
        if (running) return
        this.cb = cb
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            cb.onError(GpsErrorKind.UNAVAILABLE, "定位服务不可用")
            return
        }
        // 修 A：GPS 与 NETWORK 拆成各自独立的注册（修 A/B 见 registerProvider）——
        // 旧版两个 requestLocationUpdates 塞在同一个 try：Android 12+ 用户只给"大致位置"(COARSE) 时
        // 先注册的 GPS 会抛 SecurityException 直接跳到 catch，NETWORK 永不注册 → 用户完全拿不到定位
        // （MainActivity 的权限判定接受仅 COARSE）。故先注册 NETWORK（COARSE 权限即可满足），再注册 GPS；
        // 只要任一成功就认为定位源可用，失败的源只记错误、不打断另一个。
        var err = registerProvider(lm, LocationManager.NETWORK_PROVIDER)
        val gpsErr = registerProvider(lm, LocationManager.GPS_PROVIDER)
        // 网络源已可用时不上报 GPS 的失败：仅 COARSE 权限属正常情形，误报权限错误会吓到用户
        if (err == null) err = gpsErr
        if (registered.isEmpty()) {
            // 修 A：两个源全部注册失败 → 上报（优先权限文案），running 保持 false
            cb.onError(err?.first ?: GpsErrorKind.UNAVAILABLE, err?.second ?: "unavailable")
            return
        }
        // 末次已知 GPS 点：仅作 IDLE 态地图居中/首定复用的参考。
        // 只在 GPS 源注册成功（说明具备 FINE 权限、坐标未被模糊化）时才取——与旧版语义一致
        // （旧版靠同一个 try 兜住 SecurityException，拆分后必须自带保护，否则异常会逃出 start()）
        if (registered.contains(LocationManager.GPS_PROVIDER)) {
            try {
                @Suppress("MissingPermission")
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    last = Fix(LatLng(it.latitude, it.longitude), it.accuracy.toDouble(), "gps")
                }
            } catch (_: SecurityException) {
                // 取不到历史点不影响本次启动（仅少了 IDLE 态地图居中参考）
            }
        }
    }

    /**
     * 修 A/B：注册单个 provider，每个 provider 各自独立的 try/catch。
     * 成功 → 记入 registered、置 running=true 并返回 null；失败 → 返回 (错误类型, 信息)，不影响另一个源。
     * 修 B：running 在**每次注册成功时**立刻置位，保证 stop() 一定能摘掉已注册的监听。
     */
    private fun registerProvider(lm: LocationManager, provider: String): Pair<GpsErrorKind, String>? {
        try {
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
            registered.add(provider)
            running = true
            return null
        } catch (e: SecurityException) {
            return GpsErrorKind.DENIED to (e.message ?: "no permission")
        } catch (e: Exception) {
            return GpsErrorKind.UNAVAILABLE to (e.message ?: "unavailable")
        }
    }

    override fun stop() {
        // 修 B：去掉首行 `if (!running) return` 早退——旧版 GPS 注册成功、NETWORK 注册抛异常
        // （部分 ROM 无该 provider 会抛 IllegalArgumentException）时 running 仍为 false，
        // stop() 直接返回 → 监听器永久泄漏（耗电）。现在无条件摘除监听并复位状态。
        running = false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null) {
            lm.removeUpdates(listener)
        }
        registered.clear()
        buffer.clear() // 与网页版 P19 一致：停定位时清空缓冲，避免恢复后 immediate pause 混入旧点
        everGotGps = false // 网关复位：下次启动重新从"无 GPS"开始
    }

    companion object {
        /** 网络点精度上限：基站粗定位超过此值无参考价值 */
        private const val NET_MAX_ACC_M = 300.0
    }
}

/**
 * 高德定位占位（A-M1 之后、Key 到位时实现）：
 * 申请 Key → 填入 AndroidManifest meta-data → 用 AMapLocationClient 实现本接口（连续定位 1s）。
 */
class AmapLocationSource : LocationSource {
    override val active: Boolean get() = false
    override val lastFix: Fix? get() = null
    override fun recent(n: Int): List<Fix> = emptyList()
    override fun start(cb: LocationCallbacks) {
        cb.onError(GpsErrorKind.UNSUPPORTED, "高德定位未接入（待 Key）")
    }

    override fun stop() = Unit
}
