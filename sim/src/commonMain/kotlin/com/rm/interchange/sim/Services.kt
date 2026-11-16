package com.rm.interchange.sim

const val SERVICE_NAME_MOST = GROUP_NAME_MOST

/** The most a vehicle can be late or early, in ticks, so it never overflows. */
const val LATE_MOST = MAX_LINK_TICKS

/**
 * The vehicles of one [kind] sharing their orders, by their [shared] id, and its timetable. The arrays run parallel
 * to [orders], the orders the times were taken for: planned times in ticks with 0 for none, measured ones with -1
 * for none.
 */
class Service(val kind: Int, val shared: Int, var owner: Int) {
    var name = ""
    var timetabled = false
    var orders = IntArray(0)
    var planTravel = IntArray(0)
    var planWait = IntArray(0)
    var measuredTravel = IntArray(0)
    var measuredWait = IntArray(0)

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
private fun orderPlace(o: Int) = if (Order.isDepot(o)) o and (Order.DEPOT or 0xffff) else o and 0xffff

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
    for (k in 0 until n) {
        val f = from[k]
        if (f < 0) continue
        planWait[k] = s.planWait[f]
        measuredWait[k] = s.measuredWait[f]
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
    if (groupVehicles(kind).none { it != null && it.shared == shared }) services.remove(key)
}

/** Each work day: services no vehicle shares any more are dropped. */
internal fun World.servicesDay() {
    if (services.isEmpty()) return
    val held = HashSet<Int>()
    for (list in listOf<List<Carrier?>>(trains, roadVehicles, ships, aircraft)) for (v in list) if (v != null && v.shared >= 0) held += serviceKey(kindOf(v), v.shared)
    services.keys.removeAll { it !in held }
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
    if (s.timetabled && s.planWait[i] > 0 && callsAt(s.orders[i])) v.late = lateBy(v.late, wait - s.planWait[i])
    v.orderLeft = i
    v.orderLeftAt = tick
}

/** Whether order [o] stands at a station, so a planned wait counts there: not a depot, a via order or a waypoint. */
internal fun World.callsAt(o: Int) = !Order.isDepot(o) && !Order.has(o, Order.VIA) && stations.getOrNull(Order.station(o))?.waypoint != true

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
    services.remove(serviceKey(kind, id))
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
            if (i !in 0 until n) return
            c.values.getOrNull(1)?.let { if (it >= 0) s.planTravel[i] = it.coerceAtMost(MAX_LINK_TICKS) }
            c.values.getOrNull(2)?.let { if (it >= 0 && callsAt(s.orders[i])) s.planWait[i] = it.coerceAtMost(MAX_LINK_TICKS) }
        }
    }
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
)

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
        )
    }
    return out
}
