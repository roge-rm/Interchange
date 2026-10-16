package com.rm.interchange.sim

/*
 * Track wears and the weather gets at it. Each tile of track has a condition,
 * 0 to 255, new track at the top. Heavy trains wear it a little each time
 * they pass, thawing frost heaves it, soaked ground lets it settle; crews mend
 * a share of what's worn each work day, more the more the company's upkeep
 * pays, so a busy line settles lower than a quiet one. Worn track gets a slow
 * order, and in summer heat worn track can kink and need one too.
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
    /** What crews mend a tile of road a work day, in hundredths of a point. */
    val MEND = intArrayOf(30, 100, 250)
    /** The share of a tile of track's wear crews mend a work day, in 1024ths, and what that costs a tile a work day. Costs here are in tenths of a cent. */
    val TRACK_MEND = intArrayOf(70, 111, 256)
    val TRACK = intArrayOf(20, 50, 120)
    /** Days of a closure crews work off in a day. */
    val CLEAR = intArrayOf(1, 1, 2)

    /** A tile of company road a work day by [Surface], dirt to highway. */
    val ROAD = intArrayOf(5, 7, 15, 25)
    /** A road stop, a dock, and a tile of canal or a lock, a work day. */
    const val STOP = 50
    const val DOCK = 200
    const val CANAL = 30
    /** An airport a work day by [Airports] kind, about 5% of its price a work year. */
    val AIRPORT = intArrayOf(1_100, 700, 800, 4_100, 11_000, 27_000)
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

/** Wear from a train of [mass] kg running onto [tile]: a point for each [WEAR_TONNES] it weighs, the rest by chance. */
internal fun World.wearBy(tile: Int, mass: Long) {
    if (track[tile].toInt() == 0) return
    val tonnes = (mass / 1000).toInt()
    val points = tonnes / WEAR_TONNES + if (rng.below(WEAR_TONNES) < tonnes % WEAR_TONNES) 1 else 0
    if (points > 0) condition[tile] = (u(condition[tile]) - points).coerceAtLeast(0).toByte()
}

/** Tonnes of train that wear a tile of track a point as it passes. */
const val WEAR_TONNES = 1_000

/** Whether [tile] is a burning tile, or a burnt one still black. */
fun World.burning(tile: Int) = u(fire[tile]) in 1..FIRE_DAYS
fun World.burnt(tile: Int) = u(fire[tile]) >= BURNT_FROM

/**
 * Each work day, on every tile of track across the cores: thawing frost heaves it and soaked ground lets it
 * settle a few points now and then, and crews mend a share of its wear by the owner's [Upkeep] level. Done by work
 * days so trains, the weather and crews keep the same pace at any day length.
 */
internal fun World.trackWorkDay() {
    val year = Calendar.year(startYear, day)
    val doy = Calendar.dayOfYear(startYear, day)
    val salt = (tick / Calendar.TICKS_PER_DAY).toInt() * 977 + seedBits
    val fronts = weather.fronts.toList()
    parallelBands(height) { from, until ->
        for (y in from until until) for (x in 0 until width) {
            val i = index(x, y)
            if (track[i].toInt() == 0) continue
            var c = u(condition[i])
            if (settings.weather) {
                val frost = u(weather.frost[i])
                val soaked = u(weather.wet[i]) > SOAKED
                if (frost > 0 || soaked) {
                    val heave = frost > 0 && cellDice(x, y, salt + 1) < HEAVE_ODDS
                    val settle = soaked && cellDice(x, y, salt + 2) < SETTLE_ODDS
                    if (heave || settle) {
                        val sky = skyAt(settings.region, year, doy, weather.anomaly, fronts, height, x, y, tileHeight(x, y))
                        if (sky.temperature > 0) {
                            if (heave) c -= HEAVE
                            if (settle) c -= SETTLE
                        }
                    }
                }
            }
            if (c < 255) {
                val level = companies.getOrNull(owner[i].toInt())?.upkeep ?: Upkeep.NORMAL
                val mend = (255 - c) * Upkeep.TRACK_MEND[level]
                c += mend / 1024 + if (cellDice(x, y, salt + 5) * 4 < mend % 1024) 1 else 0
            }
            condition[i] = c.coerceIn(0, 255).toByte()
        }
    }
}

/**
 * The track's day, after the weather's: kinks, washouts and fires found on every tile across the cores, put on
 * the map in tile order. Each company's track and roads are counted on the way for [upkeepDay].
 */
