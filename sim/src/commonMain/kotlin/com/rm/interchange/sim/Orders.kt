package com.rm.interchange.sim

/**
 * An order is a station and what to do there, packed in an int: the station
 * id in the low 16 bits, then what to do as bits, the least days to stand
 * there in the next eight, then three more bits of what to do.
 *
 * With [DEPOT] set it's an order to a depot instead: the depot id in the low
 * 16 bits and its [depotKind] in the two above, the rest unused.
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

    /** The station order [o] goes to, or -1 for a depot order. */
    fun station(o: Int) = if (o and DEPOT != 0) -1 else o and 0xffff
    fun isDepot(o: Int) = o and DEPOT != 0
    /** The depot order [o] goes to, or -1 for a station order. */
    fun depot(o: Int) = if (o and DEPOT != 0) o and 0xffff else -1
    fun depotKind(o: Int) = (o ushr 16) and 3
    /** An order to depot [id] of [kind]. */
    fun toDepot(id: Int, kind: Int = DEPOT_VIA) = DEPOT or ((kind and 3) shl 16) or (id and 0xffff)

    /** The station of the first station order in [orders] from [from] on, round to the start, or -1 with none. */
    fun nextStation(orders: List<Int>, from: Int): Int {
        val n = orders.size
        for (k in 0 until n) Order.station(orders[(from + k).mod(n)]).let { if (it >= 0) return it }
        return -1
    }
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
 * Holds a loaded vehicle while it's early on its service's timetable, then until the latest departure on its list
 * is far enough behind it.
 */
internal fun World.waitForGap(v: Carrier, due: Boolean): Boolean {
    if (!due && !sentToDepot(v)) {
        // Held in chunks, so turning the timetable off or changing the service lets it go at the next check.
        val early = timetableHold(v)
        if (early > 0) {
            v.waiting = minOf(early, FULL_LOAD_RETRY)
            return true
        }
    }
    if (!due && v.gap > 0 && v.shared >= 0) {
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
        val carries = (0 until Cargo.COUNT).filter { v.capacity(it) > 0 }
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
    // Only trains run through stations.
    if (!Order.isDepot(o)) return (if (kind == Depots.RAIL) o else o and Order.VIA.inv()).takeIf { stations.getOrNull(Order.station(o))?.owner == company }
    val d = depots.getOrNull(Order.depot(o)) ?: return null
    val k = Order.depotKind(o)
    if (d.owner != company || d.kind != kind || k !in Order.DEPOT_KINDS) return null
    if (electric && !depotWired(d)) return null
    return Order.toDepot(d.id, k)
}
