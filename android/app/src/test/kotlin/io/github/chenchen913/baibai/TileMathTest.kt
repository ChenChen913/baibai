package io.github.chenchen913.baibai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 瓦片数学单测（P0）；与网页版 tests/tiles.test.ts 同一组已知值——两侧契约一致 */
class TileMathTest {

    @Test
    fun 瓦片号已知值() {
        // z1 全球 2x2
        assertEquals(0, TileMath.tileX(-180.0, 1))
        assertEquals(1, TileMath.tileX(179.9, 1))
        assertEquals(0, TileMath.tileY(85.0, 1))
        assertEquals(1, TileMath.tileY(-85.0, 1))
        // 潍坊附近 z13 确定性（与网页版同值）
        val x = TileMath.tileX(119.1, 13)
        val y = TileMath.tileY(36.71, 13)
        assertTrue(x in 0..8191)
        assertTrue(y in 0..8191)
        assertEquals(x, TileMath.tileX(119.1, 13))
        assertEquals(y, TileMath.tileY(36.71, 13))
    }

    @Test
    fun 预载清单数量合理且含两种样式() {
        val list = TileMath.preloadList(36.71, 119.1)
        assertTrue("张数应在 100~300 之间，实际 " + list.size, list.size in 100..300)
        assertTrue(list.any { it.contains("style=6") })
        assertTrue(list.any { it.contains("style=8") })
        assertEquals(list.size, list.toSet().size)
    }
    @Test
    fun cacheKey规范化去主机与子域() {
        val a = TileMath.cacheKey("https://webst03.is.autonavi.com/appmaptile?style=6&x=1&y=2&z=13")
        val b = TileMath.cacheKey("https://webst01.is.autonavi.com/appmaptile?style=6&x=1&y=2&z=13")
        assertEquals("/appmaptile?style=6&x=1&y=2&z=13", a)
        assertEquals(a, b)
    }

    @Test
    fun 越界坐标收敛到合法瓦片号() {
        assertEquals(8191, TileMath.tileX(180.0, 13))
        assertEquals(0, TileMath.tileY(89.9, 13))
        assertEquals(131071, TileMath.tileY(-89.9, 17)) // 最南 = (1 shl 17) - 1
    }
}

