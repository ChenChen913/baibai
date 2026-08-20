package io.github.chenchen913.baibai

import android.app.Application
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import io.github.chenchen913.baibai.core.errors.GpsErrorKind
import io.github.chenchen913.baibai.core.model.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 双源坐标系网关回归测试（定位不准根因）：
 * 国产 ROM 网络定位返回 GCJ-02，GPS 返回 WGS-84，混入同一缓冲会把 Home 定错坐标系，
 * 后续 GPS 点与 Home 偏离 300~600m。网关策略：GPS 点到达后 8s 内丢弃网络点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationSourceTest {

    class Collecting : LocationCallbacks {
        val fixes = mutableListOf<Fix>()
        val errors = mutableListOf<GpsErrorKind>()
        override fun onFix(f: Fix) {
            fixes += f
        }

        override fun onError(kind: GpsErrorKind, message: String) {
            errors += kind
        }
    }

    private lateinit var lm: LocationManager
    private lateinit var src: SystemLocationSource
    private lateinit var cb: Collecting

    /** 注入时钟：网关宽限窗口可确定性推进（不依赖真实墙钟） */
    private var nowMs = 1_000_000L

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        lm = app.getSystemService(Application.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).apply {
            setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        }
        src = SystemLocationSource(app) { nowMs }
        cb = Collecting()
        src.start(cb)
    }

    private fun gpsListener(): LocationListener? =
        shadowOf(lm).getRequestLocationUpdateListeners().firstOrNull()

    private fun push(provider: String, lat: Double, lng: Double, acc: Float) {
        val l = Location(provider)
        l.latitude = lat
        l.longitude = lng
        l.accuracy = acc
        l.time = nowMs
        // 直接调监听器回调（不依赖 Robolectric 版本的 ShadowLocationManager 派发 API）
        val listener = gpsListener() ?: error("未注册定位监听")
        listener.onLocationChanged(l)
    }

    @Test
    fun `无 GPS 时网络点放行（快速首定）`() {
        push(LocationManager.NETWORK_PROVIDER, 36.7, 119.1, 40f)
        assertEquals(1, cb.fixes.size)
        assertEquals(40.0, cb.fixes[0].acc, 0.01)
    }

    @Test
    fun `GPS 到达后 8 秒内网络点被丢弃（防 GCJ-02 混入）`() {
        push(LocationManager.GPS_PROVIDER, 36.7, 119.1, 8f)
        // 网络点（模拟国产 ROM 的 GCJ-02 偏移点）必须被网关拦截
        push(LocationManager.NETWORK_PROVIDER, 36.704, 119.105, 30f)
        assertEquals(1, cb.fixes.size)
        assertEquals(8.0, cb.fixes[0].acc, 0.01)
    }

    @Test
    fun `GPS 静默超过宽限窗口后网络点恢复放行（室内兜底）`() {
        push(LocationManager.GPS_PROVIDER, 36.7, 119.1, 8f)
        nowMs += 9_000 // 推进注入时钟越过 8s 宽限窗口
        push(LocationManager.NETWORK_PROVIDER, 36.701, 119.101, 50f)
        assertEquals(2, cb.fixes.size)
    }

    @Test
    fun `基站粗定位（acc 大于 300m）无 GPS 时也丢弃`() {
        push(LocationManager.NETWORK_PROVIDER, 36.7, 119.1, 800f)
        assertNull(src.lastFix)
        assertEquals(0, cb.fixes.size)
    }

    @Test
    fun `stop 清空缓冲并复位网关`() {
        push(LocationManager.GPS_PROVIDER, 36.7, 119.1, 8f)
        src.stop()
        assertEquals(0, src.recent(16).size)
        // 与网页版 P19 一致：清缓冲但保留 lastFix（供 IDLE 态地图居中）
        assertEquals(8.0, src.lastFix?.acc ?: -1.0, 0.01)
        // 网关已复位：重启后无 GPS 历史，网络点立即放行
        src.start(cb)
        push(LocationManager.NETWORK_PROVIDER, 36.701, 119.101, 50f)
        assertEquals(2, cb.fixes.size)
        src.stop()
    }
}
