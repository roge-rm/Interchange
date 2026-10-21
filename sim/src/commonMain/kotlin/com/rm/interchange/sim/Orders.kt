package com.rm.interchange.sim

/**
 * An order is a station and what to do there, packed in an int: the station
 * id in the low 16 bits, then what to do as bits, the least days to stand
 * there in the next eight, then three more bits of what to do.
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

    fun station(o: Int) = o and 0xffff
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

/** Holds a loaded vehicle until the latest departure on its list is far enough behind it. */
internal fun World.waitForGap(v: Carrier, due: Boolean): Boolean {
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
    return false
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
