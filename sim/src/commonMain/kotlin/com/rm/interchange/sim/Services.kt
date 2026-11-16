package com.rm.interchange.sim

const val SERVICE_NAME_MOST = GROUP_NAME_MOST

/** The most a vehicle can be late or early, in ticks, so it never overflows. */
const val LATE_MOST = MAX_LINK_TICKS

/** The longest a connection can hold a vehicle, in ticks, and the most connections a service can have. */
const val CONNECTION_WAIT_MOST = Calendar.TICKS_PER_DAY * 10
const val CONNECTIONS_MOST = 32

/**
 * At [order] of its service a vehicle waits for one of service [other] to arrive at the same station, up to [most]
 * ticks after it got there. Made and missed are counted this month and last.
 */
class Connection(var order: Int, val other: Int, var most: Int) {
    var made = 0
    var missed = 0
    var madeLast = 0
    var missedLast = 0
}

/**
 * The vehicles of one [kind] sharing their orders, by their [shared] id, and its timetable. The arrays run parallel
 * to [orders], the orders the times were taken for: planned times in ticks with 0 for none, measured ones with -1
 * for none.
 */
class Service(val kind: Int, val shared: Int, var owner: Int) {
    var name = ""
    var timetabled = false
    /** Its vehicles held at each stop to keep them evenly apart. */
    var spaced = false
    var orders = IntArray(0)
    var planTravel = IntArray(0)
    var planWait = IntArray(0)
    var measuredTravel = IntArray(0)
    var measuredWait = IntArray(0)
    /** The tick a vehicle of it last left and last reached each order, -1 for none. */
    var lastLeft = LongArray(0)
    var lastReached = LongArray(0)
    /** Sorted by order, then other service. */
    val connections = ArrayList<Connection>()

    val key get() = serviceKey(kind, shared)
}

/** A service's key in [World.services]: vehicle ids are only unique within a kind, so the kind goes in the top bits. */
fun serviceKey(kind: Int, shared: Int) = (kind shl 28) or shared

/** Which of [Command.CopyVehicle]'s kinds [v] is. */
internal fun kindOf(v: Carrier) = when (v) {
    is Train -> Command.CopyVehicle.TRAIN
    is RoadVehicle -> Command.CopyVehicle.ROAD
    is Ship -> Command.CopyVehicle.SHIP
    else -> Command.CopyVehicle.AIRCRAFT
}

/** What an order goes to, for matching orders when they change: the station or depot, without what's done there. */
private fun orderPlace(o: Int) = when {
    Order.isCond(o) -> o
    Order.isDepot(o) -> o and (Order.DEPOT or 0xffff)
    else -> o and 0xffff
}

/** [v]'s service, or null if it has none. */
internal fun World.serviceOf(v: Carrier): Service? = if (v.shared < 0) null else services[serviceKey(kindOf(v), v.shared)]

/** [v]'s service, made for its sharing group if it has none yet; null if it doesn't share. */
internal fun World.serviceFor(v: Carrier): Service? {
    if (v.shared < 0) return null
    return services.getOrPut(serviceKey(kindOf(v), v.shared)) {
        Service(kindOf(v), v.shared, v.owner).also { fit(it, v.orders) }
    }
}

/**
 * Moves [s]'s times onto [orders]: each order still going to the same place keeps its wait, and its travel too if the
 * order before it is the same one as before; the rest start with none.
 */
internal fun fit(s: Service, orders: List<Int>) {
    val old = s.orders
    val n = orders.size
    if (old.size == n && (0 until n).all { old[it] == orders[it] }) return
    // The longest run of places in common, in order, matches new orders to old.
    val m = old.size
    val len = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        len[i][j] = if (orderPlace(orders[i]) == orderPlace(old[j])) len[i + 1][j + 1] + 1 else maxOf(len[i + 1][j], len[i][j + 1])
    }
    val from = IntArray(n) { -1 }
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            orderPlace(orders[i]) == orderPlace(old[j]) -> { from[i] = j; i++; j++ }
            len[i + 1][j] >= len[i][j + 1] -> i++
            else -> j++
        }
    }
    val planTravel = IntArray(n)
    val planWait = IntArray(n)
    val measuredTravel = IntArray(n) { -1 }
    val measuredWait = IntArray(n) { -1 }
    val lastLeft = LongArray(n) { -1 }
    val lastReached = LongArray(n) { -1 }
    val to = IntArray(m) { -1 }
    for (k in 0 until n) {
        val f = from[k]
        if (f < 0) continue
        to[f] = k
        planWait[k] = s.planWait[f]
        measuredWait[k] = s.measuredWait[f]
        lastLeft[k] = s.lastLeft.getOrElse(f) { -1 }
        lastReached[k] = s.lastReached.getOrElse(f) { -1 }
        val before = from[(k - 1).mod(n)]
        if (before >= 0 && before == (f - 1).mod(m)) {
            planTravel[k] = s.planTravel[f]
            measuredTravel[k] = s.measuredTravel[f]
        }
    }
    s.orders = orders.toIntArray()
    s.planTravel = planTravel
    s.planWait = planWait
    s.measuredTravel = measuredTravel
    s.measuredWait = measuredWait
    s.lastLeft = lastLeft
    s.lastReached = lastReached
    // Connections follow their order, and go with it.
    s.connections.removeAll { it.order !in 0 until m || to[it.order] < 0 }
    for (c in s.connections) c.order = to[c.order]
    s.connections.sortWith(compareBy({ it.order }, { it.other }))
}

