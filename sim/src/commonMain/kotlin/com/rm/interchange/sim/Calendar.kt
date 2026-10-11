package com.rm.interchange.sim

/** Days counted from the first of January of [World.startYear], in 365 day years. */
object Calendar {
    const val TICKS_PER_DAY = 64

    private val MONTH_DAYS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    fun year(startYear: Int, day: Int) = startYear + day / 365

    /** 0 for January. */
    fun month(day: Int): Int {
        var d = day % 365
        for (m in 0 until 12) {
            if (d < MONTH_DAYS[m]) return m
            d -= MONTH_DAYS[m]
        }
        return 11
    }

    /** 1 for the first of the month. */
    fun dayOfMonth(day: Int): Int {
        var d = day % 365
        for (m in 0 until 12) {
            if (d < MONTH_DAYS[m]) return d + 1
            d -= MONTH_DAYS[m]
        }
        return d + 1
    }

    /** The day of the week of game day [day], 0 for Monday, years kept at 365 days as the game's are. */
    fun weekday(startYear: Int, day: Int): Int {
        // January 1st of the start year by Zeller's rule, as the game's own weeks start from it.
        val y = startYear - 1
        val k = y % 100
        val j = y / 100
        // Zeller for the 1st of a January counted as the 13th month of the year before; 0 is Saturday.
        val h = (1 + 13 * 14 / 5 + k + k / 4 + j / 4 + 5 * j) % 7
        val jan1 = (h + 5) % 7
        return (jan1 + day) % 7
    }

    /** Whether game day [day] is a holiday: New Year's, Dominion Day, Labour Day, Thanksgiving, Christmas or Boxing Day. */
    fun holiday(startYear: Int, day: Int): Boolean {
        val m = month(day)
        val d = dayOfMonth(day)
        val monday = weekday(startYear, day) == 0
        return (m == 0 && d == 1) || (m == 6 && d == 1) || (m == 8 && monday && d <= 7) ||
            (m == 9 && monday && d in 8..14) || (m == 11 && (d == 25 || d == 26))
    }

    /**
     * How busy passenger travel is on game day [day], in percent: more on
     * weekdays, with people going to work, less at weekends, more over the
     * holidays, at Christmas and through the summer.
     */
    fun travel(startYear: Int, day: Int): Int {
        val m = month(day)
        val d = dayOfMonth(day)
        var f = when (weekday(startYear, day)) { 5 -> 85; 6 -> 65; else -> 110 }
        if (holiday(startYear, day)) f = 150
        if ((m == 11 && d >= 20) || (m == 0 && d <= 2)) f = f * 130 / 100
        if (m == 6 || m == 7) f = f * 115 / 100
        return f
    }

    /** How much mail there is on game day [day], in percent: none on Sundays or holidays, and the Christmas rush through December. */
    fun mail(startYear: Int, day: Int): Int {
        if (weekday(startYear, day) == 6 || holiday(startYear, day)) return 0
        return if (month(day) == 11 && dayOfMonth(day) <= 24) 250 else 100
    }
}
