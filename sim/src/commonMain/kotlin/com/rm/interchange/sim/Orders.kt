package com.rm.interchange.sim

/**
 * An order is a station and what to do there, packed in an int: the station
 * id in the low 16 bits, then what to do as bits, the least days to stand
 * there in the next eight, then two more bits of what to do.
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
