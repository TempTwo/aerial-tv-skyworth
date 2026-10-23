package app.orchard.tv.time

import app.orchard.tv.R
import java.time.LocalDate

/**
 * The festivals the home date names, solar and lunar. A day can hold two (2020-10-01 was both
 * National Day and Mid-Autumn), so this returns every one of them.
 */
object Festivals {
    /** Month × 100 + day, on the solar calendar. */
    private val SOLAR = mapOf(
        101 to R.string.festival_new_year,
        214 to R.string.festival_valentines,
        308 to R.string.festival_womens_day,
        312 to R.string.festival_arbor_day,
        401 to R.string.festival_april_fools,
        501 to R.string.festival_labour_day,
        504 to R.string.festival_youth_day,
        601 to R.string.festival_childrens_day,
        701 to R.string.festival_party_day,
        801 to R.string.festival_army_day,
        910 to R.string.festival_teachers_day,
        1001 to R.string.festival_national_day,
        1031 to R.string.festival_halloween,
        1111 to R.string.festival_singles_day,
        1224 to R.string.festival_christmas_eve,
        1225 to R.string.festival_christmas,
    )

    /** Month × 100 + day, on the lunar calendar. Leap months take no festivals. */
    private val LUNAR = mapOf(
        101 to R.string.festival_spring_festival,
        115 to R.string.festival_lantern,
        202 to R.string.festival_dragon_head,
        505 to R.string.festival_dragon_boat,
        707 to R.string.festival_qixi,
        715 to R.string.festival_ghost,
        815 to R.string.festival_mid_autumn,
        909 to R.string.festival_double_ninth,
        1208 to R.string.festival_laba,
        1223 to R.string.festival_little_new_year,
    )

    fun of(date: LocalDate, lunar: LunarDate): List<Int> {
        val out = mutableListOf<Int>()
        SOLAR[date.monthValue * 100 + date.dayOfMonth]?.let(out::add)
        if (!lunar.leap) {
            // 除夕 is the last day of the twelfth month, which is 29 or 30 days long.
            if (lunar.month == 12 && lunar.day == Lunar.monthDays(lunar.year, 12)) {
                out.add(R.string.festival_new_years_eve)
            }
            LUNAR[lunar.month * 100 + lunar.day]?.let(out::add)
        }
        return out
    }
}
