package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertTrue

class ElectricTest {
    @Test
    fun anElectricEngineOnlyRunsUnderWires() {
        val w = World(64, 64, 1, startYear = 1920)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 4, 10, 6, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        // Wires only part of the way, the depot included.
        w.apply(Command.Wire(0, 2, 10, 20, 10, Track.X, remove = false))
        val boxcab = w.roster.engines.first { it.key == "base:boxcab" }
        assertTrue(boxcab.traction == Traction.ELECTRIC)
        w.apply(Command.BuyTrain(0, 0, boxcab.id, w.roster.engines.first { it.key == "base:coach_steel" }.id, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        var furthest = 0
        repeat(8000) {
            w.step()
            if (!t.inDepot) furthest = maxOf(furthest, stepTile(t.steps.last()) % w.width)
        }
        assertTrue(furthest <= 20, "ran to x $furthest off the wires")
        w.apply(Command.Wire(0, 21, 10, 39, 10, Track.X, remove = false))
        var arrived = false
        repeat(12000) {
            w.step()
            if (t.waiting > 0 && w.stationTiles[stepTile(t.steps.last())] == 1) arrived = true
        }
        assertTrue(arrived, "never got to the far end once it was wired")
    }
}
