package com.rm.interchange.sim

/**
 * An order is a station and what to do there, packed in an int: the station
 * id in the low 16 bits, then what to do as bits, the least days to stand
 * there in the next eight, then three more bits of what to do.
 *
 * With [DEPOT] set it's an order to a depot instead: the depot id in the low
 * 16 bits and its [depotKind] in the two above, the rest unused.
 *
 * With [DEPOT] and [VIA] both set ([COND]) it's a conditional order: the
 * order to jump to in bits 0-7, the value in 8-19, the [comparison][condCompare]
 * in 20-22 and [what][condWhat] is tested in 23-26. It isn't a stop: a vehicle
 * coming to one works it out at once, see [World.settleOrder].
 */
object Order {
    /** Wait until full of everything the train can carry. */
    const val FULL_LOAD = 1 shl 16
    /** Everything gets off: paid where the station takes it, left to change trains where it doesn't. */
    const val UNLOAD = 1 shl 17
    /** Everything gets off to change trains, the train credited for its leg. */
    const val TRANSFER = 1 shl 18
    /** Take nothing on here. */
    const val NO_LOAD = 1 shl 19
    /** Run through the station without stopping, on to the next. */
    const val VIA = 1 shl 28
    /** Leave this stop out when there's nothing to set down or take on there. */
    const val SKIP_IF_NOTHING = 1 shl 29
    /** Wait until full of any one cargo the vehicle can carry. */
    const val FULL_LOAD_ANY = 1 shl 30
    const val MAX_WAIT_DAYS = 255

    /** An order to a depot, not a station. */
    const val DEPOT = 1 shl 31
    /** What a depot order does: in and straight out again, the same only when due a service, or in to stop there. */
    const val DEPOT_VIA = 0
    const val DEPOT_SERVICE = 1
    const val DEPOT_STOP = 2
    val DEPOT_KINDS = intArrayOf(DEPOT_VIA, DEPOT_SERVICE, DEPOT_STOP)

    /** A conditional order: [DEPOT] and [VIA] both set. */
    const val COND = DEPOT or VIA
    /** What a conditional order tests: load percent, age in years, reliability percent, work days since a service, always, or company value k at [IF_VALUE] + k. */
    const val IF_LOAD = 0
    const val IF_AGE = 1
    const val IF_RELIABILITY = 2
    const val IF_SINCE_SERVICE = 3
    const val IF_ALWAYS = 4
    const val IF_VALUE = 8
    /** How a conditional order compares: less, at most, equal, not equal, at least, more. */
    const val LESS = 0
    const val AT_MOST = 1
    const val EQUAL = 2
    const val NOT_EQUAL = 3
    const val AT_LEAST = 4
    const val MORE = 5
    const val COND_VALUE_MOST = 4095
    /** Conditional orders jump by an 8-bit order index. */
    const val COND_TARGET_MOST = 255
    /** The most orders a vehicle takes, so every jump fits in its 8 bits. */
    const val ORDERS_MOST = COND_TARGET_MOST + 1

    /** The station order [o] goes to, or -1 for a depot or conditional order. */
    fun station(o: Int) = if (o and DEPOT != 0) -1 else o and 0xffff
    fun isDepot(o: Int) = o and COND == DEPOT
    fun isCond(o: Int) = o and COND == COND
    /** The depot order [o] goes to, or -1 for a station or conditional order. */
    fun depot(o: Int) = if (isDepot(o)) o and 0xffff else -1
    fun depotKind(o: Int) = (o ushr 16) and 3
    /** An order to depot [id] of [kind]. */
    fun toDepot(id: Int, kind: Int = DEPOT_VIA) = DEPOT or ((kind and 3) shl 16) or (id and 0xffff)

    /** A conditional order: if [what] compares by [compare] with [value], go to order [target]. */
    fun cond(what: Int, compare: Int, value: Int, target: Int) =
        COND or ((what and 15) shl 23) or ((compare and 7) shl 20) or ((value and 0xfff) shl 8) or (target and 0xff)
    fun condWhat(o: Int) = (o ushr 23) and 15
    fun condCompare(o: Int) = (o ushr 20) and 7
    fun condValue(o: Int) = (o ushr 8) and 0xfff
    fun condTarget(o: Int) = o and 0xff
    /** [o] with its jump going to [target] instead. */
    fun withTarget(o: Int, target: Int) = (o and 0xff.inv()) or (target and 0xff)

    /** Conditional order [o] with stray bits cleared, or null if what it tests or how isn't one there is. */
    fun cleanCond(o: Int): Int? {
        val what = condWhat(o)
        if (what !in IF_LOAD..IF_ALWAYS && what !in IF_VALUE until IF_VALUE + COMPANY_VALUES) return null
        if (condCompare(o) !in LESS..MORE) return null
        return cond(what, condCompare(o), condValue(o), condTarget(o))
    }

    /** Whether [x] compares with [value] by [compare]. */
    fun compare(x: Int, compare: Int, value: Int) = when (compare) {
        LESS -> x < value
        AT_MOST -> x <= value
        EQUAL -> x == value
        NOT_EQUAL -> x != value
        AT_LEAST -> x >= value
        else -> x > value
    }

