package io.github.chenchen913.baibai.core.debug

/**
 * 定位实验（GPS Debug）——纯数据定义。
 *
 * 本文件**不得 import 任何 android.\* 类**：它属于 core 纯 Kotlin 模块，可被 core 单测直接覆盖。
 *
 * 设计约束（定位实验硬约束）：
 * - 本文件只描述"记录什么"，**不参与任何过滤判断**——不新增/删除/修改任何阈值常量、闸门顺序、判定条件；
 * - 所有判定结果都由 [io.github.chenchen913.baibai.core.state.RecorderState] 现有代码产生后**只读抄录**。
 */

/**
 * 单条原始定位经过当前算法后的判定结果（仅记录，不参与判断）。
 *
 * label 是稳定字符串（进 CSV/JSON，供脚本分析），不要随意改名。
 */
enum class GpsDecision(val label: String) {
    /** 通过全部闸门 → 进入 points[] */
    ACCEPTED("accepted"),

    /** 判定为跳变点 → 入库且带 jump=true */
    ACCEPTED_JUMP("accepted_jump"),

    /** 平滑窗口未满 3 个样本，本轮不判定 */
    REJECTED_WARMUP("rejected_warmup"),

    /** 位移未超过静止门槛 */
    REJECTED_STILL("rejected_still"),

    /** 超过门槛但连续确认未满 2 次 */
    REJECTED_NEED_CONFIRM("rejected_need_confirm"),

    /** 会话不在 WALKING（状态机抛 IllegalStateException） */
    REJECTED_NOT_WALKING("rejected_not_walking"),

    /** 被 SystemLocationSource 的源侧网关丢弃 */
    REJECTED_GATEWAY("rejected_gateway");

    companion object {
        /** label → 枚举（往返用）；未知 label 返回 null，不抛异常（实验记录链路绝不能因脏数据崩） */
        fun parse(label: String): GpsDecision? =
            GpsDecision.values().firstOrNull { it.label == label }
    }
}

/** 从 android.location.Location 提取的原始快照（纯数据，无 Android 依赖） */
data class RawLocationInfo(
    /** 回调到达时刻（System.currentTimeMillis） */
    val receivedAtMs: Long,
    /** Location.time */
    val fixTimeMs: Long,
    /** Location.elapsedRealtimeNanos（部分设备/构造点可能缺失） */
    val elapsedRealtimeNanos: Long?,
    val lat: Double,
    val lng: Double,
    val accuracy: Double,
    /** "gps" / "network" / 其它 */
    val provider: String,
    /** 现有双源网关的标记："gps" / "net" */
    val src: String?,
    /** 仅 hasSpeed() 为 true 时非空 */
    val speedMps: Double?,
    /** 仅 hasBearing() 为 true 时非空 */
    val bearingDeg: Double?,
    /** 仅 hasAltitude() 时非空 */
    val altitudeM: Double?,
    /** isFromMockProvider() */
    val isMock: Boolean?,
)

