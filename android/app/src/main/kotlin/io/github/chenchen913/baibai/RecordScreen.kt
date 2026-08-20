package io.github.chenchen913.baibai

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.chenchen913.baibai.core.cny.Cny
import io.github.chenchen913.baibai.core.geo.Geo
import io.github.chenchen913.baibai.core.model.HouseNode
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.PlanItem
import io.github.chenchen913.baibai.core.model.SessionState
import io.github.chenchen913.baibai.core.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/* ============ M2 新版首页（对齐网页版 ui.ts / style.css 智驾风格设计稿） ============
 * 结构：满屏地图（底层）→ 灵动岛 + 顶部玻璃看板（品牌/日期/反馈/GPS/三格数据/状态条）
 *       → 右侧悬浮地图工具（回中/总览/图层）→ 底部座舱（核心行动键 + 深色导航坞）。
 * 【换肤不换骨】老版全部功能保留：开始/暂停/继续/结束/撤销、走路骑车切换、清单/历史
 * 跳转、提示音开关、拜年日期选择、实时轨迹与家/户标记、IDLE 态定位回中、崩溃恢复弹窗
 * （AppRoot 层，与本页无关）。修改本文件时请勿删改各区块的「保留注释」，防止回退。 */

// ---------- 配色（对齐 style.css；Android 无 backdrop blur，用更高不透明度近似玻璃） ----------
private val GlassBg = Color(0xEBFFFFFF) // 玻璃看板底：白 92%（web：白 88% + blur18）
private val GlassBorder = Color(0xBFFFFFFF) // 玻璃描边：白 75%
private val DockBg = Color(0xEB1C1917) // 底部导航坞：墨黑 92%
private val DockBorder = Color(0x1FFFFFFF) // 坞描边：白 12%
private val InkDark = Color(0xFF1C1917) // 品牌标题墨色
private val InkMid = Color(0xFF44403C) // 正文石色
private val InkSoft = Color(0xFF78716C) // 次要文字
private val InkLabel = Color(0xFF57534E) // 标签文字
private val GpsGood = Color(0xFF10B981)
private val GpsFair = Color(0xFFF59E0B)
private val GpsWeak = Color(0xFFEF4444)
private val BannerGray = Color(0xFFA8A29E)
private val DateChipBg = Color(0xE6FEF3C7) // 日期徽章底：amber-100
private val DateChipBorder = Color(0x4DF59E0B)
private val DateChipText = Color(0xFFB45309) // amber-700
private val DashDivider = Color(0xCCE7E5E4) // 看板分隔线
private val DockInactive = Color(0xFFA8A29E) // 坞未选中灰
private val BrandFire = Color(0xFFDC2626) // 品牌火苗红
private val MapToolBg = Color(0xEBFFFFFF) // 右侧工具底：白 92%

/** 主行动键渐变（web .btn-cny-cta：135deg #c8402f → #e0533c → #e8a23d） */
private fun ctaGradient(): Brush = Brush.linearGradient(
    listOf(Color(0xFFC8402F), Color(0xFFE0533C), Color(0xFFE8A23D)),
)

/** 「继续出发」键渐变（web .btn-cny-resume：135deg #059669 → #10b981 → #14b8a6） */
private fun resumeGradient(): Brush = Brush.linearGradient(
    listOf(Color(0xFF059669), Color(0xFF10B981), Color(0xFF14B8A6)),
)

