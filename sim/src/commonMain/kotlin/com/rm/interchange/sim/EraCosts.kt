package com.rm.interchange.sim

/*
 * With [NewGame.eraCosts], what things cost follows the era as well as inflation. Building is split into labour,
 * steel, concrete and land, each with its own price by year, shaped on Canada in broad strokes: labour cheap early
 * and dear late, steel dear before 1890, concrete cheap from the 1920s. Running costs split into a fixed part and
 * fuel, with fuel prices by year and the oil shocks of 1973, 1979 and 2008. Cold places pay more for foundations and
 * track upkeep, wet ones for road upkeep, and building on frozen ground costs more labour. All per mille, on top of
 * [World.price]; with the setting off nothing here is used.
 */
object EraCosts {
    const val LABOUR = 0
    const val STEEL = 1
    const val CONCRETE = 2
    const val LAND = 3
    const val INDICES = 4
    val INDEX_KEYS = listOf("labour", "steel", "concrete", "land")

    /** Price indices by year, per mille, a straight line between the years here. The mean of labour, steel and concrete stays within 5% of 1000. */
    val YEARS = intArrayOf(1836, 1870, 1890, 1920, 1950, 1980, 2000, 2050)
    val TABLE = arrayOf(
        intArrayOf(550, 600, 700, 900, 1_100, 1_300, 1_400, 1_450),
        intArrayOf(1_500, 1_400, 1_050, 1_000, 950, 900, 850, 850),
        intArrayOf(1_100, 1_100, 1_100, 950, 850, 850, 850, 850),
        intArrayOf(700, 750, 800, 900, 1_000, 1_150, 1_250, 1_300),
    )

    const val COAL = 0
    const val DIESEL = 1
    const val ELECTRIC = 2
    const val AVIATION = 3
    const val BUNKER = 4
    /** Only with [NewGame.energy]. */
    const val HYDROGEN = 5
    const val FUELS = 6
    val FUEL_KEYS = listOf("coal", "diesel", "electric", "aviation", "bunker", "hydrogen")

    /** Fuel prices by year, per mille, between the years here; the shock years jump from the year before. */
    val FUEL_YEARS = intArrayOf(1836, 1870, 1900, 1915, 1920, 1930, 1950, 1960, 1972, 1973, 1974, 1978, 1979, 1980, 1985, 1986, 1999, 2000, 2007, 2008, 2009, 2014, 2016, 2050, 2070)
    val FUEL_TABLE = arrayOf(
        intArrayOf(1_100, 1_000, 900, 1_000, 1_300, 1_000, 1_000, 1_050, 1_050, 1_100, 1_200, 1_200, 1_250, 1_300, 1_200, 1_100, 1_000, 1_000, 1_100, 1_300, 1_100, 1_100, 1_000, 1_100, 1_100),
        intArrayOf(1_000, 1_000, 1_000, 1_000, 1_000, 800, 700, 650, 650, 1_300, 1_450, 1_350, 1_700, 1_950, 1_500, 1_000, 900, 1_050, 1_450, 1_800, 1_300, 1_400, 1_000, 1_300, 1_300),
        intArrayOf(1_400, 1_400, 1_300, 1_200, 1_150, 1_050, 800, 600, 550, 560, 580, 600, 620, 640, 600, 580, 550, 550, 580, 600, 600, 620, 620, 650, 650),
        intArrayOf(1_050, 1_050, 1_050, 1_050, 1_050, 850, 750, 700, 700, 1_350, 1_500, 1_400, 1_750, 2_000, 1_550, 1_050, 950, 1_100, 1_500, 1_850, 1_350, 1_450, 1_050, 1_350, 1_350),
        intArrayOf(1_100, 1_000, 900, 1_000, 1_300, 1_000, 850, 750, 700, 1_250, 1_400, 1_300, 1_650, 1_900, 1_450, 1_000, 900, 1_050, 1_450, 1_750, 1_250, 1_350, 1_000, 1_250, 1_250),
        // Hydrogen: about three times diesel for the same work in 2025, as dear by 2050, 0.8 times by 2070.
        intArrayOf(4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 3_950, 1_300, 1_040),
    )
    /** The years a fuel shock makes the news, as [News.FUEL_SHOCK] with its place here. */
    val SHOCKS = intArrayOf(1973, 1979, 2008)

    /** The share of each fuel's model's running cost that's fuel, per mille; the rest is fixed. */
    val FUEL_SHARE = intArrayOf(500, 400, 500, 500, 450, 450)

