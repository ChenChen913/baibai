package io.github.chenchen913.baibai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ============ uxpilot 设计稿 · 设计语言 ============ */

// 基础色
val BaibaiAccent = Color(0xFFC8402F) // 朱红（主操作/强调/轨迹）
val BaibaiAccentDeep = Color(0xFFA62D22)
val BaibaiAccent2 = Color(0xFFE8A23D) // 鎏金（高亮/压轴/家）
val BaibaiGoldDeep = Color(0xFFD49130)
val BaibaiInk = Color(0xFF5A3A2A) // 墨棕（文字）

// 背景渐变（设计稿：180° #FFF8EE → #FFE9D4 → #FFDFBE）
fun baibaiBackground(): Brush = Brush.verticalGradient(
    listOf(Color(0xFFFFF8EE), Color(0xFFFFE9D4), Color(0xFFFFDFBE)),
)

// 主按钮竖向红渐变（设计稿：180deg #CE4131 → #B33425）
fun baibaiPrimaryGradient(): Brush = Brush.verticalGradient(
    listOf(Color(0xFFCE4131), Color(0xFFB33425)),
)

// 压轴金色渐变（设计稿：135deg #E8A23D → #D49130）
fun baibaiGoldGradient(): Brush = Brush.linearGradient(
    listOf(BaibaiAccent2, BaibaiGoldDeep),
)

// 选中芯片渐变（设计稿：135deg #C8402F → #A62D22）
fun baibaiChipGradient(): Brush = Brush.linearGradient(
    listOf(BaibaiAccent, BaibaiAccentDeep),
)

// 玻璃卡（设计稿：白 50~85% + 轻描边 + 大圆角）
val BaibaiGlass = Color(0xD9FFFFFF)
val BaibaiGlassLight = Color(0x80FFFFFF)
val BaibaiGlassCard = Color(0x99FFFFFF)
val BaibaiLine = Color(0x0D5A3A2A)

// 画布纸感底（设计稿：paper-canvas #FAFAFA + 金色网点）
val BaibaiPaper = Color(0xFFFAFAFA)
val BaibaiDotGold = Color(0x33E8A23D)

// 常用圆角（设计稿）
val RoundedMainCard = 40.dp
val RoundedCanvas = 32.dp
val RoundedGlass = 24.dp
val RoundedScore = 20.dp
val RoundedBrand = 16.dp

private val scheme = lightColorScheme(
    primary = BaibaiAccent,
    secondary = BaibaiAccent2,
    background = Color(0xFFFFF8EE),
    surface = Color(0xFFFAFAFA),
    onPrimary = Color.White,
    onBackground = BaibaiInk,
    onSurface = BaibaiInk,
)

@Composable
fun BaibaiTheme(content: @Composable () -> Unit) {
    // 设计稿为白天暖色基调，固定亮色（用户要求"拜年是大白天"）
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 页面统一背景容器（渐变 + 全屏） */
@Composable
fun BaibaiPage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(baibaiBackground()),
    ) {
        content()
    }
}

/* ============ 通用组件 ============ */

/** 顶部返回按钮（设计稿：40dp 方块 白50% 圆角12dp） */
@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .background(BaibaiGlassLight, RoundedCornerShape(12.dp)),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = BaibaiInk,
        )
    }
}

/** 金色竖条标题（设计稿：3dp × 16dp 金条 + 14sp 黑体） */
@Composable
fun SectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(3.dp)
                .height(16.dp)
                .background(BaibaiAccent2, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            color = BaibaiInk,
        )
    }
}
