package io.github.chenchen913.baibai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        installCrashLogHandler() // M-4/D22.5：未捕获异常写日志，绝不静默崩溃
        RecorderHub.init(application)
        RecorderHub.boot()
        setContent {
            BaibaiTheme {
                AppRoot()
            }
        }
    }

    private fun installCrashLogHandler() {
        val logDir = File(filesDir, "logs").apply { mkdirs() }
        val log = File(logDir, "crash.log")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                log.appendText("${java.time.Instant.now()} thread=${t.name}\n${e.stackTraceToString()}\n\n")
            }
            prev?.uncaughtException(t, e)
        }
    }
}

/** 读取上次崩溃日志并清空；返回给用户看的提示文案（无日志返回 null） */
private fun readCrashLogAndClear(ctx: Context): String? {
    val log = File(ctx.filesDir, "logs/crash.log")
    if (!log.exists() || log.length() == 0L) return null
    val text = runCatching { log.readText() }.getOrNull() ?: return null
    log.delete()
    return if (text.isBlank()) null else "上次运行出现异常（日志已保留），如频繁发生请反馈"
}

private const val PREFS = "baibai_prefs"
private const val KEY_WHITELIST_SEEN = "whitelist_seen"

private sealed interface Screen {
    data object Record : Screen
    data object Whitelist : Screen
    data object History : Screen
    data object Plan : Screen
    data class Review(val session: io.github.chenchen913.baibai.core.model.SessionData) : Screen
    data class Optimize(val session: io.github.chenchen913.baibai.core.model.SessionData) : Screen
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { ctx.getSharedPreferences(PREFS, 0) }

    val pendingRestore by RecorderHub.pendingRestore.collectAsState()
    val finishTooFar by RecorderHub.finishTooFar.collectAsState()
    val session by RecorderHub.session.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.Record) }

    // toast 消息
    LaunchedEffect(Unit) {
        RecorderHub.messages.collect { snackbar.showSnackbar(it) }
    }

    // 上次崩溃提示（M-4）：读一次即清空日志
    val crashMsg = remember { readCrashLogAndClear(ctx) }
    LaunchedEffect(Unit) {
        crashMsg?.let { snackbar.showSnackbar(it) }
    }

    // 结束拜年后自动进入回顾页（沿用网页版行为）
    LaunchedEffect(session) {
        if (session?.state == io.github.chenchen913.baibai.core.model.SessionState.FINISHED && screen is Screen.Record) {
            screen = Screen.Review(session!!)
        }
    }

    // 权限请求（定位 + 通知）
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val locOk = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locOk) {
            RecorderHub.startPressed()
        } else {
            RecorderHub.handleGpsError(io.github.chenchen913.baibai.core.errors.GpsErrorKind.DENIED)
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun requestStart() {
        if (!prefs.getBoolean(KEY_WHITELIST_SEEN, false)) {
            screen = Screen.Whitelist // 首次开始前引导白名单（第三层防杀）——必须切 screen，否则点击无响应
            return
        }
        if (!hasLocationPermission()) {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS,
                ),
            )
            return
        }
        RecorderHub.startPressed()
    }

    Box(Modifier.fillMaxSize()) {
        // H-1：系统返回键/手势按页面栈返回（记录页为栈底，返回键交给系统=退出）
        BackHandler(enabled = screen !is Screen.Record) {
            screen = when (val cur = screen) {
                is Screen.Review -> Screen.History
                is Screen.Optimize -> Screen.Review(cur.session)
                else -> Screen.Record
            }
        }

        when (screen) {
            is Screen.Record -> RecordScreen(
                onStartRequest = { requestStart() },
                onHistory = { screen = Screen.History },
                onPlan = { screen = Screen.Plan },
            )

            is Screen.Whitelist -> WhitelistGuideScreen(
                onDone = {
                    prefs.edit().putBoolean(KEY_WHITELIST_SEEN, true).apply()
                    screen = Screen.Record
                    requestStart()
                },
            )

            is Screen.Plan -> PlanScreen(
                year = java.time.LocalDate.now().year,
                onBack = { screen = Screen.Record },
            )

            is Screen.History -> HistoryScreen(
                onBack = { screen = Screen.Record },
                onOpen = { s -> screen = Screen.Review(s) },
            )

            is Screen.Review -> {
                val reviewScreen = screen as Screen.Review
                ReviewScreen(
                    initial = reviewScreen.session,
                    onBack = { screen = Screen.History },
                    onSave = { s2 ->
                        runCatching { RecorderHub.store.saveSession(s2) }
                        // H-4：把最新编辑结果回传，保证「三线对比」与返回都用最新数据
                        screen = Screen.Review(s2)
                    },
                    onOptimize = { screen = Screen.Optimize(reviewScreen.session) },
                )
            }

            is Screen.Optimize -> {
                val optimizeScreen = screen as Screen.Optimize
                OptimizeScreen(
                    session = optimizeScreen.session,
                    onBack = { screen = Screen.Review(optimizeScreen.session) },
                )
            }
        }

        // 轻提示：底部居中、位于导航栏/手势条之上
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }

    // 崩溃恢复：检测到未完成检查点
    if (pendingRestore) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("检测到未完成的拜年记录") },
            text = { Text("继续记录，还是放弃？") },
            confirmButton = {
                TextButton(onClick = { RecorderHub.resumeCheckpoint() }) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { RecorderHub.abandonCheckpoint() }) { Text("放弃") }
            },
        )
    }

    // 距 Home 太远时强制结束确认（D10）
    finishTooFar?.let { dist ->
        AlertDialog(
            onDismissRequest = { RecorderHub.dismissFinishTooFar() },
            title = {
                // M-10：无定位时不显示 Infinity
                Text(if (dist.isFinite()) "距 Home 约 ${dist.roundToInt()} 米" else "当前位置无法定位")
            },
            text = { Text("当前位置离出发点较远，仍要结束本次拜年吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        RecorderHub.dismissFinishTooFar()
                        RecorderHub.finishPressed(force = true)
                    },
                ) { Text("强制结束") }
            },
            dismissButton = {
                TextButton(onClick = { RecorderHub.dismissFinishTooFar() }) { Text("取消") }
            },
        )
    }
}