private fun fmtClock(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

/** 实走距离（km）：相邻入库点 haversine 累加（与网页版 render() 口径一致） */
private fun trackKm(points: List<TrackPoint>): Double {
    var m = 0.0
    for (i in 1 until points.size) m += Geo.haversineM(points[i - 1].pos, points[i].pos)
    return m / 1000.0
}

/** GPS 徽章 + 灵动岛文案（对齐网页版 render() 的四档判定） */
private data class GpsUi(val pill: String, val island: String, val dot: Color)

private fun gpsUi(acc: Double?, waiting: Boolean): GpsUi {
    val r = acc?.toInt() ?: 0
    return when {
        acc != null && acc <= 10 -> GpsUi("±" + r + "m 良好", "GPS 锁定 · 精度 ±" + r + "m", GpsGood)
        acc != null && acc <= 30 -> GpsUi("±" + r + "m 一般", "GPS 良好 · 精度 ±" + r + "m", GpsFair)
        acc != null -> GpsUi("网络 ±" + r + "m", "网络粗略定位 ±" + r + "m", GpsWeak)
        waiting -> GpsUi("搜星中…", "正在获取卫星定位…", GpsWeak)
        else -> GpsUi("定位就绪", "待机就绪 · GPS 信号极佳", GpsGood)
    }
}

/** 状态横幅文案 + 脉冲点颜色（对齐网页版 render() 的四态判定；带入清单户名） */
private fun bannerInfo(
    st: SessionState,
    waiting: Boolean,
    visits: Int,
    planItems: List<PlanItem>,
): Pair<String, Color> = when (st) {
    SessionState.IDLE ->
        if (waiting) "正在获取定位中，请稍候…" to GpsFair
        else "待机就绪 · 点击开始拜年记录" to GpsFair
    SessionState.WALKING -> {
        val next = planItems.getOrNull(visits)?.name
        ("正在前往第 " + (visits + 1) + " 户" + (next?.let { "($it)" } ?: "") + " 中…") to GpsGood
    }
    SessionState.PAUSED -> {
        val cur = planItems.getOrNull(visits - 1)?.name
        ("正在第 " + visits + " 户" + (cur?.let { "($it)" } ?: "") + " 停留喝茶/拜年中…") to GpsWeak
    }
    SessionState.FINISHED -> "本次拜年已完成保存，可复盘对比路线！" to BannerGray
}

/** 瓦片/地图链路统一日志 tag（P4）：logcat 过滤 BaibaiMap 即可看全链路 */
private const val MAP_LOG = "BaibaiMap"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(onStartRequest: () -> Unit, onHistory: () -> Unit, onPlan: () -> Unit) {
    val session by RecorderHub.session.collectAsState()
    val waiting by RecorderHub.waiting.collectAsState()
    val gpsAcc by RecorderHub.gpsAcc.collectAsState()
    val feedbackOn by RecorderHub.feedbackOn.collectAsState()
    val bizDate by RecorderHub.bizDate.collectAsState()

    val st = session?.state ?: SessionState.IDLE
    val mode = session?.currentMode ?: Mode.WALK
    val recording = st == SessionState.WALKING || st == SessionState.PAUSED
    // P9：拜访户数 = 唯一户数（nodes 不含 home，中途回家/回访不虚高）
    val visits = session?.nodes?.size ?: 0
    // 今年清单：看板「已拜访 N/M户」、横幅「下一户名字」、清单角标共用
    // （离开本页去清单页增删后返回，remember 重执行即自动刷新）
    val planItems = remember {
        RecorderHub.store.loadPlan(java.time.LocalDate.now().year)?.items ?: emptyList()
    }
    val distKm = session?.let { trackKm(it.points) } ?: 0.0
    val gps = gpsUi(gpsAcc, waiting)

    var showDatePicker by remember { mutableStateOf(false) }
    val mapCtl = remember { MapController() }
    var satLayer by remember { mutableStateOf(false) }
    var debugShown by remember { mutableStateOf(false) }

    // 满屏 edge-to-edge：地图铺满全屏（含状态栏/手势条后方），HUD 与座舱各自处理安全区
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F1EA)),
    ) {
        val mapPxH = constraints.maxHeight
        val fontScale = LocalDensity.current.fontScale
        // 看板三格等分；等宽时间 8 位 × 0.6em ≈ 4.8em，按列宽反推字号防溢出（老版 StatsRow 同款公式）
        val digitSp = (((maxWidth.value - 56f) / 3f) / 4.8f / fontScale).coerceIn(12f, 22f)

        // ---------- ① 底层：满屏实时地图 ----------
        MapLayer(ctl = mapCtl, pxHeight = mapPxH)

        // ---------- ② 顶部：灵动岛 + 玻璃看板 ----------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Island(text = gps.island)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandChip(bizDate = bizDate, onPickDate = { showDatePicker = true })
                Spacer(Modifier.weight(1f))
                FeedbackBtn(on = feedbackOn, onToggle = { RecorderHub.setFeedback(!feedbackOn) })
                Spacer(Modifier.width(6.dp))
                GpsPill(text = gps.pill, dot = gps.dot)
            }
            Spacer(Modifier.height(8.dp))
            Dashboard(
                st = st,
                waiting = waiting,
                visits = visits,
                planItems = planItems,
                distKm = distKm,
                digitSp = digitSp,
                onPlan = onPlan,
            )
        }

        // ---------- ③ 右侧悬浮工具：回中 / 总览 / 图层 ----------
        // 长按「图层」键可唤出/隐藏 map.html 诊断徽标（真机排障用，默认隐藏保持界面干净）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp, bottom = 170.dp),
        ) {
            MapToolBtn(
                icon = Icons.Filled.MyLocation,
                desc = "定位回中",
                onClick = {
                    val last = RecorderHub.recorder?.snapshot()?.points?.lastOrNull()
                    val fix = RecorderHub.source?.lastFix
                    val p = last?.pos ?: fix?.pos
                    val acc = last?.acc ?: fix?.acc ?: 0.0
                    if (p != null) {
                        mapCtl.exec("BaibaiMap.locate(" + p.lat + "," + p.lng + "," + acc + ")")
                    } else {
                        RecorderHub.toast("暂无定位，稍后再试")
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            MapToolBtn(
                icon = Icons.Filled.Route,
                desc = "总览全路线",
                onClick = {
                    val snap = RecorderHub.recorder?.snapshot()
                    if (snap != null && snap.points.size > 1) {
                        mapCtl.exec(
                            "BaibaiMap.setTrack(" + trackJson(snap.points) + "," + segBreaksJson(snap.points) + ")",
                        )
                    } else {
                        RecorderHub.toast("暂无轨迹可总览")
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            MapToolBtn(
                icon = Icons.Filled.Layers,
                desc = "切换卫星或矢量地图",
                onClick = {
                    satLayer = !satLayer
                    mapCtl.exec("BaibaiMap.switchLayer('" + (if (satLayer) "sat" else "street") + "')")
                },
                onLongClick = {
                    debugShown = !debugShown
                    mapCtl.exec("BaibaiMap.showDebug(" + debugShown + ")")
                    RecorderHub.toast(if (debugShown) "地图诊断徽标已显示" else "地图诊断徽标已隐藏")
                },
            )
        }

        // ---------- ④ 底部座舱：核心行动键（Tier 1）+ 导航坞（Tier 2） ----------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            if (st != SessionState.FINISHED) { // §8：已保存 → 自动跳转回顾页，不显示行动键
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (recording) {
                        SubAction(
                            icon = Icons.Filled.Flag,
                            iconColor = BaibaiAccent,
                            label = "结束复盘",
                            onClick = { RecorderHub.finishPressed() },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    when (st) {
                        SessionState.IDLE -> MainCta(
                            brush = ctaGradient(),
                            icon = Icons.Filled.PlayArrow,
                            title = "开始拜年记录",
                            sub = "开启 GPS 轨迹与到户记录",
                            onClick = onStartRequest,
                            modifier = Modifier.weight(1f),
                        )

                        SessionState.WALKING -> MainCta(
                            brush = ctaGradient(),
                            icon = Icons.Filled.Home,
                            title = "到一户了 · 记录停留",
                            sub = "点击自动标记到访并暂停",
                            onClick = { RecorderHub.pausePressed() },
                            modifier = Modifier.weight(1f),
                        )

                        SessionState.PAUSED -> MainCta(
                            brush = resumeGradient(),
                            icon = Icons.Filled.ArrowForward,
                            title = "拜完了 · 继续出发",
                            sub = "恢复 GPS 轨迹录制",
                            onClick = { RecorderHub.resumePressed() },
                            modifier = Modifier.weight(1f),
                        )

                        SessionState.FINISHED -> Spacer(Modifier.weight(1f))
                    }
                    if (recording) {
                        Spacer(Modifier.width(8.dp))
                        SubAction(
                            icon = Icons.Filled.RotateLeft,
                            iconColor = DateChipText,
                            label = "撤销上点",
                            onClick = { RecorderHub.undoPressed() },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            DockBar(
                mode = mode,
                recording = recording,
                planPending = planItems.size > visits,
                onWalk = { RecorderHub.setMode(Mode.WALK) },
                onBike = { RecorderHub.setMode(Mode.BIKE) },
                onPlan = onPlan,
                onHistory = onHistory,
            )
        }
    }

    // 拜年日期选择（默认今天；初一~初十自动显示「大年初X」徽章）
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = bizDate
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { ms ->
                            val picked = java.time.Instant.ofEpochMilli(ms)
                                .atZone(java.time.ZoneOffset.UTC)
                                .toLocalDate()
                            RecorderHub.setBizDate(picked)
                            RecorderHub.toast("拜年日期已设为 " + Cny.label(picked))
                        }
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/* ============ ① 满屏实时地图（WebView + Leaflet + 高德/OSM/Esri，免 Key 免费） ============
 * 【R5 真机结论保留，勿删】部分机型 WebView 在 Compose 内被测量为 0 高 → 瓦片下载成功却白屏：
 * 不依赖 Compose 测量，手动像素高写入 LayoutParams + onPageFinished 延迟 600ms 二次修正。
 * 【诊断版架构（2026-08-20 采纳外部分析），勿删】不拦截任何瓦片请求（无 shouldInterceptRequest /
 * TileCache 注入），WebView 像浏览器一样自取瓦片（网页版同链路已验证可用），变量降到最少。 */

/** 满屏地图控制器：Compose 侧单向 JS 桥（evaluateJavascript，主线程调用） */
private class MapController {
    var webView: WebView? = null

    fun exec(js: String) {
        webView?.evaluateJavascript(js, null)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MapLayer(ctl: MapController, pxHeight: Int) {
    val webView = remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    val session = RecorderHub.session.collectAsState().value
    val syncKey = (session?.state ?: SessionState.IDLE) to (session?.nodes?.size ?: 0)

    val appContext = LocalContext.current.applicationContext
    // 清理旧版遗留的瓦片缓存目录（旧版曾被高德风控占位图毒化；本版不再用缓存，直接清掉释放空间）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { java.io.File(appContext.cacheDir, "tiles").deleteRecursively() }
        }
    }

    // 全量同步：页面就绪 / 状态或户数变化 → 推整条轨迹 + 家/户标记
    LaunchedEffect(pageReady, syncKey) {
        if (!pageReady) return@LaunchedEffect
        val w = webView.value ?: return@LaunchedEffect
        val snap = RecorderHub.recorder?.snapshot()
        if (snap != null) {
            w.evaluateJavascript(
                "BaibaiMap.setTrack(" + trackJson(snap.points) + ", " + segBreaksJson(snap.points) + ")",
                null,
            )
            w.evaluateJavascript(
                "BaibaiMap.setNodes(" + latLngJson(snap.home) + ", " + nodesJson(snap.nodes) + ")",
                null,
            )
        } else {
            // P7：IDLE 态把地图中心移到用户上次的位置（当前定位源 / 系统最后已知位置 / 最近一次记录的家），
            // 而不是固定潍坊——避免用户误以为"地图加载错了"
            val lastFix = RecorderHub.source?.lastFix?.pos ?: systemLastKnown(appContext)
            // listSessions 已按 createdAt 降序；I/O 放后台线程，避免主线程解码卡顿（Low-6）
            val histHome = withContext(Dispatchers.IO) {
                RecorderHub.store.listSessions().firstOrNull()?.home
            }
            val c = lastFix ?: histHome
            if (c != null && (c.lat != 0.0 || c.lng != 0.0)) {
                w.evaluateJavascript("BaibaiMap.center(" + c.lat + "," + c.lng + ",14)", null)
            }
        }
    }

    // 增量跟随：每 1.2s 推最新定位点（JS 按时间戳去重，当前段实时增长）；
    // 等待首个定位期间（还没开始记录）也实时显示当前位置
    LaunchedEffect(Unit) {
        while (true) {
            delay(1200)
            if (pageReady) {
                val w = webView.value ?: continue
                val last = RecorderHub.recorder?.snapshot()?.points?.lastOrNull()
                if (last != null) {
                    w.evaluateJavascript(
                        "BaibaiMap.follow(" + last.pos.lat + "," + last.pos.lng + "," + last.acc + "," + last.t + ")",
                        null,
                    )
                } else {
                    RecorderHub.source?.lastFix?.let { lf ->
                        w.evaluateJavascript(
                            "BaibaiMap.locate(" + lf.pos.lat + "," + lf.pos.lng + "," + lf.acc + ")",
                            null,
                        )
                    }
                }
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    pxHeight,
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webView.value = this
                ctl.webView = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                settings.builtInZoomControls = false
                settings.setSupportZoom(false)
                settings.displayZoomControls = false
                // 瓦片由 WebView 像浏览器一样直接跨域取（https 瓦片源不受 file:// 同源限制），
                // 因此无需 allowUniversalAccessFromFileURLs / mixedContentMode 等安全放宽项
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                // P4：把 map.html 的 console.warn/error 接到 logcat（tag=BaibaiMap），真机可定位
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                        cm?.let {
                            Log.w(
                                MAP_LOG,
                                "[js:" + it.messageLevel() + "] " + it.message() +
                                    " @" + it.sourceId() + ":" + it.lineNumber(),
                            )
                        }
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageReady = true
                        view?.evaluateJavascript(
                            "window.BaibaiMap && window.BaibaiMap.invalidateSize && window.BaibaiMap.invalidateSize()",
                            null,
                        )
                        // R5 兜底：部分机型首帧测量仍为 0——延迟再强制一次高度并记日志，真机可见验证
                        view?.postDelayed(
                            {
                                view.layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    pxHeight,
                                )
                                view.requestLayout()
                                view.evaluateJavascript(
                                    "window.BaibaiMap && window.BaibaiMap.invalidateSize && window.BaibaiMap.invalidateSize()",
                                    null,
                                )
                                Log.i(MAP_LOG, "WebView 尺寸修正：h=" + view.height + "px（目标 " + pxHeight + "px）")
                            },
                            600,
                        )
                    }

                    // P4：页面/资源加载失败可见化
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        Log.w(
                            MAP_LOG,
                            "WebView 加载错误：" + request?.url + " code=" + error?.errorCode +
                                " " + error?.description,
                        )
                    }
                }
                loadUrl("file:///android_asset/baibai_map/map.html")
            }
        },
        onRelease = {
            webView.value = null
            ctl.webView = null
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** P7：读系统最后已知位置（GPS→网络），不启动持续定位、不申请权限；无权限/拿不到返回 null */
@SuppressLint("MissingPermission")
private fun systemLastKnown(context: android.content.Context): LatLng? {
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
        as? android.location.LocationManager ?: return null
    val loc = runCatching {
        lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
    }.getOrNull() ?: return null
    return LatLng(loc.latitude, loc.longitude)
}

/** 定位点列表 → JS 参数 JSON（[[lat,lng],...]） */
private fun trackJson(points: List<TrackPoint>): String {
    val arr = JSONArray()
    for (p in points) {
        val pair = JSONArray()
        pair.put(p.pos.lat)
        pair.put(p.pos.lng)
        arr.put(pair)
    }
    return arr.toString()
}

/** 家/户标记 → JS 参数 JSON（户名经 org.json 转义，防注入） */
private fun nodesJson(nodes: List<HouseNode>): String {
    val arr = JSONArray()
    for (n in nodes) {
        val o = JSONObject()
        o.put("n", n.autoNo)
        o.put("name", n.name)
        o.put("lat", n.pos.lat)
        o.put("lng", n.pos.lng)
        arr.put(o)
    }
    return arr.toString()
}

private fun latLngJson(p: LatLng): String =
    JSONObject().put("lat", p.lat).put("lng", p.lng).toString()

/** 段起点下标（seg 变化处）→ JS 参数 JSON：[0, 55, 120, ...] */
private fun segBreaksJson(points: List<TrackPoint>): String {
    val arr = JSONArray()
    var cur: String? = null
    for (i in points.indices) {
        val seg = points[i].seg
        if (seg != cur) {
            arr.put(i)
            cur = seg
        }
    }
    return arr.toString()
}

/* ============ ② 顶部：灵动岛 + 玻璃看板 ============ */

/** 脉冲圆点（web .status-pulse-dot / .island-dot 的呼吸动画） */
@Composable
private fun PulseDot(color: Color, size: Dp = 7.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(
        Modifier
            .size(size)
            .background(color.copy(alpha = alpha), CircleShape),
    )
}

/** 灵动岛（web .island：顶部黑色胶囊，动态显示 GPS 状态） */
@Composable
private fun Island(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .shadow(
                6.dp,
                RoundedCornerShape(999.dp),
                ambientColor = Color(0x33000000),
                spotColor = Color(0x4D000000),
            )
            .background(Color(0xD1000000), RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        PulseDot(GpsGood)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

/** 品牌胶囊（web .hud-brand-chip：火苗徽标 + 标题 + 日期徽章） */
@Composable
private fun BrandChip(bizDate: java.time.LocalDate, onPickDate: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .shadow(
                8.dp,
                RoundedCornerShape(999.dp),
                ambientColor = Color(0x14000000),
                spotColor = Color(0x1F000000),
            )
            .background(GlassBg, RoundedCornerShape(999.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(22.dp)
                .background(BrandFire, CircleShape),
        ) {
            Icon(
                Icons.Filled.Whatshot,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "拜拜 · 拜年智驾",
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Serif,
            color = InkDark,
        )
        Spacer(Modifier.width(8.dp))
        // 日期徽章：点击弹日期选择器（初一~初十自动显示「大年初X」）
        Box(
            modifier = Modifier
                .background(DateChipBg, RoundedCornerShape(999.dp))
                .border(1.dp, DateChipBorder, RoundedCornerShape(999.dp))
                .clickable(onClick = onPickDate)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                Cny.label(bizDate),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = DateChipText,
            )
        }
    }
}

/** P6：提示音/震动开关（web .hud-icon-btn 铃铛按钮，≥48dp 热区由 34dp 图标 + 无障碍支撑） */
@Composable
private fun FeedbackBtn(on: Boolean, onToggle: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .shadow(6.dp, CircleShape, ambientColor = Color(0x14000000), spotColor = Color(0x1F000000))
            .background(GlassBg, CircleShape)
            .border(1.dp, GlassBorder, CircleShape)
            .clickable(onClick = onToggle),
    ) {
        Icon(
            if (on) Icons.Filled.Notifications else Icons.Filled.NotificationsOff,
            contentDescription = if (on) "关闭提示音与震动" else "开启提示音与震动",
            tint = if (on) InkMid else Color(0xB3A8A29E),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** GPS 精度胶囊（web .gps-pill：圆点颜色按精度分档） */
@Composable
private fun GpsPill(text: String, dot: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .shadow(8.dp, RoundedCornerShape(999.dp), ambientColor = Color(0x14000000), spotColor = Color(0x1F000000))
            .background(GlassBg, RoundedCornerShape(999.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(dot, CircleShape),
        )
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = InkMid)
    }
}

/** 数据聚合看板（web .hud-dashboard：已拜访/记录用时/实走距离 + 动态状态条） */
@Composable
private fun Dashboard(
    st: SessionState,
    waiting: Boolean,
    visits: Int,
    planItems: List<PlanItem>,
    distKm: Double,
    digitSp: Float,
    onPlan: () -> Unit,
) {
    val (banner, dotColor) = bannerInfo(st, waiting, visits, planItems)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                12.dp,
                RoundedCornerShape(18.dp),
                ambientColor = Color(0x14000000),
                spotColor = Color(0x1F000000),
            )
            .background(GlassBg, RoundedCornerShape(18.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 已拜访：红色主数 + 计划总数十标（无清单时只显「户」）
            DashCell(label = "已拜访", modifier = Modifier.weight(1f)) {
                Text(
                    "$visits",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = BaibaiAccent,
                    maxLines = 1,
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    if (planItems.isNotEmpty()) "/" + planItems.size + "户" else "户",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = InkSoft,
                    maxLines = 1,
                )
            }
            DashVDivider()
            DashCell(label = "记录用时", modifier = Modifier.weight(1f)) {
                TickClock(digitSp)
            }
            DashVDivider()
            DashCell(label = "实走距离", modifier = Modifier.weight(1f)) {
                Text(
                    "%.1f".format(distKm),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = InkDark,
                    maxLines = 1,
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    "km",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = InkSoft,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DashDivider),
        )
        Spacer(Modifier.height(8.dp))
        // 动态状态条：脉冲点 + 状态文案 + 清单对表入口
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(dotColor)
            Spacer(Modifier.width(6.dp))
            Text(
                banner,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = InkMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "清单对表 ›",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = BaibaiAccent,
                modifier = Modifier.clickable(onClick = onPlan),
            )
        }
    }
}

/** 看板单格：数值行（基线对齐）+ 底部标签 */
@Composable
private fun DashCell(label: String, modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center,
        ) {
            content()
        }
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, color = InkLabel)
    }
}

/** 看板竖分隔线 */
@Composable
private fun DashVDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(30.dp)
            .background(DashDivider),
    )
}

/** 本次用时（M-5：秒级 tick 收敛在此节点，只重组这一格，不再整屏重组） */
@Composable
private fun TickClock(digitSp: Float) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick += 1
            delay(1000)
        }
    }
    Text(
        fmtClock(RecorderHub.elapsedMs()),
        fontSize = digitSp.sp,
        fontWeight = FontWeight.Black,
        fontFamily = FontFamily.Monospace,
        color = InkDark,
        maxLines = 1,
        softWrap = false,
    )
}

/* ============ ③ 右侧悬浮地图工具（web .map-floating-tools：回中/总览/图层） ============ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MapToolBtn(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(38.dp)
            .shadow(
                6.dp,
                RoundedCornerShape(12.dp),
                ambientColor = Color(0x14000000),
                spotColor = Color(0x1F000000),
            )
            .background(MapToolBg, RoundedCornerShape(12.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
            .then(interaction),
    ) {
        Icon(icon, contentDescription = desc, tint = InkMid, modifier = Modifier.size(18.dp))
    }
}

/* ============ ④ 底部座舱：Tier1 核心行动键 + Tier2 导航坞 ============ */

/** 核心大主按键（web .btn-cny-cta / .btn-cny-resume：图标圆 + 两行文案） */
@Composable
private fun MainCta(
    brush: Brush,
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(52.dp)
            .shadow(
                10.dp,
                RoundedCornerShape(16.dp),
                ambientColor = Color(0x33C8402F),
                spotColor = Color(0x5CC8402F),
            )
            .background(brush, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0x59FFFFFF), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(0x33FFFFFF), CircleShape),
            ) {
                Icon(icon, contentDescription = title, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    maxLines = 1,
                )
                Text(
                    sub,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xD9FFFFFF),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 辅助小键（web .btn-sub-action：结束复盘 / 撤销上点） */
@Composable
private fun SubAction(icon: ImageVector, iconColor: Color, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .width(60.dp)
            .height(52.dp)
            .shadow(
                6.dp,
                RoundedCornerShape(16.dp),
                ambientColor = Color(0x14000000),
                spotColor = Color(0x1F000000),
            )
            .background(Color(0xEBFFFFFF), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xD9FFFFFF), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = iconColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Black, color = InkMid, maxLines = 1)
    }
}

