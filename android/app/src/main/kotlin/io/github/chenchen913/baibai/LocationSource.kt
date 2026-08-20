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
 * 双源坐标系网关（定位不准根因修复）：
 * 国产 ROM 的网络定位（WiFi/基站）由高德/百度服务提供，返回 GCJ-02 坐标；
 * GPS 返回 WGS-84。两源混入同一缓冲会导致：
 * - 首定 Home 用网络点（GPS 冷启动慢）→ Home 坐标系错，后续 GPS 点与 Home 相距 300~600m；
 * - 地图层对全部点做 WGS→GCJ 转换，网络点被二次转换 → 标记偏离道路约 500m。
 * 策略：GPS 点到达后 8s 内不再接受网络点；无 GPS 时才用网络点快速首定/室内兜底。
 */
class SystemLocationSource(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis, // 可注入时钟（测试用）
) : LocationSource {

    private val buffer = ArrayDeque<Fix>()
    private var last: Fix? = null
    private var running = false
    private var cb: LocationCallbacks? = null

    /** 最近一次 GPS 点到达时刻；网络点网关依据（volatile：定位回调在主线程，网关判断同线程，保险起见） */
    @Volatile
    private var lastGpsAt = 0L

    override val active: Boolean get() = running
    override val lastFix: Fix? get() = last

    override fun recent(n: Int): List<Fix> = buffer.takeLast(n)

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val isGps = loc.provider == LocationManager.GPS_PROVIDER
            if (isGps) {
                lastGpsAt = clock()
            } else {
                // 网络点网关：GPS 近期有点则丢弃（防 GCJ-02 混入 WGS-84 缓冲）；
                // 基站粗定位（acc > 300m）无参考价值，直接丢弃
                if (lastGpsAt > 0 && clock() - lastGpsAt < GPS_GRACE_MS) return
                if (loc.accuracy > NET_MAX_ACC_M) return
            }
            val f = Fix(LatLng(loc.latitude, loc.longitude), loc.accuracy.toDouble())
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
                last = Fix(LatLng(it.latitude, it.longitude), it.accuracy.toDouble())
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
        lastGpsAt = 0L // 网关复位：下次启动重新从"无 GPS"开始
    }

    companion object {
        /** GPS 点到达后的宽限窗口：窗口内网络点一律丢弃（防坐标系混用） */
        private const val GPS_GRACE_MS = 8_000L

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
