package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.debug.GpsDebugRecord
import io.github.chenchen913.baibai.core.debug.GpsDecision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 定位实验（GPS Debug）数据层测试。
 *
 * 只覆盖纯 Kotlin 部分（GpsDecision / GpsDebugRecord 的 CSV·JSON 序列化），
 * 不触碰任何需要 Android 运行时的东西（GpsDebugRecorder / SystemLocationSource 不在 core 模块）。
 * 注意：core 模块的测试依赖是 JUnit 5（junit-jupiter），故这里用 org.junit.jupiter.api，与 GeoTest.kt 同风格。
 */
class GpsDebugTest {

    companion object {

        /** 一条"字段齐全"的记录（字符串字段不含引号/逗号，便于做列数断言） */
        fun fullRecord(
            provider: String = "gps",
            gatewayReason: String? = "源侧网关丢弃（网络点/低精度）",
            decision: GpsDecision = GpsDecision.ACCEPTED,
        ): GpsDebugRecord = GpsDebugRecord(
            seq = 7L,
            receivedAtMs = 1_700_000_000_123L,
            fixTimeMs = 1_700_000_000_000L,
            elapsedRealtimeNanos = 123_456_789L,
            lat = 31.23,
            lng = 121.47,
            accuracy = 8.5,
            provider = provider,
            src = "gps",
            speedMps = 1.25,
            bearingDeg = 88.0,
            altitudeM = 12.5,
            isMock = false,
            passedGateway = true,
            gatewayReason = gatewayReason,
            decision = decision,
            distFromPrevRawM = 3.5,
            distFromPrevStoredM = 42.25,
            thresholdM = 30.0,
            processedLat = 31.2301,
            processedLng = 121.4701,
            sessionState = "WALKING",
            storedCount = 12,
        )

        /** 一条"可空字段全空 + NaN"的记录（验证空串/null 两条规则） */        fun nullRecord(): GpsDebugRecord = GpsDebugRecord(
            seq = 1L,
            receivedAtMs = 1L,
            fixTimeMs = 0L,
            elapsedRealtimeNanos = null,
            lat = Double.NaN,
            lng = 0.0,
            accuracy = 0.0,
            provider = "network",
            src = null,
            speedMps = null,
            bearingDeg = null,
            altitudeM = null,
            isMock = null,
            passedGateway = false,
            gatewayReason = null,
            decision = GpsDecision.REJECTED_GATEWAY,
            distFromPrevRawM = null,
            distFromPrevStoredM = null,
            thresholdM = null,
            processedLat = null,
            processedLng = null,
            sessionState = "IDLE",
            storedCount = 0,
        )

        /**
         * 按 CSV 规则数列数：只数**引号外**的逗号（`""` 视为转义引号，不结束字段）。
         * 不能用 naive `split(",")`——引号内的逗号会被切开，导致列数虚高。
         */
        fun countFields(row: String): Int {
            var inQuotes = false
            var commas = 0
            var i = 0
            while (i < row.length) {
                val ch = row[i]
                if (ch == '"') {
                    if (inQuotes && i + 1 < row.length && row[i + 1] == '"') {
                        i += 2
                        continue
                    }
                    inQuotes = !inQuotes
                } else if (ch == ',' && !inQuotes) {
                    commas += 1
                }
                i += 1
            }
            return commas + 1
        }
    }

    // ---------- GpsDecision ----------

    @Test
    fun `label 往返一致`() {
        for (d in GpsDecision.values()) {
            assertEquals(d, GpsDecision.parse(d.label))
        }
    }

    @Test
    fun `七个判定标签一个不少`() {
        assertEquals(7, GpsDecision.values().size)
        assertEquals("accepted", GpsDecision.ACCEPTED.label)
        assertEquals("accepted_jump", GpsDecision.ACCEPTED_JUMP.label)
        assertEquals("rejected_warmup", GpsDecision.REJECTED_WARMUP.label)
        assertEquals("rejected_still", GpsDecision.REJECTED_STILL.label)
        assertEquals("rejected_need_confirm", GpsDecision.REJECTED_NEED_CONFIRM.label)
        assertEquals("rejected_not_walking", GpsDecision.REJECTED_NOT_WALKING.label)
        assertEquals("rejected_gateway", GpsDecision.REJECTED_GATEWAY.label)
    }

    @Test
    fun `未知 label 解析为 null`() {
        assertNull(GpsDecision.parse(""))
        assertNull(GpsDecision.parse("nope"))
        assertNull(GpsDecision.parse("ACCEPTED")) // label 全小写，大小写敏感
    }

    // ---------- CSV ----------