/** 底部常驻导航坞（web .glass-dock：走路/骑车 + 清单/历史） */
@Composable
private fun DockBar(
    mode: Mode,
    recording: Boolean,
    planPending: Boolean,
    onWalk: () -> Unit,
    onBike: () -> Unit,
    onPlan: () -> Unit,
    onHistory: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                14.dp,
                RoundedCornerShape(26.dp),
                ambientColor = Color(0x42000000),
                spotColor = Color(0x59000000),
            )
            .background(DockBg, RoundedCornerShape(26.dp))
            .border(1.dp, DockBorder, RoundedCornerShape(26.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        DockTab(
            icon = Icons.Filled.DirectionsWalk,
            label = "走路",
            active = mode == Mode.WALK,
            enabled = recording,
            onClick = onWalk,
            modifier = Modifier.weight(1f),
        )
        DockTab(
            icon = Icons.Filled.PedalBike,
            label = "骑车",
            active = mode == Mode.BIKE,
            enabled = recording,
            onClick = onBike,
            modifier = Modifier.weight(1f),
        )
        DockTab(
            icon = Icons.Filled.Assignment,
            label = "拜年清单",
            active = false,
            enabled = true,
            badge = planPending,
            iconTint = Color(0xFFD97706),
            onClick = onPlan,
            modifier = Modifier.weight(1f),
        )
        DockTab(
            icon = Icons.Filled.History,
            label = "往年历史",
            active = false,
            enabled = true,
            onClick = onHistory,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 坞内单项：选中态白 16% 底 + 白字；走路/骑车仅记录中可点（与老版 Toolbar 一致） */
@Composable
private fun DockTab(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
    iconTint: Color? = null,
) {
    val tint = iconTint ?: if (active) Color.White else DockInactive
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = 50.dp)
            .background(
                if (active) Color(0x29FFFFFF) else Color.Transparent,
                RoundedCornerShape(16.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
            if (badge) { // 清单未拜完的红点角标（web .badge-dot-red）
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-2).dp)
                        .size(7.dp)
                        .border(1.dp, DockBg, CircleShape)
                        .background(GpsWeak, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 10.5.sp,
            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (active) Color.White else DockInactive,
        )
    }
}
