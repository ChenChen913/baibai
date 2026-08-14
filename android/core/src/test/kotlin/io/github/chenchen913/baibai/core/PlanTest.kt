package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.demo.Demo
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.Mode
import io.github.chenchen913.baibai.core.model.Plan
import io.github.chenchen913.baibai.core.model.PlanItem
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.model.SessionState
import io.github.chenchen913.baibai.core.plan.PlanOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

/** 对应网页版 tests/plan.test.ts 的纯函数部分（8 项，逐位对齐） */
class PlanTest {

    companion object {
        val HOME = LatLng(31.0, 121.0)
        const val R = 6371000.0
        fun far(m: Double): LatLng = LatLng(HOME.lat + m / R * 180.0 / PI, HOME.lng)

        fun mkSession(nodes: List<Pair<String, LatLng>>): SessionData = SessionData(
            id = "s-test",
            year = 2027,
            date = "2027-02-06",
            home = HOME,
            nodes = nodes.mapIndexed { i, (id, pos) ->
                io.github.chenchen913.baibai.core.model.HouseNode(id = id, name = "", autoNo = i + 1, pos = pos)
            },
            visits = emptyList(),
            points = emptyList(),
            state = SessionState.FINISHED,
            currentMode = Mode.WALK,
            finished = true,
            createdAt = 0,
            updatedAt = 0,
        )
    }

    @Test
    fun `planFromSession 从去年会话生成清单`() {
        val prev = Demo.generateDemoSession()
        val plan = PlanOps.planFromSession(prev, 2027)
        assertEquals(2027, plan.year)
        assertEquals(8, plan.items.size)
        assertEquals("大伯家", plan.items[0].name)
        assertEquals(prev.nodes[0].pos, plan.items[0].pos)
        // 空名保留为空串
        val empty = mkSession(listOf("a" to far(100.0), "b" to far(200.0)))
        val plan2 = PlanOps.planFromSession(empty, 2027)
        assertEquals("", plan2.items[0].name)
    }

    private fun plan3(): Plan = Plan(
        year = 2027,
        createdAt = 0,
        updatedAt = 0,
        items = listOf(
            PlanItem("大伯家", far(100.0)),
            PlanItem("二叔家", far(300.0)),
            PlanItem("三舅家", far(500.0)),
        ),
    )

    @Test
    fun `matchPlan 全部到访三对三`() {
        val s = mkSession(listOf("n1" to far(103.0), "n2" to far(298.0), "n3" to far(502.0)))
        val r = PlanOps.matchPlan(s, plan3())
        assertEquals(3, r.visited.size)
        assertEquals(0, r.missing.size)
    }

    @Test
    fun `matchPlan 漏访一户没去`() {
        val s = mkSession(listOf("n1" to far(103.0), "n2" to far(298.0)))
        val r = PlanOps.matchPlan(s, plan3())
        assertEquals(2, r.visited.size)
        assertEquals(listOf("三舅家"), r.missing.map { it.name })
    }

    @Test
    fun `matchPlan 一对一贪心：两 item 争同一 node 只配最近者`() {
        val s = mkSession(listOf("n1" to far(105.0)))
        val two = plan3().copy(items = listOf(PlanItem("A", far(102.0)), PlanItem("B", far(108.0))))
        val r = PlanOps.matchPlan(s, two)
        assertEquals(1, r.visited.size)
        assertEquals("A", r.visited[0].item.name)
        assertEquals(listOf("B"), r.missing.map { it.name })
    }

    @Test
    fun `matchPlan 10m 边界：9_999 配、10_001 缺`() {
        val s = mkSession(listOf("n1" to far(0.0)))
        val edge = plan3().copy(items = listOf(PlanItem("A", far(9.999)), PlanItem("B", far(10.001))))
        val r = PlanOps.matchPlan(s, edge)
        assertEquals(listOf("A"), r.visited.map { it.item.name })
        assertEquals(listOf("B"), r.missing.map { it.name })
    }

    @Test
    fun `matchPlan 空清单与空会话`() {
        val empty = plan3().copy(items = emptyList())
        val r = PlanOps.matchPlan(mkSession(listOf("n" to far(10.0))), empty)
        assertEquals(0, r.visited.size)
        assertEquals(0, r.missing.size)
        val r2 = PlanOps.matchPlan(mkSession(emptyList()), plan3())
        assertEquals(3, r2.missing.size)
    }

    @Test
    fun `matchPlan 无坐标项不参与自动匹配，恒为 missing`() {
        val s = mkSession(listOf("n1" to far(100.0)))
        val withManual = plan3().copy(
            items = listOf(PlanItem("大伯家", far(100.0)), PlanItem("新搬来的张叔家", null)),
        )
        val r = PlanOps.matchPlan(s, withManual)
        assertEquals(listOf("大伯家"), r.visited.map { it.item.name })
        assertEquals(listOf("新搬来的张叔家"), r.missing.map { it.name })
    }

    @Test
    fun `nameCandidates 按距离升序、空名过滤、top 截断`() {
        val prev = Demo.generateDemoSession()
        val cands = PlanOps.nameCandidates(far(200.0), prev.nodes, 3)
        assertEquals(3, cands.size)
        assertTrue(cands[0].distM <= cands[1].distM)
        assertTrue(cands[1].distM <= cands[2].distM)
        assertTrue(cands.all { it.name.isNotBlank() })
    }
}
