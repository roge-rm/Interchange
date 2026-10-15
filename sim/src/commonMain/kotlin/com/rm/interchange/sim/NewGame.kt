package com.rm.interchange.sim

/** The parts of Canada a map can be made after, each with its own land and climate. */
enum class Region {
    /** A bit of everything, cold to the north and warmer to the south. */
    MIXED,
    /** Flat, dry grassland with aspen bluffs and the odd coulee. */
    PRAIRIES,
    /** Granite, lakes everywhere and boreal forest. */
    SHIELD,
    /** High mountains, snow on the peaks, forest in the valleys. */
    ROCKIES,
    /** A long coast, mixed forest and meadow. */
    MARITIMES,
    /** Tundra, rock and snow, a few stunted trees. */
    NORTH,
}

/**
 * How a new world is made. Map sides are powers of two from 64 to 4096, as in
 * OpenTTD. [sea] is how much of the map is under water, in percent; [hills]
 * from 0 (flat) to 3 (mountainous); [trees], [towns] and [industries] from 0 (none) to 3 (many).
 */
data class NewGame(
    val width: Int = 256,
    val height: Int = 256,
    val seed: Long = 1,
    val startYear: Int = 1850,
    val region: Region = Region.MIXED,
    val sea: Int = 28,
    val hills: Int = 1,
    val trees: Int = 2,
    val towns: Int = 2,
    val industries: Int = 2,
    /** Costs and fares rising year by year. */
    val inflation: Boolean = true,
    /** Weather crossing the map, with snow, ice and mud; without it every day is fair. */
    val weather: Boolean = true,
    /**
     * How many work days of [Calendar.TICKS_PER_DAY] ticks each day on the calendar lasts. Vehicles, cargo, running
     * costs and breakdowns go by work days, so the calendar, seasons and weather pass more slowly against them.
     */
    val dayLength: Int = 1,
    /** How many companies the computer runs against the player. */
    val rivals: Int = 0,
    /** Trains may run on other companies' track, paying the owner [SHARE_FEE] a tile; without it each keeps to its own. */
    val sharing: Boolean = true,
    /** How hard the computer's companies play, a [RivalLevel]. */
    val rivalLevel: Int = RivalLevel.NORMAL,
    /** Whether towns grow by themselves or only when supplied, by [townNeeds]. */
    val townGrowth: TownGrowth = TownGrowth.NORMAL,
    /** The goals the game is made with, by [makeGoals]. */
    val goals: GoalSet = GoalSet.NONE,
) {
    companion object {
        val DAY_LENGTHS = intArrayOf(1, 2, 4, 6, 10, 16)
        const val MOST_RIVALS = 7
        val SIZES = intArrayOf(64, 128, 256, 512, 1024, 2048, 4096)
        const val FIRST_YEAR = 1836
        const val LAST_YEAR = 2050
    }
}

/** How towns grow: by themselves, faster when served, or only in months they were supplied with what they need. */
enum class TownGrowth { NORMAL, SUPPLIED }

/** Which goals a new game is given: none, towns to grow, deliveries to make, towns to connect, or some of each. */
enum class GoalSet { NONE, TOWNS, DELIVERIES, CONNECTIONS, MIXED }

/** How hard rivals play: easy ones leave the player alone, hard ones use everything they can. */
object RivalLevel {
    const val EASY = 0
    const val NORMAL = 1
    const val HARD = 2
}
