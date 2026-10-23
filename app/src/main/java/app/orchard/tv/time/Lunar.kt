package app.orchard.tv.time

import android.content.Context
import app.orchard.tv.R
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A day in the Chinese lunar calendar. */
data class LunarDate(val year: Int, val month: Int, val day: Int, val leap: Boolean)

/**
 * The Chinese lunar calendar, 1900–2100. Each element of [INFO] describes one lunar year: the low
 * nibble is the leap month (0 for none), the 0x10000 bit says whether that leap month is long, and
 * the top twelve bits say whether each month has 30 days.
 */
object Lunar {
    private val BASE = LocalDate.of(1900, 1, 31) // 正月初一 of lunar 1900

    private val INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2, // 1900
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977, // 1910
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, // 1920
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950, // 1930
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557, // 1940
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, // 1950
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, // 1960
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6, // 1970
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570, // 1980
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x05ac0, 0x0ab60, 0x096d5, 0x092e0, // 1990
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, // 2000
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930, // 2010
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530, // 2020
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45, // 2030
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0, // 2040
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0, // 2050
        0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4, // 2060
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0, // 2070
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160, // 2080
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252, // 2090
        0x0d520, // 2100
    )

    fun of(date: LocalDate): LunarDate {
        var offset = ChronoUnit.DAYS.between(BASE, date).toInt()
        // Out of the table's range: show the solar date rather than crash.
        if (offset < 0) return LunarDate(date.year, date.monthValue, date.dayOfMonth, false)

        var year = 1900
        var temp = 0
        while (year < 2101 && offset > 0) {
            temp = yearDays(year)
            offset -= temp
            year++
        }
        if (offset < 0) {
            offset += temp
            year--
        }

        val leapMonth = leapMonth(year)
        var leap = false
        var month = 1
        while (month < 13 && offset > 0) {
            if (leapMonth > 0 && month == leapMonth + 1 && !leap) {
                month--
                leap = true
                temp = leapDays(year)
            } else {
                temp = monthDays(year, month)
            }
            if (leap && month == leapMonth + 1) leap = false
            offset -= temp
            month++
        }
        if (offset == 0 && leapMonth > 0 && month == leapMonth + 1) {
            if (leap) leap = false else { leap = true; month-- }
        }
        if (offset < 0) {
            offset += temp
            month--
        }
        return LunarDate(year, month, offset + 1, leap)
    }

    /** The month names and day names, so the calendar reads the way the locale says. */
    fun text(context: Context, lunar: LunarDate): String {
        val months = context.resources.getStringArray(R.array.lunar_months)
        val days = context.resources.getStringArray(R.array.lunar_days)
        var month = months[(lunar.month - 1).coerceIn(0, months.lastIndex)]
        if (lunar.leap) month = context.getString(R.string.lunar_leap, month)
        val day = days[(lunar.day - 1).coerceIn(0, days.lastIndex)]
        return context.getString(R.string.lunar_today, yearName(context, lunar.year), month, day)
    }

    /** The sexagenary year name: 2026 is 丙午, from the stem and branch that year falls on. */
    private fun yearName(context: Context, year: Int): String {
        val stems = context.resources.getStringArray(R.array.lunar_stems)
        val branches = context.resources.getStringArray(R.array.lunar_branches)
        val cycle = year - 4
        return context.getString(
            R.string.lunar_cycle,
            stems[((cycle % 10) + 10) % 10],
            branches[((cycle % 12) + 12) % 12],
        )
    }

    private fun yearDays(year: Int): Int {
        var sum = 348
        var bit = 0x8000
        while (bit > 0x8) {
            if (INFO[year - 1900] and bit != 0) sum++
            bit = bit shr 1
        }
        return sum + leapDays(year)
    }

    private fun leapMonth(year: Int): Int = INFO[year - 1900] and 0xf

    private fun leapDays(year: Int): Int =
        if (leapMonth(year) != 0) {
            if (INFO[year - 1900] and 0x10000 != 0) 30 else 29
        } else {
            0
        }

    internal fun monthDays(year: Int, month: Int): Int =
        if (INFO[year - 1900] and (0x10000 shr month) != 0) 30 else 29
}
