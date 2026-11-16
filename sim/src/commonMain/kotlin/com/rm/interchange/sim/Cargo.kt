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
    /** The base pack's cargo, ids 0 until this. */
    const val COUNT = 20
    /** The most cargo a game can have, base and packs: cargo sets are bits of an Int. */
    const val MAX = 32

    val IDS = listOf(
        "passengers", "mail", "timber", "lumber", "grain", "coal", "iron_ore", "steel", "goods", "oil", "fuel", "livestock", "food",
        "fish", "paper", "potash", "nickel_ore", "nickel", "bauxite", "aluminium",
    )

    /** The number of base cargo [id], or -1. A game's own, with its packs' cargo, is in [Roster.cargo]. */
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

    /** Whether it keeps in an industry's store; livestock, food and fish spoil there. */
    fun keeps(c: Int) = c != LIVESTOCK && c != FOOD && c != FISH

    /** Each one's colour, 0xrrggbb, for swatches and bars. */
    val COLOUR = intArrayOf(
        0x6FA8DC, 0xE8E6E1, 0x8B5A2B, 0xD9B26F, 0xE6C84F, 0x3A3A3A, 0xA0522D, 0x8A9BA8, 0xC77DBB, 0x222831,
        0xE07B39, 0xB5835A, 0x7CC36E, 0x5FB3B3, 0xF2EEDC, 0xD96C6C, 0x7D8F69, 0xB8C2C8, 0xC2603A, 0xD5DDE3,
    )

    val NAMES_EN = listOf(
        "Passengers", "Mail", "Timber", "Lumber", "Grain", "Coal", "Iron ore", "Steel", "Goods", "Oil", "Fuel", "Livestock", "Food",
        "Fish", "Paper", "Potash", "Nickel ore", "Nickel", "Bauxite", "Aluminium",
    )
    val NAMES_FR = listOf(
        "Voyageurs", "Courrier", "Bois rond", "Bois d'œuvre", "Grain", "Charbon", "Minerai de fer", "Acier", "Marchandises", "Pétrole",
        "Carburant", "Bétail", "Aliments", "Poisson", "Papier", "Potasse", "Minerai de nickel", "Nickel", "Bauxite", "Aluminium",
    )

    /** The word for amounts of each, English then French: passengers, bags of mail, head of livestock, tonnes of the rest. */
    private fun unit(c: Int) = when (c) {
        PASSENGERS -> mapOf("en" to "passengers", "fr" to "voyageurs")
        MAIL -> mapOf("en" to "bags", "fr" to "sacs")
        LIVESTOCK -> mapOf("en" to "head", "fr" to "têtes")
        else -> mapOf("en" to "t", "fr" to "t")
    }

    /** The base cargo as a game's table starts. */
    val BASE: List<CargoKind> by lazy {
        List(COUNT) { c ->
            CargoKind(
                c, IDS[c], Packs.BASE_ID, mapOf("en" to NAMES_EN[c], "fr" to NAMES_FR[c]), unit(c),
                RATE[c], GRACE[c], DECAY[c], PACE[c], FAST[c], KG[c], townWants(c), COLOUR[c], keeps(c),
            )
        }
    }

    /** What a town takes, given enough people round the station: mail and passengers from any, the rest from a bigger place. */
    fun townWants(c: Int) = c == PASSENGERS || c == MAIL || c == GOODS || c == FOOD || c == FUEL || c == LUMBER

    /** People round a station before it takes a town's goods, food, fuel and lumber. */
    const val TOWN_GOODS_PEOPLE = 60
}

/**
 * A kind of cargo as the packs in a game make it: its place [id] in the
 * game's table, the [key] packs name it by, the pack that gave it, and its
 * numbers as in [Cargo]. [towns] is taken by towns of [Cargo.TOWN_GOODS_PEOPLE]
 * and up, as goods are. [colour] is 0xrrggbb. Without [keeps] it spoils in an
 * industry's store.
 */
class CargoKind(
    val id: Int,
    val key: String,
    val pack: String,
    val names: Map<String, String>,
    val units: Map<String, String>,
    val rate: Int,
    val grace: Int,
    val decay: Int,
    val pace: Int,
    val fast: Int,
    val kg: Int,
    val towns: Boolean,
    val colour: Int,
    val keeps: Boolean = true,
) {
    fun name(language: String) = names[language] ?: names["en"] ?: key
    fun unit(language: String) = units[language] ?: units["en"] ?: ""
}

/**
 * A game's cargo, base first and then its packs', with each number in a
 * plain array by id for the sim to read. The arrays run to [Cargo.MAX], 0
 * past [size].
 */
class CargoTable(val kinds: List<CargoKind>) {
    val size get() = kinds.size
    val keys = kinds.map { it.key }
    private val byKey = kinds.associate { it.key to it.id }
    val rate = IntArray(Cargo.MAX) { kinds.getOrNull(it)?.rate ?: 0 }
    val grace = IntArray(Cargo.MAX) { kinds.getOrNull(it)?.grace ?: 0 }
    val decay = IntArray(Cargo.MAX) { kinds.getOrNull(it)?.decay ?: 0 }
    val pace = IntArray(Cargo.MAX) { kinds.getOrNull(it)?.pace ?: 0 }
    val fast = IntArray(Cargo.MAX) { kinds.getOrNull(it)?.fast ?: 0 }
    val kg = IntArray(Cargo.MAX) { kinds.getOrNull(it)?.kg ?: 0 }
    val towns = BooleanArray(Cargo.MAX) { kinds.getOrNull(it)?.towns ?: false }
    val keeps = BooleanArray(Cargo.MAX) { kinds.getOrNull(it)?.keeps ?: true }

    operator fun get(c: Int) = kinds[c]
    fun getOrNull(c: Int) = kinds.getOrNull(c)

    /** The number of cargo [key], or -1. */
    fun of(key: String) = byKey[key] ?: -1

    companion object {
        val BASE by lazy { CargoTable(Cargo.BASE) }
    }
}
