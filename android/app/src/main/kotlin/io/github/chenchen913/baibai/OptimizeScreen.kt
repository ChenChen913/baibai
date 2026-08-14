package io.github.chenchen913.baibai

import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.optimize.Optimize
import io.github.chenchen913.baibai.core.optimize.RouteMode
import io.github.chenchen913.baibai.core.playback.Playback
import io.github.chenchen913.baibai.core.polyline.Polyline
import io.github.chenchen913.baibai.core.track.Track
import io.github.chenchen913.baibai.core.track.XY
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

private const val W = 480f
private const val H = 560f
private const val MORPH_POINTS = 180
private const val MORPH_MS = 3000L

private fun fmtKm(m: Double) = "%.2f km".format(m / 1000)
private fun fmtMin(sec: Double) = "${(sec / 60).toInt()} 分 ${(sec % 60).toInt()} 秒"
private fun fmtPct(p: Double) = "${p.toInt()}%"

private fun easeInOutCubic(t: Float): Float =
    if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f

private val TAB_META = mapOf(
    RouteMode.WALK_TIME to Triple("走路时间最优", Color(0xFFC8402F), false),
    RouteMode.WALK_DIST to Triple("走路距离最优", Color(0xFFE8A23D), false),
    RouteMode.FLY to Triple("飞行最优", Color(0xFFC9971C), true),
)

