package io.github.chenchen913.baibai

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BaibaiAccent = Color(0xFFC8402F)
val BaibaiAccent2 = Color(0xFFE8A23D)
val BaibaiInk = Color(0xFF5A3A2A)
val BaibaiBg = Color(0xFFFFF8EE)

private val scheme = lightColorScheme(
    primary = BaibaiAccent,
    secondary = BaibaiAccent2,
    background = BaibaiBg,
    surface = Color(0xFFFFFDF8),
    onPrimary = Color.White,
    onBackground = BaibaiInk,
    onSurface = BaibaiInk,
)

@Composable
fun BaibaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
