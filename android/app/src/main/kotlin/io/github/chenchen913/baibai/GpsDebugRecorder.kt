package io.github.chenchen913.baibai

import android.content.Context
import io.github.chenchen913.baibai.core.debug.GpsDebugRecord
import io.github.chenchen913.baibai.core.debug.GpsDecision
import io.github.chenchen913.baibai.core.debug.RawLocationInfo
import io.github.chenchen913.baibai.core.geo.Geo
import io.github.chenchen913.baibai.core.model.Fix
import io.github.chenchen913.baibai.core.model.LatLng
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定位实验（GPS Debug）统计快照（UI 展示用；只读，不参与任何算法判断）。
 *
 * [currentSessionState] / [storedPointCount] 由 [RecorderHub.debugStats] 用实时会话值覆写；
 * 其余字段来自 [GpsDebugRecorder] 内部计数。
 */
data class GpsDebugStats(
    /** 收到的原始定位条数 */
    val rawCount: Int,
    /** 入库条数 */
    val acceptedCount: Int,
    /** 拒绝条数 */
    val rejectedCount: Int,
    /** 最近一次拒绝原因（GpsDecision.label） */
    val lastRejectReason: String?,
    /** 最近一次 provider */
    val lastProvider: String?,
    val lastSrc: String?,
    val lastAccuracy: Double?,
    val lastSpeedMps: Double?,
    val lastBearingDeg: Double?,
    val lastIsMock: Boolean?,
    val lastDistFromPrevRawM: Double?,
    val lastDistFromPrevStoredM: Double?,
    val lastThresholdM: Double?,
    val currentSessionState: String,
    /** 当前会话已入库点数 */
    val storedPointCount: Int,
    val lastDecision: String?,
)

/**
 * 定位实验记录器（真机排障用）：把"原始定位"与"算法过滤后的结果"逐条配对存下来。
 *
 * **本类只采集与展示，不参与任何判断**——所有判定结果都是 [io.github.chenchen913.baibai.core.state.RecorderState]
 * 现有代码算完后抄录过来的（阈值/闸门顺序一个都没动）。
 *
 * 线程模型：定位回调走主 Looper（[SystemLocationSource] 注册时用 Looper.getMainLooper()），
 * UI 也在主线程；但 [appendToFile] 可能在后台协程线程被调用，故所有入口 @Synchronized。
 */
object GpsDebugRecorder {

    /** 内存环形缓冲上限：超出移除最旧（真机长时间开着的内存兜底） */
    private const val MAX_RECORDS = 5000

    /** 源侧网关丢弃原因（写进记录的 gatewayReason 字段） */
    private const val GATEWAY_REASON = "源侧网关丢弃（网络点/低精度）"

    private val records = ArrayDeque<GpsDebugRecord>()

    private var seq = 0L

    // 计数
    private var rawCount = 0
    private var acceptedCount = 0
    private var rejectedCount = 0
    private var lastRejectReason: String? = null

    // 最近一次原始点的显示字段
    private var lastProvider: String? = null
    private var lastSrc: String? = null
    private var lastAccuracy: Double? = null
    private var lastSpeedMps: Double? = null
    private var lastBearingDeg: Double? = null
    private var lastIsMock: Boolean? = null
    private var lastDistFromPrevRawM: Double? = null

    // 最近一次判定的显示字段
    private var lastDistFromPrevStoredM: Double? = null
    private var lastThresholdM: Double? = null
    private var lastDecision: String? = null
    private var lastSessionState: String = "IDLE"
    private var lastStoredCount: Int = 0

    /** 待配对的原始定位：下一条 onDecision 到来时消费；若被新原始点顶掉 → 说明它没走到 applyFix（网关丢弃） */
    private var pendingRaw: RawLocationInfo? = null
    private var pendingRawDistM: Double? = null

    /** 上一条**任意**原始点坐标（算 distFromPrevRawM，观察 GPS 自身抖动） */
    private var lastRawLat: Double? = null
    private var lastRawLng: Double? = null

    /** 本次实验的落盘文件名 tag（reset 时按当前时间重生成） */
    private var fileTag: String = newTag()

    /** 已经写进 <filesDir>/gps-debug/<tag>.csv 的最大 seq（增量追加用） */
    private var flushedSeq: Long = 0L

    private fun newTag(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())

    // ---------- 采集入口 ----------

