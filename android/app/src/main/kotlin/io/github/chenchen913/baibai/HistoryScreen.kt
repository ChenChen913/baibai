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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chenchen913.baibai.core.model.SessionData

/** 历史列表页（A-M2）：会话列表 → 点击进入回顾页 */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (SessionData) -> Unit) {
    val sessions = remember { mutableStateOf(RecorderHub.store.listSessions()) }

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

        Spacer(Modifier.height(16.dp))

        if (sessions.value.isEmpty()) {
            Text("还没有记录。大年初一，出发！", color = BaibaiInk.copy(alpha = 0.6f))
        } else {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                sessions.value.forEach { s ->
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
                                "${s.nodes.size} 户 · ${s.visits.size} 次到访 · ${s.points.size} 轨迹点",
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
