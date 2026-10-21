package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullLoadTest {
    /** A fresh train at the end of its first stand, with passengers full and mail empty. */
    private fun standing(flags: Int, wagons: Int = 2): Pair<World, Train> {
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        val engine = w.roster.engines.first { it.key == "base:american" }.id
        val coach = w.roster.engines.first { it.key == "base:coach_wood" }.id
        w.apply(Command.BuyTrain(0, 0, engine, coach, wagons))
        val t = w.trains[0]!!
        // Replace one coach with a mail car of the same length.
        if (wagons > 0) t.carEngines[t.carEngines.lastIndex] = w.roster.engines.first { it.key == "base:baggage_wood" }.id
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(flags, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        var ticks = 0
        while (t.waiting == 0 && ticks++ < 10_000) w.step()
        assertTrue(t.waiting > 0, "never reached the first station")
        repeat(t.waiting - 1) { w.step() }
        assertEquals(1, t.waiting)
        assertEquals(0, t.order)
        assertFalse(w.due(t.service, t.serviced))
        if (wagons > 0) {
            assertTrue(t.capacity(Cargo.PASSENGERS) > 0)
            assertTrue(t.capacity(Cargo.MAIL) > 0)
            t.cargo[Cargo.PASSENGERS] += Packet(1, 0, t.capacity(Cargo.PASSENGERS), w.tick)
            assertEquals(0, t.load(Cargo.MAIL))
        }
        return w to t
    }

    @Test
    fun fullLoadWaitsForBothCargos() {
        val (w, t) = standing(Order.FULL_LOAD)
        w.step()
        assertEquals(0, t.order)
        assertEquals(FULL_LOAD_RETRY, t.waiting)
    }

    @Test
    fun anyCargoLeavesWithPassengersFullAndMailEmpty() {
        val (w, t) = standing(Order.FULL_LOAD_ANY)
        w.step()
        assertEquals(1, t.order)
        assertEquals(0, t.waiting)
    }

    @Test
    fun usualLoadingLeavesWhenTheStandEnds() {
        val (w, t) = standing(0)
        w.step()
        assertEquals(1, t.order)
        assertEquals(0, t.waiting)
    }

    @Test
    fun fullLoadWinsWhenBothFlagsAreSet() {
        val (w, t) = standing(Order.FULL_LOAD or Order.FULL_LOAD_ANY)
        w.step()
        assertEquals(0, t.order)
        assertEquals(FULL_LOAD_RETRY, t.waiting)
    }

    @Test
    fun anyCargoWaitsWhenNeitherCargoIsFull() {
        val (w, t) = standing(Order.FULL_LOAD_ANY)
        t.cargo[Cargo.PASSENGERS].single().count--
        w.step()
        assertEquals(0, t.order)
        assertEquals(FULL_LOAD_RETRY, t.waiting)
    }

    @Test
    fun anyCargoLeavesWhenTheTrainHasNoCapacity() {
        val (w, t) = standing(Order.FULL_LOAD_ANY, wagons = 0)
        assertTrue((0 until Cargo.COUNT).all { t.capacity(it) == 0 })
        w.step()
        assertEquals(1, t.order)
        assertEquals(0, t.waiting)
    }

    @Test
    fun anyCargoLeavesForServiceEvenWhenNeitherCargoIsFull() {
        val (w, t) = standing(Order.FULL_LOAD_ANY)
        t.cargo[Cargo.PASSENGERS].clear()
        t.serviced = w.workDay - SERVICE_DAYS
        w.step()
        assertEquals(1, t.order)
        assertEquals(0, t.waiting)
        assertTrue(t.servicing)
    }
}
