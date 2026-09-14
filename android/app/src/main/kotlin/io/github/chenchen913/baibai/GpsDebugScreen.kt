package io.github.chenchen913.baibai

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.util.Locale
import kotlinx.coroutines.delay

private val DebugCardBg = Color(0x99FFFFFF) // 白 60%
private val DebugCardBorder = Color(0x33FFFFFF) // 白 20%

/**
 * 定位实验页（GPS Debug）：开关采集 + 实时状态 + 导出/清空。
 *
 * 本页只做「采集与展示」：不改变任何过滤规则、阈值与闸门顺序。
 * 入口：记录页右侧「定位回中」按钮**长按**。
 */
@Composable
fun GpsDebugScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val enabled by RecorderHub.debugEnabled.collectAsState()
    var stats by remember { mutableStateOf(RecorderHub.debugStats()) }

    // 每 1 秒刷新一次实时状态（离开本页时协程随 LaunchedEffect 一起取消）
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            stats = RecorderHub.debugStats()
        }
    }

    /** 导出并与 HistoryScreen 完全相同的 FileProvider 分享方式（cacheDir/exports 已在 file_paths.xml 声明） */
    fun doExport(json: Boolean) {
        val f = if (json) RecorderHub.exportDebugJson(ctx) else RecorderHub.exportDebugCsv(ctx)
        if (f == null) {
            RecorderHub.toast("导出失败")
            return
        }
        val ok = runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (json) "application/json" else "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "导出定位实验数据"))
        }.isSuccess
        if (ok) RecorderHub.toast("已导出：" + f.name) else RecorderHub.toast("导出失败")
    }

    BaibaiPage {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // 顶栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Text(
                    "定位实验（GPS Debug）",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = BaibaiInk,
                )
            }

            Spacer(Modifier.height(20.dp))

            // 采集开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DebugCardBg, RoundedCornerShape(20.dp))
                    .border(1.dp, DebugCardBorder, RoundedCornerShape(20.dp))
                    .padding(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("开启采集", fontSize = 14.sp, fontWeight = FontWeight.Black, color = BaibaiInk)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "开启后每次定位都会记录原始数据与算法判定；不影响正常记录。",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 16.sp,
                        color = BaibaiInk.copy(alpha = 0.6f),
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { RecorderHub.setDebugEnabled(it) },
                )
            }

            Spacer(Modifier.height(20.dp))

            SectionTitle("实时状态")

            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DebugCardBg, RoundedCornerShape(20.dp))
                    .border(1.dp, DebugCardBorder, RoundedCornerShape(20.dp))
                    .padding(16.dp),
            ) {
                val mock = stats.lastIsMock
                DebugRow("定位来源", (stats.lastProvider ?: "—") + " / " + (stats.lastSrc ?: "—"))
                DebugRow("当前 accuracy", fmtUnit(stats.lastAccuracy, " m"))
                DebugRow("当前速度", fmtUnit(stats.lastSpeedMps, " m/s"))
                DebugRow(
                    "原始 / 入库 / 拒绝",
                    stats.rawCount.toString() + " / " + stats.acceptedCount.toString() +
                        " / " + stats.rejectedCount.toString(),
                )
                DebugRow("最近一次判定", stats.lastDecision ?: "—")
                DebugRow("最近一次拒绝原因", stats.lastRejectReason ?: "—")
                DebugRow("距上一条原始点", fmtUnit(stats.lastDistFromPrevRawM, " m"))
                DebugRow("距上一入库点", fmtUnit(stats.lastDistFromPrevStoredM, " m"))
                DebugRow("本次判定门槛", fmtUnit(stats.lastThresholdM, " m"))
                DebugRow("是否模拟位置", if (mock == null) "—" else if (mock) "是" else "否")
                DebugRow("会话状态", stats.currentSessionState)
                DebugRow("已入库点数", stats.storedPointCount.toString())
            }

            Spacer(Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                DebugBtn("导出 CSV", Modifier.weight(1f)) { doExport(json = false) }
                DebugBtn("导出 JSON", Modifier.weight(1f)) { doExport(json = true) }
            }

            Spacer(Modifier.height(8.dp))

            DebugBtn("清空实验数据", Modifier.fillMaxWidth()) {
                RecorderHub.clearDebug()
                stats = RecorderHub.debugStats()
                RecorderHub.toast("已清空实验数据")
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "数据保存在应用私有目录，导出后可用 Excel / 脚本分析。本功能只记录，不改变任何过滤规则。",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 16.sp,
                color = BaibaiInk.copy(alpha = 0.55f),
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 键值行（左标签右数值） */
@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = BaibaiInk.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f),
        )
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Black, color = BaibaiInk)
    }
}

/** 实验页按钮（红渐变实心，44dp） */
@Composable
private fun DebugBtn(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(44.dp)
            .background(baibaiChipGradient(), RoundedCornerShape(12.dp))
            .clickable { onClick() },
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White)
    }
}

/** 数值展示：null / 非有限数 → "—"，否则保留 1 位小数并带单位 */
private fun fmtUnit(v: Double?, unit: String): String =
    if (v == null || !v.isFinite()) "—" else "%.1f".format(Locale.CHINA, v) + unit
