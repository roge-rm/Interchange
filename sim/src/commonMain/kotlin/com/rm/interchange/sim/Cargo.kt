package com.rm.interchange.sim

/**
 * The kinds of cargo, by number, as packs and saves name them by id. What each
 * pays a tile in hundredths of a dollar, the days it can take before it pays
 * less, and how much less a day after that, in hundredths.
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

    val RATE = intArrayOf(20, 35, 11, 14, 12, 10, 11, 15, 22, 13, 18, 18, 24, 22, 16, 11, 12, 20, 11, 21)
    val GRACE = intArrayOf(5, 4, 30, 25, 20, 40, 40, 30, 12, 25, 15, 4, 3, 2, 20, 40, 40, 30, 40, 30)
    val DECAY = intArrayOf(1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 3, 4, 5, 1, 1, 1, 1, 1, 1)

    /** What a town takes, given enough people round the station: mail and passengers from any, the rest from a bigger place. */
    fun townWants(c: Int) = c == PASSENGERS || c == MAIL || c == GOODS || c == FOOD || c == FUEL || c == LUMBER

    /** People round a station before it takes a town's goods, food, fuel and lumber. */
    const val TOWN_GOODS_PEOPLE = 60
}