/** [v]'s timing of its orders starts afresh: no order left or reached yet. */
internal fun resetTiming(v: Carrier) {
    v.orderLeft = -1
    v.orderLeftAt = -1
    v.orderReachedAt = -1
}

/** After [v]'s orders were changed: its service's times follow the new orders, and its own timing starts afresh. */
internal fun World.ordersChanged(v: Carrier) {
    resetTiming(v)
    serviceOf(v)?.let { fit(it, v.orders) }
}

/** [v] has joined a service, or left one, or come out of a depot it was sent to: on time, its timing afresh. */
internal fun World.restartTimetable(v: Carrier) {
    resetTiming(v)
    v.late = 0
    serviceOf(v)?.let { fit(it, v.orders) }
}

/** Drops the service of [kind] with id [shared] if no vehicle shares that id any more. */
internal fun World.pruneService(kind: Int, shared: Int) {
    if (shared < 0) return
    val key = serviceKey(kind, shared)
    if (!services.containsKey(key)) return
    if (groupVehicles(kind).none { it != null && it.shared == shared }) {
        services.remove(key)
        dropLostConnections()
    }
}

/** Connections to services that have gone are dropped. */
internal fun World.dropLostConnections() {
    for (s in services.values) s.connections.removeAll { it.other !in services }
}

/** Each work day: services no vehicle shares any more are dropped. */
internal fun World.servicesDay() {
    if (services.isEmpty()) return
    val held = HashSet<Int>()
    for (list in listOf<List<Carrier?>>(trains, roadVehicles, ships, aircraft)) for (v in list) if (v != null && v.shared >= 0) held += serviceKey(kindOf(v), v.shared)
    if (services.keys.removeAll { it !in held }) dropLostConnections()
}

/** The first of each month: connections' counts become last month's. */
internal fun World.servicesMonth() {
    for (s in services.values) for (c in s.connections) {
        c.madeLast = c.made
        c.missedLast = c.missed
        c.made = 0
        c.missed = 0
    }
}

/** A measured time, [old] (-1 for none) with [sample] ticks fed in, the sample held to [SAMPLE_MOST] times the old time or a day. */
private fun averaged(old: Int, sample: Long): Int {
    if (old < 0) return sample.coerceIn(0, MAX_LINK_TICKS.toLong()).toInt()
    val most = minOf(MAX_LINK_TICKS.toLong(), maxOf(old, Calendar.TICKS_PER_DAY).toLong() * SAMPLE_MOST)
    return ((7L * old + sample.coerceIn(0, most)) / 8).toInt()
}

private fun lateBy(late: Int, by: Long) = (late + by).coerceIn(-LATE_MOST.toLong(), LATE_MOST.toLong()).toInt()

/**
 * Its service's orders and [v]'s the same, the service's times made to follow them if not, with [v]'s timing started
 * afresh. Null when it has no service or its order is out of range.
 */
private fun World.timedService(v: Carrier): Service? {
    val s = serviceFor(v) ?: return null
    val n = v.orders.size
    if (s.orders.size != n || (0 until n).any { s.orders[it] != v.orders[it] }) {
        fit(s, v.orders)
        resetTiming(v)
        return null
    }
    return if (v.order in 0 until n) s else null
}

/**
 * [v] has reached its order: a station it calls at or runs through, a waypoint or buoy, or a depot. Its travel there
 * is timed if it came from the order before, and counts towards how late it is.
 */
