package io.github.chenchen913.baibai

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.SessionState
import kotlinx.coroutines.delay

private fun fmtClock(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

private val STATE_LABEL = mapOf(
    SessionState.IDLE to "待机",
    SessionState.WALKING to "记录中",
    SessionState.PAUSED to "在某户",
    SessionState.FINISHED to "已保存",
)

/** 记录页（驾驶舱最小可用版）。onStartRequest 由外层处理权限/白名单后再真正调用 hub.startPressed() */
@Composable
fun RecordScreen(onStartRequest: () -> Unit, onHistory: () -> Unit, onPlan: () -> Unit) {
    val session by RecorderHub.session.collectAsState()
    val waiting by RecorderHub.waiting.collectAsState()
    val gpsAcc by RecorderHub.gpsAcc.collectAsState()

    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick += 1
            delay(1000)
        }
    }
    val elapsed = remember(tick) { RecorderHub.elapsedMs() }

    val st = session?.state ?: SessionState.IDLE
    val mode = session?.currentMode ?: Mode.WALK

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BaibaiBg)
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🧧 拜拜", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = BaibaiInk)
            Text("大年初一", fontSize = 12.sp, color = BaibaiAccent)
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xAAFFFFFF)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    STATE_LABEL[st] ?: st.name,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = BaibaiInk,
                )
                Text(
                    when {
                        session == null && waiting -> "正在获取定位，请允许定位权限…"
                        session == null -> "从家门口出发，按「开始拜年」"
                        st == SessionState.WALKING || st == SessionState.PAUSED ->
                            gpsAcc?.let {
                                "已定位 · 精度约 ±${it.toInt()} 米" +
                                    (if (it > 100) "（网络粗略定位）" else "")
                            } ?: "正在获取定位…"
                        else -> "本次拜年已保存"
                    },
                    fontSize = 13.sp,
                    color = BaibaiInk.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(
                            "${session?.visits?.size ?: 0}",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = BaibaiAccent,
                        )
                        Text("拜访户数", fontSize = 12.sp, color = BaibaiInk.copy(alpha = 0.6f))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(
                            fmtClock(elapsed),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = BaibaiAccent,
                        )
                        Text("本次用时", fontSize = 12.sp, color = BaibaiInk.copy(alpha = 0.6f))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when (st) {
            SessionState.IDLE -> GradientPrimaryButton("开始拜年", onClick = onStartRequest)
            SessionState.WALKING -> GradientPrimaryButton("到一户了 · 暂停", onClick = { RecorderHub.pausePressed() })
            SessionState.PAUSED -> GradientPrimaryButton("继续出发", onClick = { RecorderHub.resumePressed() })
            SessionState.FINISHED -> GradientPrimaryButton("已保存（历史页查看）", enabled = false, onClick = {})
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (st == SessionState.WALKING || st == SessionState.PAUSED) {
                OutlinedButton(onClick = { RecorderHub.undoPressed() }, modifier = Modifier.weight(1f)) {
                    Text("撤销", color = BaibaiInk)
                }
                Button(
                    onClick = { RecorderHub.finishPressed() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = BaibaiAccent),
                ) {
                    Text("结束拜年")
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canMode = session != null && (st == SessionState.WALKING || st == SessionState.PAUSED)
            OutlinedButton(
                onClick = { RecorderHub.setMode(Mode.WALK) },
                enabled = canMode,
                modifier = Modifier.weight(1f),
            ) {
                Text("走路", color = if (mode == Mode.WALK) BaibaiAccent else BaibaiInk)
            }
            OutlinedButton(
                onClick = { RecorderHub.setMode(Mode.BIKE) },
                enabled = canMode,
                modifier = Modifier.weight(1f),
            ) {
                Text("骑车", color = if (mode == Mode.BIKE) BaibaiAccent else BaibaiInk)
            }
            OutlinedButton(onClick = onPlan, modifier = Modifier.weight(1f)) {
                Text("清单", color = BaibaiInk)
            }
            OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f)) {
                Text("历史", color = BaibaiInk)
            }
        }
    }
}

@Composable
private fun GradientPrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                brush = if (enabled) {
                    Brush.horizontalGradient(listOf(BaibaiAccent, Color(0xFFD86A3C), BaibaiAccent2))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xFFDDD4C8), Color(0xFFDDD4C8)))
                },
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
