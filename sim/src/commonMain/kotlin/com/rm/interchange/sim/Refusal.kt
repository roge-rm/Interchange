package com.rm.interchange.sim

/**
 * Why a bridge or an airport can't be built where a player asked, for telling
 * them on their own screen. The checks only read the world.
 */
object Refusal {
    const val NONE = 0
    /** That kind or style isn't built yet this year. */
    const val NOT_YET = 1
    const val MONEY = 2
    /** A town nearby won't have the company. */
    const val TOWN = 3
    /** Something already on the land. */
    const val IN_THE_WAY = 4
    /** Off the edge of the map. */
    const val NO_ROOM = 5
    const val NOT_FLAT = 6
    /** A seaplane base with no open water in front. */
    const val NO_WATER = 7
    /** A bridge that isn't in a straight line. */
    const val NOT_STRAIGHT = 8
    /** A bridge too short, or over nothing lower than its deck. */
    const val NOTHING_TO_SPAN = 9
    const val TOO_LONG = 10
    /** A bridge whose ends aren't at the same height. */
    const val NOT_LEVEL = 11
    /** A bridge with land higher than its deck under it. */
    const val HILL = 12
    /** A bridge end on water, a building, or someone else's track. */
    const val ENDS = 13
}