    /** Labour on frozen ground, at least [WINTER_FROST] cm of frost, costs this much more, per mille of its share. */
    const val WINTER_EXTRA = 500
    const val WINTER_FROST = 10
    /** Foundations: a tenth of a degree of January cold below 0 adds this per mille, up to [FROST_MOST]; on permafrost they cost [PERMAFROST]. */
    const val FROST_PER_TENTH = 2
    const val FROST_MOST = 1_600
    const val PERMAFROST = 2_000
    /** A tile whose year-round mean, in tenths, is below this has permafrost. */
    const val PERMAFROST_MEAN = -20
    /** Snow sheds and fences where wood is local, and where it isn't, per mille. */
    const val WOOD_LOCAL = 800
    const val WOOD_FAR = 1_200

    private fun between(years: IntArray, values: IntArray, year: Int): Int {
        if (year <= years[0]) return values[0]
        for (k in 1 until years.size) {
            if (year > years[k]) continue
            val y0 = years[k - 1]
            return values[k - 1] + (values[k] - values[k - 1]) * (year - y0) / (years[k] - y0)
        }
        return values.last()
    }

    /** Index [kind] in [year], per mille; land as in the country near no town. */
    fun index(kind: Int, year: Int): Int = between(YEARS, TABLE[kind], year)

    /** Fuel [fuel]'s price in [year], per mille. */
    fun fuel(fuel: Int, year: Int): Int = between(FUEL_YEARS, FUEL_TABLE[fuel], year)

    /**
     * The fuel model [e] burns, or -1 for none (wagons, horses): steam engines and their tenders coal, motor road
     * vehicles diesel. With [energy] battery ones take the electric price and hydrogen ones hydrogen; without it both
     * burn what a diesel of their kind would.
     */
    fun fuelOf(e: Engine, energy: Boolean = false): Int {
        if (energy && e.traction == Traction.BATTERY) return ELECTRIC
        if (energy && e.traction == Traction.HYDROGEN) return HYDROGEN
        return when (e.kind) {
            VehicleKind.AIRCRAFT -> AVIATION
            VehicleKind.SHIP -> BUNKER
            VehicleKind.TENDER -> COAL
            VehicleKind.ENGINE -> when (e.traction) { Traction.STEAM -> COAL; Traction.ELECTRIC, Traction.DUAL, Traction.MAGLEV -> ELECTRIC; Traction.HORSE -> -1; else -> DIESEL }
            VehicleKind.ROAD -> when (e.traction) { Traction.HORSE -> -1; Traction.ELECTRIC -> ELECTRIC; else -> DIESEL }
            else -> -1
        }
    }

    /** Model [e]'s yearly running cost before inflation in [year], split into the fixed part and fuel, from [fuels] as [fuel] gives them. */
    fun running(e: Engine, fuels: IntArray, energy: Boolean = false): LongArray {
        val f = fuelOf(e, energy)
        if (f < 0) return longArrayOf(e.running, 0)
        val share = e.running * FUEL_SHARE[f] / 1_000
        return longArrayOf(e.running - share, share * fuels[f] / 1_000)
    }

    /** What a build of [mix] costs per mille in [year], with land at [land] per mille of the country price. */
    fun factor(mix: IntArray, year: Int, land: Int = 1_000): Int {
        var f = 0L
        for (k in 0 until INDICES) if (mix[k] != 0) f += mix[k].toLong() * index(k, year) * (if (k == LAND) land else 1_000) / 1_000
        return (f / 1_000).toInt()
    }

    /** Track upkeep in [region] in [year], per mille: dearer the colder January is, and with heat kinks in warm summers as the climate warms. */
    fun trackUpkeep(region: Region, year: Int): Int {
        val january = Climate.normal(region, year, 15)
        var f = (1_000 + (-january - 100) * 2).coerceIn(850, 1_400)
        if (Climate.july(region) >= 180) f += maxOf(0, Climate.drift(year)) * 4
        return f
    }

    /** Road upkeep in [region] in [year], per mille, dearer the wetter it is. */
    fun roadUpkeep(region: Region, year: Int): Int = (1_000 + (Climate.yearPrecipitation(region, year) - 800) / 3).coerceIn(800, 1_300)

    /** Whether wood is at hand in [region] for snow sheds and fences. */
    fun woodLocal(region: Region) = region != Region.PRAIRIES && region != Region.NORTH
}