    /**
     * [orders] each passed through [keep], left out where it gives null, with the conditional orders' jumps moved to
     * follow: one to an order left out goes to the order after it. A conditional order jumping past the end is left
     * out, as is every order past [ORDERS_MOST], and with no other order left none of them are kept.
     */
    fun remapped(orders: List<Int>, keep: (Int) -> Int? = { it }): List<Int> {
        val n = orders.size
        val kept = arrayOfNulls<Int>(n)
        for (i in 0 until n) {
            val o = orders[i]
            kept[i] = if (i >= ORDERS_MOST || isCond(o) && condTarget(o) >= n) null else keep(o)
        }
        // Where each old index lands: the next kept order at or after it.
        val at = IntArray(n + 1)
        var k = 0
        for (i in 0 until n) { at[i] = k; if (kept[i] != null) k++ }
        at[n] = k
        val out = ArrayList<Int>(k)
        for (i in 0 until n) {
            val o = kept[i] ?: continue
            out += if (isCond(o)) withTarget(o, if (k == 0) 0 else at[condTarget(o)] % k) else o
        }
        if (out.all { isCond(it) }) return emptyList()
        return out
    }

    /** The station of the first station order in [orders] from [from] on, round to the start, or -1 with none. */
    fun nextStation(orders: List<Int>, from: Int): Int {
        val n = orders.size
        for (k in 0 until n) Order.station(orders[(from + k).mod(n)]).let { if (it >= 0) return it }
        return -1
    }
    /** How many of [orders] go to a station: depot and conditional orders aren't stops. */
    fun stops(orders: List<Int>) = orders.count { station(it) >= 0 }
    fun has(o: Int, flag: Int) = o and flag != 0
    fun waitDays(o: Int) = (o ushr 20) and 0xff
    fun withWait(o: Int, days: Int) = (o and (0xff shl 20).inv()) or (days.coerceIn(0, MAX_WAIT_DAYS) shl 20)

    /** [o] with [flag] turned on or off; unloading and transferring rule each other out. */
    fun toggle(o: Int, flag: Int): Int {
        val on = o xor flag
        return when {
            flag == UNLOAD && has(on, UNLOAD) -> on and TRANSFER.inv()
            flag == TRANSFER && has(on, TRANSFER) -> on and UNLOAD.inv()
            else -> on
        }
    }
}

/**
 * Holds a loaded vehicle while it's early on its service's timetable, kept back for even spacing or waiting for a
 * connection, then until the latest departure on its list is far enough behind it.
 */
internal fun World.waitForGap(v: Carrier, due: Boolean): Boolean {
    if (!due && !sentToDepot(v)) {
        // Held in chunks, so turning the timetable off or changing the service lets it go at the next check.
        val early = maxOf(timetableHold(v), spacingHold(v), connectionHold(v))
        if (early > 0) {
            v.waiting = minOf(early, FULL_LOAD_RETRY)
            return true
        }
    }
    // Spacing stands in for the gap hold only once it knows its interval.
    if (!due && v.gap > 0 && v.shared >= 0 && serviceOf(v)?.let { it.spaced && interval(it) > 0 } != true) {
        val group: List<Carrier?> = when (v) {
            is Train -> trains
            is RoadVehicle -> roadVehicles
            is Ship -> ships
            is Aircraft -> aircraft
            else -> emptyList()
        }
        val last = group.asSequence().filterNotNull().filter { it !== v && it.shared == v.shared }.maxOfOrNull { it.departed } ?: 0L
        if (last > 0) {
            val left = last + v.gap * Calendar.TICKS_PER_DAY - tick
            if (left > 0) {
                v.waiting = left.toInt()
                return true
            }
        }
    }
    v.departed = tick
    countConnections(v)
    if (v.leftFrom >= 0 && v.leftAt < 0) {
        v.leftAt = tick
        v.heldTicks = 0
        countDeparture(v)
    }
    return false
}

/** Whether [v] has been sent to a depot. */
private fun sentToDepot(v: Carrier) = when (v) {
    is Train -> v.toDepot
    is RoadVehicle -> v.toDepot
    is Ship -> v.toDepot
    is Aircraft -> v.toDepot
    else -> false
}

/** Ships and aircraft retry loading before considering their departure gap. */
internal fun World.waitForLoad(v: Carrier, o: Int): Boolean {
    fun loaded(): Boolean {
        val carries = (0 until cargoCount).filter { v.capacity(it) > 0 }
        return when {
            Order.has(o, Order.FULL_LOAD) -> carries.all { v.load(it) >= v.capacity(it) }
            Order.has(o, Order.FULL_LOAD_ANY) -> carries.isEmpty() || carries.any { v.load(it) >= v.capacity(it) }
            else -> true
        }
    }
    val s = stations.getOrNull(Order.station(o)) ?: return false
    if (loaded()) return false
    loadAt(v, s)
    if (loaded()) return false
    v.waiting = FULL_LOAD_RETRY
    return true
}

