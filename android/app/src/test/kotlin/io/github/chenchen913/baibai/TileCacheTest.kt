package io.github.chenchen913.baibai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 风控占位图防护回归测试（真机"地图空白"根因，2026-08-20）：
 * 高德对 Kotlin HttpURLConnection 等非浏览器指纹请求返回 HTTP 200 的 1×1 米色 PNG（179 字节）——
 * 「假成功」若入缓存会毒化磁盘缓存、骗过 tileload 计数、让回退看门狗永不触发。
 * 契约：占位图不入缓存、读取时自清理并视同未命中、正常瓦片不受误伤。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TileCacheTest {

    private lateinit var cache: TileCache

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        cache = TileCache(app)
    }

    /** 构造任意宽高的 PNG 字节头（IHDR 宽高位于偏移 16/20，大端 4 字节） */
    private fun pngHeader(w: Int, h: Int, total: Int): ByteArray {
        val b = ByteArray(maxOf(total, 24))
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b, 0)
        b[16] = (w shr 24).toByte(); b[17] = (w shr 16).toByte(); b[18] = (w shr 8).toByte(); b[19] = w.toByte()
        b[20] = (h shr 24).toByte(); b[21] = (h shr 16).toByte(); b[22] = (h shr 8).toByte(); b[23] = h.toByte()
        return b
    }

    @Test
    fun `1x1 占位 PNG（真机实测样本形态）被识别`() {
        assertTrue(cache.isPlaceholderTile(pngHeader(1, 1, 179)))
        assertTrue(cache.isPlaceholderTile(pngHeader(2, 2, 200)))
    }

    @Test
    fun `正常瓦片不误伤`() {
        // 256×256 PNG：街道瓦片典型形态
        assertFalse(cache.isPlaceholderTile(pngHeader(256, 256, 3000)))
        // 非 PNG（卫星 JPEG 等）按体积判定：真实瓦片至少 1.5KB+
        assertFalse(cache.isPlaceholderTile(ByteArray(2000) { 0x1 }))
    }

    @Test
    fun `非 PNG 的小文件视为可疑占位`() {
        assertTrue(cache.isPlaceholderTile(ByteArray(500) { 0x1 }))
    }

    @Test
    fun `历史中毒缓存（旧版写入的 1x1）读取时自清理并视同未命中`() {
        val url = "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=1&y=1&z=3"
        val f = cache.fileFor(url)
        f.writeBytes(pngHeader(1, 1, 179))
        assertNull(cache.get(url))
        assertFalse("中毒缓存文件应被删除", f.exists())
    }

    @Test
    fun `正常瓦片缓存命中原样返回`() {
        val url = "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=2&y=2&z=3"
        val bytes = pngHeader(256, 256, 2048)
        cache.fileFor(url).writeBytes(bytes)
        assertArrayEquals(bytes, cache.get(url))
    }
}
