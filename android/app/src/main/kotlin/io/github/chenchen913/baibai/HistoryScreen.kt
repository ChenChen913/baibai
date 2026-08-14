package io.github.chenchen913.baibai

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
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
import java.io.File
import java.time.LocalDate

/** 历史列表页（A-M2）：会话列表 → 回顾页；A-M5：导出/导入（与网页版互通） */
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BaibaiBg)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xAAFFFFFF))) {
                Text("← 返回", color = BaibaiInk)
            }
            Spacer(Modifier.weight(1f))
            Text("历史记录", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = BaibaiInk)
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { doExport() },
                colors = ButtonDefaults.buttonColors(containerColor = BaibaiAccent),
            ) { Text("导出 JSON") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                Text("导入 JSON", color = BaibaiInk)
            }
        }
        Text(
            "导出/导入与网页版同一格式：安卓记录 → 电脑复盘，或反之。",
            fontSize = 11.sp,
            color = BaibaiInk.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 4.dp),
        )
        message?.let {
            Text(it, fontSize = 13.sp, color = BaibaiAccent, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(12.dp))

        if (sessions.value.isEmpty()) {
            Text("还没有记录。大年初一，出发！", color = BaibaiInk.copy(alpha = 0.6f))
        } else {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                sessions.value.forEach { s ->
                    val stat = runCatching {
                        val routes = io.github.chenchen913.baibai.core.optimize.Optimize.optimizeSession(s)
                        val c = io.github.chenchen913.baibai.core.optimize.Optimize.scorecard(s, routes)
                        "${s.nodes.size} 户 · ${"%.2f".format(c.actualDistM / 1000)} km · 绕路率 ${c.savingsTimePct.toInt()}%"
                    }.getOrElse { "${s.nodes.size} 户" }
                    Card(
                        onClick = { onOpen(s) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xAAFFFFFF)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("📅 ${s.date}", fontWeight = FontWeight.Bold, color = BaibaiInk)
                            Text(
                                stat,
                                fontSize = 12.sp,
                                color = BaibaiInk.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }
    }
}