    /**
     * 原始定位到达（[SystemLocationSource.debugSink]，在源侧网关**判断之前**调用）。
     *
     * 配对机制：若 [pendingRaw] 非空，说明上一条原始点没有走到 applyFix（被源侧网关丢了），
     * 先把它落成一条 REJECTED_GATEWAY 记录，再把本条设为新的 pending。
     */
    @Synchronized
    fun onRawLocation(info: RawLocationInfo) {
        rawCount += 1
        // 距上一条**原始**点（任意，含被丢弃的）的距离
        val prevLat = lastRawLat
        val prevLng = lastRawLng
        val distRaw = if (prevLat != null && prevLng != null) {
            Geo.haversineM(LatLng(prevLat, prevLng), LatLng(info.lat, info.lng))
        } else {
            null
        }
        lastRawLat = info.lat
        lastRawLng = info.lng
        lastDistFromPrevRawM = distRaw
        lastProvider = info.provider
        lastSrc = info.src
        lastAccuracy = info.accuracy
        lastSpeedMps = info.speedMps
        lastBearingDeg = info.bearingDeg
        lastIsMock = info.isMock

        val stale = pendingRaw
        if (stale != null) {
            // 上一条没等到判定 → 源侧网关丢弃
            rejectedCount += 1
            lastRejectReason = GpsDecision.REJECTED_GATEWAY.label
            lastDecision = GpsDecision.REJECTED_GATEWAY.label
            addRecord(
                buildRecord(
                    info = stale,
                    distFromPrevRawM = pendingRawDistM,
                    passedGateway = false,
                    gatewayReason = GATEWAY_REASON,
                    decision = GpsDecision.REJECTED_GATEWAY,
                    distFromPrevStoredM = null,
                    thresholdM = null,
                    processedLat = null,
                    processedLng = null,
                ),
            )
        }
        pendingRaw = info
        pendingRawDistM = distRaw
    }

    /**
     * 算法判定结果（[RecorderHub.applyFix] 调完 addPoint / 捕获 IllegalStateException 后调用）。
     * 消费 [pendingRaw] 组装完整记录；pending 为空（如无旁路数据）时用 fix 兜底构造。
     */
    @Synchronized
    fun onDecision(
        fix: Fix,
        decisionLabel: String,
        distFromPrevStoredM: Double?,
        thresholdM: Double?,
        storedCount: Int,
        sessionState: String,
        processedLat: Double?,
        processedLng: Double?,
    ) {
        val info = pendingRaw ?: RawLocationInfo(
            receivedAtMs = System.currentTimeMillis(),
            fixTimeMs = 0L,
            elapsedRealtimeNanos = null,
            lat = fix.pos.lat,
            lng = fix.pos.lng,
            accuracy = fix.acc,
            provider = "unknown",
            src = fix.src,
            speedMps = null,
            bearingDeg = null,
            altitudeM = null,
            isMock = null,
        )
        val distRaw = pendingRawDistM
        pendingRaw = null
        pendingRawDistM = null

        val decision = GpsDecision.parse(decisionLabel) ?: GpsDecision.REJECTED_NOT_WALKING
        lastDecision = decisionLabel
        lastDistFromPrevStoredM = distFromPrevStoredM
        lastThresholdM = thresholdM
        lastSessionState = sessionState
        lastStoredCount = storedCount
        if (decision == GpsDecision.ACCEPTED || decision == GpsDecision.ACCEPTED_JUMP) {
            acceptedCount += 1
        } else {
            rejectedCount += 1
            lastRejectReason = decisionLabel
        }

        addRecord(
            buildRecord(
                info = info,
                distFromPrevRawM = distRaw,
                passedGateway = true,
                gatewayReason = null,
                decision = decision,
                distFromPrevStoredM = distFromPrevStoredM,
                thresholdM = thresholdM,
                processedLat = processedLat,
                processedLng = processedLng,
            ),
        )
    }

    /** 清空内存与 pending，并按当前时间重生成落盘 tag（下次落盘写新文件） */
    @Synchronized
    fun reset() {
        records.clear()
        seq = 0L
        rawCount = 0
        acceptedCount = 0
        rejectedCount = 0
        lastRejectReason = null
        lastProvider = null
        lastSrc = null
        lastAccuracy = null
        lastSpeedMps = null
        lastBearingDeg = null
        lastIsMock = null
        lastDistFromPrevRawM = null
        lastDistFromPrevStoredM = null
        lastThresholdM = null
        lastDecision = null
        lastSessionState = "IDLE"
        lastStoredCount = 0
        pendingRaw = null
        pendingRawDistM = null
        lastRawLat = null
        lastRawLng = null
        fileTag = newTag()
        flushedSeq = 0L
    }

    /** 内存中的全部记录（按时间正序，最多 [MAX_RECORDS] 条） */
    @Synchronized
    fun records(): List<GpsDebugRecord> = records.toList()

    @Synchronized
    fun count(): Int = records.size

