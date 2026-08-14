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

/** 系统 LocationManager（GPS 优先，1s 间隔；零 Key 零依赖，先跑通全链路） */
class SystemLocationSource(private val context: Context) : LocationSource {

    private val buffer = ArrayDeque<Fix>()
    private var last: Fix? = null
    private var running = false
    private var cb: LocationCallbacks? = null

    override val active: Boolean get() = running
    override val lastFix: Fix? get() = last

    override fun recent(n: Int): List<Fix> = buffer.takeLast(n)

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val f = Fix(LatLng(loc.latitude, loc.longitude), loc.accuracy.toDouble())
            last = f
            buffer.addLast(f)
            while (buffer.size > 8) buffer.removeFirst()
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
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
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
