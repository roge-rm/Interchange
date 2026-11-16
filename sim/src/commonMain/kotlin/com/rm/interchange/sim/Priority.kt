package com.rm.interchange.sim

/*
 * A company can mark its own track and roads as priority. Crews mend priority
 * track first and keep it above [PRIORITY_FLOOR], and dig out a train
 * snowbound on it after [DIG_DAYS] days; priority road is plowed as often as
 * a big town's streets and salted or sanded from twice as far. Each tile costs
 * extra every work day, booked as [Books.PRIORITY].
 */

/** Marks or clears priority along [c]'s line, on the company's own track or road, up to [PRIORITY_MOST] tiles a company. */
internal fun World.setPriority(company: Company, c: Command.Priority) {
    val tiles = if (c.road) straightLine(c.sx, c.sy, c.ex, c.ey) else Track.line(this, c.sx, c.sy, c.ex, c.ey, c.first)?.map { it / 64 }?.distinct()
    if (tiles == null) return
    val map = if (c.road) priorityRoad else priorityTrack
    var count = priorityCount(company.id)
    var changed = false
    for (i in tiles) {
        if (!c.on) {
            if (map[i] == company.id) { map.remove(i); changed = true; touched(i % width, i / width) }
            continue
        }
        if (map[i] == company.id || !priorityFits(i, company.id, c.road)) continue
        if (count >= PRIORITY_MOST) break
        map[i] = company.id
        count++
        changed = true
        touched(i % width, i / width)
    }
    if (changed) layoutVersion++
}

/** The tiles along a row or column from ([sx], [sy]) to ([ex], [ey]), or null for a line that's neither or leaves the map. */
private fun World.straightLine(sx: Int, sy: Int, ex: Int, ey: Int): List<Int>? {
    if (sx != ex && sy != ey) return null
    if (!inside(sx, sy) || !inside(ex, ey)) return null
    val dx = (ex - sx).coerceIn(-1, 1)
    val dy = (ey - sy).coerceIn(-1, 1)
    val n = maxOf(kotlin.math.abs(ex - sx), kotlin.math.abs(ey - sy))
    return (0..n).map { k -> index(sx + dx * k, sy + dy * k) }
}

/** Whether tile [i] has [company]'s own track, or with [road] its own road, to make priority. */
private fun World.priorityFits(i: Int, company: Int, road: Boolean): Boolean =
    if (road) this.road[i].toInt() != 0 && roadOwner[i].toInt() == company && roadEdges(i) != 0
    else track[i].toInt() != 0 && owner[i].toInt() == company

/** How many tiles of track and road [company] has made priority. */
fun World.priorityCount(company: Int): Int = priorityTrack.values.count { it == company } + priorityRoad.values.count { it == company }

/** Whether tile [i]'s track is priority for its owner. */
fun World.priorityTrackAt(i: Int): Boolean = priorityTrack[i]?.let { it == owner[i].toInt() } ?: false

/** Whether tile [i]'s road is priority for its owner. */
fun World.priorityRoadAt(i: Int): Boolean = priorityRoad[i]?.let { it == roadOwner[i].toInt() } ?: false

/**
 * The calendar day's priority work, after the track's: marks on track or road that's gone or changed hands are
 * dropped, and crews dig out each train that's stood [DIG_DAYS] days snowbound against its company's
 * priority track, clearing up to [DIG_TILES] tiles of it ahead, for [DIG_COST].
 */
internal fun World.priorityDay() {
    if (priorityTrack.isNotEmpty()) for (k in priorityTrack.keys.sorted()) if (!priorityFits(k, priorityTrack.getValue(k), false)) priorityTrack.remove(k)
    if (priorityRoad.isNotEmpty()) for (k in priorityRoad.keys.sorted()) if (!priorityFits(k, priorityRoad.getValue(k), true)) priorityRoad.remove(k)
    for (t in trains) {
        if (t == null) continue
        if (!t.snowbound || t.inDepot || t.wreckedFor > 0 || t.steps.isEmpty() || priorityTrack.isEmpty()) { t.dugDays = 0; continue }
        val ahead = nextFrom(t, t.steps.last(), t.routeAt)
        if (ahead < 0 || priorityTrack[stepTile(ahead)] != t.owner) { t.dugDays = 0; continue }
        t.dugDays++
        if (t.dugDays < DIG_DAYS) continue
        t.dugDays = 0
        var cur = t.steps.last()
        var at = t.routeAt
        for (k in 0 until DIG_TILES) {
            val next = nextFrom(t, cur, at)
            if (next < 0) break
            val n = stepTile(next)
            if (priorityTrack[n] != t.owner) break
            if (stepLevel(next) == 0) weather.snow[n] = 0
            cur = next
            at++
        }
        companies.getOrNull(t.owner)?.let { spend(it, Books.PRIORITY, price(DIG_COST)) }
    }
}

/** What each company's priority tiles cost this work day, in tenths of a cent, by company id. */
internal fun World.priorityOwedToday(): LongArray {
    val owed = LongArray(MAX_COMPANIES)
    for (o in priorityTrack.values) if (o in 0 until MAX_COMPANIES) owed[o] += PRIORITY_TRACK
    for (o in priorityRoad.values) if (o in 0 until MAX_COMPANIES) owed[o] += PRIORITY_ROAD
    return owed
}

/** What [company]'s priority tiles cost a month of work days, in dollars now. */
fun World.priorityMonth(company: Int): Long {
    val tenths = priorityTrack.values.count { it == company } * PRIORITY_TRACK + priorityRoad.values.count { it == company } * PRIORITY_ROAD
    return price(tenths * WORK_DAYS_A_MONTH / 1_000)
}

/**
 * The most tiles of track and road a company can make priority: a long main line with its yards, or a few key
 * lines. More and it stops being a priority, and the daily checks grow with it.
 */
const val PRIORITY_MOST = 1_000
/** The share of priority track's wear crews mend a work day, in 1024ths, above [Upkeep.TRACK_MEND]'s high level; and the condition it's kept at or above, just over [WORN] so wear never slows trains on it. */
const val PRIORITY_MEND = 400
const val PRIORITY_FLOOR = WORN + 10
/** What a tile of priority track and of priority road costs a work day on top of its upkeep, in tenths of a cent: about another high upkeep level, and a plow and salt round. */
const val PRIORITY_TRACK = 100L
const val PRIORITY_ROAD = 30L
/** Chance out of 256 a day a priority road is plowed once it's stopped snowing, as a town over 5000's streets are. */
const val PRIORITY_PLOW = 128
/**
 * Calendar days' checks a train is found snowbound against priority track before crews dig it out, how many tiles
 * ahead they clear, and what it costs in dollars before inflation. One: the next day's crews come, as a train
 * rams through even the deepest drift in under three work days by itself.
 */
const val DIG_DAYS = 1
const val DIG_TILES = 8
const val DIG_COST = 1_500L
/** Work days in a month, for [priorityMonth]. */
private const val WORK_DAYS_A_MONTH = 30L
