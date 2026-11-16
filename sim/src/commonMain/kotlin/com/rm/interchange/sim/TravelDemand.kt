package com.rm.interchange.sim

/*
 * How good the service from each station is, and the travel between towns that isn't carried.
 *
 * Each month a station's service score compares the whole way's cost to the places it reaches, waits and changes
 * included, with going there in a straight line at [SERVICE_KMH]; good service makes more passengers and mail, poor
 * service fewer. The demand between the biggest towns is worked out the same month from their sizes and distance,
 * against what was carried between them, for the screen only.
 */

/** The speed, in km/h, a station's service is measured against, door to door in a straight line. */
const val SERVICE_KMH = 50

/** The most a station's service moves its passengers, in percent either way; mail moves half as much. */
const val SERVICE_MOST = 50

/** The biggest towns demand is worked out between, and the most pairs kept. */
const val DEMAND_TOWNS = 64
const val DEMAND_TOP = 16

/** A pair of towns' trips a month: their populations multiplied, over (distance + [GRAVITY_NEAR]) * [GRAVITY]. */
const val GRAVITY = 128
const val GRAVITY_NEAR = 20

/**
 * Travel between towns [townA] and [townB] (the lower id first) that isn't carried well: [potential] trips a month
 * between them, [carried] last month between stations near each, and [served] if passengers can go between them at all.
 */
class DemandView(val townA: Int, val townB: Int, val potential: Int, val carried: Int, val served: Boolean) {
    val unmet get() = (potential - carried).coerceAtLeast(0)
}

/** A way's cost in ticks, less one change, as tiles at [SERVICE_KMH], for weighing where people go. */
internal fun costTiles(ticks: Int) = (ticks - CHANGE_TICKS).coerceAtLeast(0) * SERVICE_KMH / TILE_TICKS_KMH

/**
 * Station [s]'s service score, 0 to 100, from the passengers' way to each station it reaches that takes them,
 * weighed by the people there: 50 when the way is as quick as a straight line at [SERVICE_KMH], 100 at twice that.
 * -1 when it reaches nowhere.
 */
internal fun World.serviceScore(s: Station): Int {
    val costs = routeCosts[Cargo.PASSENGERS][s.id] ?: return -1
    var ideal = 0L
    var actual = 0L
    for (d in costs.keys.sorted()) {
        val to = stations.getOrNull(d) ?: continue
        if (!accepts(to, Cargo.PASSENGERS)) continue
        val w = (stationPeople[d] ?: 0) + 20L
        ideal += w * stationDistance(s.id, d) * TILE_TICKS_KMH / SERVICE_KMH
        actual += w * (costs.getValue(d) - CHANGE_TICKS).coerceAtLeast(1)
    }
    if (actual == 0L) return -1
    return (ideal * 50 / actual).coerceIn(0, 100).toInt()
}

/** What station [s]'s service does to how much of cargo [c] it makes, in percent: passengers and mail only. */
internal fun serviceFactor(s: Station, c: Int): Int {
    if (s.service < 0) return 100
    val by = (s.service - 50) * SERVICE_MOST / 50
    return when (c) {
        Cargo.PASSENGERS -> 100 + by
        Cargo.MAIL -> 100 + by / 2
        else -> 100
    }
}

/** The first of each month: each station's service score, by the way as it is now. */
internal fun World.serviceMonth() {
    if (catchmentDirty) workOutCatchment()
    if (linksDirty) workOutLinks()
    for (s in stations) if (s != null) s.service = if (s.waypoint) -1 else serviceScore(s)
}

/**
 * The first of each month, after the trips roll over: the potential trips between each pair of the [DEMAND_TOWNS]
 * biggest towns against those carried last month, keeping the [DEMAND_TOP] pairs with the most not carried among
 * those not served at all or carried less than half. For the screen only.
 */
internal fun World.demandMonth() {
    if (catchmentDirty) workOutCatchment()
    if (linksDirty) workOutLinks()
    val top = towns.filter { it.population > 0 }.sortedWith(compareByDescending<Town> { it.population }.thenBy { it.id }).take(DEMAND_TOWNS).sortedBy { it.id }
    val inTop = HashMap<Int, Int>()
    for ((i, t) in top.withIndex()) inTop[t.id] = i
    val n = top.size
    // The stations near each town, by id.
    val near = Array(n) { ArrayList<Int>() }
    val townOf = HashMap<Int, Int>()
    for (s in stations) {
        if (s == null || s.waypoint) continue
        val i = stationTown(s)?.id?.let { inTop[it] } ?: continue
        near[i] += s.id
        townOf[s.id] = i
    }
    val carried = Array(n) { IntArray(n) }
    for ((k, count) in tripsLast) {
        val a = townOf[(k shr 16).toInt()] ?: continue
        val b = townOf[(k and 0xffff).toInt()] ?: continue
        if (a == b) continue
        carried[minOf(a, b)][maxOf(a, b)] += count
    }
    val hops = nextHops[Cargo.PASSENGERS]
    val found = ArrayList<DemandView>()
    for (i in 0 until n) for (j in i + 1 until n) {
        val ta = top[i]
        val tb = top[j]
        val dist = kotlin.math.abs(ta.x - tb.x) + kotlin.math.abs(ta.y - tb.y)
        val potential = (ta.population.toLong() * tb.population / ((dist + GRAVITY_NEAR).toLong() * GRAVITY) * settings.dayLength).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val got = carried[i][j]
        if (potential - got <= 0) continue
        val served = near[i].any { a -> near[j].any { b -> hops[a]?.containsKey(b) == true || hops[b]?.containsKey(a) == true } }
        if (served && got * 2L >= potential) continue
        found += DemandView(ta.id, tb.id, potential, got, served)
    }
    demand = found.sortedWith(compareByDescending<DemandView> { it.unmet }.thenBy { it.townA }.thenBy { it.townB }).take(DEMAND_TOP)
}
