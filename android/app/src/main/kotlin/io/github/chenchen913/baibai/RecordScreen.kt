package io.github.chenchen913.baibai

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

/** 记录页（uxpilot 设计稿 Home）：品牌栏 / 状态大卡 / 地图卡 / 模式胶囊 / 主按钮 / 次级操作 */
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
    val recording = st == SessionState.WALKING || st == SessionState.PAUSED

    BaibaiPage {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            // ---------- 1. 品牌栏 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(BaibaiAccent.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("拜", color = BaibaiAccent, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("拜拜", fontSize = 24.sp, fontWeight = FontWeight.Black, color = BaibaiInk)
                    Text(
                        "拜年轨迹复盘",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = BaibaiInk.copy(alpha = 0.4f),
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .background(BaibaiAccent2.copy(alpha = 0.1f), RoundedCornerShape(999.dp))
                        .border(1.dp, BaibaiAccent2.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("大年初一", fontSize = 12.sp, fontWeight = FontWeight.Black, color = BaibaiAccent2)
                }
            }

            Spacer(Modifier.height(24.dp))

            // ---------- 2. 状态大卡 ----------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BaibaiGlass, RoundedCornerShape(RoundedMainCard))
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        STATE_LABEL[st] ?: st.name,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        color = BaibaiInk,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Color(0x80FFFFFF), RoundedCornerShape(999.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(BaibaiAccent2, CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                recording && gpsAcc != null ->
                                    "已定位 · 精度约 ±${gpsAcc!!.toInt()} 米" +
                                        (if (gpsAcc!! > 100) "（网络粗略定位）" else "")
                                recording -> "正在获取定位…"
                                waiting -> "正在获取定位，请允许定位权限…"
                                else -> "从家门口出发，按「开始拜年」"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = BaibaiInk.copy(alpha = 0.6f),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "${session?.visits?.size ?: 0}",
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Black,
                                color = BaibaiAccent,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "拜访户数",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = BaibaiInk.copy(alpha = 0.4f),
                            )
                        }
                        Box(
                            Modifier
                                .width(1.5.dp)
                                .height(48.dp)
                                .background(BaibaiLine),
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                fmtClock(elapsed),
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Black,
                                color = BaibaiInk,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "本次用时",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = BaibaiInk.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---------- 3. 地图卡（真地图待接高德 Key；折叠/展开） ----------
            var mapOpen by remember { mutableStateOf(true) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BaibaiGlass, RoundedCornerShape(RoundedMainCard)),
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text("实时轨迹", fontSize = 14.sp, fontWeight = FontWeight.Black, color = BaibaiInk)
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color.White, CircleShape)
                                .clickable { mapOpen = !mapOpen },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (mapOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.PlayArrow,
                                contentDescription = if (mapOpen) "收起地图" else "展开地图",
                                tint = BaibaiInk,
                                modifier = Modifier.size(if (mapOpen) 16.dp else 14.dp),
                            )
                        }
                    }
                    if (mapOpen) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .background(Color(0xFFF4F1EA)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "真实地图待接入（高德 Key 到位后显示）\n轨迹记录不受影响",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = BaibaiInk.copy(alpha = 0.35f),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x33FFFFFF))
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            "© OpenStreetMap / 高德地图",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = BaibaiInk.copy(alpha = 0.4f),
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // ---------- 4. 模式胶囊 + 主按钮 + 次级操作 ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .background(Color(0xCCFFFFFF), RoundedCornerShape(999.dp))
                    .padding(6.dp)
                    .align(Alignment.CenterHorizontally),
            ) {
                ModeChip(Icons.Filled.DirectionsWalk, "走路", mode == Mode.WALK, recording && mode == Mode.WALK) {
                    RecorderHub.setMode(Mode.WALK)
                }
                ModeIconButton(Icons.Filled.PedalBike, "骑车", enabled = recording, active = mode == Mode.BIKE) {
                    RecorderHub.setMode(Mode.BIKE)
                }
                Box(
                    Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .background(BaibaiLine),
                )
                ModeIconButton(Icons.Filled.CheckCircle, "清单", enabled = true, active = false) { onPlan() }
                ModeIconButton(Icons.Filled.History, "历史", enabled = true, active = false) { onHistory() }
            }

            Spacer(Modifier.height(20.dp))

            when (st) {
                SessionState.IDLE -> GradientPrimaryButton(
                    text = "开始拜年",
                    icon = Icons.Filled.PlayArrow,
                    enabled = true,
                    onClick = onStartRequest,
                )

                SessionState.WALKING -> GradientPrimaryButton(
                    text = "到一户了 · 暂停",
                    icon = Icons.Filled.PauseCircle,
                    enabled = true,
                    onClick = { RecorderHub.pausePressed() },
                )

                SessionState.PAUSED -> GradientPrimaryButton(
                    text = "继续出发",
                    icon = Icons.Filled.PlayArrow,
                    enabled = true,
                    onClick = { RecorderHub.resumePressed() },
                )

                SessionState.FINISHED -> GradientPrimaryButton(
                    text = "已保存 · 前往历史查看",
                    icon = Icons.Filled.CheckCircle,
                    enabled = false,
                    onClick = {},
                )
            }

            if (recording) {
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextAction(Icons.Filled.StopCircle, "结束拜年", BaibaiAccent) {
                        RecorderHub.finishPressed()
                    }
                    Spacer(Modifier.width(24.dp))
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(BaibaiLine, CircleShape),
                    )
                    Spacer(Modifier.width(24.dp))
                    TextAction(Icons.Filled.RotateLeft, "撤销", BaibaiInk.copy(alpha = 0.6f)) {
                        RecorderHub.undoPressed()
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ModeChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) {
        Modifier.background(baibaiChipGradient(), RoundedCornerShape(999.dp))
    } else {
        Modifier
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(bg)
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) Color.White else BaibaiInk.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            color = if (selected) Color.White else BaibaiInk.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun ModeIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) {
        Modifier.background(baibaiChipGradient(), RoundedCornerShape(999.dp))
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .then(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Color.White else BaibaiInk.copy(alpha = if (enabled) 0.4f else 0.15f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TextAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun GradientPrimaryButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(
                if (enabled) baibaiPrimaryGradient() else androidx.compose.ui.graphics.SolidColor(Color(0xFFDDD4C8)),
                RoundedCornerShape(999.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = text, tint = Color.White, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
    }
}
