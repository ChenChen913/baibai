package io.github.chenchen913.baibai.core.cny

import java.time.LocalDate

/**
 * 春节日期与「大年初X」标签（纯函数）。
 * 与网页版 src/cny.ts 同一张春节日期表——两侧口径一致（契约）。
 */
object Cny {

    /** 春节（正月初一）公历日期表：2020–2037（历法公布值，与权威日历一致） */
    val springFestival: Map<Int, LocalDate> = mapOf(
        2020 to LocalDate.of(2020, 1, 25),
        2021 to LocalDate.of(2021, 2, 12),
        2022 to LocalDate.of(2022, 2, 1),
        2023 to LocalDate.of(2023, 1, 22),
        2024 to LocalDate.of(2024, 2, 10),
        2025 to LocalDate.of(2025, 1, 29),
        2026 to LocalDate.of(2026, 2, 17),
        2027 to LocalDate.of(2027, 2, 6),
        2028 to LocalDate.of(2028, 1, 26),
        2029 to LocalDate.of(2029, 2, 13),
        2030 to LocalDate.of(2030, 2, 3),
        2031 to LocalDate.of(2031, 1, 23),
        2032 to LocalDate.of(2032, 2, 11),
        2033 to LocalDate.of(2033, 1, 31),
        2034 to LocalDate.of(2034, 2, 19),
        2035 to LocalDate.of(2035, 2, 8),
        2036 to LocalDate.of(2036, 1, 28),
        2037 to LocalDate.of(2037, 2, 15),
    )

    /** 春节当天（初一）之后 9 天、即初一到初十；前一天为除夕 */
    const val MAX_DAY = 10L

    /** 日期 → 拜年标签：除夕/大年初X（初一到初十）；其余回退「M月d日」 */
    fun label(date: LocalDate): String {
        val cny = springFestival[date.year] ?: return fmtMonthDay(date)
        val diff = date.toEpochDay() - cny.toEpochDay() // 正月初一 = 0
        return if (diff == -1L) {
            "除夕"
        } else if (diff >= 0 && diff < MAX_DAY) {
            "大年初" + cnNum(diff + 1)
        } else {
            fmtMonthDay(date)
        }
    }

    private fun cnNum(n: Long): String = when (n) {
        1L -> "一"
        2L -> "二"
        3L -> "三"
        4L -> "四"
        5L -> "五"
        6L -> "六"
        7L -> "七"
        8L -> "八"
        9L -> "九"
        10L -> "十"
        else -> n.toString()
    }

    private fun fmtMonthDay(date: LocalDate): String =
        date.monthValue.toString() + "月" + date.dayOfMonth.toString() + "日"
}
