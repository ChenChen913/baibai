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
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.optimize.Optimize
import java.io.File
import java.time.LocalDate

private val GlassCardBg = Color(0x80FFFFFF) // 白 50%
private val GlassCardBorder = Color(0x4DFFFFFF) // 白 30%

/** 历史列表页（A-M2）：会话列表 → 回顾页；A-M5：导出/导入（与网页版互通）
 * 视觉参照 uxpilot-export/2-baibai - History.html */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (SessionData) -> Unit) {
    val ctx = LocalContext.current
    val sessions = remember { mutableStateOf(RecorderHub.store.listSessions()) }
    var message by remember { mutableStateOf<String?>(null) }

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
                    sessions.value = RecorderHub.store.listSessions()
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

            if (sessions.value.isEmpty()) {
                Text("还没有记录。大年初一，出发！", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BaibaiInk.copy(alpha = 0.6f))
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    sessions.value.forEach { s ->
                        val stat = runCatching {
                            val routes = Optimize.optimizeSession(s)
                            val c = Optimize.scorecard(s, routes)
                            "${s.nodes.size} 户 · ${"%.2f".format(c.actualDistM / 1000)} km · 绕路率 ${c.savingsTimePct.toInt()}%"
                        }.getOrElse { "${s.nodes.size} 户" }
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
