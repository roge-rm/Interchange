package com.rm.interchange.sim

/*
 * Forecasts worked out from what's known today: the fronts on the map moved on
 * day by day as the game will move them, the air's warm or cold spell fading as
 * it does, and the season's normals. New fronts aren't known till they're made,
 * so the further out the less sure it is. Nothing here draws from the world's
 * RNG or changes the game, so asking for one never puts players out of step.
 */

/** How sure a day of a forecast is. */
object Confidence {
    const val LOW = 0
    const val FAIR = 1
    const val HIGH = 2
}

/** One day of a forecast for a tile. */
class ForecastDay(
    /** Days from today, 1 for tomorrow, and the calendar day it is. */
    val ahead: Int,
    val day: Int,
    /** The day's high and low, in tenths of a degree. */
    val high: Int,
    val low: Int,
    /** Precipitation from the fronts known today, in hundredths of a mm. */
    val precipitation: Int,
    /** Snow expected to fall that day, and lying at its end, in cm. */
    val snowfall: Int,
    val snow: Int,
    /** Rain, in mm. */
    val rain: Int,
    val freezing: Boolean,
    /** Ice at the day's end: a glaze in mm on land, its thickness in cm on water. */
    val ice: Int,
    /** A [Confidence] level. */
    val confidence: Int,
)

/** The air and the known fronts for each day ahead, index 0 for tomorrow, with the wind expected at the middle of the map. */
internal class Outlook(val days: Int) {
    val year = IntArray(days)
    val doy = IntArray(days)
    val anomaly = IntArray(days)
    val fronts = arrayOfNulls<List<Front>>(days)
    val wind = IntArray(days)
}

/** Whether front [f] has had its day or left the map, as [weatherDay] drops them. */
internal fun World.frontGone(f: Front): Boolean {
    val r = f.radius * 256
    return f.life <= 0 || f.x > width * 256 + r || f.x < -2 * r || f.y > height * 256 + r || f.y < -r
}

/** A region's mean wind, in km/h. */
internal fun windMean(region: Region) = when (region) { Region.PRAIRIES -> 24; Region.MARITIMES -> 21; Region.NORTH -> 19; else -> 15 }

/** The next [days] days as known today, on copies of the fronts. */
internal fun World.outlook(days: Int): Outlook {
    val o = Outlook(days)
    var fronts = weather.fronts.map { it.copy() }
    var a = weather.anomaly
    val mx = width / 2
    val my = height / 2
    for (d in 0 until days) {
        val on = day + d + 1
        o.year[d] = Calendar.year(startYear, on)
        o.doy[d] = Calendar.dayOfYear(startYear, on)
        // The daily draw averages nothing, so a spell just fades; snaps not yet drawn can't be known.
        a = a * 7 / 8
        o.anomaly[d] = a
        val next = ArrayList<Front>(fronts.size)
        for (f in fronts) {
            val m = f.copy()
            m.x += m.dx
            m.y += m.dy
            m.life--
            if (!frontGone(m)) next += m
        }
        fronts = next
        o.fronts[d] = fronts
        var wind = windMean(settings.region)
        for (f in fronts) if (f.kind != Front.FOG) wind += f.reach(mx, my) * (if (f.kind == Front.STORM) 50 else 15) / 256
        o.wind[d] = wind
    }
    return o
}

/**
 * The next [days] days' weather on tile ([x], [y]) from what's known today, tomorrow first. Empty off the map or
 * with the weather off. Never draws from the world's RNG nor changes anything.
 */
fun World.forecast(x: Int, y: Int, days: Int = 5): List<ForecastDay> {
    if (!settings.weather || !inside(x, y) || days <= 0) return emptyList()
    return forecastTile(outlook(days), x, y)
}

