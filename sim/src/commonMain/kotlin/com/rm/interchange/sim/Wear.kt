package com.rm.interchange.sim

/*
 * Track wears and the weather gets at it. Each tile of track has a condition,
 * 0 to 255, new track at the top. Heavy trains wear it a little each time
 * they pass, thawing frost heaves it, soaked ground lets it settle; crews mend
 * it each day, as much as the company's upkeep pays for. Worn track gets a
 * slow order, and in summer heat worn track can kink and need one too.
 *
 * Downpours on soaked low ground wash track out, and in hot dry summers a
 * fire in the forest spreads with the wind; either closes the line till the
 * crews have it open again. Fire burns the trees off and leaves the ground
 * black for a while.
 */

object Upkeep {
    const val LOW = 0
    const val NORMAL = 1
    const val HIGH = 2
    /** What crews mend a tile a day, in hundredths of a point, and what that costs a tile a day, in cents. */
    val MEND = intArrayOf(30, 100, 250)
    val CENTS = intArrayOf(5, 20, 50)
    /** Days of a closure crews work off in a day. */
    val CLEAR = intArrayOf(1, 1, 2)
}

/** Why track is closed. */
object Closure {
    const val WASHOUT = 1
    const val FIRE = 2
}

/** Condition below which track has a slow order, and below which a worse one. */
const val WORN = 140
const val VERY_WORN = 70
/** Speed limits on worn track, very worn track and a heat kink, in km/h. */
const val WORN_KMH = 80
const val VERY_WORN_KMH = 40
const val KINK_KMH = 25
/** Fire: days a tile burns, and the burnt look after, counted down from [BURNT] a day. */
const val FIRE_DAYS = 4
const val BURNT = 200
const val BURNT_FROM = 100

private fun u(b: Byte) = b.toInt() and 255

/** The fastest a train may go on [tile] for its track's condition, in km/h, or -1 for no limit. */
fun World.trackLimit(tile: Int): Int = when {
    kinks.containsKey(tile) -> KINK_KMH
    track[tile].toInt() == 0 -> -1
    u(condition[tile]) < VERY_WORN -> VERY_WORN_KMH
    u(condition[tile]) < WORN -> WORN_KMH
    else -> -1
}

/** Wear from a train of [mass] kg running onto [tile]: a point now and then, more often the heavier it is. */
internal fun World.wearBy(tile: Int, mass: Long) {
    if (track[tile].toInt() == 0) return
    if (rng.below(WEAR_ODDS) < mass / 1000) condition[tile] = (u(condition[tile]) - 1).coerceAtLeast(0).toByte()
}

/** A wearing train of a tonne wears a point one time in this many. */
const val WEAR_ODDS = 5_000

/** Whether [tile] is a burning tile, or a burnt one still black. */
fun World.burning(tile: Int) = u(fire[tile]) in 1..FIRE_DAYS
fun World.burnt(tile: Int) = u(fire[tile]) >= BURNT_FROM

/**
 * The track's day, after the weather's: wear and mending on every tile across the cores, then kinks,
 * washouts and fires found there put on the map in tile order, and each company paying for its crews.
 */
