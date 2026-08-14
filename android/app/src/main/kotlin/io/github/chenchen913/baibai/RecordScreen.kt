package io.github.chenchen913.baibai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
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

private val GlassCardBg = Color(0xCCFFFFFF) // 白 80%
private val GlassCardBorder = Color(0x66FFFFFF) // 白 40%
private val MapPlaceholderBg = Color(0xFFF4F1EA)

/** 记录页（原始需求 §7.1）：
 * 品牌栏 → 状态大卡 → 实时地图卡（可折叠，展开约屏高 30~35%）→ 主按钮区 → 工具条。
 * 上部滚动、底部主按钮区+工具条固定于拇指热区；安全区由 BaibaiPage 统一处理。 */
@Composable
fun RecordScreen(onStartRequest: () -> Unit, onHistory: () -> Unit, onPlan: () -> Unit) {
    val session by RecorderHub.session.collectAsState()
    val waiting by RecorderHub.waiting.collectAsState()
    val gpsAcc by RecorderHub.gpsAcc.collectAsState()
    val feedbackOn by RecorderHub.feedbackOn.collectAsState()

    val st = session?.state ?: SessionState.IDLE
    val mode = session?.currentMode ?: Mode.WALK
    val recording = st == SessionState.WALKING || st == SessionState.PAUSED

    BaibaiPage {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 地图展开高度：屏幕可用高度 30%~35%（约 260dp 上限、150dp 下限）
            val mapOpenHeight = (maxHeight * 0.33f).coerceIn(150.dp, 260.dp)
            val pageWidth = maxWidth

            Column(Modifier.fillMaxSize()) {
                // ---------- 上部可滚动区 ----------
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Spacer(Modifier.height(20.dp))
                    BrandBar(
                        feedbackOn = feedbackOn,
                        onToggleFeedback = { RecorderHub.setFeedback(!feedbackOn) },
                    )
                    Spacer(Modifier.height(20.dp))
                    StatusCard(
                        st = st,
                        recording = recording,
                        waiting = waiting,
                        gpsAcc = gpsAcc,
                        // P9：拜访户数 = 唯一户数（nodes 不含 home，中途回家/回访不虚高）
                        visits = session?.nodes?.size ?: 0,
                        pageWidth = pageWidth,
                    )
                    Spacer(Modifier.height(16.dp))
                    MapCard(mapOpenHeight = mapOpenHeight)
                    Spacer(Modifier.height(16.dp))
                }

                // ---------- 底部固定区：主按钮区 + 工具条（拇指热区） ----------
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                ) {
                    when (st) {
                        SessionState.IDLE -> MainButton(
                            text = "开始拜年",
                            icon = Icons.Filled.PlayArrow,
                            onClick = onStartRequest,
                        )

                        SessionState.WALKING -> MainButton(
                            text = "到一户了 · 暂停",
                            icon = Icons.Filled.PauseCircle,
                            onClick = { RecorderHub.pausePressed() },
                        )

                        SessionState.PAUSED -> MainButton(
                            text = "继续出发",
                            icon = Icons.Filled.PlayArrow,
                            onClick = { RecorderHub.resumePressed() },
                        )

                        SessionState.FINISHED -> Unit // §8：已保存 → 自动跳转回顾页，不显示主按钮
                    }

                    if (recording) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SmallAction(Icons.Filled.StopCircle, "结束拜年", BaibaiAccent) {
                                RecorderHub.finishPressed()
                            }
                            Spacer(Modifier.width(24.dp))
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .background(BaibaiLine, CircleShape),
                            )
                            Spacer(Modifier.width(24.dp))
                            SmallAction(Icons.Filled.RotateLeft, "撤销", BaibaiInk.copy(alpha = 0.6f)) {
                                RecorderHub.undoPressed()
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Toolbar(
                        mode = mode,
                        recording = recording,
                        onWalk = { RecorderHub.setMode(Mode.WALK) },
                        onBike = { RecorderHub.setMode(Mode.BIKE) },
                        onPlan = onPlan,
                        onHistory = onHistory,
                    )
                }
            }
        }
    }
}