/** Each day's weather on tile ([x], [y]) through [o]: snow and ice carried forward by a simple version of [tileWeather]'s rules, without its dice. */
internal fun World.forecastTile(o: Outlook, x: Int, y: Int, far: Int = -1): List<ForecastDay> {
    val i = index(x, y)
    val h = tileHeight(x, y)
    val water = isWater(x, y) || canals.contains(i) || locks.containsKey(i)
    val shore = if (!water) 0 else if (far >= 0) far else shoreDistances()[i].toInt()
    val shed = sheds.contains(i)
    // In hundredths, so a day's little adds up.
    var snow = (weather.snow[i].toInt() and 255) * 100
    var ice = (weather.ice[i].toInt() and 255) * 100
    val out = ArrayList<ForecastDay>(o.days)
    for (d in 0 until o.days) {
        val fronts = o.fronts[d]!!
        val sky = skyAt(settings.region, o.year[d], o.doy[d], o.anomaly[d], fronts, height, x, y, h)
        val t = sky.temperature
        val p = sky.precipitation
        var fall = 0
        if (water) {
            if (t < 0) ice = minOf(25_000, ice + minOf(-t * 30 / (ice / 100 + 3) * 10 / (10 + snow / 100) * 3 / (3 + shore), 400))
            else if (ice > 0) ice = maxOf(0, ice - (t * 15 + 100))
            if (ice >= 500) {
                if (t <= 0) { fall = p; snow += p }
                if (t > 0) snow -= t * 15 + p / 2
                snow = snow.coerceIn(0, 25_000)
            } else snow = 0
        } else {
            if (t <= 0) {
                fall = if (t < -100) p * 3 / 2 else if (t > DRY_SNOW) p / 2 else p
                snow += fall
            } else snow -= minOf(snow, t * 13 + p / 2)
            snow -= snow * (if (t > DRY_SNOW) 15 else 5) / 100
            if (sky.freezing) ice += p
            ice -= if (t > 0) t * 10 + 200 else 30
            ice = ice.coerceIn(0, 25_000)
            if (shed) { snow = 0; fall = 0 }
        }
        val known = fronts.any { it.kind != Front.FOG && it.reach(x, y) > 0 }
        val swing = if (p > 0) DAY_SWING_WET else DAY_SWING
        out += ForecastDay(
            d + 1, day + d + 1, t + swing, t - swing, p, fall / 100, snow / 100, if (t > 0) p / 100 else 0,
            sky.freezing, ice / 100, confidence(d + 1, known),
        )
    }
    return out
}

/** How sure day [ahead] is: less each day out, a little more when a known front is over the place then. */
internal fun confidence(ahead: Int, known: Boolean): Int {
    val points = 100 - SURE_LOST_A_DAY * (ahead - 1) + if (known) SURE_FROM_FRONT else 0
    return when {
        points >= SURE_HIGH -> Confidence.HIGH
        points >= SURE_FAIR -> Confidence.FAIR
        else -> Confidence.LOW
    }
}

/**
 * Packs a weather warning's [News.about]: the town the trouble is nearest, [Warning.NO_TOWN] for none; how many days
 * off it is; and whose line it's on.
 */
object Warning {
    const val NO_TOWN = 0xFFFF
    fun pack(town: Int, days: Int, company: Int) = (town and 0xFFFF) or ((days and 15) shl 16) or (company shl 20)
    fun town(about: Int) = about and 0xFFFF
    fun days(about: Int) = (about ushr 16) and 15
    fun company(about: Int) = about ushr 20
}

/**
 * Once a day, after the weather: for each player's company, its track and roads, its road and air strips and its
 * docks against the next [WARN_DAYS] days, news of heavy snow coming, ice closing a harbour, a storm holding ships
 * and deep cold, and with the climate trend fire weather, floods and thaw on track, one item per kind and nearest town, told once while it stands and not again till it's been gone
 * [WARN_QUIET] days. It makes news only.
 */
internal fun World.warnDay() {
    if (!settings.weather) return
    val found = warnFound(outlook(WARN_DAYS)) ?: return
    // The soonest of each kind for each company and town, the town taken from the middle of each cell.
    val towns = HashMap<Int, Int>()
    val soonest = HashMap<Long, Int>()
    for ((k, days) in found) {
        val cell = (k and 0xFFFFFFFFL).toInt()
        val town = towns.getOrPut(cell) { nearestTown(warnCellMiddle(cell)).let { if (it < 0) Warning.NO_TOWN else it } }
        val key = (k and 0xFFFFFFFFL.inv()) or town.toLong()
        val was = soonest[key]
        if (was == null || days < was) soonest[key] = days
    }
    val today = day
    for (key in soonest.keys.sorted()) {
        // Told when it's first seen; kept while it's seen, and for a while after, so one that comes and goes isn't told again.
        if (!warned.containsKey(key)) {
            tell((key ushr 40).toInt(), Warning.pack((key and 0xFFFF).toInt(), soonest.getValue(key), ((key ushr 32) and 0xFF).toInt()))
        }
        warned[key] = today
    }
    warned.entries.removeAll { today - it.value > WARN_QUIET }
}

