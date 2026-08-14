package io.github.chenchen913.baibai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

/**
 * 省电白名单卡片 → 系统设置页跳转（v2）。
 *
 * 参考业界通行做法（Don't kill my app / StackOverflow "autostart for all manufacturers"）：
 *  1. 品牌专属组件（含国产 ROM 的自启动/省电策略/后台耗电页，按 ROM 版本给多组候选）；
 *  2. 系统"允许后台高耗电"直弹对话框（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，一键授权）；
 *  3. 通用「忽略电池优化」列表；
 *  4. 本应用详情页（任何机型必达）。
 * 每级失败自动落下一级，绝不无响应；同时把该品牌的设置路径复制到剪贴板，跳不过去也能照着做。
 */
object WhitelistDeepLink {

    enum class Brand(val label: String, val manualPath: String) {
        XIAOMI("小米 / 红米", "设置 → 应用设置 → 拜拜 → 省电策略 → 无限制；并开启「自启动」"),
        HUAWEI("华为 / 荣耀", "设置 → 应用 → 应用启动管理 → 拜拜 → 手动管理 → 允许自启动/关联启动/后台活动"),
        OPPO("OPPO / 一加", "设置 → 电池 → 更多设置 → 拜拜 → 允许后台运行；应用管理里开自启动"),
        VIVO("vivo / iQOO", "i管家 → 电池 → 后台耗电管理 → 拜拜 → 允许后台高耗电"),
        SAMSUNG("三星", "设置 → 电池 → 后台使用限制 → 拜拜 → 不休眠"),
    }

    /** 按 Build.MANUFACTURER 识别机型品牌（识别不到返回 null） */
    fun detectBrand(): Brand? {
        val m = Build.MANUFACTURER.lowercase(Locale.ROOT)
        return when {
            m.contains("xiaomi") || m.contains("redmi") -> Brand.XIAOMI
            m.contains("huawei") || m.contains("honor") -> Brand.HUAWEI
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") -> Brand.OPPO
            m.contains("vivo") || m.contains("iqoo") -> Brand.VIVO
            m.contains("samsung") -> Brand.SAMSUNG
            else -> null
        }
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
            "允许后台运行弹窗" to directAllow(ctx),
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
                "荣耀应用启动管理" to component(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
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
                "三星电池设置(国行)" to component(
                    "com.samsung.android.sm_cn",
                    "com.samsung.android.sm_cn.ui.battery.BatteryActivity",
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

    /** 系统"允许后台高耗电"直弹对话框（需 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限） */
    private fun directAllow(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:" + ctx.packageName)
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