internal fun World.reachOrder(v: Carrier) {
    if (v.shared < 0 || v.orderReachedAt >= 0) return
    val s = timedService(v) ?: return
    val i = v.order
    if (v.orderLeftAt >= 0 && v.orderLeft == (i - 1).mod(v.orders.size)) {
        val travel = tick - v.orderLeftAt
        s.measuredTravel[i] = averaged(s.measuredTravel[i], travel)
        if (s.timetabled && s.planTravel[i] > 0) v.late = lateBy(v.late, travel - s.planTravel[i])
    }
    v.orderLeft = -1
    v.orderLeftAt = -1
    v.orderReachedAt = tick
    s.lastReached[i] = tick
}

/**
 * [v] is going on from its order. If it reached it, its wait there is timed and counts towards how late it is, and
 * its travel to the next is timed from now; if not, as when an order is left out, the next isn't timed.
 */
internal fun World.passOrder(v: Carrier) {
    val reached = v.orderReachedAt
    v.orderReachedAt = -1
    v.orderLeft = -1
    v.orderLeftAt = -1
    if (v.shared < 0 || reached < 0) return
    val s = timedService(v) ?: return
    val i = v.order
    val wait = tick - reached
    s.measuredWait[i] = averaged(s.measuredWait[i], wait)
    s.lastLeft[i] = tick
    if (s.timetabled && s.planWait[i] > 0 && callsAt(s.orders[i])) v.late = lateBy(v.late, wait - s.planWait[i])
    v.orderLeft = i
    v.orderLeftAt = tick
}

/** Whether order [o] stands at a station, so a planned wait counts there: not a depot, a via order or a waypoint. */
internal fun World.callsAt(o: Int) = !Order.isDepot(o) && !Order.isCond(o) && !Order.has(o, Order.VIA) && stations.getOrNull(Order.station(o))?.waypoint != true

/**
 * Ticks [v] stays at its stop to be back on its service's timetable, or 0 if it's not early. It never stands more
 * than twice the stop's planned wait, the rest of how early it is carried on.
 */
internal fun World.timetableHold(v: Carrier): Int {
    if (v.shared < 0 || v.orderReachedAt < 0) return 0
    val s = serviceOf(v) ?: return 0
    if (!s.timetabled) return 0
    val i = v.order
    if (i !in s.planWait.indices || s.orders.size != v.orders.size || s.planWait[i] <= 0 || !callsAt(s.orders[i])) return 0
    val stood = tick - v.orderReachedAt
    val ahead = v.late + stood - s.planWait[i]
    val most = 2L * s.planWait[i] - stood
    return if (ahead < 0) minOf(-ahead, most).coerceIn(0, LATE_MOST.toLong()).toInt() else 0
}

/** Whether [v] is out running: not in a depot, stopped or wrecked. */
private fun running(v: Carrier) = when (v) {
    is Train -> v.running && !v.inDepot && v.wreckedFor == 0
    is RoadVehicle -> v.running && !v.inDepot && v.wreckedFor == 0
    is Ship -> v.running && !v.inDepot
    is Aircraft -> v.running && !v.inDepot && v.wreckedFor == 0
    else -> false
}

/**
 * Ticks round [s]'s orders: each order's travel and wait, planned where set and measured where not, or -1 while a
 * stop has neither for its travel. Conditional orders take no time.
 */
internal fun World.roundTrip(s: Service): Long {
    var sum = 0L
    for (i in s.orders.indices) {
        val o = s.orders[i]
        if (Order.isCond(o)) continue
        sum += when {
            s.planTravel[i] > 0 -> s.planTravel[i]
            s.measuredTravel[i] >= 0 -> s.measuredTravel[i]
            callsAt(o) -> return -1
            else -> 0
        }
        sum += when {
            s.planWait[i] > 0 -> s.planWait[i]
            s.measuredWait[i] >= 0 -> s.measuredWait[i]
            else -> 0
        }
    }
    return sum
}

/** Ticks between [s]'s vehicles when evenly spaced: the round trip over how many are running, or 0 with under two or no round trip yet. */
internal fun World.interval(s: Service): Long {
    val running = groupVehicles(s.kind).count { it != null && it.shared == s.shared && running(it) }
    if (running < 2) return 0
    val trip = roundTrip(s)
    return if (trip <= 0) 0 else trip / running
}

/** [v]'s service and its order index when it's standing at a stop of the service, else null. */
private fun World.standingAt(v: Carrier): Service? {
    if (v.shared < 0 || v.orderReachedAt < 0) return null
    val s = serviceOf(v) ?: return null
    val i = v.order
    if (s.orders.size != v.orders.size || i !in s.orders.indices || !callsAt(s.orders[i])) return null
    return s
}

/**
 * Ticks [v] stays at its stop to keep an interval behind the last of its spaced service to leave it, or 0. It
 * never stands more than an interval.
 */
