package io.github.chenchen913.baibai

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.playback.Playback
import io.github.chenchen913.baibai.core.review.Review
import io.github.chenchen913.baibai.core.track.Track
import kotlinx.coroutines.delay
import kotlin.math.min

private const val W = 480f
private const val H = 560f

private fun fmtDur(ms: Long): String = "${ms / 60000}分${(ms % 60000) / 1000}秒"

private fun fmtClock(t: Long): String {
    val d = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
    return d.format(java.util.Date(t))
}

private val GlassCardBg = Color(0x99FFFFFF) // 白 60%
private val GlassCardBorder = Color(0x66FFFFFF) // 白 40%
private val ChipIdleBg = Color(0x4DFFFFFF) // 白 30%
private val InputBorder = Color(0xFFFFE9D4)
private val MissRowBg = Color(0x0DC8402F) // 红 5%
private val MissRowBorder = Color(0x1AC8402F) // 红 10%

/** 回顾页（A-M2）：Canvas 轨迹回放 + 收拾（改名/合并点选芯片/拆分/跳变剔除）
 * 视觉参照 uxpilot-export/5-baibai - Review.html */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(
    initial: SessionData,
    onBack: () -> Unit,
    onSave: (SessionData) -> Unit,
    onOptimize: () -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    fun mutate(next: SessionData) {
        s = next
        onSave(next)
    }

    val plan = remember(s) { Playback.buildPlan(s, W.toDouble(), H.toDouble()) }
    val density = LocalDensity.current.density

    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(2f) }
    var progressMs by remember { mutableStateOf(0L) }

    LaunchedEffect(playing, speed) {
        if (playing && plan.totalMs > 0) {
            val base = progressMs
            val t0 = System.currentTimeMillis()
            while (true) {
                val now = System.currentTimeMillis()
                val next = min(plan.totalMs, base + ((now - t0) * speed).toLong())
                progressMs = next
                if (next >= plan.totalMs) break
                delay(16)
            }
            playing = false
        }
    }

    BaibaiPage {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // 顶栏：返回 + 标题 + 红色胶囊「三线对比」
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Text("${s.date} · 拜年复盘", fontSize = 18.sp, fontWeight = FontWeight.Black, color = BaibaiInk, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .background(baibaiPrimaryGradient(), RoundedCornerShape(999.dp))
                        .clickable(onClick = onOptimize)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("三线对比", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Spacer(Modifier.height(16.dp))

            // 纸感回放画布（320dp，金色网点 + 悬浮播放控制）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(RoundedCanvas))
                    .background(BaibaiPaper)
                    .border(1.dp, Color(0x0D000000), RoundedCornerShape(RoundedCanvas)),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // 金色网点（20px 网格，隔行错位）
                    var row = 0
                    var y = 10f
                    while (y < size.height) {
                        val ox = if (row % 2 == 1) 10f else 0f
                        var x = ox + 10f
                        while (x < size.width) {
                            drawCircle(BaibaiDotGold, radius = 1.5f, center = Offset(x, y))
                            x += 20f
                        }
                        y += 20f
                        row++
                    }
                    // 虚拟坐标 W×H，等比缩放居中
                    val scale = min(size.width / W, size.height / H)
                    val ox = (size.width - W * scale) / 2f
                    val oy = (size.height - H * scale) / 2f
                    withTransform({
                        translate(ox, oy)
                        scale(scale, scale, pivot = Offset.Zero)
                    }) {
                        val path = Path()
                        plan.pts.forEachIndexed { i, p ->
                            if (i == 0) path.moveTo(p.x.toFloat(), p.y.toFloat())
                            else path.lineTo(p.x.toFloat(), p.y.toFloat())
                        }
                        // 底轨（红 20%）
                        drawPath(path.asComposePath(), color = BaibaiAccent.copy(alpha = 0.2f), style = Stroke(width = 4f, cap = StrokeCap.Round))
                        // 已播部分（红实线）
                        val pm = PathMeasure(path, false)
                        val frac = Playback.fractionAt(plan, progressMs).toFloat()
                        if (pm.length > 0 && frac > 0) {
                            val partial = Path()
                            pm.getSegment(0f, pm.length * frac, partial, true)
                            drawPath(partial.asComposePath(), color = BaibaiAccent, style = Stroke(width = 4f, cap = StrokeCap.Round))
                        }
                        // 行进光点
                        Playback.positionAt(plan, progressMs)?.let { pos ->
                            drawCircle(Color.White, radius = 8f, center = Offset(pos.x.toFloat(), pos.y.toFloat()))
                            drawCircle(BaibaiAccent2, radius = 8f, style = Stroke(width = 3f), center = Offset(pos.x.toFloat(), pos.y.toFloat()))
                        }
                        // 家（金）+ 户（白底红边）与名字
                        val labelPaint = TextPaint().apply {
                            color = 0xFF5A3A2A.toInt()
                            textSize = 15f * density / scale
                            textAlign = Paint.Align.CENTER
                            isFakeBoldText = true
                        }
                        fun project(p: LatLng) = Track.projectToView(listOf(p), W.toDouble(), H.toDouble())[0]
                        val homeProj = project(s.home)
                        drawCircle(BaibaiAccent2, radius = 10f, center = Offset(homeProj.x.toFloat(), homeProj.y.toFloat()))
                        drawContext.canvas.nativeCanvas.drawText("家", homeProj.x.toFloat(), (homeProj.y - 16).toFloat(), labelPaint)
                        s.nodes.forEach { n ->
                            val pr = project(n.pos)
                            drawCircle(Color.White, radius = 9f, center = Offset(pr.x.toFloat(), pr.y.toFloat()))
                            drawCircle(BaibaiAccent, radius = 9f, style = Stroke(width = 2.5f), center = Offset(pr.x.toFloat(), pr.y.toFloat()))
                            drawContext.canvas.nativeCanvas.drawText(
                                n.name.ifEmpty { "户${n.autoNo}" },
                                pr.x.toFloat(),
                                (pr.y - 14).toFloat(),
                                labelPaint,
                            )
                        }
                    }
                }
                // 悬浮播放控制行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(baibaiChipGradient(), RoundedCornerShape(16.dp))
                            .clickable { playing = !playing },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "重置",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = BaibaiInk.copy(alpha = 0.4f),
                        modifier = Modifier
                            .clickable { progressMs = 0; playing = false }
                            .padding(vertical = 10.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1f, 2f, 4f).forEach { v ->
                            val active = speed == v
                            Text(
                                "${v.toInt()}x",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = if (active) BaibaiAccent else BaibaiInk.copy(alpha = 0.2f),
                                modifier = Modifier
                                    .clickable { speed = v }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${fmtDur(progressMs)} / ${fmtDur(plan.totalMs)}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = BaibaiInk.copy(alpha = 0.6f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            /* ---------- 户名与收拾 ---------- */
            GlassCard {
                SectionTitle("户名与收拾")
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    s.nodes.forEach { n ->
                        var nameText by remember(n.id) { mutableStateOf(n.name) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "%02d".format(n.autoNo),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                color = BaibaiInk.copy(alpha = 0.3f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(Color(0xCCFFFFFF), RoundedCornerShape(12.dp))
                                    .border(1.dp, InputBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                BasicTextField(
                                    value = nameText,
                                    onValueChange = { nameText = it },
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 14.sp, color = BaibaiInk),
                                    cursorBrush = SolidColor(BaibaiAccent),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { inner ->
                                        Box {
                                            if (nameText.isEmpty()) {
                                                Text("起个名（如大伯家）", fontSize = 14.sp, color = BaibaiInk.copy(alpha = 0.3f))
                                            }
                                            inner()
                                        }
                                    },
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .width(96.dp)
                                    .height(48.dp)
                                    .background(
                                        if (nameText != n.name) baibaiChipGradient() else BaibaiInk.copy(alpha = 0.1f),
                                        RoundedCornerShape(12.dp),
                                    )
                                    .clickable(enabled = nameText != n.name) { mutate(Review.renameNode(s, n.id, nameText.trim())) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (nameText != n.name) "改名" else "已改",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (nameText != n.name) Color.White else BaibaiInk.copy(alpha = 0.4f),
                                )
                            }
                        }
                        val visits = s.visits.filter { it.nodeId == n.id }
                        if (visits.size > 1) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(start = 30.dp),
                            ) {
                                visits.forEachIndexed { idx, v ->
                                    val visitIdx = s.visits.indexOf(v)
                                    Box(
                                        modifier = Modifier
                                            .background(ChipIdleBg, RoundedCornerShape(999.dp))
                                            .clickable { mutate(Review.splitVisit(s, visitIdx)) }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        Text(
                                            "拆第 ${idx + 1} 次（${fmtClock(v.arriveT)}）",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BaibaiInk.copy(alpha = 0.6f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            /* ---------- 合并两户（点选芯片） ---------- */
            GlassCard {
                SectionTitle("合并两户（误拆并为一户时）")
                Spacer(Modifier.height(8.dp))
                Text("点选两户：第一个保留，第二个并入", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = BaibaiInk.copy(alpha = 0.6f))
                val selected = remember { mutableStateListOf<String>() }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    s.nodes.forEach { n ->
                        val isSel = n.id in selected
                        Box(
                            modifier = Modifier
                                .background(if (isSel) baibaiChipGradient() else ChipIdleBg, RoundedCornerShape(999.dp))
                                .clickable {
                                    if (isSel) selected.remove(n.id)
                                    else {
                                        if (selected.size >= 2) selected.removeAt(0)
                                        selected.add(n.id)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                n.name.ifEmpty { "户${n.autoNo}" },
                                color = if (isSel) Color.White else BaibaiInk,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(baibaiPrimaryGradient(), RoundedCornerShape(12.dp))
                        .clickable(enabled = selected.size == 2) {
                            mutate(Review.mergeNodes(s, selected[0], selected[1]))
                            selected.clear()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "合并所选两户",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White.copy(alpha = if (selected.size == 2) 1f else 0.4f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            /* ---------- 异常跳变点 ---------- */
            val jumps = s.points.filter { it.jump == true }
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("异常跳变点")
                    Spacer(Modifier.weight(1f))
                    if (jumps.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .background(BaibaiAccent2.copy(alpha = 0.1f), RoundedCornerShape(999.dp))
                                .clickable {
                                    var next = s
                                    jumps.forEach { next = Review.removePoint(next, it.t) }
                                    mutate(next)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("剔除全部 (${jumps.size})", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = BaibaiAccent2)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (jumps.isEmpty()) {
                    Text("无异常跳变点", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BaibaiInk.copy(alpha = 0.6f))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        jumps.take(10).forEach { p ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ChipIdleBg, RoundedCornerShape(12.dp))
                                    .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                            ) {
                                Text("${fmtClock(p.t)} · 精度 ${p.acc.toInt()}m", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = BaibaiInk.copy(alpha = 0.6f))
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "剔除",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BaibaiInk.copy(alpha = 0.3f),
                                    modifier = Modifier
                                        .clickable { mutate(Review.removePoint(s, p.t)) }
                                        .padding(vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            /* ---------- 漏访检查（今年清单，F-9） ---------- */
            val yearPlan = remember(s) { RecorderHub.store.loadPlan(s.year) }
            GlassCard {
                SectionTitle("漏访检查")
                Spacer(Modifier.height(12.dp))
                if (yearPlan == null) {
                    Text("今年还没有清单。回记录页 →「清单」先导入/添加。", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BaibaiInk.copy(alpha = 0.6f))
                } else if (yearPlan.items.isEmpty()) {
                    Text("今年清单是空的。", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BaibaiInk.copy(alpha = 0.6f))
                } else {
                    val res = remember(s, yearPlan) { io.github.chenchen913.baibai.core.plan.PlanOps.matchPlan(s, yearPlan) }
                    if (res.missing.isEmpty()) {
                        Text("清单 ${yearPlan.items.size} 户全部到访，没有漏拜！", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BaibaiInk.copy(alpha = 0.6f))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = BaibaiAccent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("疑似漏访 ${res.missing.size} 户", fontSize = 12.sp, fontWeight = FontWeight.Black, color = BaibaiAccent)
                        }
                        Spacer(Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            res.missing.forEach { it ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MissRowBg, RoundedCornerShape(12.dp))
                                        .border(1.dp, MissRowBorder, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                ) {
                                    Text("${it.name.ifEmpty { "(未命名)" }} 没去！", fontSize = 14.sp, fontWeight = FontWeight.Black, color = BaibaiAccent)
                                    Spacer(Modifier.weight(1f))
                                    if (it.pos == null) {
                                        Text("无坐标·手动核对", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = BaibaiAccent.copy(alpha = 0.4f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            /* ---------- 套用去年的户名（D17） ---------- */
            val prevSession = remember(s) {
                RecorderHub.store.listSessions()
                    .filter { it.year < s.year }
                    .maxByOrNull { it.createdAt }
            }
            GlassCard {
                SectionTitle("套用去年的户名")
                Spacer(Modifier.height(12.dp))
                if (prevSession == null || prevSession.nodes.isEmpty()) {
                    Text("没有往年记录可套用。", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BaibaiInk.copy(alpha = 0.6f))
                } else {
                    Text("点击候选名立即套用到该户（按距离排序，取前 3）：", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = BaibaiInk.copy(alpha = 0.6f))
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.nodes.forEach { n ->
                            val cands = io.github.chenchen913.baibai.core.plan.PlanOps.nameCandidates(n.pos, prevSession.nodes, 3)
                            if (cands.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(n.name.ifEmpty { "户${n.autoNo}" }, fontWeight = FontWeight.Black, color = BaibaiInk, fontSize = 13.sp)
                                    Spacer(Modifier.width(8.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        cands.forEach { c ->
                                            Box(
                                                modifier = Modifier
                                                    .background(ChipIdleBg, RoundedCornerShape(999.dp))
                                                    .clickable { mutate(Review.renameNode(s, n.id, c.name)) }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                            ) {
                                                Text("${c.name}（${c.distM.toInt()}m）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BaibaiInk)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "提示：拜完年后系统自动对比漏了谁",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = BaibaiInk.copy(alpha = 0.3f),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 玻璃分组卡（白 60% + 白 40% 描边 + 24dp 圆角 + 20dp 内边距） */
@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlassCardBg, RoundedCornerShape(RoundedGlass))
            .border(1.dp, GlassCardBorder, RoundedCornerShape(RoundedGlass))
            .padding(20.dp),
    ) {
        content()
    }
}