    /** 统计快照（会话态/入库点数由 [RecorderHub.debugStats] 覆写为实时值） */
    @Synchronized
    fun stats(): GpsDebugStats = GpsDebugStats(
        rawCount = rawCount,
        acceptedCount = acceptedCount,
        rejectedCount = rejectedCount,
        lastRejectReason = lastRejectReason,
        lastProvider = lastProvider,
        lastSrc = lastSrc,
        lastAccuracy = lastAccuracy,
        lastSpeedMps = lastSpeedMps,
        lastBearingDeg = lastBearingDeg,
        lastIsMock = lastIsMock,
        lastDistFromPrevRawM = lastDistFromPrevRawM,
        lastDistFromPrevStoredM = lastDistFromPrevStoredM,
        lastThresholdM = lastThresholdM,
        currentSessionState = lastSessionState,
        storedPointCount = lastStoredCount,
        lastDecision = lastDecision,
    )

    // ---------- 落盘 / 导出 ----------

    /**
     * 把**新增**记录追加写到 `File(ctx.filesDir, "gps-debug")/<tag>.csv`（追加模式，不清旧内容）。
     * 新文件首次写入时补表头；失败静默（实验记录绝不能影响正常记录链路）。
     */
    @Synchronized
    fun appendToFile(ctx: Context) {
        runCatching {
            val dir = File(ctx.filesDir, "gps-debug")
            dir.mkdirs()
            val file = File(dir, "$fileTag.csv")
            val writeHeader = !file.exists() || file.length() == 0L
            val pending = records.filter { it.seq > flushedSeq }
            if (pending.isNotEmpty() || writeHeader) {
                FileOutputStream(file, true).use { fos ->
                    BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { w ->
                        if (writeHeader) {
                            w.write(GpsDebugRecord.CSV_HEADER)
                            w.newLine()
                        }
                        for (r in pending) {
                            w.write(r.toCsvRow())
                            w.newLine()
                        }
                        w.flush()
                    }
                }
                if (pending.isNotEmpty()) flushedSeq = pending.last().seq
            }
        }
    }

    /**
     * 导出全部内存记录为 CSV：`File(ctx.cacheDir, "exports")/gps-debug-<tag>.csv`。
     * 必须放在 cacheDir/exports —— res/xml/file_paths.xml 只声明了 `cache-path path="exports/"`，
     * FileProvider 才允许分享。失败返回 null。
     */
    @Synchronized
    fun exportCsv(ctx: Context): File? = runCatching {
        val dir = File(ctx.cacheDir, "exports")
        dir.mkdirs()
        val f = File(dir, "gps-debug-$fileTag.csv")
        FileOutputStream(f).use { fos ->
            BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { w ->
                w.write(GpsDebugRecord.CSV_HEADER)
                w.newLine()
                for (r in records) {
                    w.write(r.toCsvRow())
                    w.newLine()
                }
                w.flush()
            }
        }
        f
    }.getOrNull()

    /** 导出全部内存记录为 NDJSON（每行一个 JSON 对象）：`File(ctx.cacheDir, "exports")/gps-debug-<tag>.json`。失败返回 null。 */
    @Synchronized
    fun exportJson(ctx: Context): File? = runCatching {
        val dir = File(ctx.cacheDir, "exports")
        dir.mkdirs()
        val f = File(dir, "gps-debug-$fileTag.json")
        FileOutputStream(f).use { fos ->
            BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { w ->
                for (r in records) {
                    w.write(r.toJson())
                    w.newLine()
                }
                w.flush()
            }
        }
        f
    }.getOrNull()

    // ---------- 内部 ----------

    private fun buildRecord(
        info: RawLocationInfo,
        distFromPrevRawM: Double?,
        passedGateway: Boolean,
        gatewayReason: String?,
        decision: GpsDecision,
        distFromPrevStoredM: Double?,
        thresholdM: Double?,
        processedLat: Double?,
        processedLng: Double?,
    ): GpsDebugRecord {
        seq += 1
        return GpsDebugRecord(
            seq = seq,
            receivedAtMs = info.receivedAtMs,
            fixTimeMs = info.fixTimeMs,
            elapsedRealtimeNanos = info.elapsedRealtimeNanos,
            lat = info.lat,
            lng = info.lng,
            accuracy = info.accuracy,
            provider = info.provider,
            src = info.src,
            speedMps = info.speedMps,
            bearingDeg = info.bearingDeg,
            altitudeM = info.altitudeM,
            isMock = info.isMock,
            passedGateway = passedGateway,
            gatewayReason = gatewayReason,
            decision = decision,
            distFromPrevRawM = distFromPrevRawM,
            distFromPrevStoredM = distFromPrevStoredM,
            thresholdM = thresholdM,
            processedLat = processedLat,
            processedLng = processedLng,
            sessionState = lastSessionState,
            storedCount = lastStoredCount,
        )
    }

    private fun addRecord(r: GpsDebugRecord) {
        records.addLast(r)
        while (records.size > MAX_RECORDS) records.removeFirst()
    }
}