/* ---------- 1. 品牌栏 ---------- */

@Composable
private fun BrandBar(feedbackOn: Boolean, onToggleFeedback: () -> Unit) {
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
        // 金色描边徽章
        Box(
            modifier = Modifier
                .background(BaibaiAccent2.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                .border(1.dp, BaibaiAccent2.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text("大年初一", fontSize = 12.sp, fontWeight = FontWeight.Black, color = BaibaiAccent2)
        }
        Spacer(Modifier.width(4.dp))
        // P6：提示音/震动开关（≥48dp 热区）
        IconButton(onClick = onToggleFeedback) {
            Icon(
                if (feedbackOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                contentDescription = if (feedbackOn) "关闭提示音与震动" else "开启提示音与震动",
                tint = if (feedbackOn) BaibaiInk.copy(alpha = 0.55f) else BaibaiInk.copy(alpha = 0.3f),
            )
        }
    }
}

/* ---------- 2. 状态大卡（玻璃拟态） ---------- */

@Composable
private fun StatusCard(
    st: SessionState,
    recording: Boolean,
    waiting: Boolean,
    gpsAcc: Double?,
    visits: Int,
    pageWidth: androidx.compose.ui.unit.Dp,
) {
    val subText = when {
        st == SessionState.FINISHED -> "本次拜年已保存"
        recording && gpsAcc != null ->
            "已定位 · 精度约 ±${gpsAcc.toInt()} 米" + if (gpsAcc > 100) "（网络粗略定位）" else ""
        recording || waiting -> "正在获取定位…"
        else -> "从家门口出发，按「开始拜年」"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 14.dp,
                shape = RoundedCornerShape(RoundedMainCard),
                ambientColor = Color(0x145A3A2A),
                spotColor = Color(0x1F5A3A2A),
            )
            .background(GlassCardBg, RoundedCornerShape(RoundedMainCard))
            .border(1.dp, GlassCardBorder, RoundedCornerShape(RoundedMainCard))
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                STATE_LABEL[st] ?: st.name,
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                color = BaibaiInk,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                subText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = BaibaiInk.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(22.dp))
            StatsRow(visits = visits, pageWidth = pageWidth)
        }
    }
}

@Composable
private fun StatsRow(visits: Int, pageWidth: androidx.compose.ui.unit.Dp) {
    val fontScale = LocalDensity.current.fontScale
    // HH:MM:SS 等宽 8 位 × 0.6em ≈ 4.8em；卡片内半宽 = (页宽 - 页边距40 - 卡边距40)/2，按此反推字号
    val digitSp = ((pageWidth.value - 80f) / 2f / 4.8f / fontScale).coerceIn(20f, 40f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        StatColumn(
            value = "$visits",
            label = "拜访户数",
            color = BaibaiAccent,
            digitSp = digitSp,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .width(1.dp)
                .height((digitSp * 1.2f).dp)
                .background(BaibaiLine),
        )
        TimeColumn(digitSp = digitSp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatColumn(value: String, label: String, color: Color, digitSp: Float, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        // 单层等宽数字，无描边无阴影
        Text(
            value,
            fontSize = digitSp.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = BaibaiInk.copy(alpha = 0.45f),
        )
    }
}

/** 本次用时（M-5：秒级 tick 收敛在此节点，只重组这一列，不再整屏重组） */
@Composable
private fun TimeColumn(digitSp: Float, modifier: Modifier) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick += 1
            delay(1000)
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            fmtClock(RecorderHub.elapsedMs()),
            fontSize = digitSp.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = BaibaiInk,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "本次用时",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = BaibaiInk.copy(alpha = 0.45f),
        )
    }
}

