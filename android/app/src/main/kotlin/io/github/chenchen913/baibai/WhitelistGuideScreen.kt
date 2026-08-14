package io.github.chenchen913.baibai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chenchen913.baibai.WhitelistDeepLink.Brand

private val BrandCardBg = Color(0x66FFFFFF) // 白 40%
private val BrandCardBorder = Color(0x33FFFFFF) // 白 20%

/** 省电白名单引导（四层防杀之第三层：国产 ROM 决定性动作；首次开始拜年前展示一次）
 * 视觉参照 uxpilot-export/4-baibai - Guide.html
 *
 * 点任意厂商卡片 → 直达该品牌系统设置页（WhitelistDeepLink，多级兜底）。
 */
@Composable
fun WhitelistGuideScreen(onDone: () -> Unit, onDeepLink: (Brand) -> Unit) {
    BaibaiPage {
        Column(Modifier.fillMaxSize()) {
            // 上部可滚动区
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                Spacer(Modifier.height(32.dp))

                // 24sp 两行标题 + 副标题
                Text(
                    "锁屏记录前，\n先做这一步",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 34.sp,
                    color = BaibaiInk,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "安卓系统为了省电，可能在锁屏后杀掉后台记录。把「拜拜」加入省电白名单后，锁屏揣兜里也能持续记录。",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 20.sp,
                    color = BaibaiInk.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "点下方自己手机的卡片，可直接跳转对应设置页；跳不过去就按卡片上的路径手动设置。",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp,
                    color = BaibaiAccent,
                )

                Spacer(Modifier.height(24.dp))

                // §7.2：五张厂商卡片（整卡可点 → 直达系统设置）
                BrandCard(
                    brand = Brand.XIAOMI,
                    lines = listOf("设置 → 应用设置 → 拜拜 → 省电策略 → 无限制；并开启「自启动」"),
                    onDeepLink = onDeepLink,
                )
                Spacer(Modifier.height(12.dp))
                BrandCard(
                    brand = Brand.HUAWEI,
                    lines = listOf("设置 → 应用 → 应用启动管理 → 拜拜 → 手动管理 → 允许自启动/关联启动/后台活动"),
                    onDeepLink = onDeepLink,
                )
                Spacer(Modifier.height(12.dp))
                BrandCard(
                    brand = Brand.OPPO,
                    lines = listOf("设置 → 电池 → 更多设置 → 拜拜 → 允许后台运行；应用管理里开自启动"),
                    onDeepLink = onDeepLink,
                )
                Spacer(Modifier.height(12.dp))
                BrandCard(
                    brand = Brand.VIVO,
                    lines = listOf("i管家 → 电池 → 后台耗电管理 → 拜拜 → 允许后台高耗电"),
                    onDeepLink = onDeepLink,
                )
                Spacer(Modifier.height(12.dp))
                BrandCard(
                    brand = Brand.SAMSUNG,
                    lines = listOf("设置 → 电池 → 后台使用限制 → 拜拜 → 不休眠"),
                    onDeepLink = onDeepLink,
                )
                Spacer(Modifier.height(24.dp))
            }

            // 底部固定：64dp 红色主按钮（拇指热区，永不挤出屏幕）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(baibaiPrimaryGradient(), RoundedCornerShape(16.dp))
                        .clickable(onClick = onDone),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "我已完成设置，开始拜年",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandCard(brand: Brand, lines: List<String>, onDeepLink: (Brand) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandCardBg, RoundedCornerShape(RoundedBrand))
            .border(1.dp, BrandCardBorder, RoundedCornerShape(RoundedBrand))
            .clickable { onDeepLink(brand) }
            .padding(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(brand.label, fontSize = 14.sp, fontWeight = FontWeight.Black, color = BaibaiAccent)
                Spacer(Modifier.width(8.dp))
                Text(
                    "去设置",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = BaibaiAccent.copy(alpha = 0.85f),
                    modifier = Modifier
                        .background(BaibaiAccent.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            lines.forEachIndexed { i, line ->
                Text(
                    line,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = BaibaiInk.copy(alpha = 0.4f),
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = if (i == 0) 4.dp else 2.dp),
                )
            }
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = "跳转" + brand.label + "系统设置",
            tint = BaibaiInk.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp),
        )
    }
}
