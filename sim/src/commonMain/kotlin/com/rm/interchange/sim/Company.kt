package com.rm.interchange.sim

/** A player's company, or one run by the computer. Money is in whole dollars. */
class Company(val id: Int, var name: String, var money: Long) {
    /** Running costs owed short of a whole dollar a day, carried to the next. */
    var runningOwed = 0L
}

/** What things cost, in dollars. */
object Prices {
    const val TRACK = 120L
    const val TRACK_REMOVE = 20L
    /** Building a sloping tile up level under track. */
    const val FOUNDATION = 250L
    const val TERRAFORM = 60L
    const val PLATFORM = 600L
    const val DEPOT = 1_500L
    const val SIGNAL = 200L
    const val CLEAR = 50L
    /** Clearing a tree to build. */
    const val TREE = 40L
    const val START_MONEY = 250_000L
}
