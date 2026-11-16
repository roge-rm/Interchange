package com.rm.interchange.sim

/*
 * What each link and station did, for the screen: departures, capacity and load leaving on each link, ticks vehicles
 * stood held on it, and how long what boarded at each station waited. Counted as vehicles run, rolled over each month,
 * saved, and left out of the checksum. The game reads only links' departures and held ticks, for the weekly link
 * costs, which are in the checksum.
 */

/** A month on a link: departures, capacity and load leaving on it by cargo, and ticks held on the runs that ended on it. */
class LinkMonth {
    var departures = 0
    val capacity = IntArray(Cargo.MAX)
    val load = IntArray(Cargo.MAX)
    var lost = 0L
    var runs = 0

    val empty get() = departures == 0 && runs == 0

    fun copyFrom(o: LinkMonth) {
        departures = o.departures
        o.capacity.copyInto(capacity)
        o.load.copyInto(load)
        lost = o.lost
        runs = o.runs
    }

    fun clear() {
        departures = 0
        capacity.fill(0)
        load.fill(0)
        lost = 0
        runs = 0
    }
}

/** A link's stats for the month so far and last month. */
class LinkStats {
    val month = LinkMonth()
    val last = LinkMonth()
}

/** Why cargo can't get from one station to another, by [whyNot]. */
object WhyNot {
    const val OK = 0
    /** Nothing links the two, by any cargo. */
    const val NO_SERVICE = 1
    /** Vehicles link them, but none on the way carries the cargo. */
    const val NOT_CARRIED = 2
    /** A vehicle that carries it calls at both, but its orders don't let it load at the one or get to the other. */
    const val ORDERS = 3
    /** The station it's going to doesn't take it. */
    const val NOT_ACCEPTED = 4
}

/** Which kind of vehicle [v] is, as [OverviewVehicle] modes. */
internal fun modeOf(v: Carrier) = when (v) {
    is Train -> OverviewVehicle.TRAIN
    is RoadVehicle -> OverviewVehicle.ROAD
    is Ship -> OverviewVehicle.SHIP
    else -> OverviewVehicle.AIR
}

/** Vehicle [v] leaving the station it called at: a departure on the link to its next stop, with its capacity and load. */
internal fun World.countDeparture(v: Carrier) {
    val to = nextStop(v)
    if (to < 0 || to == v.leftFrom) return
    val m = linkStats.getOrPut(linkKey(v.leftFrom, to)) { LinkStats() }.month
    m.departures++
    for (c in 0 until cargoCount) {
        val cap = v.capacity(c)
        if (cap == 0) continue
        m.capacity[c] += cap
        m.load[c] += v.load(c)
    }
}

/** A timed run over the link from [from] to [to] that stood held [held] ticks on the way. */
internal fun World.countRun(from: Int, to: Int, held: Int) {
    val m = linkStats.getOrPut(linkKey(from, to)) { LinkStats() }.month
    m.runs++
    m.lost += held.coerceAtLeast(0)
}

/** The first of each month: this month's stats become last month's, and links with neither are dropped. */
internal fun World.networkStatsMonth() {
    val it = linkStats.values.iterator()
    while (it.hasNext()) {
        val s = it.next()
        s.last.copyFrom(s.month)
        s.month.clear()
        if (s.last.empty) it.remove()
    }
    tripsLast.clear()
    tripsLast.putAll(tripsMonth)
    tripsMonth.clear()
    flowsMonthEnd()
    for (s in stations) {
        if (s == null) continue
        s.waitedTicks.copyInto(s.waitedLastMonth)
        s.boarded.copyInto(s.boardedLastMonth)
        s.gaveUp.copyInto(s.gaveUpLastMonth)
        s.waitedTicks.fill(0)
        s.boarded.fill(0)
        s.gaveUp.fill(0)
    }
}

/**
 * The vehicles and which station each one's orders go to after which, made once for many [whyNot] calls, as when
 * building a view.
 */
class WhyGraph internal constructor(internal val runs: List<Carrier>) {
    internal val outs: Map<Int, List<Int>> by lazy {
        val outs = HashMap<Int, ArrayList<Int>>()
        for (v in runs) {
            val ids = v.orders.map { Order.station(it) }.filter { it >= 0 }
            if (ids.size < 2) continue
            for (k in ids.indices) {
                val a = ids[k]
                val b = ids[(k + 1) % ids.size]
                if (a != b) outs.getOrPut(a) { ArrayList() } += b
            }
        }
        outs
    }
}

/** A [WhyGraph] of the world's vehicles as they are now. */
fun World.whyGraph() = WhyGraph(carriers())

/**
 * Why cargo [c] can't be sent from station [from] to [to], a [WhyNot] reason: [WhyNot.OK] if it can. Worked out from
 * the link tables and vehicles' orders in [graph] when asked, never in the step.
 */
