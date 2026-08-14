package io.github.chenchen913.baibai

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
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

/** 回顾页（A-M2）：Canvas 轨迹回放 + 收拾（改名/合并点选芯片/拆分/跳变剔除） */
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
            Text("${s.date} · 拜年复盘", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = BaibaiInk)
            Spacer(Modifier.weight(1f))
            Button(onClick = onOptimize, colors = ButtonDefaults.buttonColors(containerColor = BaibaiAccent)) {
                Text("三线对比", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xAAFFFFFF)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Box(Modifier.padding(6.dp)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(W / H)
                        .background(Color(0xFFF3EAD9)),
                ) {
                    val path = Path()
                    plan.pts.forEachIndexed { i, p ->
                        if (i == 0) path.moveTo(p.x.toFloat(), p.y.toFloat())
                        else path.lineTo(p.x.toFloat(), p.y.toFloat())
                    }
                    // 底轨
                    drawPath(path.asComposePath(), color = BaibaiAccent.copy(alpha = 0.18f), style = Stroke(width = 4f, cap = StrokeCap.Round))
                    // 已播部分
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
                    // 节点与标签
                    val labelPaint = TextPaint().apply {
                        color = 0xFF5A3A2A.toInt()
                        textSize = 15f * density
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
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { playing = !playing }, colors = ButtonDefaults.buttonColors(containerColor = BaibaiAccent)) {
                Text(if (playing) "⏸ 暂停" else "▶ 播放")
            }
            OutlinedButton(onClick = { progressMs = 0; playing = false }) { Text("重置", color = BaibaiInk) }
            OutlinedButton(onClick = { speed = 1f }) { Text("1x", color = BaibaiInk) }
            OutlinedButton(onClick = { speed = 2f }) { Text("2x", color = BaibaiInk) }
            OutlinedButton(onClick = { speed = 4f }) { Text("4x", color = BaibaiInk) }
            Spacer(Modifier.weight(1f))
            Text("${fmtDur(progressMs)}/${fmtDur(plan.totalMs)}", fontSize = 12.sp, color = BaibaiInk.copy(alpha = 0.7f))
        }

        Spacer(Modifier.height(14.dp))

        // ---------- 户名与收拾 ----------
        Text("户名与收拾", fontWeight = FontWeight.ExtraBold, color = BaibaiInk)
        s.nodes.forEach { n ->
            var nameText by remember(n.id) { mutableStateOf(n.name) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("户${n.autoNo}", fontWeight = FontWeight.Bold, color = BaibaiInk)
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    singleLine = true,
                    placeholder = { Text("起个名（如大伯家）") },
                )
                Button(
                    onClick = { mutate(Review.renameNode(s, n.id, nameText.trim())) },
                    enabled = nameText != n.name,
                    colors = ButtonDefaults.buttonColors(containerColor = BaibaiAccent),
                ) { Text("改名") }
            }
            val visits = s.visits.filter { it.nodeId == n.id }
            if (visits.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 34.dp, bottom = 4.dp)) {
                    visits.forEachIndexed { idx, v ->
                        val visitIdx = s.visits.indexOf(v)
                        OutlinedButton(onClick = { mutate(Review.splitVisit(s, visitIdx)) }) {
                            Text("拆第 ${idx + 1} 次（${fmtClock(v.arriveT)}）", fontSize = 12.sp, color = BaibaiInk)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---------- 合并两户（点选芯片） ----------
        Text("合并两户（误拆并为一户时）", fontWeight = FontWeight.ExtraBold, color = BaibaiInk)
        val selected = remember { mutableStateListOf<String>() }
        Text("点选两户：第一个保留，第二个并入", fontSize = 12.sp, color = BaibaiInk.copy(alpha = 0.6f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            s.nodes.forEach { n ->
                val isSel = n.id in selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isSel) BaibaiAccent else Color(0xAAFFFFFF))
                        .clickable {
                            if (isSel) selected.remove(n.id)
                            else {
                                if (selected.size >= 2) selected.removeAt(0)
                                selected.add(n.id)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(n.name.ifEmpty { "户${n.autoNo}" }, color = if (isSel) Color.White else BaibaiInk, fontSize = 13.sp)
                }
            }
        }
        Button(
            onClick = {
                if (selected.size == 2) {
                    mutate(Review.mergeNodes(s, selected[0], selected[1]))
                    selected.clear()
                }
            },
            enabled = selected.size == 2,
            colors = ButtonDefaults.buttonColors(containerColor = BaibaiAccent),
        ) { Text("合并所选两户") }

        Spacer(Modifier.height(10.dp))

        // ---------- 异常跳变点 ----------
        Text("异常跳变点", fontWeight = FontWeight.ExtraBold, color = BaibaiInk)
        val jumps = s.points.filter { it.jump == true }
        if (jumps.isEmpty()) {
            Text("无异常跳变点 🎉", fontSize = 13.sp, color = BaibaiInk.copy(alpha = 0.6f))
        } else {
            Button(
                onClick = {
                    var next = s
                    jumps.forEach { next = Review.removePoint(next, it.t) }
                    mutate(next)
                },
                colors = ButtonDefaults.buttonColors(containerColor = BaibaiAccent2),
            ) { Text("剔除全部（${jumps.size} 个）") }
            jumps.take(10).forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("${fmtClock(p.t)} · 精度 ${p.acc.toInt()}m", fontSize = 12.sp, color = BaibaiInk.copy(alpha = 0.7f))
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { mutate(Review.removePoint(s, p.t)) }) {
                        Text("剔除", fontSize = 12.sp, color = BaibaiInk)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- 漏访检查（今年清单，F-9） ----------
        Text("漏访检查（今年清单）", fontWeight = FontWeight.ExtraBold, color = BaibaiInk)
        val plan = remember(s) { RecorderHub.store.loadPlan(s.year) }
        if (plan == null) {
            Text("今年还没有清单。回记录页 →「清单」先导入/添加。", fontSize = 13.sp, color = BaibaiInk.copy(alpha = 0.6f))
        } else if (plan.items.isEmpty()) {
            Text("今年清单是空的。", fontSize = 13.sp, color = BaibaiInk.copy(alpha = 0.6f))
        } else {
            val res = remember(s, plan) { io.github.chenchen913.baibai.core.plan.PlanOps.matchPlan(s, plan) }
            if (res.missing.isEmpty()) {
                Text("✅ 清单 ${plan.items.size} 户全部到访，没有漏拜！", fontSize = 13.sp, color = BaibaiInk)
            } else {
                Text("⚠️ 疑似漏访 ${res.missing.size} 户：", fontSize = 13.sp, color = Color(0xFFB3261E), fontWeight = FontWeight.Bold)
                res.missing.forEach { it ->
                    Text(
                        "❌ ${it.name.ifEmpty { "(未命名)" }} 没去！" + if (it.pos == null) "（无坐标·手动核对）" else "",
                        fontSize = 13.sp,
                        color = Color(0xFFB3261E),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- 套用去年的户名（D17） ----------
        Text("套用去年的户名", fontWeight = FontWeight.ExtraBold, color = BaibaiInk)
        val prevSession = remember(s) {
            RecorderHub.store.listSessions()
                .filter { it.year < s.year }
                .maxByOrNull { it.createdAt }
        }
        if (prevSession == null || prevSession.nodes.isEmpty()) {
            Text("没有往年记录可套用。", fontSize = 13.sp, color = BaibaiInk.copy(alpha = 0.6f))
        } else {
            Text("点击候选名立即套用到该户（按距离排序，取前 3）：", fontSize = 12.sp, color = BaibaiInk.copy(alpha = 0.6f))
            s.nodes.forEach { n ->
                val cands = io.github.chenchen913.baibai.core.plan.PlanOps.nameCandidates(n.pos, prevSession.nodes, 3)
                if (cands.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(n.name.ifEmpty { "户${n.autoNo}" }, fontWeight = FontWeight.Bold, color = BaibaiInk)
                        Spacer(Modifier.weight(0.2f))
                        cands.forEach { c ->
                            OutlinedButton(
                                onClick = { mutate(Review.renameNode(s, n.id, c.name)) },
                                modifier = Modifier.padding(start = 4.dp),
                            ) {
                                Text("${c.name}（${c.distM.toInt()}m）", fontSize = 11.sp, color = BaibaiInk)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}