/** 三线对比页（A-M3）：成绩单四卡 + 三线推演动画 + ✨压轴 morph */
@Composable
fun OptimizeScreen(session: SessionData, onBack: () -> Unit) {
    val routes = remember(session) { Optimize.optimizeSession(session) }
    val card = remember(session) { Optimize.scorecard(session, routes) }
    val plan = remember(session) { Playback.buildPlan(session, W.toDouble(), H.toDouble()) }
    val density = LocalDensity.current.density

    var tab by remember { mutableStateOf(RouteMode.WALK_TIME) }
    var revealK by remember { mutableIntStateOf(Int.MAX_VALUE) } // 初始显示完整路线
    var revealAnim by remember { mutableStateOf(false) }
    var morphT by remember { mutableStateOf<Float?>(null) }
    var morphTrigger by remember { mutableIntStateOf(0) }

    val route = routes.first { it.mode == tab }
    fun posOf(id: String) = if (id == "home") session.home else session.nodes.first { it.id == id }.pos
    fun proj(id: String) = Track.projectToView(listOf(posOf(id)), W.toDouble(), H.toDouble())[0]

    // morph 端点：实走路径与飞行星形（同点数重采样）
    val actualRes = remember(plan) {
        Polyline.resamplePolyline(plan.pts.map { XY(it.x, it.y) }, MORPH_POINTS)
    }
    val flyRoute = routes.first { it.mode == RouteMode.FLY }
    val flyRes = remember(session) {
        val flyLatLng = Polyline.routePolyline(session, flyRoute.order)
        Polyline.resamplePolyline(Track.projectToView(flyLatLng, W.toDouble(), H.toDouble()), MORPH_POINTS)
    }

    // 推演动画：逐段点亮
    LaunchedEffect(revealAnim, tab) {
        if (!revealAnim) return@LaunchedEffect
        revealK = 0
        val total = maxOf(1000L, route.edges.size * 350L)
        val t0 = System.currentTimeMillis()
        while (true) {
            val f = min(1.0, (System.currentTimeMillis() - t0).toDouble() / total)
            revealK = floor(f * route.edges.size).toInt()
            if (f >= 1.0) break
            delay(16)
        }
        revealK = route.edges.size
        revealAnim = false
    }

    // 压轴 morph：实走轨迹 → 金色星图（以 trigger 为键，避免每帧自取消）
    LaunchedEffect(morphTrigger) {
        if (morphTrigger == 0) return@LaunchedEffect
        morphT = 0f
        val t0 = System.currentTimeMillis()
        while (true) {
            val f = min(1f, (System.currentTimeMillis() - t0).toFloat() / MORPH_MS)
            morphT = easeInOutCubic(f)
            if (f >= 1f) break
            delay(16)
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
                Text("← 复盘", color = BaibaiInk)
            }
            Spacer(Modifier.weight(1f))
            Text("${session.date} · 三线对比", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = BaibaiInk)
        }

        Spacer(Modifier.height(10.dp))

        // 成绩单四卡
        val cards = listOf(
            "🧧 今年实走" to "${fmtKm(card.actualDistM)}\n路上 ${fmtMin(card.actualMoveSec)} · 骑行 ${fmtKm(card.bikeDistM)} · 全天 ${fmtMin(card.actualTotalSec)}",
            "🚶 走路时间最优（理论）" to "${fmtMin(card.timeOptSec)}\n比实走路上时间省 ${fmtPct(card.savingsTimePct)}",
            "📏 距离最优" to "${fmtKm(card.distOptM)}\n省 ${fmtPct(card.savingsDistPct)}",
            "✈️ 如果能飞" to "${fmtKm(card.flyOptM)}\n少走 ${fmtPct(card.savingsFlyPct)}",
        )
        cards.forEach { (title, body) ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xAAFFFFFF)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(Modifier.padding(12.dp, 10.dp)) {
                    Text(title, fontSize = 13.sp, color = BaibaiInk.copy(alpha = 0.65f))
                    Text(body, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = BaibaiAccent)
                }
            }
        }

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
                    val (_, color, _) = TAB_META.getValue(tab)
                    // 实走轨迹（morph 时淡出）
                    val actualPath = Path()
                    plan.pts.forEachIndexed { i, p ->
                        if (i == 0) actualPath.moveTo(p.x.toFloat(), p.y.toFloat())
                        else actualPath.lineTo(p.x.toFloat(), p.y.toFloat())
                    }
                    val actualAlpha = if (morphT != null) (1f - morphT!!) * 0.3f else 0.3f
                    drawPath(
                        actualPath.asComposePath(),
                        color = BaibaiAccent.copy(alpha = actualAlpha),
                        style = Stroke(width = 3f, cap = StrokeCap.Round),
                    )

                    // 路线边：实线/虚线，逐段点亮
                    route.edges.forEachIndexed { k, e ->
                        val a = proj(e.from)
                        val b = proj(e.to)
                        val lit = k < revealK
                        val path = Path().apply {
                            moveTo(a.x.toFloat(), a.y.toFloat())
                            lineTo(b.x.toFloat(), b.y.toFloat())
                        }
                        drawPath(
                            path.asComposePath(),
                            color = color.copy(alpha = if (lit) 1f else 0.3f),
                            style = Stroke(width = if (lit) 4f else 1.5f, cap = StrokeCap.Round),
                            pathEffect = if (e.known) null else PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                        )
                    }

                    // 节点与序号
                    val labelPaint = TextPaint().apply {
                        color = 0xFF5A3A2A.toInt()
                        textSize = 15f * density
                        textAlign = Paint.Align.CENTER
                        isFakeBoldText = true
                    }
                    route.order.forEachIndexed { k, id ->
                        val p = proj(id)
                        val isHome = id == "home"
                        drawCircle(
                            if (isHome) BaibaiAccent2 else Color.White,
                            radius = if (isHome) 10f else 9f,
                            center = Offset(p.x.toFloat(), p.y.toFloat()),
                        )
                        drawCircle(
                            color,
                            radius = if (isHome) 10f else 9f,
                            style = Stroke(width = 2.5f),
                            center = Offset(p.x.toFloat(), p.y.toFloat()),
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            if (isHome) "家" else "$k",
                            p.x.toFloat(),
                            (p.y - if (isHome) 16 else 14).toFloat(),
                            labelPaint,
                        )
                    }

                    // 压轴 morph 金色路径
                    morphT?.let { mt ->
                        if (actualRes.isNotEmpty() && flyRes.isNotEmpty()) {
                            val mp = Polyline.lerpPolyline(actualRes, flyRes, mt.toDouble())
                            val morphPath = Path()
                            mp.forEachIndexed { i, p ->
                                if (i == 0) morphPath.moveTo(p.x.toFloat(), p.y.toFloat())
                                else morphPath.lineTo(p.x.toFloat(), p.y.toFloat())
                            }
                            drawPath(
                                morphPath.asComposePath(),
                                color = Color(0xFFC9971C),
                                style = Stroke(width = 4f, cap = StrokeCap.Round),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TAB_META.forEach { (mode, meta) ->
                OutlinedButton(
                    onClick = {
                        tab = mode
                        revealK = Int.MAX_VALUE // 切换即显示完整路线
                        morphT = null
                    },
                ) {
                    Text(meta.first, color = if (mode == tab) meta.second else BaibaiInk)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { morphT = null; revealAnim = true },
                colors = ButtonDefaults.buttonColors(containerColor = BaibaiAccent),
            ) { Text("▶ 推演") }
            Button(
                onClick = {
                    revealK = Int.MAX_VALUE
                    morphT = 0f
                    morphTrigger += 1
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC9971C)),
            ) { Text("✨ 压轴动画") }
            OutlinedButton(
                onClick = {
                    revealK = Int.MAX_VALUE
                    morphT = null
                },
            ) { Text("重置", color = BaibaiInk) }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            if (tab == RouteMode.FLY) "飞行视角：直线距离，纯几何幻想 ✨"
            else "虚线 = 今年没走过的路段（估算）；实线 = 实走数据",
            fontSize = 12.sp,
            color = BaibaiInk.copy(alpha = 0.6f),
        )

        Spacer(Modifier.height(20.dp))
    }
}
