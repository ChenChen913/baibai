package io.github.chenchen913.baibai

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.optimize.Optimize
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val GlassCardBg = Color(0x80FFFFFF) // 白 50%
private val GlassCardBorder = Color(0x4DFFFFFF) // 白 30%

/** 历史列表页（A-M2）：会话列表 → 回顾页；A-M5：导出/导入（与网页版互通）
 * 视觉参照 uxpilot-export/2-baibai - History.html */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (SessionData) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 修（P1 性能/ANR）：原版在组合期、主线程同步跑 store.listSessions()（读盘 + JSON 解码全部会话）
    // 并对每条记录现算 Optimize.optimizeSession（内含 3 次 Held-Karp 精确解，n≤16 时每次分配 8MB+ dp 数组）
    // + Optimize.scorecard；而 onBack/onOpen 是不稳定 lambda，AppRoot 每次重组（例如弹一次 toast）
    // 都会重跑整表——多年记录的用户打开历史页就可能白屏数秒甚至 ANR（网页版同一计算明确放在 Web Worker）。
    // 现改为：IO 线程读盘、Default 线程算统计，算完切回主线程赋值；列表项渲染时只查表。
    val sessions = remember { mutableStateOf<List<SessionData>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) } // 首帧未读完时显示「读取中」，避免误报「还没有记录」
    var stats by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var message by remember { mutableStateOf<String?>(null) }

    /** 重载会话列表 + 逐条统计：全部在后台线程算，算完回主线程赋值（导入后刷新也走这里） */
    fun reloadSessions() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { RecorderHub.store.listSessions() }
            sessions.value = list
            loaded = true
            stats = withContext(Dispatchers.Default) { sessionStats(list) }
        }
    }

    LaunchedEffect(Unit) { reloadSessions() }

    fun doExport() {
        runCatching {
            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
            val f = File(dir, "baibai-backup-${LocalDate.now()}.json")
            f.writeText(RecorderHub.store.exportAllJson())
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "导出拜拜数据"))
        }.onFailure { message = "导出失败：${it.message}" }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    val text = ins.readBytes().toString(Charsets.UTF_8)
                    val n = RecorderHub.store.importAllJson(text)
                    // 修：导入后不再在主线程同步 listSessions()（要解码全部会话 JSON），
                    // 改为交给 reloadSessions() 在 IO 线程重读、Default 线程重算统计
                    reloadSessions()
                    message = "已导入 $n 场拜年记录"
                } ?: run { message = "无法读取文件" }
            }.onFailure { message = "导入失败：${it.message}" }
        }
    }

    BaibaiPage {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            // 顶栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Text("历史记录", fontSize = 20.sp, fontWeight = FontWeight.Black, color = BaibaiInk)
            }

            Spacer(Modifier.height(24.dp))

            // 导出（红实心）/ 导入（红描边）
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(baibaiPrimaryGradient(), RoundedCornerShape(12.dp))
                        .clickable { doExport() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("导出 JSON", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(Color.Transparent, RoundedCornerShape(12.dp))
                        .border(1.dp, BaibaiAccent, RoundedCornerShape(12.dp))
                        .clickable { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("导入 JSON", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BaibaiAccent)
                }
            }
            Text(
                "导出/导入与网页版同一格式：安卓记录 → 电脑复盘，或反之。",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = BaibaiInk.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
            message?.let {
                Text(it, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BaibaiAccent, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            }

            Spacer(Modifier.height(24.dp))

            if (!loaded) {
                // 修：会话列表改为后台线程读取，首帧先显示读取中（否则会闪一下「还没有记录」）
                Text("正在读取历史记录…", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BaibaiInk.copy(alpha = 0.6f))
            } else if (sessions.value.isEmpty()) {
                Text("还没有记录。大年初一，出发！", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BaibaiInk.copy(alpha = 0.6f))
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    sessions.value.forEach { s ->
                        // 修：统计只在后台算一次并缓存进 stats 表，组合期只查表；未算完显示占位
                        val stat = stats[s.id] ?: "${s.nodes.size} 户 · 计算中…"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(GlassCardBg, RoundedCornerShape(RoundedGlass))
                                .border(1.dp, GlassCardBorder, RoundedCornerShape(RoundedGlass))
                                .clickable { onOpen(s) }
                                .padding(20.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.date, fontSize = 16.sp, fontWeight = FontWeight.Black, color = BaibaiInk)
                                Text(
                                    stat,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BaibaiInk.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Icon(
                                Icons.Filled.KeyboardArrowRight,
                                contentDescription = "打开",
                                tint = BaibaiInk.copy(alpha = 0.2f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单条会话的统计文案（格式与旧版逐字一致）；内含 Held-Karp 精确解，只能在后台线程调用 */
private fun sessionStatLine(s: SessionData): String = runCatching {
    val routes = Optimize.optimizeSession(s)
    val c = Optimize.scorecard(s, routes)
    // 修：原 "%.2f".format(...) 未指定 Locale，德语等区域会输出逗号小数点（1,23 km），统一按中国区域
    "${s.nodes.size} 户 · ${"%.2f".format(Locale.CHINA, c.actualDistM / 1000)} km · 绕路率 ${c.savingsTimePct.toInt()}%"
}.getOrElse { "${s.nodes.size} 户" } // 单条算失败只退化为户数，不影响整页

/** 会话 id → 统计文案映射：一次算全表，列表项渲染时只查表（未算完显示占位） */
private fun sessionStats(list: List<SessionData>): Map<String, String> =
    list.associate { it.id to sessionStatLine(it) }
