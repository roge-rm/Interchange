package com.rm.interchange.sim

/**
 * Days counted from the first of January of [World.startYear], on the real
 * calendar, leap years and all, so dates and weekdays match history. All in
 * integers, as everything the game decides by must be.
 */
object Calendar {
    const val TICKS_PER_DAY = 64

    /** Days since 1 January 1970 of [y]-[m]-[d], [m] from 1; Gregorian, any year. */
    private fun civilToDays(y: Int, m: Int, d: Int): Long {
        val yy = (if (m <= 2) y - 1 else y).toLong()
        val era = yy.floorDiv(400L)
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** The year, month (1 to 12) and day of the month of day [z] since 1 January 1970, packed as year * 10000 + month * 100 + day. */
    private fun daysToCivil(z0: Long): Long {
        val z = z0 + 719468
        val era = z.floorDiv(146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        return y * 10000 + m * 100 + d
    }

    private fun civil(startYear: Int, day: Int) = daysToCivil(civilToDays(startYear, 1, 1) + day)

    fun year(startYear: Int, day: Int) = (civil(startYear, day) / 10000).toInt()

    /** 0 for January. */
    fun month(startYear: Int, day: Int) = (civil(startYear, day) / 100 % 100).toInt() - 1

    /** 1 for the first of the month. */
    fun dayOfMonth(startYear: Int, day: Int) = (civil(startYear, day) % 100).toInt()

    /** Days since the first of January of game day [day]'s year, 0 to 365. */
    fun dayOfYear(startYear: Int, day: Int): Int {
        val z = civilToDays(startYear, 1, 1) + day
        return (z - civilToDays((daysToCivil(z) / 10000).toInt(), 1, 1)).toInt()
    }

    /** Whether game day [day] is the first of January. */
    fun newYear(startYear: Int, day: Int) = civil(startYear, day) % 10000 == 101L

    /** The day of the week of game day [day], 0 for Monday. 1 January 1970 was a Thursday. */
    fun weekday(startYear: Int, day: Int): Int = (civilToDays(startYear, 1, 1) + day + 3).mod(7L).toInt()

    /**
     * Whether game day [day] is a holiday, as kept in Canada that year:
     * New Year's, Christmas and Boxing Day always; Dominion Day from 1879;
     * Labour Day from 1894; Thanksgiving from 1879, on the days it was held.
     */
    fun holiday(startYear: Int, day: Int): Boolean {
        val y = year(startYear, day)
        val m = month(startYear, day)
        val d = dayOfMonth(startYear, day)
        val wd = weekday(startYear, day)
        return (m == 0 && d == 1) || (m == 11 && (d == 25 || d == 26)) ||
            (y >= 1879 && m == 6 && d == 1) ||
            (y >= 1894 && m == 8 && wd == 0 && d <= 7) ||
            thanksgiving(y, m, d, wd)
    }

    /**
     * Thanksgiving by era: a Thursday in early November from 1879, a Thursday
     * in mid-October from 1899, the Monday of the week of Armistice Day from
     * 1921, and the second Monday of October from 1931, fixed by law in 1957.
     */
    private fun thanksgiving(y: Int, m: Int, d: Int, wd: Int): Boolean = when {
        y < 1879 -> false
        y < 1899 -> m == 10 && wd == 3 && d <= 7
        y < 1921 -> m == 9 && wd == 3 && d in 15..21
        y < 1931 -> m == 10 && wd == 0 && d in 5..11
        else -> m == 9 && wd == 0 && d in 8..14
    }

    /**
     * How busy passenger travel is on game day [day], in percent: more on
     * weekdays, with people going to work, less at weekends, more over the
     * holidays, at Christmas and through the summer.
     */
    fun travel(startYear: Int, day: Int): Int {
        val m = month(startYear, day)
        val d = dayOfMonth(startYear, day)
        var f = when (weekday(startYear, day)) { 5 -> 85; 6 -> 65; else -> 110 }
        if (holiday(startYear, day)) f = 150
        if ((m == 11 && d >= 20) || (m == 0 && d <= 2)) f = f * 130 / 100
        if (m == 6 || m == 7) f = f * 115 / 100
        return f
    }

    /** How much mail there is on game day [day], in percent: none on Sundays or holidays, and the Christmas rush through December. */
    fun mail(startYear: Int, day: Int): Int {
        if (weekday(startYear, day) == 6 || holiday(startYear, day)) return 0
        return if (month(startYear, day) == 11 && dayOfMonth(startYear, day) <= 24) 250 else 100
    }
}