internal fun World.trackDay() {
    val year = Calendar.year(startYear, day)
    val doy = Calendar.dayOfYear(startYear, day)
    val salt = day * 977 + seedBits
    val fronts = weather.fronts.toList()
    val bands = Workers.threads * 4
    val found = Array(bands) { ArrayList<IntArray>() }
    val tiles = Array(bands) { IntArray(MAX_COMPANIES) }
    val was = fire.copyOf()
    val wind = weather.windFrom
    parallelBandsIndexed(height, bands) { b, from, until ->
        for (y in from until until) for (x in 0 until width) {
            val i = index(x, y)
            val t = track[i].toInt()
            // Fire burns down, spreads, and leaves black ground that fades.
            val f = u(was[i])
            if (f in 1..FIRE_DAYS) {
                fire[i] = (if (f == 1) BURNT else f - 1).toByte()
                if (f == 1 && Trees.count(trees[i].toInt()) > 0) found[b] += intArrayOf(EVENT_BURNT, i)
            } else if (f > BURNT_FROM) fire[i] = (f - 1).toByte()
            if (t == 0 && Trees.count(trees[i].toInt()) == 0) continue
            val sky = if (settings.weather) skyAt(settings.region, year, doy, weather.anomaly, fronts, height, x, y, tileHeight(x, y)) else null
            if (sky != null && f == 0 && Trees.count(trees[i].toInt()) > 0 && sky.precipitation == 0 && sky.temperature > FIRE_HEAT && u(weather.wet[i]) < 12) {
                // Lightning now and then, or sparks from a neighbour on fire, more of them downwind; in 65536ths.
                var odds = 1
                for ((nx, ny, from2) in arrayOf(Triple(x - 1, y, 0), Triple(x + 1, y, 4), Triple(x, y - 1, 2), Triple(x, y + 1, 6))) {
                    if (inside(nx, ny) && u(was[index(nx, ny)]) in 1..FIRE_DAYS) odds += if (from2 == wind) 36_000 else 16_000
                }
                if (cellDice(x, y, salt + 11) * 256 + cellDice(x, y, salt + 12) < odds) found[b] += intArrayOf(EVENT_FIRE, i)
            }
            if (t == 0) continue
            val owner = owner[i].toInt()
            if (owner in 0 until MAX_COMPANIES) tiles[b][owner]++
            var c = u(condition[i])
            if (sky != null) {
                val frost = u(weather.frost[i])
                if (frost > 0 && sky.temperature > 0 && cellDice(x, y, salt + 1) < 80) c--
                if (u(weather.wet[i]) > 150 && cellDice(x, y, salt + 2) < 50) c--
                if (sky.temperature > KINK_HEAT && c < 170 && !kinks.containsKey(i) && cellDice(x, y, salt + 3) < (170 - c) / 8) found[b] += intArrayOf(EVENT_KINK, i)
                if (sky.precipitation >= WASHOUT_RAIN && u(weather.wet[i]) > 200 && lowGround(x, y) && cellDice(x, y, salt + 4) < 14) found[b] += intArrayOf(EVENT_WASHOUT, i)
            }
            val level = companies.getOrNull(owner)?.upkeep ?: Upkeep.NORMAL
            val mend = Upkeep.MEND[level]
            c += mend / 100 + if (cellDice(x, y, salt + 5) * 100 / 256 < mend % 100) 1 else 0
            condition[i] = c.coerceIn(0, 255).toByte()
        }
    }
    for (b in 0 until bands) for (e in found[b]) {
        val i = e[1]
        when (e[0]) {
            EVENT_KINK -> kinks[i] = KINK_DAYS
            EVENT_WASHOUT -> if (!closed.containsKey(i)) {
                closed[i] = Closure.WASHOUT shl 16 or (WASHOUT_DAYS_FROM + rng.below(6))
                news += News(News.WASHOUT, i, tick)
            }
            EVENT_FIRE -> if (u(fire[i]) == 0) {
                fire[i] = FIRE_DAYS.toByte()
                touched(i % width, i / width)
            }
            EVENT_BURNT -> {
                trees[i] = 0
                touched(i % width, i / width)
            }
        }
    }
    // Track by or in a fire is closed while it burns.
    for (i in 0 until width * height) if (u(fire[i]) == FIRE_DAYS) {
        val x = i % width
        val y = i / width
        for (dy in -1..1) for (dx in -1..1) {
            if (!inside(x + dx, y + dy)) continue
            val n = index(x + dx, y + dy)
            if (track[n].toInt() != 0 && !closed.containsKey(n)) {
                closed[n] = Closure.FIRE shl 16 or (FIRE_DAYS + 2)
                news += News(News.FIRE, n, tick)
            }
        }
    }
    // The crews work off closures and kinks, and are paid.
    for (k in closed.keys.sorted()) {
        val v = closed.getValue(k)
        val level = companies.getOrNull(owner[k].toInt())?.upkeep ?: Upkeep.NORMAL
        val left = (v and 0xffff) - Upkeep.CLEAR[level]
        if (left <= 0) closed.remove(k) else closed[k] = (v and 0xffff.inv()) or left
    }
    for (k in kinks.keys.sorted()) {
        val left = kinks.getValue(k) - 1
        if (left <= 0) kinks.remove(k) else kinks[k] = left
    }
    for (c in companies) {
        val n = (0 until bands).sumOf { tiles[it].getOrElse(c.id) { 0 } }
        c.upkeepOwed += n.toLong() * Upkeep.CENTS[c.upkeep]
        val dollars = price(c.upkeepOwed / 100)
        c.upkeepOwed %= 100
        if (dollars > 0) spend(c, Books.UPKEEP, dollars)
    }
}

/** Whether ([x], [y]) lies low: water within two tiles, or a hollow lower than the land round it. */
private fun World.lowGround(x: Int, y: Int): Boolean {
    val h = tileHeight(x, y)
    var higher = 0
    for (dy in -2..2) for (dx in -2..2) {
        if (!inside(x + dx, y + dy)) continue
        if (isWater(x + dx, y + dy)) return true
        if (tileHeight(x + dx, y + dy) > h) higher++
    }
    return higher >= 12
}

/** Whether [tile] is closed to trains. */
fun World.closedTile(tile: Int) = closed.containsKey(tile)

/** Splits 0 until [size] into [bands] bands and calls [body] with each band's number, start and end. */
internal fun parallelBandsIndexed(size: Int, bands: Int, body: (band: Int, from: Int, until: Int) -> Unit) {
    Workers.run(bands) { b -> body(b, size * b / bands, size * (b + 1) / bands) }
}

private const val EVENT_KINK = 0
private const val EVENT_WASHOUT = 1
private const val EVENT_FIRE = 2
private const val EVENT_BURNT = 3
/** A day's mean heat in tenths of a degree that kinks worn rail, and that dries the forest for fire; rain in hundredths of a mm a day that washes track out. */
const val KINK_HEAT = 230
const val FIRE_HEAT = 200
const val WASHOUT_RAIN = 2500
const val KINK_DAYS = 5
const val WASHOUT_DAYS_FROM = 6
