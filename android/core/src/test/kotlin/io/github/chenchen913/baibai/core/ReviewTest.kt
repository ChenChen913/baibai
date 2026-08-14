package io.github.chenchen913.baibai.core

import io.github.chenchen913.baibai.core.model.Fix
import io.github.chenchen913.baibai.core.model.LatLng
import io.github.chenchen913.baibai.core.model.SessionData
import io.github.chenchen913.baibai.core.review.Review
import io.github.chenchen913.baibai.core.state.RecorderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.math.PI

/** 对应网页版 tests/review.test.ts（9 项，逐位对齐） */
class ReviewTest {

    companion object {
        val HOME = LatLng(31.0, 121.0)
        const val R = 6371000.0
        const val T0 = 1_700_000_000_000L

        fun far(m: Double): LatLng = LatLng(HOME.lat + m / R * 180.0 / PI, HOME.lng)
        fun fix(pos: LatLng, acc: Double = 5.0) = Fix(pos, acc)

        /** 两户场景：A（拜访两次，相距 8m 合并），B（拜访一次） */
        fun twoNodeSession(): SessionData {
            val r = RecorderState.fresh()
            r.start(listOf(fix(HOME)), T0)
            r.addPoint(HOME, 5.0, T0 + 100)
            r.pause(listOf(fix(far(100.0))), T0 + 1000) // A 第 1 次
            r.resume(T0 + 2000)
            r.addPoint(far(104.0), 5.0, T0 + 2100) // 靠近 A 的点（供拆分取坐标）
            r.pause(listOf(fix(far(108.0))), T0 + 3000) // A 第 2 次（8m 内合并）
            r.resume(T0 + 4000)
            r.addPoint(far(200.0), 5.0, T0 + 4100)
            r.pause(listOf(fix(far(300.0))), T0 + 5000) // B
            r.resume(T0 + 6000)
            r.addPoint(HOME, 5.0, T0 + 6100)
            r.finish(listOf(fix(HOME)), T0 + 7000)
            return r.snapshot()
        }
    }

    @Test
    fun `renameNode 改名生效`() {
        val s = twoNodeSession()
        val id = s.nodes[0].id
        val out = Review.renameNode(s, id, "大伯家")
        assertEquals("大伯家", out.nodes.first { it.id == id }.name)
        assertEquals(s.visits, out.visits)
    }

    @Test
    fun `renameNode home 不可改名`() {
        val s = twoNodeSession()
        assertSame(s, Review.renameNode(s, "home", "X"))
    }

    @Test
    fun `mergeNodes 访问并入、节点删除、空名继承 drop 名`() {
        val s = twoNodeSession()
        val (a, b) = s.nodes.map { it.id }
        val named = Review.renameNode(s, b, "二叔家")
        val out = Review.mergeNodes(named, a, b)
        assertEquals(1, out.nodes.size)
        assertEquals(a, out.nodes[0].id)
        assertEquals(3, out.visits.count { it.nodeId == a }) // A 2 次 + B 1 次
        assertEquals(0, out.visits.count { it.nodeId == b })
        assertEquals("二叔家", out.nodes[0].name) // keep 原名空 → 继承 drop 名
        val out2 = Review.mergeNodes(named, b, a) // 反向：keep=B（有名字）
        assertEquals("二叔家", out2.nodes[0].name)
    }

    @Test
    fun `mergeNodes 不存在的 id 原样返回`() {
        val s = twoNodeSession()
        assertSame(s, Review.mergeNodes(s, "x", "y"))
    }

    @Test
    fun `splitVisit 拆出第二次到访为新户，坐标取 arriveT 前最近点`() {
        val s = twoNodeSession()
        val visitIdx = s.visits.indexOfFirst { it.nodeId == s.nodes[0].id && it.arriveT == T0 + 3000 }
        val out = Review.splitVisit(s, visitIdx)
        assertEquals(3, out.nodes.size)
        val newNode = out.nodes.first { it.autoNo == 2 }
        assertEquals(far(104.0), newNode.pos) // T0+2100 的点
        assertEquals(newNode.id, out.visits[visitIdx].nodeId)
        assertEquals(listOf(1, 2, 3), out.nodes.map { it.autoNo }.sorted())
    }

    @Test
    fun `splitVisit home 访问不可拆`() {
        val r = RecorderState.fresh()
        r.start(listOf(fix(HOME)), T0)
        r.addPoint(HOME, 5.0, T0 + 100)
        r.pause(listOf(fix(far(5.0))), T0 + 1000) // 合并回 Home
        r.resume(T0 + 2000)
        r.addPoint(far(50.0), 5.0, T0 + 2100)
        r.pause(listOf(fix(far(100.0))), T0 + 3000)
        val s = r.snapshot()
        assertEquals("home", s.visits[0].nodeId)
        assertSame(s, Review.splitVisit(s, 0))
    }

    @Test
    fun `splitVisit 无轨迹点可借时原样返回`() {
        val r = RecorderState.fresh()
        r.start(listOf(fix(HOME)), T0)
        r.pause(listOf(fix(far(100.0))), T0 + 1000) // 没有 addPoint
        val s = r.snapshot()
        assertSame(s, Review.splitVisit(s, 0))
    }

    @Test
    fun `removePoint 按时间戳剔除单个点`() {
        val s = twoNodeSession()
        val t = s.points[1].t
        val out = Review.removePoint(s, t)
        assertEquals(s.points.size - 1, out.points.size)
        assertEquals(0, out.points.count { it.t == t })
    }

    @Test
    fun `renumberNodes 按首次到访顺序编号`() {
        val s = twoNodeSession()
        val (a, b) = s.nodes.map { it.id }
        val out = Review.renumberNodes(s)
        assertEquals(1, out.nodes.first { it.id == a }.autoNo)
        assertEquals(2, out.nodes.first { it.id == b }.autoNo)
    }
}
