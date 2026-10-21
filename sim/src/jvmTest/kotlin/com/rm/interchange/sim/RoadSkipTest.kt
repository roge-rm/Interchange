package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoadSkipTest {
    /** A bus at the end of its wait at the first of three stops. */
    private fun standing(flags: Int = Order.SKIP_IF_NOTHING): Pair<World, RoadVehicle> {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        for (x in listOf(8, 23, 38)) w.apply(Command.RoadStop(0, x, 20))
        assertEquals(3, w.stations.size)
        val model = w.roster.engines.first { it.key == "base:new_look" }.id
        w.apply(Command.BuyRoadVehicle(0, 0, model))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1 or flags, 2)))
        val v = w.roadVehicles[0]!!
        v.inDepot = false
        v.running = true
        v.at = roadStep(w.index(8, 20), Track.XM, Track.XP)
        v.waiting = 1
        assertTrue(v.capacity(Cargo.PASSENGERS) > 0)
        return w to v
    }

    @Test
    fun aStopWithNothingToDoIsSkipped() {
        val (w, v) = standing()
        w.step()
        assertEquals(2, v.order)
        assertEquals(0, v.waiting)
    }

    @Test
    fun waitingCargoWithoutCapacityDoesNotKeepTheStop() {
        val (w, v) = standing()
        assertEquals(0, v.capacity(Cargo.MAIL))
        w.stations[1]!!.cargo[Cargo.MAIL] += Packet(2, 1, 10, w.tick)
        w.step()
        assertEquals(2, v.order)
    }

    @Test
    fun waitingCargoWithCapacityKeepsTheStop() {
        val (w, v) = standing()
        w.stations[1]!!.cargo[Cargo.PASSENGERS] += Packet(2, 1, 10, w.tick)
        w.step()
        assertEquals(1, v.order)
    }

    @Test
    fun anEmptyStopWithoutTheFlagIsKept() {
        val (w, v) = standing(flags = 0)
        w.step()
        assertEquals(1, v.order)
    }

    @Test
    fun cargoToSetDownKeepsTheStop() {
        val (w, v) = standing()
        v.cargo[Cargo.PASSENGERS] += Packet(1, 0, 10, w.tick)
        w.step()
        assertEquals(1, v.order)
    }

    @Test
    fun skippingEveryStopEndsAtTheStartingOrder() {
        val (w, v) = standing()
        for (i in v.orders.indices) v.orders[i] = v.orders[i] or Order.SKIP_IF_NOTHING
        v.order = 1
        v.at = roadStep(w.index(23, 20), Track.XM, Track.XP)
        w.step()
        assertEquals(1, v.order)
        assertEquals(0, v.waiting)
        assertEquals(1, v.target)
    }
}
