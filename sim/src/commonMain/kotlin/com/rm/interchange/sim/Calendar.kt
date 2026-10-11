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
}
