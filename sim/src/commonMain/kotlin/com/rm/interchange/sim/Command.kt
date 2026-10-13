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

    /** Builds a bridge of [style] from head ([x0], [y0]) to head ([x1], [y1]). */
    data class Bridge(override val company: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val style: Int) : Command

    /** Digs a tunnel into the hill from the portal at ([x], [y]), through to where it comes out. */
    data class Tunnel(override val company: Int, val x: Int, val y: Int) : Command

    /** Hangs wires over a line of track, as [Rail]'s line goes, or takes them down; a bridge or tunnel on the line is wired whole. */
    data class Wire(override val company: Int, val sx: Int, val sy: Int, val ex: Int, val ey: Int, val first: Int, val remove: Boolean) : Command

    /** Lays road of [surface] along a straight line, or lifts the company's own. */
    data class Road(override val company: Int, val sx: Int, val sy: Int, val ex: Int, val ey: Int, val surface: Int, val remove: Boolean, val tram: Boolean = false) : Command

    /** Turns a straight road one-way, the other way, or back to both. */
    data class RoadOneWay(override val company: Int, val x: Int, val y: Int) : Command

    /** A drive-through road stop on straight road. */
    data class RoadStop(override val company: Int, val x: Int, val y: Int) : Command

    /** A garage for road vehicles, its door at [edge] onto a road. */
    data class Garage(override val company: Int, val x: Int, val y: Int, val edge: Int) : Command

    /** Buys road vehicle [model] in a garage, set to carry [cargo] if it can. */
    data class BuyRoadVehicle(override val company: Int, val depot: Int, val model: Int, val cargo: Int = -1) : Command

    /** Tells a road vehicle what to do, as [TrainOrder] does a train, or turns its seasonal tires on or off. */
    class RoadOrder(override val company: Int, val vehicle: Int, val what: Int, val stations: IntArray = IntArray(0)) : Command {
        companion object {
            const val TIRES = 20
        }
    }

    /** Builds a salt depot by the company's road, or takes it down. */
    data class SaltDepot(override val company: Int, val x: Int, val y: Int) : Command

    /** Digs a canal along a straight line on flat land, or fills in the company's own. */
    data class Canal(override val company: Int, val sx: Int, val sy: Int, val ex: Int, val ey: Int, val remove: Boolean) : Command

    /** A lock on a straight slope, lifting ships a step. */
    data class Lock(override val company: Int, val x: Int, val y: Int) : Command

    /** A dock on the shore, facing the water. */
    data class Dock(override val company: Int, val x: Int, val y: Int) : Command

    /** A shipyard on the shore, launching onto the water in front. */
    data class Shipyard(override val company: Int, val x: Int, val y: Int) : Command

    /** A buoy on open water, a waypoint for ships. */
    data class Buoy(override val company: Int, val x: Int, val y: Int) : Command

    /** An airport of [kind] ([Airports]) with its corner at ([x], [y]), its runway along [axis]. */
    data class Airport(override val company: Int, val x: Int, val y: Int, val kind: Int, val axis: Int) : Command

    /** Adds landing aids ([what] 0) or de-icing (1) to an airport. */
    data class AirportUpgrade(override val company: Int, val airport: Int, val what: Int) : Command

    /** Buys a ship at a shipyard, or an aircraft in a hangar, set to carry [cargo] if it can. */
    data class BuyCraft(override val company: Int, val depot: Int, val model: Int, val cargo: Int = -1) : Command

    /** Tells a ship ([aircraft] false) or an aircraft what to do, as [TrainOrder] does a train, or turns a bush plane's seasonal gear on or off. */
    class CraftOrder(override val company: Int, val aircraft: Boolean, val vehicle: Int, val what: Int, val stations: IntArray = IntArray(0)) : Command {
        companion object {
            const val GEAR = 21
        }
    }

    /** Puts up a snow shed over track at ([x], [y]), or a snow fence on open land there; again takes it down. */
    data class SnowWorks(override val company: Int, val x: Int, val y: Int, val shed: Boolean) : Command

    /** How much the company spends keeping its track up, an [Upkeep] level. */
    data class SetUpkeep(override val company: Int, val level: Int) : Command

    /** Marks a tile of straight track as a waypoint, for orders to route trains through. */
    data class Waypoint(override val company: Int, val x: Int, val y: Int) : Command

    /** Clears a tile of all the company's own things on it, its trees, or a town building on it. */
    data class Clear(override val company: Int, val x: Int, val y: Int) : Command

    /** Clears every tile from ([x0], [y0]) to ([x1], [y1]) as [Clear] does one. */
    data class ClearArea(override val company: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int) : Command

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
