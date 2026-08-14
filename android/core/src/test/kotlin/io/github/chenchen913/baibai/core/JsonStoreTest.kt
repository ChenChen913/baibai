package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Plan
import io.github.chenchen913.baibai.core.model.PlanItem
import io.github.chenchen913.baibai.core.state.RecorderState
import io.github.chenchen913.baibai.core.store.JsonStore
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.PI

/** 对应网页版 tests/db.test.ts（4 项；fake-indexeddb → @TempDir 真实文件） */
class JsonStoreTest {

    @TempDir
    lateinit var tmp: File

    companion object {
        val HOME = LatLng(31.0, 121.0)
        const val R = 6371000.0
        const val T0 = 1_700_000_000_000L
        fun fix(pos: LatLng) = io.github.chenchen913.baibai.core.model.Fix(pos, 5.0)
    }

    private fun makeCheckpoint() = RecorderState.fresh().apply {
        start(listOf(fix(HOME)), T0)
        pause(listOf(fix(LatLng(31.0 + 100.0 / R * 180.0 / PI, 121.0))), T0 + 1000)
    }.checkpoint()

    @Test
    fun `活跃检查点保存读取往返一致`() {
        val store = JsonStore(tmp)
        val ck = makeCheckpoint()
        store.saveActive(ck)
        assertEquals(ck, store.loadActive())
    }

    @Test
    fun `重复保存覆盖旧检查点`() {
        val store = JsonStore(tmp)
        store.saveActive(makeCheckpoint())
        val ck2 = makeCheckpoint().let {
            it.copy(session = it.session.copy(year = 2027))
        }
        store.saveActive(ck2)
        assertEquals(2027, store.loadActive()?.session?.year)
    }

    @Test
    fun `clear 后读不到`() {
        val store = JsonStore(tmp)
        store.saveActive(makeCheckpoint())
        store.clearActive()
        assertNull(store.loadActive())
    }

    @Test
    fun `历史会话保存列表导出`() {
        val store = JsonStore(tmp)
        val r = RecorderState.fresh()
        r.start(listOf(fix(HOME)), T0)
        r.pause(listOf(fix(LatLng(31.0 + 100.0 / R * 180.0 / PI, 121.0))), T0 + 1000)
        r.resume(T0 + 2000)
        r.addPoint(HOME, 5.0, T0 + 2100)
        r.finish(listOf(fix(HOME)), T0 + 3000)
        store.saveSession(r.snapshot())

        val list = store.listSessions()
        assertEquals(1, list.size)
        assertTrue(list[0].finished)

        val parsed = JsonStore.defaultJson().parseToJsonElement(store.exportAllJson()).jsonObject
        assertEquals("baibai", parsed["app"]?.jsonPrimitive?.content)
        assertEquals(1, parsed["sessions"]?.jsonArray?.size)
    }

    @Test
    fun `导入导出往返（互通契约）`() {
        val store = JsonStore(tmp)
        val s = io.github.chenchen913.baibai.core.demo.Demo.generateDemoSession()
        store.saveSession(s)
        val json = store.exportAllJson()

        val store2 = JsonStore(File(tmp, "other"))
        val n = store2.importAllJson(json)
        assertEquals(1, n)
        assertEquals(s, store2.listSessions().first())
    }

    @Test
    fun `导出含清单且导入往返恢复清单`() {
        val store = JsonStore(tmp)
        store.savePlan(Plan(2026, listOf(PlanItem("大伯家", null)), 0, 0))
        val json = store.exportAllJson()

        val store2 = JsonStore(File(tmp, "other"))
        store2.importAllJson(json)
        assertEquals("大伯家", store2.loadPlan(2026)?.items?.first()?.name)
        assertNull(store2.loadPlan(2026)?.items?.first()?.pos)
    }

    @Test
    fun `导入拒绝：app 不是 baibai`() {
        val store = JsonStore(tmp)
        val bad = """{"app":"other","version":1,"exportedAt":"","sessions":[]}"""
        assertThrows(IllegalArgumentException::class.java) { store.importAllJson(bad) }
    }

    @Test
    fun `导入拒绝：version 不符`() {
        val store = JsonStore(tmp)
        val bad = """{"app":"baibai","version":2,"exportedAt":"","sessions":[]}"""
        assertThrows(IllegalArgumentException::class.java) { store.importAllJson(bad) }
    }

    @Test
    fun `导入拒绝：路径穿越 id`() {
        val store = JsonStore(tmp)
        val bad =
            """{"app":"baibai","version":1,"exportedAt":"","sessions":[{"id":"../checkpoint","year":2026,"date":"2026-01-01","home":{"lat":0.0,"lng":0.0},"nodes":[],"visits":[],"points":[],"state":"IDLE","currentMode":"walk","finished":false,"createdAt":0,"updatedAt":0}]}"""
        assertThrows(IllegalArgumentException::class.java) { store.importAllJson(bad) }
        // 未写入任何文件（穿越被拦截）
        assertEquals(emptyList<String>(), store.listSessions().map { it.id })
    }
}
