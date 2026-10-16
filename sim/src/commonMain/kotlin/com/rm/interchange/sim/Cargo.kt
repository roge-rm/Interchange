package com.rm.interchange.sim

/**
 * The kinds of cargo, by number, as packs and saves name them by id. What each
 * pays a tile in hundredths of a dollar, the days it can take before it pays
 * less (days a tile and days more), how much less a day after that, in
 * hundredths, and how much more passengers, mail and goods pay carried fast.
 */
object Cargo {
    const val PASSENGERS = 0
    const val MAIL = 1
    const val TIMBER = 2
    const val LUMBER = 3
    const val GRAIN = 4
    const val COAL = 5
    const val IRON_ORE = 6
    const val STEEL = 7
    const val GOODS = 8
    const val OIL = 9
    const val FUEL = 10
    const val LIVESTOCK = 11
    const val FOOD = 12
    const val FISH = 13
    const val PAPER = 14
    const val POTASH = 15
    const val NICKEL_ORE = 16
    const val NICKEL = 17
    const val BAUXITE = 18
    const val ALUMINIUM = 19
    const val COUNT = 20

    val IDS = listOf(
        "passengers", "mail", "timber", "lumber", "grain", "coal", "iron_ore", "steel", "goods", "oil", "fuel", "livestock", "food",
        "fish", "paper", "potash", "nickel_ore", "nickel", "bauxite", "aluminium",
    )

    /** The number of cargo [id], as packs name it, or -1. */
    fun of(id: String) = IDS.indexOf(id)

    /** Full fare, in cents for one carried a tile. */
    val RATE = intArrayOf(20, 35, 22, 28, 24, 20, 22, 30, 44, 26, 36, 36, 48, 44, 32, 22, 24, 40, 22, 42)
    val GRACE = intArrayOf(5, 4, 30, 25, 20, 40, 40, 30, 12, 25, 15, 4, 3, 2, 20, 40, 40, 30, 40, 30)
    val DECAY = intArrayOf(1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 3, 4, 5, 1, 1, 1, 1, 1, 1)
    /** Days a tile it can take before it pays less: 1 is a tile a day, about 40 km/h; freight that keeps takes 2, as a laker goes. */
    val PACE = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2)
    /** Percent more paid at [FAST_TO] tiles a day on board or better, rising from [FAST_FROM]: what an aircraft earns over a train. */
    val FAST = intArrayOf(100, 50, 0, 0, 0, 0, 0, 0, 50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    const val FAST_FROM = 3
    const val FAST_TO = 12

    /** What one of each weighs, in kg: a passenger with their bags, a bag of mail, a head of livestock, a tonne of the rest. */
    val KG = intArrayOf(90, 50, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 500, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000)

    /** What a town takes, given enough people round the station: mail and passengers from any, the rest from a bigger place. */
    fun townWants(c: Int) = c == PASSENGERS || c == MAIL || c == GOODS || c == FOOD || c == FUEL || c == LUMBER

    /** People round a station before it takes a town's goods, food, fuel and lumber. */
    const val TOWN_GOODS_PEOPLE = 60
}