internal fun World.spacingHold(v: Carrier): Int {
    val s = standingAt(v) ?: return 0
    if (!s.spaced) return 0
    val left = s.lastLeft[v.order]
    if (left < 0) return 0
    val interval = interval(s)
    if (interval <= 0) return 0
    val stood = tick - v.orderReachedAt
    return minOf(left + interval - tick, interval - stood).coerceIn(0, LATE_MOST.toLong()).toInt()
}

/** The last tick a vehicle of service [other] reached [station], or -1. */
private fun World.reachedBy(other: Int, station: Int): Long {
    val o = services[other] ?: return -1
    var last = -1L
    for (j in o.orders.indices) if (!Order.isCond(o.orders[j]) && Order.station(o.orders[j]) == station) last = maxOf(last, o.lastReached.getOrElse(j) { -1 })
    return last
}

/** Whether connection [c] of [s] is made for a vehicle that got to its stop at [reached]. */
private fun World.made(s: Service, c: Connection, reached: Long) = reachedBy(c.other, Order.station(s.orders[c.order])).let { it >= 0 && it >= reached - c.most }

/** Ticks [v] stays at its stop waiting for a connection not yet made, or 0 once each is made or waited out. */
internal fun World.connectionHold(v: Carrier): Int {
    val s = standingAt(v) ?: return 0
    var hold = 0L
    for (c in s.connections) {
        if (c.order != v.order || c.other !in services || made(s, c, v.orderReachedAt)) continue
        hold = maxOf(hold, v.orderReachedAt + c.most - tick)
    }
    return hold.coerceIn(0, LATE_MOST.toLong()).toInt()
}

/** [v] is leaving its stop: each connection there is counted made or missed. */
internal fun World.countConnections(v: Carrier) {
    val s = standingAt(v) ?: return
    for (c in s.connections) {
        if (c.order != v.order || c.other !in services) continue
        if (made(s, c, v.orderReachedAt)) c.made++ else c.missed++
    }
}

/**
 * A shared id for [v] to start a group with: its own id unless a vehicle of its kind already shares that, else the
 * lowest one none does. Any service left at that id is dropped.
 */
internal fun World.freshShared(v: Carrier): Int {
    val kind = kindOf(v)
    val used = HashSet<Int>()
    for (g in groupVehicles(kind)) if (g != null && g.shared >= 0) used += g.shared
    var id = v.id
    if (id in used) {
        id = 0
        while (id in used) id++
    }
    if (services.remove(serviceKey(kind, id)) != null) dropLostConnections()
    return id
}

/** [ticks] rounded up to a whole tenth of a day, as the timetable shows them. */
internal fun roundUpTenth(ticks: Int): Int {
    if (ticks <= 0) return 0
    val tenths = (ticks.toLong() * 10 + Calendar.TICKS_PER_DAY - 1) / Calendar.TICKS_PER_DAY
    return ((tenths * Calendar.TICKS_PER_DAY + 9) / 10).toInt().coerceAtMost(MAX_LINK_TICKS)
}

