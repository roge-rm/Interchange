package com.rm.interchange.sim

/**
 * Each region's climate, from real normals: the mean temperature of each
 * month in tenths of a degree and the precipitation in mm, at a town of the
 * region (Ottawa for mixed, Regina, Sudbury, Banff, Halifax, Yellowknife).
 * The map is a few degrees colder to the north and colder with height, and
 * the climate drifts warmer through the decades, faster in winter.
 */
object Climate {
    private val TEMPERATURE = arrayOf(
        intArrayOf(-103, -81, -23, 65, 136, 186, 212, 199, 151, 81, 18, -61),
        intArrayOf(-147, -116, -44, 48, 113, 160, 186, 177, 115, 42, -50, -124),
        intArrayOf(-130, -110, -48, 39, 113, 165, 192, 179, 127, 59, -16, -91),
        intArrayOf(-94, -68, -22, 29, 74, 112, 144, 137, 91, 37, -43, -96),
        intArrayOf(-59, -55, -16, 41, 96, 146, 186, 186, 146, 89, 36, -25),
        intArrayOf(-256, -234, -170, -53, 55, 137, 170, 143, 72, -15, -130, -220),
    )
    private val PRECIPITATION = arrayOf(
        intArrayOf(63, 50, 64, 72, 85, 96, 92, 88, 88, 85, 82, 72),
        intArrayOf(15, 11, 18, 26, 52, 72, 62, 44, 34, 21, 15, 16),
        intArrayOf(60, 47, 58, 64, 78, 90, 79, 86, 98, 82, 81, 71),
        intArrayOf(34, 28, 31, 38, 57, 61, 53, 52, 40, 33, 35, 32),
        intArrayOf(134, 106, 120, 111, 111, 96, 96, 94, 103, 125, 136, 132),
        intArrayOf(14, 13, 13, 11, 18, 28, 36, 39, 33, 30, 22, 16),
    )
    /** How far north each region's maps lie, in degrees, for the length of the day. */
    val LATITUDE = intArrayOf(45, 50, 47, 51, 45, 62)

    /** Days in from the start of the year that each month's normal is for: its middle. */
    private val MID = intArrayOf(15, 45, 74, 105, 135, 166, 196, 227, 258, 288, 319, 349)

    /** The height in steps the normals are for, and how much colder each step above it is, in tenths. */
    const val BASE_HEIGHT = 4
    const val LAPSE = 6

    /** How much warmer the south edge of the map is than the north, in tenths. */
    const val NORTH_SOUTH = 50

    /** The normal on day [doy] of the year (0 for 1 January) from a month table, eased between month middles. */
    private fun between(table: IntArray, doy: Int): Int {
        var m = 11
        while (m > 0 && doy < MID[m]) m--
        if (doy < MID[0]) m = 11
        val next = (m + 1) % 12
        val from = MID[m]
        var span = MID[next] - from
        var at = doy - from
        if (span <= 0) span += 365
        if (at < 0) at += 365
        return table[m] + (table[next] - table[m]) * at / span
    }

    /** The mean temperature of [region] on day [doy] of [year], at the base height in the middle of the map, in tenths. */
    fun normal(region: Region, year: Int, doy: Int): Int {
        val t = between(TEMPERATURE[region.ordinal], doy)
        // Winter warms half as fast again as the year round.
        val winter = doy < 75 || doy > 320
        return t + drift(year) * (if (winter) 3 else 2) / 2
    }

    /** The normal precipitation of [region] on day [doy] of [year], in hundredths of a mm a day. */
    fun precipitation(region: Region, year: Int, doy: Int): Int {
        val month = between(PRECIPITATION[region.ordinal], doy)
        val wetter = if (year > 1970) minOf(year - 1970, 200) / 10 else 0
        return month * 100 * (100 + wetter) / 100 / 30
    }

    /**
     * How much warmer than the normals year [year] is, in tenths: the tail of
     * the cold 1800s, level through the middle of the 1900s, then warming
     * a quarter of a degree a decade and levelling off in the 2200s.
     */
    fun drift(year: Int): Int = when {
        year < 1900 -> -4
        year < 1970 -> -4 + (year - 1900) * 4 / 70
        year < 2100 -> (year - 1970) * 25 / 100
        else -> 32 + minOf(year - 2100, 200) * 15 / 100
    }

    /** Day of the year, 0 for 1 January, of game day [day]. */
    fun dayOfYear(startYear: Int, day: Int): Int = Calendar.dayOfYear(startYear, day)

    /** The season of day [doy] in [region], for looks: 0 spring, 1 summer, 2 autumn, 3 winter, by the normals' temperature. */
    fun season(region: Region, year: Int, doy: Int): Int {
        val t = normal(region, year, doy)
        val warming = normal(region, year, (doy + 30) % 365) > t
        val peak = TEMPERATURE[region.ordinal].max() + drift(year)
        return when {
            t < 0 -> 3
            t >= peak - 45 -> 1
            warming -> 0
            else -> 2
        }
    }

    /** The looks of the year, for the land and the leafy trees. */
    const val EARLY_SPRING = 0
    const val SPRING = 1
    const val SUMMER = 2
    const val LATE_SUMMER = 3
    const val AUTUMN = 4
    const val LATE_AUTUMN = 5
    const val WINTER = 6

    /** How the land looks on day [doy] in [region]: the grass greening and drying, the leaves turning and falling. */
    fun look(region: Region, year: Int, doy: Int): Int {
        val t = normal(region, year, doy)
        val warming = normal(region, year, (doy + 30) % 365) > t
        val peak = TEMPERATURE[region.ordinal].max() + drift(year)
        return when {
            t < 0 -> WINTER
            warming && t < 60 -> EARLY_SPRING
            warming && t < peak - 45 -> SPRING
            warming -> SUMMER
            t >= 130 -> LATE_SUMMER
            t >= 50 -> AUTUMN
            else -> LATE_AUTUMN
        }
    }
}
