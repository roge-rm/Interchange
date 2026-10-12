package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpkeepTest {
    private fun line(): World {
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        val e = w.roster.engines.first { it.key == "base:american" }
        w.apply(Command.BuyTrain(0, 0, e.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        return w
    }

    @Test
    fun trainsWearBreakDownAndAreServicedBackToTheirBest() {
        val w = line()
        val t = w.trains[0]!!
        assertEquals(8500, t.reliability)
        var serviced = false
        var broke = false
        repeat(Calendar.TICKS_PER_DAY * (SERVICE_DAYS + 60)) {
            w.step()
            if (t.servicing) serviced = true
            if (t.brokenFor > 0) broke = true
        }
        assertTrue(serviced, "never went for a service")
        assertTrue(broke || t.breakdowns > 0, "never broke down")
        assertTrue(w.day - t.serviced < 70, "last serviced on day ${t.serviced}")
        assertTrue(!t.inDepot && t.running, "didn't go back to work")
    }

    @Test
    fun anOldTrainIsWorthLessAndRenewingMakesItNew() {
        val w = line()
        val t = w.trains[0]!!
        val new = w.value(t)
        t.built = w.day - 40 * 365
        assertTrue(w.value(t) < new)
        assertTrue(w.bestReliability(t) < t.engineInfo.reliability * 100)
        // Back in the depot to renew it.
        t.service = false
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.TO_DEPOT))
        repeat(6000) { if (!t.inDepot) w.step() }
        assertTrue(t.inDepot)
        val money = w.companies[0].money
        val cost = w.price(t.price) - w.value(t)
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.RENEW))
        assertEquals(0, w.age(t))
        assertEquals(t.engineInfo.reliability * 100, t.reliability)
        assertEquals(money - cost, w.companies[0].money)
        assertEquals(new, w.value(t))
    }
}
