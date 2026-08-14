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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BrandCardBg = Color(0x66FFFFFF) // 白 40%
private val BrandCardBorder = Color(0x33FFFFFF) // 白 20%

/** 省电白名单引导（四层防杀之第三层：国产 ROM 决定性动作；首次开始拜年前展示一次）
 * 视觉参照 uxpilot-export/4-baibai - Guide.html */
@Composable
fun WhitelistGuideScreen(onDone: () -> Unit) {
    BaibaiPage {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
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

            Spacer(Modifier.height(40.dp))

            BrandCard(
                brand = "小米 / 红米",
                lines = listOf("设置 → 应用设置 → 拜拜 → 省电策略 → 无限制；并开启「自启动」"),
                chevron = true,
            )
            Spacer(Modifier.height(12.dp))
            BrandCard(
                brand = "华为 / 荣耀",
                lines = listOf("设置 → 应用 → 应用启动管理 → 拜拜 → 手动管理 → 允许自启动/关联启动/后台活动"),
                chevron = true,
            )
            Spacer(Modifier.height(12.dp))
            // 设计稿第三张卡：OPPO / vivo / 三星 合并，整体 60% 透明度
            Column(Modifier.alpha(0.6f)) {
                BrandCard(
                    brand = "OPPO / vivo / 三星",
                    lines = listOf(
                        "OPPO/一加：设置 → 电池 → 更多设置 → 拜拜 → 允许后台运行",
                        "vivo/iQOO：i管家 → 电池 → 后台耗电管理 → 拜拜 → 允许后台高耗电",
                        "三星：设置 → 电池 → 后台使用限制 → 拜拜 → 不休眠",
                    ),
                    chevron = false,
                )
            }

            Spacer(Modifier.height(32.dp))

            // 64dp 红色主按钮
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
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BrandCard(brand: String, lines: List<String>, chevron: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandCardBg, RoundedCornerShape(RoundedBrand))
            .border(1.dp, BrandCardBorder, RoundedCornerShape(RoundedBrand))
            .padding(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(brand, fontSize = 14.sp, fontWeight = FontWeight.Black, color = BaibaiAccent)
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
        if (chevron) {
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = BaibaiInk.copy(alpha = 0.1f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
