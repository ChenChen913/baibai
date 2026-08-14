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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chenchen913.baibai.core.model.Plan
import io.github.chenchen913.baibai.core.model.PlanItem
import io.github.chenchen913.baibai.core.plan.PlanOps

private val DescCardBg = Color(0x66FFFFFF) // 白 40%
private val DescCardBorder = Color(0x33FFFFFF) // 白 20%
private val ItemRowBg = Color(0x4DFFFFFF) // 白 30%

/** 今年清单管理页（A-M4/F-1）：从去年导入、增删改名。拜年时照常只按「暂停」，回来自动对比漏了谁
 * 视觉参照 uxpilot-export/3-baibai - List.html（系统通知预览已由 Notifications.kt 实现，此处略） */
@Composable
fun PlanScreen(year: Int, onBack: () -> Unit) {
    var plan by remember {
        mutableStateOf(RecorderHub.store.loadPlan(year) ?: Plan(year, emptyList(), System.currentTimeMillis(), System.currentTimeMillis()))
    }
    var showImportConfirm by remember { mutableStateOf(false) }

    fun persist(next: Plan) {
        plan = next
        RecorderHub.store.savePlan(next)
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
                Text("$year 年拜年清单", fontSize = 20.sp, fontWeight = FontWeight.Black, color = BaibaiInk)
            }

            Spacer(Modifier.height(24.dp))

            // 玻璃说明卡
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DescCardBg, RoundedCornerShape(20.dp))
                    .border(1.dp, DescCardBorder, RoundedCornerShape(20.dp))
                    .padding(16.dp),
            ) {
                Text(
                    "出门前看一眼，拜年时照常只按「暂停」，回来系统自动对比漏了谁。",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 18.sp,
                    color = BaibaiInk.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.height(24.dp))

            // 从去年导入（红实心）+ 加号（红描边 48dp）
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(baibaiPrimaryGradient(), RoundedCornerShape(12.dp))
                        .clickable { showImportConfirm = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("从去年导入", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Transparent, RoundedCornerShape(12.dp))
                        .border(1.dp, BaibaiAccent, RoundedCornerShape(12.dp))
                        .clickable { persist(plan.copy(items = plan.items + PlanItem("", null))) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加一户", tint = BaibaiAccent)
                }
            }

            Spacer(Modifier.height(24.dp))

            if (plan.items.isEmpty()) {
                Text("清单为空：点「从去年导入」或点 ＋ 手动添加", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BaibaiInk.copy(alpha = 0.6f))
            } else {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    plan.items.forEachIndexed { i, item ->
                        var nameText by remember(item) { mutableStateOf(item.name) }
                        val changed = nameText != item.name
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ItemRowBg, RoundedCornerShape(16.dp))
                                .border(1.dp, DescCardBorder, RoundedCornerShape(16.dp))
                                .padding(12.dp),
                        ) {
                            Text(
                                "%02d".format(i + 1),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                color = BaibaiInk.copy(alpha = 0.2f),
                            )
                            Spacer(Modifier.width(12.dp))
                            BasicTextField(
                                value = nameText,
                                onValueChange = { nameText = it },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BaibaiInk),
                                cursorBrush = SolidColor(BaibaiAccent),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    Box {
                                        if (nameText.isEmpty()) {
                                            Text("户名", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BaibaiInk.copy(alpha = 0.3f))
                                        }
                                        inner()
                                    }
                                },
                            )
                            if (item.pos == null && nameText.isNotBlank()) {
                                Text(
                                    "无位置·手动核对",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BaibaiAccent.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                            // 改名：有改动时红色实心，无改动时淡灰
                            Box(
                                modifier = Modifier
                                    .height(48.dp)
                                    .padding(horizontal = 12.dp)
                                    .background(
                                        if (changed) baibaiChipGradient() else BaibaiInk.copy(alpha = 0.1f),
                                        RoundedCornerShape(12.dp),
                                    )
                                    .clickable(enabled = changed) {
                                        val items = plan.items.toMutableList()
                                        items[i] = items[i].copy(name = nameText.trim())
                                        persist(plan.copy(items = items))
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (changed) "改名" else "已改",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (changed) Color.White else BaibaiInk.copy(alpha = 0.4f),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    val items = plan.items.toMutableList()
                                    items.removeAt(i)
                                    persist(plan.copy(items = items))
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除",
                                    tint = BaibaiInk.copy(alpha = 0.25f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showImportConfirm) {
        val prev = RecorderHub.store.listSessions()
            .filter { it.year < year }
            .maxByOrNull { it.createdAt }
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text("从去年导入") },
            text = {
                Text(
                    if (prev == null) "还没有历史拜年记录，无法导入。"
                    else "从 ${prev.date} 的 ${prev.nodes.size} 户生成清单？（会覆盖当前清单）",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (prev != null) {
                            persist(PlanOps.planFromSession(prev, year))
                        }
                        showImportConfirm = false
                    },
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) { Text("取消") }
            },
        )
    }
}
