package io.github.chenchen913.baibai

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.chenchen913.baibai.core.cny.Cny
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.SessionState
import io.github.chenchen913.baibai.core.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

/** 瓦片/地图链路统一日志 tag（P4）：logcat 过滤 BaibaiMap 即可看全链路 */
private const val MAP_LOG = "BaibaiMap"

/** 记录页（原始需求 §7.1）：
 * 品牌栏 → 状态大卡 → 实时地图卡（可折叠，展开约屏高 30~35%）→ 主按钮区 → 工具条。
 * 上部滚动、底部主按钮区+工具条固定于拇指热区；安全区由 BaibaiPage 统一处理。 */
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
    var showDatePicker by remember { mutableStateOf(false) }

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
                        bizDate = bizDate,
                        onToggleFeedback = { RecorderHub.setFeedback(!feedbackOn) },
                        onPickDate = { showDatePicker = true },
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

/* ---------- 1. 品牌栏 ---------- */

@Composable
private fun BrandBar(
    feedbackOn: Boolean,
    bizDate: java.time.LocalDate,
    onToggleFeedback: () -> Unit,
    onPickDate: () -> Unit,
) {
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
        // 金色描边徽章：显示拜年日期（可点 → 日期选择器，初一~初十自动显示「大年初X」）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(BaibaiAccent2.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                .border(1.dp, BaibaiAccent2.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                .clickable(onClick = onPickDate)
                .padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        ) {
            Text(Cny.label(bizDate), fontSize = 12.sp, fontWeight = FontWeight.Black, color = BaibaiAccent2)
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "选择拜年日期",
                tint = BaibaiAccent2,
                modifier = Modifier.size(16.dp),
            )
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

/* ---------- 3. 实时地图卡（真实地图：WebView + Leaflet + 高德/OSM，免 Key 免费） ----------
 * 折叠 = 40dp 标题条，展开 = 屏高 30%~35%。
 * 瓦片：shouldInterceptRequest 只查本地缓存（命中即返回；未命中异步回填并放行 WebView 自取），
 * 预载走 TileCache 独立线程池，两者互不阻塞（审核报告 P1/P3）；
 * 失败自动换源（高德→OSM），全部失败时 map.html 显示可见兜底浮层（P6），轨迹/标记照常绘制。 */

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MapCard(mapOpenHeight: androidx.compose.ui.unit.Dp) {
    var mapOpen by remember { mutableStateOf(true) }
    val shape = RoundedCornerShape(22.dp)
    val webView = remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    val session = RecorderHub.session.collectAsState().value
    val syncKey = (session?.state ?: SessionState.IDLE) to (session?.nodes?.size ?: 0)

    // P0 离线瓦片缓存：Home 确定后自动预载周边；手动按钮随时可再预载
    val appContext = LocalContext.current.applicationContext
    val tileCache = remember { TileCache(appContext) }
    val scope = rememberCoroutineScope()
    var preloading by remember { mutableStateOf(false) }
    var preloadedFor by remember { mutableStateOf<String?>(null) }

    fun startPreload(homeLat: Double, homeLng: Double) {
        if (preloading) return
        preloading = true
        val urls = TileMath.preloadList(homeLat, homeLng)
        RecorderHub.toast("正在预载周边地图（" + urls.size + " 张，约几 MB）…")
        scope.launch {
            val ok = withContext(Dispatchers.IO) { tileCache.preload(urls) }
            preloading = false
            RecorderHub.toast(
                if (ok > 0) "已预载 " + ok + " 张瓦片，断网也能看地图"
                else "预载失败：请检查网络后重试",
            )
        }
    }

    // 全量同步：页面就绪 / 状态或户数变化 / 重新展开 → 推整条轨迹 + 家/户标记
    LaunchedEffect(pageReady, syncKey, mapOpen) {
        if (!pageReady || !mapOpen) return@LaunchedEffect
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
            // P0：Home 确定后自动预载周边瓦片（每个 Home 只预载一次）
            val homeValid = snap.home.lat != 0.0 || snap.home.lng != 0.0
            if (homeValid) {
                val key = "%.5f,%.5f".format(snap.home.lat, snap.home.lng)
                if (preloadedFor != key) {
                    preloadedFor = key
                    startPreload(snap.home.lat, snap.home.lng)
                }
            }
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
            if (pageReady && mapOpen) {
                val w = webView.value
                if (w != null) {
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
    }

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
                // P0：预载周边地图（Home 或最近一次记录的 Home）
                Text(
                    if (preloading) "预载中…" else "预载",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (preloading) BaibaiInk.copy(alpha = 0.4f) else BaibaiAccent,
                    modifier = Modifier
                        .clickable(enabled = !preloading) {
                            val home = RecorderHub.recorder?.snapshot()?.home
                                ?: RecorderHub.store.listSessions().maxByOrNull { it.createdAt }?.home
                            if (home == null || (home.lat == 0.0 && home.lng == 0.0)) {
                                RecorderHub.toast("还没有定位或历史记录：先开始拜年，再点这里预载")
                            } else {
                                startPreload(home.lat, home.lng)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                )
                // 线性折叠图标（无圆形白底），热区 ≥48dp
                IconButton(onClick = { pageReady = false; mapOpen = false }) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = "收起地图",
                        tint = BaibaiInk,
                    )
                }
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        webView.value = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        settings.builtInZoomControls = false
                        settings.setSupportZoom(false)
                        settings.displayZoomControls = false
                        // P5：与 TileCache 完全一致的 UA（浏览器默认 UA + baibai 标识），命中/未命中行为不再分裂
                        settings.userAgentString = tileCache.userAgent
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

                            // 瓦片统一走 TileCache.download（缓存优先→网络下载）并通过 WebResourceResponse
                            // 注入字节给 <img>。这是最可靠路径：字节在网络层注入，绕过 file:// origin 对
                            // 跨域 https 子资源的任何限制（部分国产 ROM 的 WebView 从 file:// 自取 https 瓦片不稳，
                            // 故不采用“返回 null 让 WebView 自取”的方案）。
                            // P1 已拆锁：单 key 锁 + 预载走独立线程池，此处同步下载不会饿死其他瓦片请求。
                            // P2：MIME 按 URL 判定（style=6 卫星实为 JPEG）。
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val u = request?.url?.toString() ?: return null
                                val isTile = u.contains("is.autonavi.com/appmaptile") ||
                                    u.contains("tile.openstreetmap.org")
                                if (!isTile) return null
                                val bytes = tileCache.download(u) ?: return null // 下载失败→放行 WebView 自取兜底
                                val mime = if (u.contains("style=6")) "image/jpeg" else "image/png"
                                return WebResourceResponse(
                                    mime,
                                    null,
                                    200,
                                    "OK",
                                    mapOf("Cache-Control" to "max-age=31536000, immutable"),
                                    ByteArrayInputStream(bytes),
                                )
                            }
                        }
                        loadUrl("file:///android_asset/baibai_map/map.html")
                    }
                },
                onRelease = { webView.value = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(mapOpenHeight),
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "© OpenStreetMap 贡献者 · 瓦片失败自动降级",
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
                .clickable { pageReady = false; mapOpen = true },
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
private fun nodesJson(nodes: List<io.github.chenchen913.baibai.core.model.HouseNode>): String {
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

private fun latLngJson(p: io.github.chenchen913.baibai.core.model.LatLng): String =
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