internal fun World.trackDay() {
    val year = Calendar.year(startYear, day)
    val doy = Calendar.dayOfYear(startYear, day)
    val salt = day * 977 + seedBits
    val fronts = weather.fronts.toList()
    val bands = Workers.threads * 4
    val found = Array(bands) { ArrayList<IntArray>() }
    val was = fire.copyOf()
    val wind = weather.windFrom
    val tracks = Array(bands) { IntArray(MAX_COMPANIES) }
    val roads = Array(bands) { LongArray(MAX_COMPANIES) }
    parallelBandsIndexed(height, bands) { b, from, until ->
        for (y in from until until) for (x in 0 until width) {
            val i = index(x, y)
            val t = track[i].toInt()
            if (t != 0) {
                val o = owner[i].toInt()
                if (o in 0 until MAX_COMPANIES) tracks[b][o]++
            }
            if (road[i].toInt() != 0) {
                val o = roadOwner[i].toInt()
                if (o in 0 until MAX_COMPANIES && roadEdges(i) != 0) roads[b][o] += Upkeep.ROAD[surfaceAt(i)].toLong()
            }
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
            if (sky != null) {
                val c = u(condition[i])
                if (sky.temperature > KINK_HEAT && c < KINK_BELOW && !kinks.containsKey(i) && cellDice(x, y, salt + 3) * 256 + cellDice(x, y, salt + 6) < (KINK_BELOW - c) * KINK_ODDS) found[b] += intArrayOf(EVENT_KINK, i)
                if (sky.precipitation >= WASHOUT_RAIN && u(weather.wet[i]) > 200 && lowGround(x, y) && cellDice(x, y, salt + 4) < WASHOUT_ODDS) found[b] += intArrayOf(EVENT_WASHOUT, i)
            }
        }
    }
    setUpkeepCounts(tracks, roads)
    for (b in 0 until bands) for (e in found[b]) {
        val i = e[1]
        when (e[0]) {
            EVENT_KINK -> kinks[i] = KINK_DAYS
            EVENT_WASHOUT -> if (!closed.containsKey(i)) {
                closed[i] = Closure.WASHOUT shl 16 or (WASHOUT_DAYS_FROM + rng.below(6))
                tell(News.WASHOUT, i)
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
                tell(News.FIRE, n)
            }
        }
    }
    // The crews work off closures and kinks.
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
}

/** Stores each company's track tiles and road upkeep from per-band counts by owner. */
private fun World.setUpkeepCounts(tracks: Array<IntArray>, roads: Array<LongArray>) {
    for (c in companies) {
        if (c.id !in 0 until MAX_COMPANIES) continue
        c.trackTiles = tracks.sumOf { it[c.id] }
        c.roadUpkeep = roads.sumOf { it[c.id] }
    }
    upkeepCounted = true
}

/** Counts each company's track and roads from the whole map, for a world that hasn't had a calendar day yet. */
private fun World.countUpkeep() {
    val bands = Workers.threads * 4
    val tracks = Array(bands) { IntArray(MAX_COMPANIES) }
    val roads = Array(bands) { LongArray(MAX_COMPANIES) }
    parallelBandsIndexed(height, bands) { b, from, until ->
        for (i in from * width until until * width) {
            if (track[i].toInt() != 0) {
                val o = owner[i].toInt()
                if (o in 0 until MAX_COMPANIES) tracks[b][o]++
            }
            if (road[i].toInt() != 0 && roadEdges(i) != 0) {
                val o = roadOwner[i].toInt()
                if (o in 0 until MAX_COMPANIES) roads[b][o] += Upkeep.ROAD[surfaceAt(i)].toLong()
            }
        }
    }
    setUpkeepCounts(tracks, roads)
}

/**
 * Each work day, each company pays for its track at its [Upkeep] level, its roads by surface, its road stops,
 * docks, canals, locks and airports. Track and roads are as [trackDay] last counted them, saved with the world
 * so a loaded one pays the same; the rest is counted each time.
 */
internal fun World.upkeepDay() {
    if (companies.isEmpty()) return
    if (!upkeepCounted) countUpkeep()
    val owed = LongArray(MAX_COMPANIES)
    for (s in stations) {
        if (s == null || s.waypoint || s.owner !in 0 until MAX_COMPANIES) continue
        owed[s.owner] += s.stops.size.toLong() * Upkeep.STOP + s.docks.size.toLong() * Upkeep.DOCK
    }
    for (i in canals) { val o = owner[i].toInt(); if (o in 0 until MAX_COMPANIES) owed[o] += Upkeep.CANAL.toLong() }
    for (i in locks.keys) { val o = owner[i].toInt(); if (o in 0 until MAX_COMPANIES) owed[o] += Upkeep.CANAL.toLong() }
    for (a in airports) if (a != null && a.owner in 0 until MAX_COMPANIES) owed[a.owner] += Upkeep.AIRPORT[a.kind].toLong()
    for (c in companies) {
        if (c.id !in 0 until MAX_COMPANIES) continue
        c.upkeepOwed += c.trackTiles.toLong() * Upkeep.TRACK[c.upkeep] + c.roadUpkeep + owed[c.id]
        val dollars = price(c.upkeepOwed / 1_000)
        c.upkeepOwed %= 1_000
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
/** Chance out of 256 a day that a soaked low tile of track washes out in a downpour. */
const val WASHOUT_ODDS = 1
const val KINK_DAYS = 5
/** Worn track below this condition can kink on a hot day, [KINK_ODDS] times in 65536 for each point below. */
const val KINK_BELOW = 170
const val KINK_ODDS = 10
/** Ground wetter than this, in mm, lets track settle. Chances out of 256 a work day that thawing frost heaves a tile of track, and that soaked ground settles it, and by how many points. */
const val SOAKED = 120
const val HEAVE_ODDS = 128
const val HEAVE = 8
const val SETTLE_ODDS = 128
const val SETTLE = 6
const val WASHOUT_DAYS_FROM = 6
