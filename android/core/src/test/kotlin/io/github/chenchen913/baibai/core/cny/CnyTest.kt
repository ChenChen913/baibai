package io.github.chenchen913.baibai.core.cny

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 对应网页版 tests/cny.test.ts */
class CnyTest {

    @Test
    fun 春节当天显示大年初一() {
        assertEquals("大年初一", Cny.label(LocalDate.of(2026, 2, 17)))
    }

    @Test
    fun 初二到初十() {
        assertEquals("大年初二", Cny.label(LocalDate.of(2026, 2, 18)))
        assertEquals("大年初五", Cny.label(LocalDate.of(2026, 2, 21)))
        assertEquals("大年初十", Cny.label(LocalDate.of(2026, 2, 26)))
    }

    @Test
    fun 除夕() {
        assertEquals("除夕", Cny.label(LocalDate.of(2026, 2, 16)))
    }

    @Test
    fun 初十之后回退月日() {
        assertEquals("2月27日", Cny.label(LocalDate.of(2026, 2, 27)))
    }

    @Test
    fun 非春节时段回退月日() {
        assertEquals("8月14日", Cny.label(LocalDate.of(2026, 8, 14)))
    }

    @Test
    fun 跨年边界() {
        assertEquals("大年初一", Cny.label(LocalDate.of(2025, 1, 29)))
        assertEquals("大年初十", Cny.label(LocalDate.of(2025, 2, 7)))
        assertEquals("2月8日", Cny.label(LocalDate.of(2025, 2, 8)))
        assertEquals("大年初一", Cny.label(LocalDate.of(2027, 2, 6)))
    }

    @Test
    fun 表外年份回退月日() {
        assertEquals("1月1日", Cny.label(LocalDate.of(2019, 1, 1)))
    }
}
