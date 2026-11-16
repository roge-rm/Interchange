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
 * and deep cold, one item per kind and nearest town, told once while it stands. It makes news only.
 */
internal fun World.warnDay() {
    if (!settings.weather) return
    val players = companies.filter { !it.gone && human(it.id) }.map { it.id }
    if (players.isEmpty()) return
    val mine = BooleanArray(MAX_COMPANIES)
    for (p in players) if (p in 0 until MAX_COMPANIES) mine[p] = true
    val o = outlook(WARN_DAYS)
    val shore = shoreDistances()
    val bands = Workers.threads * 4
    // Each finding as kind, company, tile and days off.
    val found = Array(bands) { ArrayList<IntArray>() }
    parallelBandsIndexed(height, bands) { b, from, until ->
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
                if (d >= 0) found[b] += intArrayOf(News.WARN_SNOW, if (onTrack) tc else rc, i, d + 1)
            }
            if (onTrack) {
                val today = skyAt(x, y).temperature
                if (today >= DEEP_COLD) {
                    val d = days.indexOfFirst { (it.high + it.low) / 2 < DEEP_COLD }
                    if (d >= 0) found[b] += intArrayOf(News.WARN_COLD, tc, i, d + 1)
                }
            }
        }
    }
    val all = ArrayList<IntArray>()
    for (b in 0 until bands) all += found[b]
    for (a in airports) {
        if (a == null || a.kind != Airports.STRIP || a.owner !in 0 until MAX_COMPANIES || !mine[a.owner]) continue
        for (i in a.tiles(this)) {
            if ((weather.snow[i].toInt() and 255) >= STRIP_SNOW) continue
            val d = forecastTile(o, i % width, i / width, 0).indexOfFirst { it.snow >= STRIP_SNOW }
            if (d >= 0) all += intArrayOf(News.WARN_SNOW, a.owner, i, d + 1)
        }
    }
    val storm = (0 until o.days).firstOrNull { o.wind[it] >= STORM_WIND }
    for (s in stations) {
        if (s == null || s.owner !in 0 until MAX_COMPANIES || !mine[s.owner] || s.docks.isEmpty()) continue
        for (berth in berths(s.id)) {
            if (!inside(berth % width, berth / width)) continue
            if (storm != null && weather.wind < STORM_WIND) all += intArrayOf(News.WARN_STORM, s.owner, berth, storm + 1)
            if ((weather.ice[berth].toInt() and 255) < SHIP_ICE) {
                val d = forecastTile(o, berth % width, berth / width, shore[berth].toInt()).indexOfFirst { it.ice >= SHIP_ICE }
                if (d >= 0) all += intArrayOf(News.WARN_ICE, s.owner, berth, d + 1)
            }
        }
    }
    // The soonest of each kind for each company and town, told in that order.
    val soonest = HashMap<Long, Int>()
    for (f in all) {
        val key = (f[0].toLong() shl 40) or (f[1].toLong() shl 32) or nearestTown(f[2]).let { if (it < 0) Warning.NO_TOWN else it }.toLong()
        val was = soonest[key]
        if (was == null || f[3] < was) soonest[key] = f[3]
    }
    val today = day
    for (key in soonest.keys.sorted()) {
        // Told when it's first seen; kept while it's seen each day after.
        if (!warned.containsKey(key)) {
            tell((key ushr 40).toInt(), Warning.pack((key and 0xFFFF).toInt(), soonest.getValue(key), ((key ushr 32) and 0xFF).toInt()))
        }
        warned[key] = today
    }
    warned.entries.removeAll { it.value < today }
}

/** Snow on track, in cm, a warning is given for: near [Weather.BLOCKING], where trains slow hard and may stick. */
const val WARN_SNOW = 60
/** Days ahead warnings look. */
const val WARN_DAYS = 3
/** Half a day's swing round its mean heat, in tenths of a degree: clear, and under cloud. */
const val DAY_SWING = 50
const val DAY_SWING_WET = 25
/** Confidence points lost each day out, added when a known front is over the place, and the points for high and fair. */
const val SURE_LOST_A_DAY = 18
const val SURE_FROM_FRONT = 15
const val SURE_HIGH = 70
const val SURE_FAIR = 45
