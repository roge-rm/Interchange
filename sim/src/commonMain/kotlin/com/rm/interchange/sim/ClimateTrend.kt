package com.rm.interchange.sim

/*
 * The climate in later decades, with [NewGame.future] on. The warming itself is [Climate.drift], the same with
 * future on or off, so ice roads open later and close sooner and the sailing season runs longer either way. With
 * future on, from [TREND_FROM] a trend step a decade, at most [TREND_MOST], adds: thaw under track and roads on
 * ground that was permafrost in [THAW_BASE_YEAR], with slumps and now and then a washout; fires and washouts more
 * often; burnt forest growing back; warnings for fire, floods and thaw on a player's lines; a Chronicle line each
 * decade on the ice season, and stories for big fires and floods. With future off none of this runs.
 */

/** The trend's first year, a step each decade from it, and the most steps. */
const val TREND_FROM = 2030
const val TREND_MOST = 7

/** Fires and washouts are this many per cent more likely for each trend step. */
const val TREND_ODDS = 25

/** Ground whose year-round mean in this year's climate was below [EraCosts.PERMAFROST_MEAN] is thawing permafrost. */
const val THAW_BASE_YEAR = 2020

/** Chance out of 256 a work day, for each step, that thawing ground under track slumps, and by how many points. */
const val THAW_ODDS = 3
const val THAW_SLUMP = 60

/** Chance out of 65536 a day, for each step, that track on thawing ground below this condition washes out. */
const val THAW_WASHOUT = 3
const val THAW_WASHOUT_BELOW = 200

/** Chance out of 256 a work day, for each step, that thawing ground under a road slumps, and by how many points. */
const val THAW_ROAD_ODDS = 1
const val THAW_ROAD = 20

/** Chance out of 65536 a day that a burnt tile, past its black days, grows trees again: about once in 4 years. */
const val REGROW_ODDS = 45

/** Tiles burning at once that make a big fire, and washouts in a day that make a flood, for the Chronicle. */
const val BIG_FIRE = 40
const val BIG_FLOOD = 3

/** Trend steps in [year], 0 with future off or before [TREND_FROM]. */
fun World.trendSteps(year: Int): Int =
    if (!settings.future || year < TREND_FROM) 0 else minOf(TREND_MOST, (year - TREND_FROM) / 10 + 1)

/** The usual odds of a fire or washout scaled for [steps], in per cent. */
fun trendOdds(steps: Int) = 100 + TREND_ODDS * steps

/**
 * Whether a [dice] below [odds] comes up, made [trendOdds] for [steps] more likely by [fraction], a further dice of
 * 0 to 255, for the share between whole numbers. With no steps it's [dice] below [odds] alone, as before the trend.
 */
fun trendHit(dice: Int, fraction: Int, odds: Int, steps: Int): Boolean =
    if (steps == 0) dice < odds else (dice * 256L + fraction) * 100 < odds * 256L * trendOdds(steps)

/** Whether tile ([x], [y]) is ground that was permafrost in [THAW_BASE_YEAR]: the coldest, by the north edge and up high. */
fun World.thawGround(x: Int, y: Int): Boolean {
    val shift = (y - height / 2) * Climate.NORTH_SOUTH / height.coerceAtLeast(1) - (tileHeight(x, y) - Climate.BASE_HEIGHT) * Climate.LAPSE
    return Climate.mean(settings.region) + Climate.drift(THAW_BASE_YEAR) + shift < EraCosts.PERMAFROST_MEAN
}

/**
 * The lakes' winter in [region] from the autumn of [year], from the normals at the water's height in the middle of
 * the map: the day of the year (0 for 1 January, past 365 into the next) the normal drops below freezing, and the
 * day it's above again, both -1 if it never freezes. Ice roads open some weeks after the freeze and close some
 * weeks before the thaw, and ships sail the rest of the year, so all three follow these days.
 */
class IceSeason(val freezes: Int, val thaws: Int) {
    val frozenDays get() = if (freezes < 0) 0 else thaws - freezes
    val openDays get() = 365 - frozenDays
}

/** The height of water tiles, in steps. */
private const val WATER_HEIGHT = 0

/** [IceSeason] of [region] in [year], the same with future on or off. */
fun iceSeason(region: Region, year: Int): IceSeason {
    var freezes = -1
    var thaws = -1
    // From the first day below freezing to the day after the last, so a day or two above in between doesn't count.
    for (k in 0 until 365) {
        val day = 181 + k
        val y = if (day >= 365) year + 1 else year
        val t = Climate.normal(region, y, day % 365) - (WATER_HEIGHT - Climate.BASE_HEIGHT) * Climate.LAPSE
        if (t < 0) { if (freezes < 0) freezes = day; thaws = day + 1 }
    }
    return IceSeason(freezes, thaws)
}

/**
 * Packs the Chronicle's decade line in [News.about]: days later the lakes freeze than in the 2020s, days sooner
 * they thaw, and days longer they're open, each 0 to 254, all [NO_FREEZE] where they don't freeze now.
 */
object ClimateLine {
    const val NO_FREEZE = 255
    fun pack(later: Int, sooner: Int, open: Int) = (later and 255) or ((sooner and 255) shl 8) or ((open and 255) shl 16)
    fun later(about: Int) = about and 255
    fun sooner(about: Int) = (about ushr 8) and 255
    fun open(about: Int) = (about ushr 16) and 255
}

/** The decade [ClimateLine] for [region] in [year], against 2025. */
fun climateLine(region: Region, year: Int): Int {
    val then = iceSeason(region, 2025)
    val now = iceSeason(region, year)
    if (then.freezes < 0 || now.freezes < 0) return ClimateLine.pack(ClimateLine.NO_FREEZE, ClimateLine.NO_FREEZE, ClimateLine.NO_FREEZE)
    return ClimateLine.pack((now.freezes - then.freezes).coerceIn(0, 254), (then.thaws - now.thaws).coerceIn(0, 254), (now.openDays - then.openDays).coerceIn(0, 254))
}

/** On the first day of each decade with the trend on, with the weather on: the Chronicle's line on the ice season. */
internal fun World.climateYear(year: Int) {
    if (!settings.weather || year % 10 != 0 || trendSteps(year) == 0) return
    tell(News.CLIMATE_DECADE, climateLine(settings.region, year))
}