/** A command on the service of the vehicle it names: made for it when there's none, a lone vehicle starting one of its own. */
internal fun World.serviceCommand(company: Company, c: Command.ServiceOrder) {
    val v = groupVehicles(c.kind).getOrNull(c.vehicle) ?: return
    if (v.owner != company.id) return
    if (when (v) {
        is Train -> v.wreckedFor > 0
        is RoadVehicle -> v.wreckedFor > 0
        is Aircraft -> v.wreckedFor > 0
        else -> false
    }) return
    if (v.shared < 0) {
        // A service of one, as sharing with itself.
        v.shared = freshShared(v)
        restartTimetable(v)
    }
    val s = serviceFor(v) ?: return
    fit(s, v.orders)
    val n = s.orders.size
    when (c.what) {
        Command.ServiceOrder.START -> {}
        Command.ServiceOrder.NAME -> s.name = capName(c.name.trim(), SERVICE_NAME_MOST).trim()
        Command.ServiceOrder.TIMETABLE -> {
            val on = c.values.firstOrNull() == 1
            if (on && !s.timetabled) for (g in groupVehicles(c.kind)) if (g != null && g.shared == s.shared) g.late = 0
            s.timetabled = on
        }
        Command.ServiceOrder.AUTOFILL -> for (i in 0 until n) {
            if (s.measuredTravel[i] >= 0) s.planTravel[i] = roundUpTenth(s.measuredTravel[i])
            if (!callsAt(s.orders[i])) s.planWait[i] = 0
            else if (s.measuredWait[i] >= 0) s.planWait[i] = roundUpTenth(maxOf(s.measuredWait[i], Order.waitDays(s.orders[i]) * Calendar.TICKS_PER_DAY))
        }
        Command.ServiceOrder.CLEAR -> {
            s.planTravel.fill(0)
            s.planWait.fill(0)
        }
        Command.ServiceOrder.PLAN -> {
            val i = c.values.getOrNull(0) ?: return
            if (i !in 0 until n || Order.isCond(s.orders[i])) return
            c.values.getOrNull(1)?.let { if (it >= 0) s.planTravel[i] = it.coerceAtMost(MAX_LINK_TICKS) }
            c.values.getOrNull(2)?.let { if (it >= 0 && callsAt(s.orders[i])) s.planWait[i] = it.coerceAtMost(MAX_LINK_TICKS) }
        }
        Command.ServiceOrder.SPACED -> s.spaced = c.values.firstOrNull() == 1
        Command.ServiceOrder.CONNECT -> {
            if (c.values.size < 3) return
            val i = c.values[0]
            val other = services[c.values[1]] ?: return
            val most = c.values[2]
            if (i !in 0 until n || !callsAt(s.orders[i]) || other === s || other.owner != company.id || most !in 1..CONNECTION_WAIT_MOST) return
            val had = s.connections.firstOrNull { it.order == i && it.other == other.key }
            if (had != null) had.most = most
            else if (s.connections.size < CONNECTIONS_MOST) {
                s.connections += Connection(i, other.key, most)
                s.connections.sortWith(compareBy({ it.order }, { it.other }))
            }
        }
        Command.ServiceOrder.DISCONNECT -> {
            if (c.values.size < 2) return
            s.connections.removeAll { it.order == c.values[0] && it.other == c.values[1] }
        }
    }
}

/** Sets [company]'s value [Command.CompanyValue.index] and its name, each left as it is when not given. */
internal fun World.companyValue(company: Company, c: Command.CompanyValue) {
    if (c.index !in 0 until COMPANY_VALUES) return
    if (c.value in 0..Order.COND_VALUE_MOST) company.values[c.index] = c.value
    c.name?.let { company.valueNames[c.index] = capName(it.trim(), COMPANY_VALUE_NAME_MOST).trim() }
}

/** A service as the screen shows it, for the player's company; times in ticks as [Service] keeps them. */
class ServiceView(
    val id: Int,
    val kind: Int,
    val owner: Int,
    val name: String,
    val orders: IntArray,
    val planTravel: IntArray,
    val planWait: IntArray,
    val measuredTravel: IntArray,
    val measuredWait: IntArray,
    val timetabled: Boolean,
    val vehicleIds: IntArray,
    /** Ticks late, parallel to [vehicleIds]; less than 0 is early. */
    val late: IntArray,
    val spaced: Boolean = false,
    /** Ticks between its vehicles when evenly spaced, 0 while it can't be worked out. */
    val interval: Long = 0,
    /** The tick a vehicle last left each order, -1 for none. */
    val lastLeft: LongArray = LongArray(0),
    val connections: List<ConnectionView> = emptyList(),
)

/** A connection as the screen shows it: at [order], waits up to [most] ticks for service [other], made and missed last month. */
class ConnectionView(val order: Int, val other: Int, val otherName: String, val most: Int, val made: Int, val missed: Int)

/** The services of [company], sorted by key, each with its vehicles in id order. */
internal fun World.serviceViews(company: Int): List<ServiceView> {
    if (services.isEmpty()) return emptyList()
    val members = HashMap<Int, ArrayList<Carrier>>()
    for (list in listOf<List<Carrier?>>(trains, roadVehicles, ships, aircraft)) for (v in list) {
        if (v != null && v.shared >= 0 && v.owner == company) members.getOrPut(serviceKey(kindOf(v), v.shared)) { ArrayList() } += v
    }
    val out = ArrayList<ServiceView>()
    for (k in services.keys.sorted()) {
        val s = services.getValue(k)
        if (s.owner != company) continue
        val vs = members[k].orEmpty()
        out += ServiceView(
            k, s.kind, s.owner, s.name, s.orders.copyOf(), s.planTravel.copyOf(), s.planWait.copyOf(),
            s.measuredTravel.copyOf(), s.measuredWait.copyOf(), s.timetabled,
            IntArray(vs.size) { vs[it].id }, IntArray(vs.size) { vs[it].late },
            s.spaced, interval(s), s.lastLeft.copyOf(),
            s.connections.map { ConnectionView(it.order, it.other, services[it.other]?.name ?: "", it.most, it.madeLast, it.missedLast) },
        )
    }
    return out
}