/** What each kind of build is made of, per mille of labour, steel, concrete and land, in [EraCosts] order. */
object Mix {
    val TRACK = intArrayOf(400, 500, 0, 100)
    /** Maglev guideway: a concrete beam with its steel stator. */
    val GUIDEWAY = intArrayOf(300, 300, 300, 100)
    val LABOUR = intArrayOf(1_000, 0, 0, 0)
    val FOUNDATION = intArrayOf(500, 0, 500, 0)
    val PLATFORM = intArrayOf(200, 200, 500, 100)
    val DEPOT = intArrayOf(300, 200, 300, 200)
    val STEELWORK = intArrayOf(200, 800, 0, 0)
    val WIRE = intArrayOf(300, 700, 0, 0)
    val ROAD_STOP = intArrayOf(200, 100, 500, 200)
    val DEMOLISH = intArrayOf(500, 0, 0, 500)
    /** By [Spans] style: trestle, truss, concrete, tunnel. */
    val SPANS = arrayOf(intArrayOf(850, 150, 0, 0), intArrayOf(300, 700, 0, 0), intArrayOf(300, 100, 600, 0), intArrayOf(400, 200, 400, 0))
    /** By [Surface]: dirt, gravel, paved, highway. */
    val SURFACES = arrayOf(intArrayOf(900, 0, 0, 100), intArrayOf(700, 0, 200, 100), intArrayOf(300, 0, 600, 100), intArrayOf(300, 100, 500, 100))
    val AIRPORT = intArrayOf(200, 200, 400, 200)
    val CANAL = intArrayOf(800, 0, 100, 100)
    val LOCK = intArrayOf(300, 200, 500, 0)
    val DOCK = intArrayOf(300, 100, 400, 200)
    val SHIPYARD = intArrayOf(300, 400, 200, 100)
    val BUOY = intArrayOf(0, 1_000, 0, 0)
    val SHED = intArrayOf(600, 200, 200, 0)
    val FENCE = intArrayOf(900, 100, 0, 0)
    val INDUSTRY = intArrayOf(300, 300, 300, 100)
    val EQUIPMENT = intArrayOf(200, 600, 200, 0)
}

/** Bits of [World.costMark]: a price raised by the cold of the place, or by building on frozen ground now. */
object CostMark {
    const val FROST = 1
    const val WINTER = 2
}

/** The calendar year now. */
private val World.yearNow get() = Calendar.year(startYear, day)

/** Land near tile [i] per mille of the country's: dearer near bigger towns, from 600 in the country to 2000. */
internal fun World.landAt(i: Int): Int {
    if (i < 0) return 1_000
    val x = i % width
    val y = i / width
    var most = 0
    // By index, as the screen prices tools with this too.
    for (k in 0 until towns.size) {
        val t = towns.getOrNull(k) ?: continue
        val d = maxOf(kotlin.math.abs(t.x - x), kotlin.math.abs(t.y - y))
        most = maxOf(most, t.population * 4 / (d + 4))
    }
    return 600 + minOf(1_400, most)
}

/** The mean temperature on tile [i] in January this year, and over the whole year, in tenths. */
private fun World.tileCold(i: Int): Pair<Int, Int> {
    val x = i % width
    val y = i / width
    val shift = (y - height / 2) * Climate.NORTH_SOUTH / height.coerceAtLeast(1) - (tileHeight(x, y) - Climate.BASE_HEIGHT) * Climate.LAPSE
    val year = yearNow
    return (Climate.normal(settings.region, year, 15) + shift) to (Climate.mean(settings.region) + Climate.drift(year) + shift)
}

/** Foundations on tile [i] per mille for its cold: frost depth by January's cold, double on permafrost. */
internal fun World.frostAt(i: Int): Int {
    if (i < 0) return 1_000
    val (january, mean) = tileCold(i)
    if (mean < EraCosts.PERMAFROST_MEAN) return EraCosts.PERMAFROST
    return (1_000 + maxOf(0, -january) * EraCosts.FROST_PER_TENTH).coerceAtMost(EraCosts.FROST_MOST)
}

/** Whether the ground on tile [i] is frozen hard enough to make building dearer. */
internal fun World.frozenAt(i: Int): Boolean = i >= 0 && (weather.frost[i].toInt() and 255) >= EraCosts.WINTER_FROST

/**
 * [base] dollars of a build of [mix] on tile [i] (-1 for none) now: [price] with era costs off, otherwise priced by
 * the year's indices, the land near [i], frozen ground on [i], and with [foundation] the place's frost.
 */
fun World.build(base: Long, mix: IntArray, i: Int = -1, foundation: Boolean = false): Long {
    val p = price(base)
    if (!settings.eraCosts) return p
    var cost = p * EraCosts.factor(mix, yearNow, if (mix[EraCosts.LAND] != 0) landAt(i) else 1_000) / 1_000
    if (foundation) cost = cost * frostAt(i) / 1_000
    if (mix[EraCosts.LABOUR] != 0 && frozenAt(i)) cost = cost * (1_000 + mix[EraCosts.LABOUR].toLong() * EraCosts.WINTER_EXTRA / 1_000) / 1_000
    return cost
}

