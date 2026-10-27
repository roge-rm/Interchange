package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DualModeTest {
    private fun alp45(w: World) = w.roster.engines.first { it.key == "base:alp45" }
    private fun coach(w: World) = w.roster.engines.first { it.key == "base:coach_streamline" }.id

    /** A straight line 240 tiles long with a station at each end, wired to x [wiredTo] if [wired]. */
    private fun line(wired: Boolean, wiredTo: Int = 245): World {
        val w = World(256, 16, 1, startYear = 2015)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        w.apply(Command.Depot(0, 2, 8, Track.XP))
        w.apply(Command.Rail(0, 3, 8, 245, 8, Track.X, remove = false))
        w.apply(Command.Station(0, 4, 8, 6, 8, Track.X))
        w.apply(Command.Station(0, 242, 8, 245, 8, Track.X))
        if (wired) w.apply(Command.Wire(0, 2, 8, wiredTo, 8, Track.X, remove = false))
        return w
    }

    /** The fastest it goes past x [past] on the way to the far end, in km/h, and whether it got there. */
    private fun run(w: World, past: Int = 0): Pair<Int, Boolean> {
        w.apply(Command.BuyTrain(0, 0, alp45(w).id, coach(w), 4))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        var fastest = 0
        var arrived = false
        repeat(20_000) {
            if (arrived) return@repeat
            w.step()
            if (!t.inDepot && stepTile(t.steps.last()) % w.width >= past) fastest = maxOf(fastest, t.speed * 2700 / 65536)
            if (t.waiting > 0 && w.stationTiles[stepTile(t.steps.last())] == 1) arrived = true
        }
        return fastest to arrived
    }

    @Test
    fun itsPowerAndSpeedComeFromTheWiresOrItsDiesel() {
        val e = alp45(line(false))
        assertEquals(Traction.DUAL, e.traction)
        assertTrue(e.power(true) > e.power(false) && e.speed(true) > e.speed(false))
        val w = line(false)
        w.apply(Command.BuyTrain(0, 0, e.id, coach(w), 4))
        val t = w.trains[0]!!
        assertEquals(e.speed, t.topSpeed)
        assertEquals(e.dieselSpeed, t.dieselSpeed)
        assertTrue(t.tractiveForce(4_000, 100, wired = true) > t.tractiveForce(4_000, 100, wired = false))
    }

    @Test
    fun itRunsOnItsDieselOffTheWires() {
        val (fastest, arrived) = run(line(false))
        assertTrue(arrived, "never got to the far end with no wires")
        val e = alp45(line(false))
        assertTrue(fastest in e.dieselSpeed - 15..e.dieselSpeed, "went $fastest km/h on its diesel")
    }

    @Test
    fun itRunsFasterUnderTheWires() {
        val (fastest, arrived) = run(line(true))
        assertTrue(arrived, "never got to the far end under wires")
        val e = alp45(line(true))
        assertTrue(fastest > e.dieselSpeed + 10 && fastest <= e.speed, "went $fastest km/h under wires")
    }

    @Test
    fun itSlowsToItsDieselSpeedWhereTheWiresEnd() {
        val (fastest, arrived) = run(line(true, wiredTo = 120), past = 140)
        assertTrue(arrived, "never got to the far end")
        val e = alp45(line(false))
        assertTrue(fastest <= e.dieselSpeed, "went $fastest km/h past the end of the wires")
    }

    @Test
    fun itsSoldInADepotWithNoWires() {
        val w = line(false)
        w.apply(Command.BuyTrain(0, 0, alp45(w).id, coach(w), 2))
        assertEquals(1, w.trains.size, "not sold with no wires over the depot")
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        repeat(200) { w.step() }
        assertTrue(!w.trains[0]!!.inDepot, "stayed in its depot")
    }
}
