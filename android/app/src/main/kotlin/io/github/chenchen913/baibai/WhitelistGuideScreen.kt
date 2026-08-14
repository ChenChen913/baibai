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
import androidx.compose.runtime.remember
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
 * 点任意厂商卡片 → 直达该品牌系统设置页（多级兜底）；自动识别机型并把你的品牌卡片置顶。
 */
@Composable
fun WhitelistGuideScreen(onDone: () -> Unit, onDeepLink: (Brand) -> Unit, detected: Brand? = null) {
    // 识别到机型 → 该品牌卡片置顶并打「你的机型」标
    val brands = remember(detected) {
        val all = listOf(Brand.XIAOMI, Brand.HUAWEI, Brand.OPPO, Brand.VIVO, Brand.SAMSUNG)
        if (detected != null) listOf(detected) + all.filter { it != detected } else all
    }

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
                    if (detected != null) {
                        "检测到你的机型：" + detected.label + "（置顶）。点卡片直达对应设置页；跳不过去会弹系统授权框，路径也已复制到剪贴板。"
                    } else {
                        "点下方自己手机的卡片，直达对应设置页；跳不过去会弹系统授权框，路径也已复制到剪贴板。"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp,
                    color = BaibaiAccent,
                )

                Spacer(Modifier.height(24.dp))

                // §7.2：五张厂商卡片（整卡可点 → 直达系统设置；识别到的品牌置顶）
                brands.forEach { brand ->
                    BrandCard(
                        brand = brand,
                        isMine = brand == detected,
                        onDeepLink = onDeepLink,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.height(12.dp))
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
private fun BrandCard(brand: Brand, isMine: Boolean, onDeepLink: (Brand) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandCardBg, RoundedCornerShape(RoundedBrand))
            .border(
                if (isMine) 1.5.dp else 1.dp,
                if (isMine) BaibaiAccent2.copy(alpha = 0.8f) else BrandCardBorder,
                RoundedCornerShape(RoundedBrand),
            )
            .clickable { onDeepLink(brand) }
            .padding(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(brand.label, fontSize = 14.sp, fontWeight = FontWeight.Black, color = BaibaiAccent)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isMine) "你的机型" else "去设置",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = if (isMine) Color.White else BaibaiAccent.copy(alpha = 0.85f),
                    modifier = Modifier
                        .background(
                            if (isMine) BaibaiAccent2 else BaibaiAccent.copy(alpha = 0.12f),
                            RoundedCornerShape(999.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text(
                brand.manualPath,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = BaibaiInk.copy(alpha = 0.4f),
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = "跳转" + brand.label + "系统设置",
            tint = BaibaiInk.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp),
        )
    }
}
