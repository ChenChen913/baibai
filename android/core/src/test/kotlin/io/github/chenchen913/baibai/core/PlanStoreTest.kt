package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Plan
import io.github.chenchen913.baibai.core.model.PlanItem
import io.github.chenchen913.baibai.core.store.JsonStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 对应网页版 tests/plan.test.ts 的 db plans 部分（2 项） */
class PlanStoreTest {

    @TempDir
    lateinit var tmp: File

    private lateinit var store: JsonStore

    @BeforeEach
    fun setUp() {
        store = JsonStore(tmp)
    }

    @Test
    fun `保存读取覆盖往返`() {
        val p = Plan(
            year = 2027,
            createdAt = 1,
            updatedAt = 2,
            items = listOf(PlanItem("大伯家", LatLng(36.75, 118.96))),
        )
        store.savePlan(p)
        assertEquals(p, store.loadPlan(2027))
        val p2 = p.copy(items = emptyList())
        store.savePlan(p2)
        assertEquals(0, store.loadPlan(2027)?.items?.size)
        // 无坐标项序列化后 pos 缺省仍可读（契约：null 不输出）
        val p3 = p.copy(items = listOf(PlanItem("张叔家", null)))
        store.savePlan(p3)
        assertNull(store.loadPlan(2027)?.items?.first()?.pos)
    }

    @Test
    fun `清除后读不到`() {
        store.savePlan(Plan(2027, emptyList(), 0, 0))
        store.clearPlan(2027)
        assertNull(store.loadPlan(2027))
    }
}