/**
 * Order [o] as [company] may give it, stray bits cleared, or null if it may not: a station of its own, or one of its
 * depots of [kind], with wires over it for an [electric] train.
 */
internal fun World.allowedOrder(o: Int, company: Int, kind: Int, electric: Boolean = false): Int? {
    if (Order.isCond(o)) return Order.cleanCond(o)
    // Only trains run through stations.
    if (!Order.isDepot(o)) return (if (kind == Depots.RAIL) o else o and Order.VIA.inv()).takeIf { stations.getOrNull(Order.station(o))?.owner == company }
    val d = depots.getOrNull(Order.depot(o)) ?: return null
    val k = Order.depotKind(o)
    if (d.owner != company || d.kind != kind || k !in Order.DEPOT_KINDS) return null
    if (electric && !depotWired(d)) return null
    return Order.toDepot(d.id, k)
}

/**
 * [raw] orders from a command as [company] may give them to a vehicle of depot [kind], with conditional jumps
 * following any left out; ships and aircraft ([depotOrders] false) take no depot orders.
 */
internal fun World.allowedOrders(raw: IntArray, company: Int, kind: Int, electric: Boolean = false, depotOrders: Boolean = true): List<Int> =
    Order.remapped(raw.toList()) { o -> if (!depotOrders && Order.isDepot(o)) null else allowedOrder(o, company, kind, electric) }

/** Takes out of [v]'s orders those [drop] picks, conditional jumps following; true if any went. */
internal fun removeOrders(v: Carrier, drop: (Int) -> Boolean): Boolean {
    if (v.orders.none { !Order.isCond(it) && drop(it) }) return false
    val kept = Order.remapped(v.orders) { if (!Order.isCond(it) && drop(it)) null else it }
    v.orders.clear()
    v.orders += kept
    return true
}

/** Load percent, age in years, reliability percent, days since its last service, or a company value, as [what] asks. */
private fun World.condValueOf(v: Carrier, what: Int): Int {
    val built: Int
    val serviced: Int
    val reliability: Int
    when (v) {
        is Train -> { built = v.built; serviced = v.serviced; reliability = v.reliability }
        is RoadVehicle -> { built = v.built; serviced = v.serviced; reliability = v.reliability }
        is Ship -> { built = v.built; serviced = v.serviced; reliability = v.reliability }
        is Aircraft -> { built = v.built; serviced = v.serviced; reliability = v.reliability }
        else -> { built = workDay; serviced = workDay; reliability = 0 }
    }
    return when (what) {
        Order.IF_LOAD -> {
            var load = 0L
            var room = 0L
            for (c in 0 until cargoCount) { load += v.load(c); room += v.capacity(c) }
            if (room <= 0) 0 else (load * 100 / room).toInt()
        }
        Order.IF_AGE -> (workDay - built).coerceAtLeast(0) / 365
        Order.IF_RELIABILITY -> reliability / 100
        Order.IF_SINCE_SERVICE -> (workDay - serviced).coerceAtLeast(0)
        else -> companies.getOrNull(v.owner)?.values?.getOrNull(what - Order.IF_VALUE) ?: 0
    }
}

/** Whether conditional order [o] holds for [v] as it stands. */
internal fun World.condHolds(v: Carrier, o: Int): Boolean {
    val what = Order.condWhat(o)
    if (what == Order.IF_ALWAYS) return true
    return Order.compare(condValueOf(v, what), Order.condCompare(o), Order.condValue(o))
}

/**
 * Moves [v] off any conditional order it's on: each is worked out at once, going to its target if it holds and on to
 * the next if not. After [Carrier.orders] steps, as a loop of them would take, it goes on to the next order that
 * isn't one. Timing carries over one it goes straight past, and stops at a jump.
 */
internal fun World.settleOrder(v: Carrier) {
    val n = v.orders.size
    if (n == 0 || !Order.isCond(v.orders[v.order.mod(n)])) return
    v.order = v.order.mod(n)
    var steps = 0
    while (Order.isCond(v.orders[v.order])) {
        val o = v.orders[v.order]
        if (steps++ >= n) {
            // A loop of conditions: on in order to the first that isn't one, or stay put if there's none.
            for (k in 1..n) if (!Order.isCond(v.orders[(v.order + k) % n])) { v.order = (v.order + k) % n; return }
            return
        }
        val target = Order.condTarget(o)
        if (condHolds(v, o) && target < n && target != (v.order + 1) % n) {
            v.orderLeft = -1
            v.orderLeftAt = -1
            v.order = target
        } else {
            if (v.orderLeftAt >= 0 && v.orderLeft == (v.order - 1).mod(n)) v.orderLeft = v.order
            v.order = (v.order + 1) % n
        }
    }
}

/** [v] on to its next order, past any conditional ones. */
internal fun World.stepOrder(v: Carrier) {
    val n = v.orders.size
    if (n == 0) return
    v.order = (v.order + 1) % n
    settleOrder(v)
}

/** [v] back to the start of its orders, past any conditional ones. */
internal fun World.firstOrder(v: Carrier) {
    v.order = 0
    settleOrder(v)
}
