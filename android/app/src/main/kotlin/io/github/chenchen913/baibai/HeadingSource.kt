package io.github.chenchen913.baibai

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.chenchen913.baibai.core.model.LatLng

/**
 * 朝向源（v1.0.10「探照灯」）：ROTATION_VECTOR 传感器 → 真北方位角（度，顺时针，0=正北）。
 *
 * 原理（高德/百度同款）：
 * - ROTATION_VECTOR 是系统已融合磁力计+加速度计的姿态传感器，无需自己写互补滤波；
 * - 方位角取「用户视线方向」（设备 -Z 轴 = 屏幕法线反方向）的水平投影：
 *   竖持手机看地图时视线沿 -Z，绕竖轴转动手机方位角正确跟随（atan2(-Z东, -Z北)）；
 *   ——不用 remapCoordinateSystem(AXIS_X, AXIS_Z) + getOrientation 的官方 compass 写法，
 *   该组合给出的是屏幕法线（=视线反方向）方位，竖持面北会算出 180°，差半圈；
 * - GeomagneticField 按当前位置查磁偏角（真北 vs 磁北，国内约 -7°~+11°），
 *   不校正的话光锥会系统性偏转——必须加；
 * - 节流：传感器 ~50Hz，但只有角度变化 ≥2° 且距上次推送 ≥66ms（≤15Hz）才回调，
 *   省电 + WebView evaluateJavascript 开销可控。
 *
 * 已知边界（可接受）：手机平放桌面低头看（屏幕朝天）时 -Z 指天、无水平分量，
 * 方位角退化为 0——拜年场景是竖持走动看屏，此姿势罕见。
 *
 * 生命周期：进入记录页 start()（传感器存在才注册），离开 stop()。
 * 无 ROTATION_VECTOR 的设备（极老机型/模拟器）hasSensor=false，不注册——
 * UI 侧拿不到任何回调 → 光锥保持隐藏（静默降级，不报错不打扰）。
 *
 * 线程：start() 恒在主线程调用（DisposableEffect），registerListener 默认绑定
 * 调用线程的 Looper → onSensorChanged 在主线程回调，直接 evaluateJavascript 安全
 * （与现有 MapController.exec 同链路）。
 */
class HeadingSource(
    context: Context,
    private val positionProvider: () -> LatLng?, // 拿最新定位算磁偏角（拿不到时不校正，误差≤11°可接受）
    private val clock: () -> Long = System::currentTimeMillis,
) : SensorEventListener {

    /** 是否有可用传感器（无则 start() 静默不注册，光锥不显示） */
    val hasSensor: Boolean

    /** 真北方位角回调（度，0~360 顺时针）；主线程 */
    var onHeading: ((Double) -> Unit)? = null

    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor: Sensor?

    private var running = false
    private var lastPushAt = 0L
    private var lastPushDeg = Double.NaN

    init {
        rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        hasSensor = rotSensor != null
    }

    fun start() {
        val s = rotSensor ?: return
        if (running) return
        running = true
        // 不显式传 Handler：默认用调用线程的 Looper——start() 恒在主线程（DisposableEffect）调用，
        // onSensorChanged 即主线程回调。同时在 companion 里预建 Handler 会让单测类加载即崩
        // （ExceptionInInitializerError：JVM 无 Looper），故不用。
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        running = false
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return
        val deg = computeHeadingDeg(event.values, magneticDeclinationDeg())
        if (deg.isNaN()) return
        val now = clock()
        // 节流：角度变化 <2° 不推（光锥无需更新）；变化够大也至少间隔 66ms（≤15Hz）
        if (!lastPushDeg.isNaN() && Math.abs(wrapDelta(deg - lastPushDeg)) < 2.0) return
        if (now - lastPushAt < 66) return
        lastPushAt = now
        lastPushDeg = deg
        onHeading?.invoke(deg)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** 当前位置的磁偏角（度；无定位返回 0 = 不校正） */
    private fun magneticDeclinationDeg(): Double {
        val p = positionProvider() ?: return 0.0
        return try {
            val gf = android.hardware.GeomagneticField(
                p.lat.toFloat(),
                p.lng.toFloat(),
                50f, // 海拔（米）：对磁偏角影响极小（<0.1°），给常量即可
                clock(),
            )
            gf.declination.toDouble()
        } catch (_: Exception) {
            0.0
        }
    }

    companion object {

        /** 最短弧差：把任意角度差折到 [-180, 180]（359°→1° 的差是 +2°，不是 -358°） */
        fun wrapDelta(deltaDeg: Double): Double {
            var d = (deltaDeg + 180.0) % 360.0
            if (d < 0) d += 360.0
            return d - 180.0
        }

        /**
         * ROTATION_VECTOR 值 → 真北方位角（度，0~360 顺时针）。
         * 纯函数（标准四元数数学，不依赖 SensorManager native 方法——Robolectric 单测可直接跑）。
         *
         * 数学：rotation vector = 四元数 (x,y,z,w)，表示设备→East-North-Up 世界的旋转；
         * 旋转矩阵 R（设备→世界）第 3 列 = 设备 Z 轴（屏幕法线）的世界方向：
         *   Z_world = (2(xz+wy), 2(yz-wx), 1-2(x²+y²))
         * 用户竖持看屏的视线 = -Z，其水平投影方位 = atan2(-Z东, -Z北)。
         *
         * @param rv ROTATION_VECTOR 传感器 values（≥4 元：x,y,z,cos(θ/2)，部分 ROM 带第 5 元磁精度）
         * @param declinationDeg 磁偏角（度，东偏为正）
         */
        fun computeHeadingDeg(rv: FloatArray, declinationDeg: Double): Double {
            if (rv.size < 4) return Double.NaN
            val x = rv[0].toDouble()
            val y = rv[1].toDouble()
            val z = rv[2].toDouble()
            val w = rv[3].toDouble()
            // 设备 Z 轴（屏幕法线）的世界方向（东、北分量）
            val zEast = 2.0 * (x * z + w * y)
            val zNorth = 2.0 * (y * z - w * x)
            // 视线 = -Z：方位角 = atan2(视线东分量, 视线北分量)
            var deg = Math.toDegrees(Math.atan2(-zEast, -zNorth)) + declinationDeg
            deg %= 360.0
            if (deg < 0) deg += 360.0
            return deg
        }
    }
}