/** The cells [warnFound] groups by across, and the tile in the middle of [cell]. */
private fun World.warnCells() = (width + WARN_CELL - 1) / WARN_CELL
private fun World.warnCellMiddle(cell: Int): Int {
    val cx = cell % warnCells()
    val cy = cell / warnCells()
    return index(minOf(width - 1, cx * WARN_CELL + WARN_CELL / 2), minOf(height - 1, cy * WARN_CELL + WARN_CELL / 2))
}

/**
 * What [warnDay] finds through [o], as the soonest days off for each kind, company and [WARN_CELL] cell, keyed
 * kind shl 40, company shl 32 and the cell. Null with no player's company. Read-only.
 */
internal fun World.warnFound(o: Outlook): HashMap<Long, Int>? {
    val players = companies.filter { !it.gone && human(it.id) }.map { it.id }
    if (players.isEmpty()) return null
    val mine = BooleanArray(MAX_COMPANIES)
    for (p in players) if (p in 0 until MAX_COMPANIES) mine[p] = true
    val shore = shoreDistances()
    val cells = warnCells()
    fun key(kind: Int, company: Int, i: Int): Long =
        (kind.toLong() shl 40) or (company.toLong() shl 32) or ((i / width / WARN_CELL) * cells + (i % width) / WARN_CELL).toLong()
    fun HashMap<Long, Int>.note(k: Long, days: Int) { val was = this[k]; if (was == null || days < was) this[k] = days }
    val bands = Workers.threads * 4
    val found = Array(bands) { HashMap<Long, Int>() }
    val trend = o.days > 0 && trendSteps(o.year[0]) > 0
    parallelBandsIndexed(height, bands) { b, from, until ->
        val mineHere = found[b]
        for (y in from until until) for (x in 0 until width) {
            val i = index(x, y)
            val tc = if (track[i].toInt() != 0) owner[i].toInt() else -1
            val rc = if (road[i].toInt() != 0 && !isWater(x, y)) roadOwner[i].toInt() else -1
            val onTrack = tc in 0 until MAX_COMPANIES && mine[tc]
            val onRoad = rc in 0 until MAX_COMPANIES && mine[rc]
            if (!onTrack && !onRoad) continue
            val days = forecastTile(o, x, y, 0)
            val now = (weather.snow[i].toInt() and 255)
            val deep = if (onTrack) WARN_SNOW else DEEP_ROAD_SNOW
            if (now < deep) {
                val d = days.indexOfFirst { it.snow >= deep }
                if (d >= 0) mineHere.note(key(News.WARN_SNOW, if (onTrack) tc else rc, i), d + 1)
            }
            // Only for a real drop on the way, so a day hovering round the line doesn't tell it.
            if (onTrack && skyAt(x, y).temperature >= DEEP_COLD + COLD_MARGIN) {
                val d = days.indexOfFirst { (it.high + it.low) / 2 < DEEP_COLD }
                if (d >= 0) mineHere.note(key(News.WARN_COLD, tc, i), d + 1)
            }
            if (trend && onTrack && !guideway[i] && !tube[i]) climateWarnings(x, y, days)?.let { (kind, d) -> mineHere.note(key(kind, tc, i), d + 1) }
        }
    }
    val all = HashMap<Long, Int>()
    for (b in 0 until bands) for ((k, d) in found[b]) all.note(k, d)
    for (a in airports) {
        if (a == null || a.kind != Airports.STRIP || a.owner !in 0 until MAX_COMPANIES || !mine[a.owner]) continue
        for (i in a.tiles(this)) {
            if ((weather.snow[i].toInt() and 255) >= STRIP_SNOW) continue
            val d = forecastTile(o, i % width, i / width, 0).indexOfFirst { it.snow >= STRIP_SNOW }
            if (d >= 0) all.note(key(News.WARN_SNOW, a.owner, i), d + 1)
        }
    }
    val storm = (0 until o.days).firstOrNull { o.wind[it] >= STORM_WIND }
    for (s in stations) {
        if (s == null || s.owner !in 0 until MAX_COMPANIES || !mine[s.owner] || s.docks.isEmpty()) continue
        for (berth in berths(s.id)) {
            if (!inside(berth % width, berth / width)) continue
            if (storm != null && weather.wind < STORM_WIND) all.note(key(News.WARN_STORM, s.owner, berth), storm + 1)
            if ((weather.ice[berth].toInt() and 255) < SHIP_ICE) {
                val d = forecastTile(o, berth % width, berth / width, shore[berth].toInt()).indexOfFirst { it.ice >= SHIP_ICE }
                if (d >= 0) all.note(key(News.WARN_ICE, s.owner, berth), d + 1)
            }
        }
    }
    return all
}

