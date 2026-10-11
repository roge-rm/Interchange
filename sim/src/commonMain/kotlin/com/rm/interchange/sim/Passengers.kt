package com.rm.interchange.sim

/*
 * Passengers and mail: made by town buildings each day, gathered at the
 * station whose platforms are nearest within [CATCHMENT] tiles, carried by
 * trains and paid for by distance and speed where they get off.
 *
 * Which station each building sends to is worked out again only when stations
 * or buildings change ([catchmentDirty]), so a day's work is a walk down that
 * list rather than over the map.
 */

/** The kinds of cargo, by number. More come with industries. */
object Cargo {
    const val PASSENGERS = 0
    const val MAIL = 1
    const val COUNT = 2
    val IDS = listOf("passengers", "mail")

    /** The number of cargo [id], as packs name it, or -1. */
    fun of(id: String) = IDS.indexOf(id)

    /** What carrying one a tile pays, in hundredths of a dollar, at full speed. */
    val RATE = intArrayOf(20, 35)
}

/** How far from its platforms a station gathers from, in tiles each way. */
const val CATCHMENT = 4

/** The most of each cargo a station holds waiting. */
const val MAX_WAITING = 4095

/** A payment where cargo got off, kept a while for the screen to show rising over the station. */
class Payment(val tile: Int, val amount: Long, val tick: Long)

/** Each building tile within reach of a station, and the station it sends to, as pairs. */
internal fun World.workOutCatchment() {
    catchmentDirty = false
    val best = HashMap<Int, Int>()
    val bestDist = HashMap<Int, Int>()
    for (s in stations) {
        if (s == null) continue
        for (p in s.tiles) {
            val px = p % width
            val py = p / width
            for (y in maxOf(0, py - CATCHMENT)..minOf(height - 1, py + CATCHMENT)) for (x in maxOf(0, px - CATCHMENT)..minOf(width - 1, px + CATCHMENT)) {
                val i = index(x, y)
                if (house[i].toInt() == 0) continue
                val d = maxOf(kotlin.math.abs(x - px), kotlin.math.abs(y - py))
                val had = bestDist[i]
                // The nearest platform wins; between stations as near, the older.
                if (had == null || d < had || (d == had && s.id < best.getValue(i))) {
                    best[i] = s.id
                    bestDist[i] = d
                }
            }
        }
    }
    val keys = best.keys.sorted()
    catchment = IntArray(keys.size * 2).also { a -> for ((k, tile) in keys.withIndex()) { a[k * 2] = tile; a[k * 2 + 1] = best.getValue(tile) } }
}

/** A day of passengers and mail: each building in reach of a station sends what it makes there. */
internal fun World.passengersDay() {
    if (catchmentDirty) workOutCatchment()
    val c = catchment
    var k = 0
    while (k < c.size) {
        val tile = c[k]
        val s = stations.getOrNull(c[k + 1])
        k += 2
        val h = roster.houses.getOrNull(houseOf(house[tile].toInt())) ?: continue
        if (s == null) continue
        val people = h.residents + h.jobs
        val passengers = (people * 8 + rng.below(256)) / 256
        val mail = (people * 2 + rng.below(256)) / 256
        s.waiting[Cargo.PASSENGERS] = minOf(MAX_WAITING, s.waiting[Cargo.PASSENGERS] + passengers)
        s.waiting[Cargo.MAIL] = minOf(MAX_WAITING, s.waiting[Cargo.MAIL] + mail)
        towns.getOrNull(townOf[tile].toInt())?.let { it.servedThisMonth += passengers + mail }
    }
}

/** Whether station [s] has any building within reach, to take passengers and mail off trains. */
internal fun World.accepts(s: Station): Boolean {
    if (catchmentDirty) workOutCatchment()
    val c = catchment
    var k = 1
    while (k < c.size) {
        if (c[k] == s.id) return true
        k += 2
    }
    return false
}

/**
 * Train [t] standing at station [s]: what it brought from elsewhere gets off
 * and is paid for, then it takes on what's waiting, as much as it has room for.
 */
internal fun World.callAt(t: Train, s: Station) {
    val company = companies.getOrNull(t.owner) ?: return
    if (accepts(s)) {
        var paid = 0L
        for (c in 0 until Cargo.COUNT) {
            val n = t.load[c]
            val from = stations.getOrNull(t.loadedAt[c])
            if (n == 0 || from == null || from.id == s.id) continue
            paid += fare(c, n, from, s, ((tick - t.loadedTick[c]) / Calendar.TICKS_PER_DAY).toInt())
            t.load[c] = 0
            t.loadedAt[c] = -1
        }
        if (paid > 0) {
            company.money += paid
            t.profitThisYear += paid
            payments += Payment(s.tiles.first(), paid, tick)
        }
    }
    for (c in 0 until Cargo.COUNT) {
        val room = t.capacity(c) - t.load[c]
        val take = minOf(room, s.waiting[c])
        if (take <= 0) continue
        if (t.load[c] == 0) {
            t.loadedAt[c] = s.id
            t.loadedTick[c] = tick
        }
        t.load[c] += take
        s.waiting[c] -= take
    }
}

/**
 * What [n] of cargo [c] pays carried from [from] to [to] in [days]: by the
 * distance between the stations, full if it came about as fast as a train
 * reasonably could, less a hundredth a day after, down to a third.
 */
fun fare(c: Int, n: Int, from: Station, to: Station, days: Int): Long {
    val dist = kotlin.math.abs(from.signX - to.signX) + kotlin.math.abs(from.signY - to.signY)
    val quick = dist / 2 + 5
    val share = (100 - maxOf(0, days - quick)).coerceAtLeast(33)
    return n.toLong() * dist * Cargo.RATE[c] * share / 10_000
}

/** The first of each month: what towns had picked up last month is what makes them grow. */
internal fun World.passengersMonth() {
    for (t in towns) {
        t.servedLastMonth = t.servedThisMonth
        t.servedThisMonth = 0
    }
}

/** The first of each year: trains' profit moves to last year's. */
internal fun World.passengersYear() {
    for (t in trains) {
        if (t == null) continue
        t.profitLastYear = t.profitThisYear
        t.profitThisYear = 0
    }
}

/** Payments shown rising over stations, kept this many ticks. */
const val PAYMENT_TICKS = Calendar.TICKS_PER_DAY * 3L
