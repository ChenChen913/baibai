package io.github.chenchen913.baibai

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chenchen913.baibai.core.model.Plan
import io.github.chenchen913.baibai.core.model.PlanItem
import io.github.chenchen913.baibai.core.plan.PlanOps

/** 今年清单管理页（A-M4/F-1）：从去年导入、增删改名。拜年时照常只按「暂停」，回来自动对比漏了谁 */
@Composable
fun PlanScreen(year: Int, onBack: () -> Unit) {
    var plan by remember {
        mutableStateOf(RecorderHub.store.loadPlan(year) ?: Plan(year, emptyList(), System.currentTimeMillis(), System.currentTimeMillis()))
    }
    var newName by remember { mutableStateOf("") }
    var showImportConfirm by remember { mutableStateOf(false) }

    fun persist(next: Plan) {
        plan = next
        RecorderHub.store.savePlan(next)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BaibaiBg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xAAFFFFFF))) {
                Text("← 返回", color = BaibaiInk)
            }
            Spacer(Modifier.weight(1f))
            Text("${year} 年拜年清单", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = BaibaiInk)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "出门前看一眼，拜年时照常只按「暂停」，回来系统自动对比漏了谁。",
            fontSize = 12.sp,
            color = BaibaiInk.copy(alpha = 0.6f),
        )

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { showImportConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = BaibaiAccent),
            ) { Text("📥 从去年导入") }
            OutlinedButton(onClick = {
                if (newName.isNotBlank()) {
                    persist(plan.copy(items = plan.items + PlanItem(newName.trim(), null)))
                    newName = ""
                }
            }) { Text("添加", color = BaibaiInk) }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("新户名字（无坐标，仅提醒）") },
        )

        Spacer(Modifier.height(14.dp))

        if (plan.items.isEmpty()) {
            Text("清单为空：点「从去年导入」或手动添加", color = BaibaiInk.copy(alpha = 0.6f))
        } else {
            plan.items.forEachIndexed { i, item ->
                var nameText by remember(item) { mutableStateOf(item.name) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text("${i + 1}", fontWeight = FontWeight.Bold, color = BaibaiInk)
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        singleLine = true,
                        placeholder = { Text("户名") },
                    )
                    Button(
                        onClick = {
                            val items = plan.items.toMutableList()
                            items[i] = items[i].copy(name = nameText.trim())
                            persist(plan.copy(items = items))
                        },
                        enabled = nameText != item.name,
                        colors = ButtonDefaults.buttonColors(containerColor = BaibaiAccent),
                    ) { Text("改名") }
                    OutlinedButton(
                        onClick = {
                            val items = plan.items.toMutableList()
                            items.removeAt(i)
                            persist(plan.copy(items = items))
                        },
                    ) { Text("删除", color = BaibaiInk) }
                }
            }
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