/** 一条完整实验记录 = 原始定位 + 算法判定 +（若接受）处理后的点 */
data class GpsDebugRecord(
    val seq: Long,
    val receivedAtMs: Long,
    val fixTimeMs: Long,
    val elapsedRealtimeNanos: Long?,
    val lat: Double,
    val lng: Double,
    val accuracy: Double,
    val provider: String,
    val src: String?,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val altitudeM: Double?,
    val isMock: Boolean?,
    /** 是否通过源侧网关（false 时走 REJECTED_GATEWAY） */
    val passedGateway: Boolean,
    /** 未通过网关的原因（可为 null） */
    val gatewayReason: String?,
    val decision: GpsDecision,
    /** 距上一条**原始**点的距离（观察 GPS 自身抖动） */
    val distFromPrevRawM: Double?,
    /** 距上一**入库**点的距离（观察门槛余量） */
    val distFromPrevStoredM: Double?,
    /** 本次判定使用的静止门槛（算法算出来的，仅记录） */
    val thresholdM: Double?,
    /** 接受时：入库后的坐标（中位数可能改写） */
    val processedLat: Double?,
    val processedLng: Double?,
    /** 记录时的会话状态（IDLE/WALKING/PAUSED/FINISHED） */
    val sessionState: String,
    /** 记录时已入库点数 */
    val storedCount: Int,
) {
    /**
     * CSV 一行。**字段顺序与 [CSV_HEADER] 严格一致**（23 列）：
     * 数字为 null 或非有限数（NaN/Inf）时输出空串；字符串字段用双引号包裹并把内部 " 转义为 ""。
     */
    fun toCsvRow(): String = listOf(
        csvLong(seq),
        csvLong(receivedAtMs),
        csvLong(fixTimeMs),
        csvLong(elapsedRealtimeNanos),
        csvNum(lat),
        csvNum(lng),
        csvNum(accuracy),
        csvStr(provider),
        csvStr(src),
        csvNum(speedMps),
        csvNum(bearingDeg),
        csvNum(altitudeM),
        csvBool(isMock),
        csvBool(passedGateway),
        csvStr(gatewayReason),
        decision.label,
        csvNum(distFromPrevRawM),
        csvNum(distFromPrevStoredM),
        csvNum(thresholdM),
        csvNum(processedLat),
        csvNum(processedLng),
        csvStr(sessionState),
        csvInt(storedCount),
    ).joinToString(",")

    /**
     * 单行 JSON 对象（NDJSON，便于大文件流式分析）：字段顺序与 CSV 一致；
     * null 字段输出 `null`（不是空串）；字符串做标准转义。
     */
    fun toJson(): String {
        val fields = listOf(
            "\"seq\":" + seq,
            "\"receivedAtMs\":" + receivedAtMs,
            "\"fixTimeMs\":" + fixTimeMs,
            "\"elapsedRealtimeNanos\":" + jsonLong(elapsedRealtimeNanos),
            "\"lat\":" + jsonNum(lat),
            "\"lng\":" + jsonNum(lng),
            "\"accuracy\":" + jsonNum(accuracy),
            "\"provider\":" + jsonStr(provider),
            "\"src\":" + jsonStr(src),
            "\"speedMps\":" + jsonNum(speedMps),
            "\"bearingDeg\":" + jsonNum(bearingDeg),
            "\"altitudeM\":" + jsonNum(altitudeM),
            "\"isMock\":" + jsonBool(isMock),
            "\"passedGateway\":" + passedGateway,
            "\"gatewayReason\":" + jsonStr(gatewayReason),
            "\"decision\":" + jsonStr(decision.label),
            "\"distFromPrevRawM\":" + jsonNum(distFromPrevRawM),
            "\"distFromPrevStoredM\":" + jsonNum(distFromPrevStoredM),
            "\"thresholdM\":" + jsonNum(thresholdM),
            "\"processedLat\":" + jsonNum(processedLat),
            "\"processedLng\":" + jsonNum(processedLng),
            "\"sessionState\":" + jsonStr(sessionState),
            "\"storedCount\":" + storedCount,
        )
        return "{" + fields.joinToString(",") + "}"
    }

    companion object {
        /** CSV 表头：字段顺序与 [toCsvRow] 严格一致（GpsDebugTest 有列数一致性断言） */
        val CSV_HEADER: String = listOf(
            "seq",
            "receivedAtMs",
            "fixTimeMs",
            "elapsedRealtimeNanos",
            "lat",
            "lng",
            "accuracy",
            "provider",
            "src",
            "speedMps",
            "bearingDeg",
            "altitudeM",
            "isMock",
            "passedGateway",
            "gatewayReason",
            "decision",
            "distFromPrevRawM",
            "distFromPrevStoredM",
            "thresholdM",
            "processedLat",
            "processedLng",
            "sessionState",
            "storedCount",
        ).joinToString(",")
    }
}

// ---------- CSV/JSON 转义（文件私有，避免污染 core 公共 API） ----------

/** CSV 数字：null 或非有限数（NaN/Inf）→ 空串 */
private fun csvNum(v: Double?): String = if (v == null || !v.isFinite()) "" else v.toString()

private fun csvLong(v: Long?): String = if (v == null) "" else v.toString()

private fun csvInt(v: Int?): String = if (v == null) "" else v.toString()

private fun csvBool(v: Boolean?): String = if (v == null) "" else v.toString()

/** CSV 字符串：双引号包裹，内部 " 转义为 "" */
private fun csvStr(s: String?): String =
    if (s == null) "" else "\"" + s.replace("\"", "\"\"") + "\""

/** JSON 数字：null 或非有限数 → null 字面量（NaN/Infinity 不是合法 JSON） */
private fun jsonNum(v: Double?): String = if (v == null || !v.isFinite()) "null" else v.toString()

private fun jsonLong(v: Long?): String = if (v == null) "null" else v.toString()

private fun jsonBool(v: Boolean?): String = if (v == null) "null" else v.toString()

/** JSON 字符串：标准转义（引号/反斜杠/常见控制字符） */
private fun jsonStr(s: String?): String {
    if (s == null) return "null"
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
