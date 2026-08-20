package io.github.chenchen913.baibai

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
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
 * GPS 静默超窗后的网络点仍会混入）；无 GPS 时才用网络点快速首定/室内兜底。
 */
class SystemLocationSource(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis, // 可注入时钟（测试用）
) : LocationSource {

    private val buffer = ArrayDeque<Fix>()
    private var last: Fix? = null
    private var running = false
    private var cb: LocationCallbacks? = null

    /** 本次运行是否到过 GPS 点；到过一次后网络点（GCJ-02）永久拒绝——
     * 旧「8s 宽限窗口」有致命漏洞：首个 GPS 点到达**前**的网络点照样进缓冲，
     * GPS 静默超窗后网络点又混回来，两套坐标系的点各聚一团 → 人不动也画出长直线（真机复现） */
    @Volatile
    private var everGotGps = false

    override val active: Boolean get() = running
    override val lastFix: Fix? get() = last

    override fun recent(n: Int): List<Fix> = buffer.takeLast(n)

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val isGps = loc.provider == LocationManager.GPS_PROVIDER
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
        try {
            // GPS + 网络双源：GPS 民码误差大/首定慢，网络定位（WiFi/基站）互补；
            // 两个源的点一起进缓冲做中位数，Home/户点/结束判定都更稳（实机 17m 偏差主因之一）
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                1000L,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            running = true
            @Suppress("MissingPermission")
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                last = Fix(LatLng(it.latitude, it.longitude), it.accuracy.toDouble(), "gps")
            }
        } catch (e: SecurityException) {
            cb.onError(GpsErrorKind.DENIED, e.message ?: "no permission")
        } catch (e: Exception) {
            cb.onError(GpsErrorKind.UNAVAILABLE, e.message ?: "unavailable")
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        lm.removeUpdates(listener)
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