/**
 * With the climate trend on, the first of fire weather, a flood and a thaw slow order coming to track tile ([x], [y])
 * through [days], as a [News] kind and the day's place in [days], or null: hot dry days with trees by the line and
 * the ground dry, a downpour on soaked low ground, and thawing days on permafrost where one slump puts the track on
 * a slow order.
 */
private fun World.climateWarnings(x: Int, y: Int, days: List<ForecastDay>): Pair<Int, Int>? {
    val i = index(x, y)
    val wet = weather.wet[i].toInt() and 255
    var trees = false
    for (dy in -1..1) for (dx in -1..1) if (inside(x + dx, y + dy) && Trees.count(this.trees[index(x + dx, y + dy)].toInt()) > 0) trees = true
    if (trees && wet < FIRE_WET) {
        val d = days.indexOfFirst { (it.high + it.low) / 2 > FIRE_HEAT && it.precipitation == 0 }
        if (d >= 0) return News.WARN_FIRE to d
    }
    if (wet > FLOOD_WET && lowGround(x, y)) {
        val d = days.indexOfFirst { it.precipitation >= WASHOUT_RAIN }
        if (d >= 0) return News.WARN_FLOOD to d
    }
    val c = condition[i].toInt() and 255
    if (c in WORN until WORN + THAW_SLUMP && (weather.frost[i].toInt() and 255) == 0 && thawGround(x, y)) {
        val d = days.indexOfFirst { (it.high + it.low) / 2 > 0 }
        if (d >= 0) return News.WARN_THAW to d
    }
    return null
}

/** Ground wetness, in mm, below which hot dry days are fire weather, and above which a downpour may flood low track. */
const val FIRE_WET = 30
const val FLOOD_WET = 150

/**
 * The tiles of [company]'s track and roads that the next [days] days' snow would slow or stop, with how: a tile in
 * the first array, its level (1 slows, 2 stops) in the same place of the second. Track slows from [Weather.DEEP]
 * and stops from [Weather.BLOCKING]; roads slow from [DEEP_ROAD_SNOW] and stop from [Weather.BLOCKING]. Read-only,
 * worked out in bands across the workers.
 */
fun World.snowRisk(company: Int, days: Int = WARN_DAYS): Pair<IntArray, ByteArray> {
    if (!settings.weather) return IntArray(0) to ByteArray(0)
    val o = outlook(days)
    val bands = Workers.threads * 4
    val tiles = Array(bands) { ArrayList<Int>() }
    val levels = Array(bands) { ArrayList<Byte>() }
    parallelBandsIndexed(height, bands) { b, from, until ->
        for (y in from until until) for (x in 0 until width) {
            val i = index(x, y)
            val onTrack = track[i].toInt() != 0 && owner[i].toInt() == company
            val onRoad = road[i].toInt() != 0 && roadOwner[i].toInt() == company && !isWater(x, y)
            if (!onTrack && !onRoad) continue
            val deepest = forecastTile(o, x, y, 0).maxOf { it.snow }
            val level = when {
                deepest >= Weather.BLOCKING -> 2
                onTrack && deepest >= Weather.DEEP -> 1
                !onTrack && deepest >= DEEP_ROAD_SNOW -> 1
                else -> 0
            }
            if (level > 0) { tiles[b] += i; levels[b] += level.toByte() }
        }
    }
    // Bands in order, so the tiles come out as a single walk would give them.
    val out = ArrayList<Int>()
    val outLevels = ArrayList<Byte>()
    for (b in 0 until bands) { out += tiles[b]; outLevels += levels[b] }
    return out.toIntArray() to outLevels.toByteArray()
}

/** Snow on track, in cm, a warning is given for: near [Weather.BLOCKING], where trains slow hard and may stick. */
const val WARN_SNOW = 60
/** Days ahead warnings look. */
const val WARN_DAYS = 3
/** Days a warning that's lapsed is kept before it can be told again. */
const val WARN_QUIET = 5
/** Tenths of a degree today has to be above [DEEP_COLD] for deep cold coming to be told. */
const val COLD_MARGIN = 30
/** Tiles across a square cell warnings are grouped by before naming the nearest town. */
const val WARN_CELL = 16
/** Half a day's swing round its mean heat, in tenths of a degree: clear, and under cloud. */
const val DAY_SWING = 50
const val DAY_SWING_WET = 25
/** Confidence points lost each day out, added when a known front is over the place, and the points for high and fair. */
const val SURE_LOST_A_DAY = 18
const val SURE_FROM_FRONT = 15
const val SURE_HIGH = 70
const val SURE_FAIR = 45