    @Test
    fun `表头列数与行数一致且首尾列正确`() {
        val cols = GpsDebugRecord.CSV_HEADER.split(",")
        assertEquals(23, cols.size)
        assertEquals("seq", cols[0])
        assertEquals("decision", cols[15])
        assertEquals("storedCount", cols[cols.size - 1])
        assertEquals(cols.size, fullRecord().toCsvRow().split(",").size)
        assertEquals(cols.size, nullRecord().toCsvRow().split(",").size)
    }

    @Test
    fun `null 与 NaN 输出空串`() {
        val c = nullRecord().toCsvRow().split(",")
        assertEquals(23, c.size)
        assertEquals("1", c[0])
        assertEquals("", c[3]) // elapsedRealtimeNanos = null
        assertEquals("", c[4]) // lat = NaN
        assertEquals("0.0", c[5]) // lng 正常输出
        assertEquals("\"network\"", c[7]) // 字符串带双引号
        assertEquals("", c[8]) // src = null
        assertEquals("", c[9]) // speedMps = null
        assertEquals("", c[10]) // bearingDeg = null
        assertEquals("", c[11]) // altitudeM = null
        assertEquals("", c[12]) // isMock = null
        assertEquals("false", c[13]) // passedGateway 非空布尔
        assertEquals("", c[14]) // gatewayReason = null
        assertEquals("rejected_gateway", c[15]) // 判定标签不加引号
        assertEquals("", c[16]) // distFromPrevRawM = null
        assertEquals("", c[17]) // distFromPrevStoredM = null
        assertEquals("", c[18]) // thresholdM = null
        assertEquals("", c[19]) // processedLat = null
        assertEquals("", c[20]) // processedLng = null
        assertEquals("\"IDLE\"", c[21])
        assertEquals("0", c[22])
    }

    @Test
    fun `含逗号与引号的字符串被转义`() {
        val r = fullRecord(provider = "a,b\"c")
        val row = r.toCsvRow()
        // 字符串字段被双引号包裹，内部 " 转义为 ""
        assertTrue(row.contains("\"a,b\"\"c\""))
        // 引号内的逗号不构成新列：按 CSV 规则（"" 是转义引号）数列应仍为 23
        // 注意：不能用 naive split(",") 断言——引号内的逗号照样会被 split 切开
        assertEquals(23, countFields(row))
        assertTrue(row.endsWith(",\"WALKING\",12"))
    }

    @Test
    fun `普通行按 CSV 规则数列也为 23`() {
        assertEquals(23, countFields(fullRecord().toCsvRow()))
        assertEquals(23, countFields(nullRecord().toCsvRow()))
    }

    // ---------- JSON（NDJSON：一行一个对象） ----------

    @Test
    fun `是单行对象且花括号配平`() {
        val line = fullRecord().toJson()
        assertTrue(line.startsWith("{"))
        assertTrue(line.endsWith("}"))
        assertFalse(line.contains("\n"))
        assertEquals(1, line.count { it == '{' })
        assertEquals(1, line.count { it == '}' })
        assertEquals(0, line.count { it == '"' } % 2) // 引号成对（内容无引号时）
    }

    @Test
    fun `null 字段输出 null 而不是空串`() {
        val line = nullRecord().toJson()
        assertTrue(line.contains("\"elapsedRealtimeNanos\":null"))
        assertTrue(line.contains("\"lat\":null")) // NaN 不是合法 JSON → null
        assertTrue(line.contains("\"src\":null"))
        assertTrue(line.contains("\"speedMps\":null"))
        assertTrue(line.contains("\"isMock\":null"))
        assertTrue(line.contains("\"gatewayReason\":null"))
        assertTrue(line.contains("\"processedLat\":null"))
        assertTrue(line.contains("\"provider\":\"network\""))
        assertTrue(line.contains("\"passedGateway\":false"))
        assertTrue(line.contains("\"decision\":\"rejected_gateway\""))
        assertTrue(line.contains("\"storedCount\":0"))
        assertEquals(0, line.count { it == '"' } % 2)
    }

    @Test
    fun `JSON 字符串做标准转义`() {
        val line = fullRecord(provider = "a\"b\\c").toJson()
        // provider 原值 a"b\c → JSON 中为 "a\"b\\c"
        assertTrue(line.contains("\"provider\":\"a\\\"b\\\\c\""))
        assertTrue(line.contains("\"decision\":\"accepted\""))
        assertTrue(line.contains("\"sessionState\":\"WALKING\""))
        // 中文原样保留（不转义为 \uXXXX）
        assertTrue(fullRecord().toJson().contains("源侧网关丢弃"))
    }
}
