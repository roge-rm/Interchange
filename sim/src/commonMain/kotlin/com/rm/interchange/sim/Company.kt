package com.rm.interchange.sim

/** A player's company, or one run by the computer. Money is in whole dollars. */
class Company(val id: Int, var name: String, var money: Long)

/** What things cost, in dollars. */
object Prices {
    const val TRACK = 120L
    const val TRACK_REMOVE = 20L
    /** Building a sloping tile up level under track. */
    const val FOUNDATION = 250L
    const val TERRAFORM = 60L
    const val START_MONEY = 250_000L
}