fun World.whyNot(from: Int, to: Int, c: Int, graph: WhyGraph? = null): Int {
    val dest = stations.getOrNull(to) ?: return WhyNot.NO_SERVICE
    if (from == to) return WhyNot.OK
    val reached = nextHop(from, to, c) >= 0
    if (!accepts(dest, c)) return WhyNot.NOT_ACCEPTED
    if (reached) return WhyNot.OK
    val g = graph ?: whyGraph()
    val runs = g.runs
    // A vehicle carrying it that names both stations in its orders, but whose orders don't link them for it.
    for (v in runs) {
        if (v.capacity(c) == 0) continue
        var a = false
        var b = false
        for (o in v.orders) {
            val id = Order.station(o)
            if (id == from) a = true
            if (id == to) b = true
        }
        if (a && b) return WhyNot.ORDERS
    }
    // Linked at all, by any vehicle's station orders one after another, whatever it carries.
    val outs = g.outs
    val seen = HashSet<Int>()
    val queue = ArrayDeque<Int>()
    queue += from
    seen += from
    while (queue.isNotEmpty()) {
        val s = queue.removeFirst()
        for (n in outs[s].orEmpty()) {
            if (n == to) return WhyNot.NOT_CARRIED
            if (seen.add(n)) queue += n
        }
    }
    return WhyNot.NO_SERVICE
}

/**
 * A link as the screen shows it: from station [from] to [to], for company [owner], run by the vehicle [modes] (bits
 * by [OverviewVehicle] mode). [ticks] is its journey time, [measured] when vehicles have timed it rather than an
 * estimate from distance and top speed. Last month: [runs] timed runs and the ticks they stood held, on average
 * [lostPerRun]; [departures], and the capacity and load leaving on it, by cargo. The vehicles running it now are
 * [vehicleModes] and [vehicleIds], by mode and id.
 */
class LinkView(
    val from: Int,
    val to: Int,
    val owner: Int,
    val modes: Int,
    val ticks: Int,
    val measured: Boolean,
    val runs: Int,
    val lostPerRun: Int,
    val departures: Int,
    val capacity: IntArray,
    val load: IntArray,
    val vehicleModes: IntArray,
    val vehicleIds: IntArray,
    /** In ticks, worked out now as the weekly link costs are: the wait for it, half the time between departures, and its reliability charge. */
    val wait: Int = 0,
    val reliability: Int = 0,
)

/**
 * The links each company's vehicles run, stop to stop as their orders go, and those from its stations it ran last
 * month, by company and then key.
 */
internal fun World.linkViews(): List<LinkView> {
    class Building(var modes: Int = 0, var guess: Int = MAX_LINK_TICKS, val modesList: ArrayList<Int> = ArrayList(), val ids: ArrayList<Int> = ArrayList())
    // By company, then link key.
    val found = HashMap<Pair<Int, Long>, Building>()
    for (v in carriers()) {
        val stops = v.orders.filter { isStop(it) }
        if (stops.size < 2) continue
        val seen = HashSet<Long>()
        for (k in stops.indices) {
            val a = Order.station(stops[k])
            val b = Order.station(stops[(k + 1) % stops.size])
            if (a == b) continue
            val key = linkKey(a, b)
            val l = found.getOrPut(v.owner to key) { Building() }
            val ticks = (stationDistance(a, b) * TILE_TICKS_KMH / v.topSpeed.coerceAtLeast(1)).coerceIn(1, MAX_LINK_TICKS)
            l.guess = minOf(l.guess, ticks)
            l.modes = l.modes or (1 shl modeOf(v))
            if (seen.add(key)) {
                l.modesList += modeOf(v)
                l.ids += v.id
            }
        }
    }
    val charges = linkCharges()
    for (key in linkStats.keys) {
        val owner = stations.getOrNull((key shr 16).toInt())?.owner ?: continue
        if ((owner to key) !in found) found[owner to key] = Building()
    }
    return found.keys.sortedWith(compareBy<Pair<Int, Long>> { it.first }.thenBy { it.second }).map { id ->
        val (company, key) = id
        val l = found.getValue(id)
        val charge = charges[key]
        val last = linkStats[key]?.last
        val time = linkTimes[key]
        LinkView(
            (key shr 16).toInt(), (key and 0xffff).toInt(), company, l.modes, time ?: l.guess, time != null,
            last?.runs ?: 0, if (last == null || last.runs == 0) 0 else (last.lost / last.runs).toInt(), last?.departures ?: 0,
            last?.capacity?.copyOf() ?: IntArray(Cargo.MAX), last?.load?.copyOf() ?: IntArray(Cargo.MAX),
            l.modesList.toIntArray(), l.ids.toIntArray(), if (time == null) 0 else charge?.get(0) ?: 0, if (time == null) 0 else charge?.get(1) ?: 0,
        )
    }
}
