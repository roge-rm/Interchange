package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClearTest {
    private fun flat(): World {
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 1_000_000
        return w
    }

    @Test
    fun anAreaClearsTrackRoadAndTreesTogether() {
        val w = flat()
        w.apply(Command.Rail(0, 10, 10, 20, 10, Track.X, remove = false))
        w.apply(Command.Road(0, 10, 12, 20, 12, Surface.DIRT, remove = false))
        w.trees[w.index(13, 11)] = Trees.pack(Trees.MAPLE, 3).toByte()
        w.trees[w.index(15, 11)] = Trees.pack(Trees.MAPLE, 3).toByte()
        val area = Command.ClearArea(0, 12, 10, 14, 12)
        val (cost, n) = w.clearCost(0, area)
        assertTrue(cost > 0 && n == 7, "cost $cost over $n tiles")
        val before = w.companies[0].money
        w.apply(area)
        for (x in 12..14) {
            assertEquals(0, w.track[w.index(x, 10)].toInt())
            assertEquals(0, w.road[w.index(x, 12)].toInt())
        }
        // The road either side no longer leads onto the cleared tiles.
        assertEquals(0, w.road[w.index(11, 12)].toInt() and roadBit(Track.XP))
        assertTrue(w.track[w.index(11, 10)].toInt() != 0 && w.track[w.index(15, 10)].toInt() != 0)
        assertEquals(cost, before - w.companies[0].money)
        assertEquals(0, Trees.count(w.trees[w.index(13, 11)].toInt()))
        // Trees outside the area stay.
        assertTrue(Trees.count(w.trees[w.index(15, 11)].toInt()) > 0)
    }

    @Test
    fun aRoadStopAndAGarageClear() {
        val w = flat()
        w.apply(Command.Road(0, 5, 20, 20, 20, Surface.DIRT, remove = false))
        w.apply(Command.RoadStop(0, 10, 20))
        w.apply(Command.Garage(0, 12, 21, Track.YM))
        assertTrue(w.roadStops.containsKey(w.index(10, 20)) && w.depotTiles.containsKey(w.index(12, 21)))
        w.apply(Command.Clear(0, 10, 20))
        w.apply(Command.Clear(0, 12, 21))
        assertTrue(!w.roadStops.containsKey(w.index(10, 20)) && !w.depotTiles.containsKey(w.index(12, 21)))
    }

    @Test
    fun aTownBuildingComesDownForAPriceAndTheTownMinds() {
        val w = World(NewGame(64, 64, 1, 1900, Region.MIXED, towns = 1, industries = 0))
        w.companies[0].money = 1_000_000
        val i = w.house.indices.first { w.house[it].toInt() != 0 }
        val t = w.towns[w.townOf[i].toInt()]
        val people = t.population
        val approval = t.approval[0]
        val cost = w.demolishCost(0, i)
        assertTrue(cost > 0)
        w.apply(Command.Clear(0, i % w.width, i / w.width))
        assertEquals(0, w.house[i].toInt())
        assertTrue(t.population < people)
        assertEquals(approval - DEMOLISH_APPROVAL, t.approval[0])
        // A town that thinks too little of the company won't let it.
        val j = w.house.indices.first { w.house[it].toInt() != 0 && w.townOf[it].toInt() == t.id }
        t.approval[0] = APPROVAL_REFUSES
        w.apply(Command.Clear(0, j % w.width, j / w.width))
        assertTrue(w.house[j].toInt() != 0)
    }
}
