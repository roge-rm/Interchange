package com.rm.interchange.sim

/**
 * Everything a player does to the world goes through a command, in single
 * player too. In a multiplayer game the server puts every player's commands in
 * order and each copy of the world runs them on the same tick.
 */
sealed interface Command {
    /** The company giving the command. */
    val company: Int

    /** Raises or lowers the corner ([x], [y]) a step, and the land round it as far as it has to. */
    data class Terraform(override val company: Int, val x: Int, val y: Int, val up: Boolean) : Command

    /** Lays or lifts a line of track, as [Track.line] makes it. */
    data class Rail(
        override val company: Int,
        val sx: Int, val sy: Int, val ex: Int, val ey: Int,
        val first: Int,
        val remove: Boolean,
    ) : Command

    /** Lays platforms over a rectangle, running along [axis]. */
    data class Station(override val company: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val axis: Int) : Command

    /** Builds a depot open at [edge]. */
    data class Depot(override val company: Int, val x: Int, val y: Int, val edge: Int) : Command

    /** Puts a signal of [kind] on a piece facing [facing], or changes the one there to it; the same again makes it one-way, then takes it away. */
    data class Signal(override val company: Int, val x: Int, val y: Int, val piece: Int, val facing: Int, val kind: Int = Signals.TWO_WAY) : Command

    /** Marks a tile of straight track as a waypoint, for orders to route trains through. */
    data class Waypoint(override val company: Int, val x: Int, val y: Int) : Command

    /** Clears a tile of track, signals, a platform or a depot. */
    data class Clear(override val company: Int, val x: Int, val y: Int) : Command

    /** Buys a train in a depot, its wagons set to carry [cargo] if they can, or their first. */
    data class BuyTrain(override val company: Int, val depot: Int, val engine: Int, val wagon: Int, val wagons: Int, val cargo: Int = -1) : Command

    /** Pays for a new industry of [kind] somewhere on the map: built for sure if it takes cargo, prospected if not. */
    data class FundIndustry(override val company: Int, val kind: Int) : Command

    /** Borrows a step more from the bank, or repays one. */
    data class Loan(override val company: Int, val borrow: Boolean) : Command

    /** Tells a train what to do; [stations] only for [ORDERS]. */
    class TrainOrder(override val company: Int, val train: Int, val what: Int, val stations: IntArray = IntArray(0)) : Command {
        companion object {
            const val START_STOP = 0
            const val ORDERS = 1
            const val TO_DEPOT = 2
            const val TURN = 3
            const val SELL = 4
            /** Share orders with the train in [stations]'s first place. */
            const val SHARE = 5
            const val UNSHARE = 6
            /** Turn servicing at the depot on or off. */
            const val SERVICE = 7
            /** Make it new again, in its depot, for its price less what it's worth. */
            const val RENEW = 8
        }
    }
}
