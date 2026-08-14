package io.github.chenchen913.baibai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.chenchen913.baibai.core.demo.Demo
import io.github.chenchen913.baibai.core.review.Review
import io.github.chenchen913.baibai.core.store.JsonStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 回顾页操作 → JsonStore 落盘冒烟（A-M2 对照表 §6，3 项） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewStoreSmokeTest {

    private lateinit var store: JsonStore

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        store = JsonStore(File(app.filesDir, "smoke-test"))
        File(app.filesDir, "smoke-test").deleteRecursively()
    }

    private fun reload(id: String) = store.listSessions().first { it.id == id }

    @Test
    fun `改名落盘`() {
        val s = Demo.generateDemoSession()
        store.saveSession(s)
        val renamed = Review.renameNode(s, s.nodes[0].id, "测试户")
        store.saveSession(renamed)
        assertEquals("测试户", reload(s.id).nodes.first { it.id == s.nodes[0].id }.name)
    }

    @Test
    fun `合并落盘`() {
        val s = Demo.generateDemoSession()
        store.saveSession(s)
        val merged = Review.mergeNodes(s, s.nodes[0].id, s.nodes[1].id)
        store.saveSession(merged)
        val r = reload(s.id)
        assertEquals(7, r.nodes.size)
        assertEquals(8, r.visits.size) // 合并重挂访问，访问总数不变
    }

    @Test
    fun `剔除跳变点落盘`() {
        val s = Demo.generateDemoSession()
        store.saveSession(s)
        var next = s
        s.points.filter { it.jump == true }.forEach { next = Review.removePoint(next, it.t) }
        store.saveSession(next)
        assertNull(reload(s.id).points.firstOrNull { it.jump == true })
    }
}
