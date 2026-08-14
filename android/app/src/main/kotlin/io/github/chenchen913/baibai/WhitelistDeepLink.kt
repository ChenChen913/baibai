package io.github.chenchen913.baibai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 省电白名单卡片 → 系统设置页跳转。
 *
 * 策略（每级都有兜底，绝不无响应）：
 *  1. 品牌专属组件（国产 ROM 的自启动/省电策略/后台耗电页）；
 *  2. 通用「忽略电池优化」列表（Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS）；
 *  3. 本应用详情页（Settings.ACTION_APPLICATION_DETAILS_SETTINGS，任何机型必达）。
 *
 * 组件名随 ROM 版本可能变化：startActivity 失败（ActivityNotFoundException / SecurityException）
 * 时自动落到下一级，最后一级（应用详情页）在所有 Android 设备上必然存在。
 */
object WhitelistDeepLink {

    enum class Brand(val label: String) {
        XIAOMI("小米 / 红米"),
        HUAWEI("华为 / 荣耀"),
        OPPO("OPPO / 一加"),
        VIVO("vivo / iQOO"),
        SAMSUNG("三星"),
    }

    /** 打开对应品牌的系统设置页；返回实际打开的目标描述（用于轻提示） */
    fun open(ctx: Context, brand: Brand): String {
        for ((label, intent) in candidates(brand, ctx)) {
            if (runCatching { ctx.startActivity(intent) }.isSuccess) return label
        }
        return "系统设置"
    }

    /** 品牌 → 有序候选（先专属后通用） */
    private fun candidates(brand: Brand, ctx: Context): List<Pair<String, Intent>> {
        val fallback = listOf(
            "电池优化列表" to batteryList(),
            "应用详情" to appDetails(ctx),
        )
        return when (brand) {
            Brand.XIAOMI -> listOf(
                "小米省电策略" to component("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
                "小米自启动管理" to component(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
                "小米自启动管理" to miuiAutoStart(ctx.packageName),
            ) + fallback

            Brand.HUAWEI -> listOf(
                "华为应用启动管理" to component(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                "华为应用启动管理" to component(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                ),
                "华为受保护应用" to component(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
            ) + fallback

            Brand.OPPO -> listOf(
                "OPPO 自启动管理" to component(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                ),
                "一加自启动管理" to component(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                ),
                "OPPO 耗电保护" to component(
                    "com.oplus.battery",
                    "com.oplus.battery.ui.activity.NewHighPowerDetailActivity",
                ),
            ) + fallback

            Brand.VIVO -> listOf(
                "vivo 后台耗电管理" to component(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                "iQOO 后台启动管理" to component(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                ),
            ) + fallback

            Brand.SAMSUNG -> listOf(
                "三星电池设置" to component(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
            ) + fallback
        }
    }

    private fun component(pkg: String, cls: String): Intent =
        Intent().apply {
            component = ComponentName(pkg, cls)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** MIUI 自启动直达 action（带包名参数；部分 MIUI 版本仅此方式可直达） */
    private fun miuiAutoStart(pkgName: String): Intent =
        Intent("miui.intent.action.OP_AUTO_START").apply {
            putExtra("extra_pkgname", pkgName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun appDetails(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:" + ctx.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun batteryList(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
