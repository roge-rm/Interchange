package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodBuyTest {
    private fun id(w: World, key: String) = w.roster.engines.first { it.key == key }.id

    /** A 2080 tube or rail line along y = 10 with a depot at x 2 and stations at either end, and maybe a stray depot below it. */
    private fun line(stray: Boolean, tube: Boolean = true): World {
        val w = World(NewGame(128, 128, 1, 2080, towns = 0, industries = 0, weather = false, handling = false, eraCosts = false, future = true))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 1_000_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP, tube = tube))
        if (stray) w.apply(Command.Depot(0, 2, 11, Track.XP, tube = tube))
        w.apply(Command.Rail(0, 3, 10, 120, 10, Track.X, remove = false, tube = tube))
        w.apply(Command.Station(0, 10, 10, 14, 10, Track.X, tube = tube))
        w.apply(Command.Station(0, 106, 10, 110, 10, Track.X, tube = tube))
        return w
    }

    /** Buys a single pod, as the depot card does, in [depot]. */
    private fun buyPod(w: World, depot: Int = 0, inherit: Boolean = false): Train {
        val n = w.trains.size
        w.apply(Command.BuyTrain(0, depot, id(w, "base:vela"), id(w, "base:aurora_coach"), 0, inherit = inherit))
        return w.trains[n]!!
    }

    private fun start(w: World, t: Train, orders: Boolean = true) {
        if (orders) w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.START_STOP))
    }

    private fun runs(w: World, t: Train): Boolean {
        var moved = false
        repeat(3_000) { w.step(); if (!t.inDepot && t.speed > 0) moved = true }
        return moved
    }

    @Test
    fun aPodWithNoWagonsSetsOut() {
        val w = line(false)
        val t = buyPod(w)
        start(w, t)
        assertTrue(runs(w, t))
    }

    @Test
    fun aPodGivenTheSoldOnesOrdersSetsOut() {
        for (stray in listOf(false, true)) {
            val w = line(stray)
            val old = buyPod(w)
            start(w, old)
            assertTrue(runs(w, old))
            w.apply(Command.TrainOrder(0, old.id, Command.TrainOrder.TO_DEPOT))
            var i = 0
            while (!old.inDepot && i++ < 30_000) w.step()
            assertTrue(old.inDepot)
            assertEquals(0, old.depot)
            w.apply(Command.TrainOrder(0, old.id, Command.TrainOrder.SELL))
            assertNull(w.trains[old.id])
            val t = buyPod(w, inherit = true)
            assertEquals(listOf(0, 1), t.orders.toList())
            start(w, t, orders = false)
            assertTrue(runs(w, t), "stray $stray")
        }
    }

    @Test
    fun aPodSetsOutWithAnotherRunning() {
        val w = line(false)
        val a = buyPod(w)
        start(w, a)
        repeat(1_000) { w.step() }
        val b = buyPod(w)
        start(w, b)
        assertTrue(runs(w, b))
    }

    @Test
    fun aPodInAStrayDepotStandsInItsDoorAsATrainDoes() {
        for (tube in listOf(true, false)) {
            val w = line(true, tube)
            if (tube) buyPod(w, depot = 1)
            else {
                val engine = w.roster.engines.first { it.pulls && it.trackKind == TrackKind.RAIL && it.traction != Traction.ELECTRIC && w.onSale(it) }
                val coach = w.roster.engines.first { it.kind == VehicleKind.WAGON && Cargo.PASSENGERS in it.cargoes && w.onSale(it) }
                w.apply(Command.BuyTrain(0, 1, engine.id, coach.id, 2))
            }
            val t = w.trains[0]!!
            start(w, t)
            repeat(3_000) { w.step() }
            assertFalse(t.inDepot)
            assertEquals(0, t.speed)
            assertEquals(1, t.steps.size)
            assertNull(t.route)
            // Seen standing, then shown lost once it's stood a while.
            w.view()
            repeat(16) { w.step() }
            assertTrue(w.view().trains.first { it.id == t.id }.lost, "tube $tube")
        }
    }

    @Test
    fun aTrainOnItsRunsIsNeverShownLost() {
        val w = line(false)
        val t = buyPod(w)
        start(w, t)
        var calls = 0
        var was = false
        repeat(20_000) {
            w.step()
            val v = w.view().trains.first { it.id == t.id }
            assertFalse(v.lost, "tick ${w.tick}")
            if (v.waiting && !was) calls++
            was = v.waiting
        }
        assertTrue(calls >= 2)
    }
}
