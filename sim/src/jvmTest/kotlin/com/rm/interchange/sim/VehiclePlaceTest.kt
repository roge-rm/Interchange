package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals

class VehiclePlaceTest {
    @Test
    fun aTrainInADepotIsShownAtItsMiddle() {
        val w = World(NewGame(64, 64, 1, 1900, towns = 0, industries = 0, inflation = false, weather = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 10, 12, Track.XP))
        val depot = w.depots.indexOfFirst { it != null }
        val engine = w.roster.engines.first { it.key == "base:american" }.id
        val wagon = w.roster.engines.first { it.key == "base:coach_wood" }.id
        w.apply(Command.BuyTrain(0, depot, engine, wagon, 2))
        val view = w.view().trains.single()
        assertEquals(10.5f, view.x)
        assertEquals(12.5f, view.y)
        assertEquals(true, view.inDepot)
    }
}
