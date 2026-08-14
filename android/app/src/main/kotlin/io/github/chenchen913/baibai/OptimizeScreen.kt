package io.github.chenchen913.baibai

import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
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

private fun fmtKm(m: Double) = "%.2f".format(m / 1000)
private fun fmtMin(sec: Double) = "${(sec / 60).toInt()} 分 ${(sec % 60).toInt()} 秒"
private fun fmtPct(p: Double) = "${p.toInt()}%"

private fun easeInOutCubic(t: Float): Float =
    if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f

private val TAB_META = mapOf(
    RouteMode.WALK_TIME to Triple("走路时间最优", Color(0xFFC8402F), false),
    RouteMode.WALK_DIST to Triple("走路距离最优", Color(0xFFE8A23D), false),
    RouteMode.FLY to Triple("飞行最优", Color(0xFFC9971C), true),
)

private val ScoreBg = Color(0x66FFFFFF) // 白 40%
private val ScoreBorder = Color(0x4DFFFFFF) // 白 30%
private val ChipIdleBg = Color(0x99FFFFFF) // 白 60%

/** 三线对比页（A-M3）：成绩单四卡 + 三线推演动画 + 压轴 morph
 * 视觉参照 uxpilot-export/1-baibai - Comparison.html */
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
                Text("${session.date} · 三线对比", fontSize = 18.sp, fontWeight = FontWeight.Black, color = BaibaiInk)
            }

            Spacer(Modifier.height(16.dp))

            // 成绩单 2×2（M-2：实走卡补「路上时间」作为节省率对比基准）
            val scoreCards = listOf(
                ScoreSpec(Icons.Filled.DirectionsWalk, "今年实走", fmtKm(card.actualDistM), "km", "路上 ${fmtMin(card.actualMoveSec)} · 骑行 ${fmtKm(card.bikeDistM)} · 全天 ${fmtMin(card.actualTotalSec)}", null),
                ScoreSpec(Icons.Filled.Psychology, "时间最优(理论)", fmtMin(card.timeOptSec), "", "比实走省 ${fmtPct(card.savingsTimePct)}", BaibaiAccent),
                ScoreSpec(Icons.Filled.Straighten, "距离最优", fmtKm(card.distOptM), "km", "省 ${fmtPct(card.savingsDistPct)}", BaibaiAccent2),
                ScoreSpec(Icons.Filled.Send, "如果能飞", fmtKm(card.flyOptM), "km", "少走 ${fmtPct(card.savingsFlyPct)}", BaibaiAccent2),
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScoreCard(scoreCards[0], Modifier.weight(1f))
                    ScoreCard(scoreCards[1], Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScoreCard(scoreCards[2], Modifier.weight(1f))
                    ScoreCard(scoreCards[3], Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(16.dp))

            // 画布卡：淡红实走 + 金色虚线最优 + 右上竖排芯片
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(RoundedCanvas))
                    .background(
                        Brush.verticalGradient(listOf(Color(0xCCFFFFFF), Color(0xCCFFF8EE))),
                    )
                    .border(1.dp, Color(0x80FFFFFF), RoundedCornerShape(RoundedCanvas)),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val scale = min(size.width / W, size.height / H)
                    val ox = (size.width - W * scale) / 2f
                    val oy = (size.height - H * scale) / 2f
                    withTransform({
                        translate(ox, oy)
                        scale(scale, scale, pivot = Offset.Zero)
                    }) {
                        val (_, routeColor, _) = TAB_META.getValue(tab)
                        // 实走轨迹（淡红 30%，morph 时淡出）
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
                                color = routeColor.copy(alpha = if (lit) 1f else 0.3f),
                                style = Stroke(
                                    width = if (lit) 4f else 1.5f,
                                    cap = StrokeCap.Round,
                                    pathEffect = if (e.known) null else PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                                ),
                            )
                        }

                        // 节点：家金色（白字「家」），其余白底红边 + 红色序号（设计稿：序号在圆内）
                        val idxPaint = TextPaint().apply {
                            color = 0xFFC8402F.toInt()
                            textSize = 9f * density / scale
                            textAlign = Paint.Align.CENTER
                            isFakeBoldText = true
                        }
                        val homePaint = TextPaint().apply {
                            color = 0xFFFFFFFF.toInt()
                            textSize = 8f * density / scale
                            textAlign = Paint.Align.CENTER
                            isFakeBoldText = true
                        }
                        route.order.forEachIndexed { k, id ->
                            val p = proj(id)
                            val isHome = id == "home"
                            drawCircle(
                                if (isHome) BaibaiAccent2 else Color.White,
                                radius = if (isHome) 10f else 8f,
                                center = Offset(p.x.toFloat(), p.y.toFloat()),
                            )
                            drawCircle(
                                if (isHome) BaibaiAccent2 else routeColor,
                                radius = if (isHome) 10f else 8f,
                                style = Stroke(width = 2f),
                                center = Offset(p.x.toFloat(), p.y.toFloat()),
                            )
                            val label = if (isHome) "家" else "$k"
                            val paint = if (isHome) homePaint else idxPaint
                            drawContext.canvas.nativeCanvas.drawText(
                                label,
                                p.x.toFloat(),
                                p.y.toFloat() + paint.textSize * 0.35f,
                                paint,
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

                // 右上竖排模式芯片（选中红实心，未选中白 60%）
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                ) {
                    TAB_META.forEach { (mode, meta) ->
                        val active = mode == tab
                        Box(
                            modifier = Modifier
                                .then(
                                    if (active) {
                                        Modifier.background(baibaiChipGradient(), RoundedCornerShape(4.dp))
                                    } else {
                                        Modifier.background(ChipIdleBg, RoundedCornerShape(4.dp))
                                    },
                                )
                                .clickable {
                                    tab = mode
                                    revealK = Int.MAX_VALUE // 切换即显示完整路线
                                    morphT = null
                                }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                meta.first,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = if (active) Color.White else BaibaiInk.copy(alpha = 0.4f),
                            )
                        }
                    }
                }

                // 画布底部说明行
                Text(
                    if (tab == RouteMode.FLY) "飞行视角：直线距离，纯几何幻想" else "虚线 = 今年没走过的路段（估算）；实线 = 实走数据",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = BaibaiInk.copy(alpha = 0.4f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            // 按钮行：推演（红）/ 压轴动画（金）/ 重置（56dp 方块）
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .background(baibaiPrimaryGradient(), RoundedCornerShape(16.dp))
                        .clickable { morphT = null; revealAnim = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("推演", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .background(baibaiGoldGradient(), RoundedCornerShape(16.dp))
                        .clickable {
                            revealK = Int.MAX_VALUE
                            morphT = 0f
                            morphTrigger += 1
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("压轴动画", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color.White)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(ChipIdleBg, RoundedCornerShape(16.dp))
                        .clickable {
                            revealK = Int.MAX_VALUE
                            morphT = null
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重置", tint = BaibaiInk.copy(alpha = 0.2f), modifier = Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private data class ScoreSpec(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val value: String,
    val unit: String,
    val sub: String,
    val subColor: Color?,
)

@Composable
private fun ScoreCard(spec: ScoreSpec, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(ScoreBg, RoundedCornerShape(RoundedScore))
            .border(1.dp, ScoreBorder, RoundedCornerShape(RoundedScore))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(spec.icon, contentDescription = null, tint = BaibaiAccent, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(6.dp))
            Text(spec.title, fontSize = 11.sp, fontWeight = FontWeight.Black, color = BaibaiInk.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(spec.value, fontSize = 16.sp, fontWeight = FontWeight.Black, color = BaibaiAccent)
            if (spec.unit.isNotEmpty()) {
                Text(spec.unit, fontSize = 9.sp, fontWeight = FontWeight.Black, color = BaibaiAccent, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
            }
        }
        Text(
            spec.sub,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = spec.subColor ?: BaibaiInk.copy(alpha = 0.4f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