/* ---------- 3. 实时地图卡（折叠 = 40dp 标题条，展开 = 屏高 30%~35%） ---------- */

@Composable
private fun MapCard(mapOpenHeight: androidx.compose.ui.unit.Dp) {
    var mapOpen by remember { mutableStateOf(true) }
    val shape = RoundedCornerShape(22.dp)

    if (mapOpen) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 10.dp,
                    shape = shape,
                    ambientColor = Color(0x145A3A2A),
                    spotColor = Color(0x1F5A3A2A),
                )
                .clip(shape)
                .background(GlassCardBg)
                .border(1.dp, GlassCardBorder, shape),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Text("实时轨迹", fontSize = 14.sp, fontWeight = FontWeight.Black, color = BaibaiInk)
                Spacer(Modifier.weight(1f))
                // 线性折叠图标（无圆形白底），热区 ≥48dp
                IconButton(onClick = { mapOpen = false }) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = "收起地图",
                        tint = BaibaiInk,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(mapOpenHeight)
                    .background(MapPlaceholderBg),
                contentAlignment = Alignment.Center,
            ) {
                // §6 空态：线性地图小图标 + 居中一句文案（真地图待接高德 Key）
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Map,
                        contentDescription = null,
                        tint = BaibaiInk.copy(alpha = 0.4f),
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "真实地图待接入（高德 Key 到位后显示）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = BaibaiInk.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "© OpenStreetMap / 高德地图",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = BaibaiInk.copy(alpha = 0.45f),
                )
            }
        }
    } else {
        // 折叠：40dp 标题条（整条可点，触控热区 48dp）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = shape,
                    ambientColor = Color(0x145A3A2A),
                    spotColor = Color(0x1F5A3A2A),
                )
                .clip(shape)
                .background(GlassCardBg)
                .border(1.dp, GlassCardBorder, shape)
                .clickable { mapOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 20.dp),
            ) {
                Text("实时轨迹", fontSize = 14.sp, fontWeight = FontWeight.Black, color = BaibaiInk)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "展开地图",
                    tint = BaibaiInk,
                )
            }
        }
    }
}

/* ---------- 4. 主按钮区（同一时刻只显示一个主按钮，§8） ---------- */

@Composable
private fun MainButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(baibaiPrimaryGradient(), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = text, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

/* ---------- 5. 工具条（胶囊容器 · 走路/骑车带选中态，待机/已保存置灰） ---------- */

@Composable
private fun Toolbar(
    mode: Mode,
    recording: Boolean,
    onWalk: () -> Unit,
    onBike: () -> Unit,
    onPlan: () -> Unit,
    onHistory: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlassCardBg, RoundedCornerShape(999.dp))
            .padding(6.dp),
    ) {
        ToolbarItem(
            icon = Icons.Filled.DirectionsWalk,
            label = "走路",
            selected = recording && mode == Mode.WALK,
            enabled = recording,
            onClick = onWalk,
            modifier = Modifier.weight(1f),
        )
        ToolbarItem(
            icon = Icons.Filled.PedalBike,
            label = "骑车",
            selected = recording && mode == Mode.BIKE,
            enabled = recording,
            onClick = onBike,
            modifier = Modifier.weight(1f),
        )
        ToolbarItem(
            icon = Icons.Filled.CheckCircle,
            label = "清单",
            selected = false,
            enabled = true,
            onClick = onPlan,
            modifier = Modifier.weight(1f),
        )
        ToolbarItem(
            icon = Icons.Filled.History,
            label = "历史",
            selected = false,
            enabled = true,
            onClick = onHistory,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ToolbarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val bg = if (selected) {
        Modifier.background(baibaiChipGradient(), RoundedCornerShape(999.dp))
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .then(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) Color.White else BaibaiInk.copy(alpha = if (enabled) 0.55f else 0.25f),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = if (selected) Color.White else BaibaiInk.copy(alpha = if (enabled) 0.55f else 0.25f),
            )
        }
    }
}
