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
 * from 0 (flat) to 3 (mountainous); [trees] and [towns] from 0 (none) to 3 (many).
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
) {
    companion object {
        val SIZES = intArrayOf(64, 128, 256, 512, 1024, 2048, 4096)
        const val FIRST_YEAR = 1836
        const val LAST_YEAR = 2050
    }
}