/** A snow shed or fence of [mix] now, cheaper where wood is local. */
fun World.woodBuild(base: Long, mix: IntArray, i: Int): Long {
    val cost = build(base, mix, i)
    if (!settings.eraCosts) return cost
    return cost * (if (EraCosts.woodLocal(settings.region)) EraCosts.WOOD_LOCAL else EraCosts.WOOD_FAR) / 1_000
}

/** [CostMark] bits for building on tile [i] now, 0 with era costs off: [CostMark.FROST] where foundations cost more for the cold, [CostMark.WINTER] on frozen ground. */
fun World.costMark(i: Int): Int {
    if (!settings.eraCosts || i !in 0 until width * height) return 0
    return (if (frostAt(i) > 1_000) CostMark.FROST else 0) or (if (frozenAt(i)) CostMark.WINTER else 0)
}

/** Model [e]'s running cost a year now, after inflation: as it always was with era costs off, otherwise its fixed part and its fuel at this year's price. */
fun World.running(e: Engine): Long {
    if (!settings.eraCosts) return price(e.running)
    val year = yearNow
    val f = EraCosts.fuelOf(e, settings.energy)
    if (f < 0) return price(e.running)
    val p = price(e.running)
    val share = EraCosts.FUEL_SHARE[f].toLong()
    return p * (1_000 * 1_000 - share * 1_000 + share * EraCosts.fuel(f, year)) / 1_000_000
}

/** Track and road upkeep per mille for the climate this year, 1000 each with era costs off. */
internal fun World.trackUpkeepFactor(): Int = if (settings.eraCosts) EraCosts.trackUpkeep(settings.region, yearNow) else 1_000
internal fun World.roadUpkeepFactor(): Int = if (settings.eraCosts) EraCosts.roadUpkeep(settings.region, yearNow) else 1_000

/** The fuel shock of this year, its place in [EraCosts.SHOCKS], told on the first of the year with era costs on, and when a new game starts in one. News only. */
internal fun World.fuelShockNews() {
    if (!settings.eraCosts) return
    val k = EraCosts.SHOCKS.indexOf(yearNow)
    if (k >= 0) tell(News.FUEL_SHOCK, k)
}

/**
 * Prices for the screen: this year's [indices] and [fuels] per mille, the five before hydrogen, with hydrogen's
 * price and history in [hydrogen] and [hydrogenHistory] with energy on (null off), their history a point a year from the start
 * ([years] in order, fewer on long games but always each shock year and the year before it; [indexHistory] by index, [fuelHistory] by fuel), the climate's track and road upkeep per mille, and
 * [scale] for working out running costs with [running].
 */
class PricesView(
    val year: Int,
    val indices: IntArray,
    val fuels: IntArray,
    val years: IntArray,
    val indexHistory: Array<IntArray>,
    val fuelHistory: Array<IntArray>,
    val trackUpkeep: Int,
    val roadUpkeep: Int,
    val scale: Long,
    val hydrogen: Int = 0,
    val hydrogenHistory: IntArray? = null,
) {
    private val all = fuels + hydrogen

    /** Model [e]'s running cost a year now after inflation, the fixed part and the fuel. */
    fun running(e: Engine): LongArray = EraCosts.running(e, all, hydrogenHistory != null).let { (a, b) -> longArrayOf(a * scale shr 16, b * scale shr 16) }
}

/** The most points a year [PricesView] keeps; longer games skip years evenly. */
const val PRICE_POINTS = 120

/** [PricesView] for now, null with era costs off; worked out again once a day. */
internal fun World.pricesView(): PricesView? {
    if (!settings.eraCosts) return null
    pricesViewCache?.let { if (it.first == day) return it.second }
    val year = yearNow
    val first = Calendar.year(startYear, 0)
    val span = year - first
    val step = span / PRICE_POINTS + 1
    // A point every step years, this year, and each shock year so far with the year before it, so the jumps show.
    val shocks = EraCosts.SHOCKS.flatMap { listOf(it - 1, it) }.filter { it in first..year }
    val years = (IntArray(span / step + 1) { first + it * step }.toList() + year + shocks).distinct().sorted().toIntArray()
    val view = PricesView(
        year, IntArray(EraCosts.INDICES) { EraCosts.index(it, year) }, IntArray(EraCosts.HYDROGEN) { EraCosts.fuel(it, year) }, years,
        Array(EraCosts.INDICES) { k -> IntArray(years.size) { EraCosts.index(k, years[it]) } },
        Array(EraCosts.HYDROGEN) { f -> IntArray(years.size) { EraCosts.fuel(f, years[it]) } },
        trackUpkeepFactor(), roadUpkeepFactor(), costScale,
        EraCosts.fuel(EraCosts.HYDROGEN, year),
        if (settings.energy) IntArray(years.size) { EraCosts.fuel(EraCosts.HYDROGEN, years[it]) } else null,
    )
    pricesViewCache = day to view
    return view
}
