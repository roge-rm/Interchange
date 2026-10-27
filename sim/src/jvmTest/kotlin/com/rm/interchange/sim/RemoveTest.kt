package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoveTest {
    private fun flat(): World {
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 1_000_000
        return w
    }

    @Test
    fun removeSellsTrackAndLeavesTheTreeAndTheTown() {
        val w = flat()
        w.apply(Command.Rail(0, 10, 10, 12, 10, Track.X, remove = false))
        val track = w.index(11, 10)
        w.trees[track] = Trees.pack(Trees.MAPLE, 2).toByte()
        val house = w.index(11, 12)
        w.house[house] = 1.toShort()
        w.companies[0].money = 0
        val area = Command.RemoveArea(0, 11, 10, 11, 12)
        val (cost, n) = w.removeCost(0, area)
        assertEquals(-(w.price(Prices.TRACK) / 2), cost)
        assertEquals(1, n)
        w.apply(area)
        assertEquals(0, w.track[track].toInt())
        assertTrue(Trees.count(w.trees[track].toInt()) > 0)
        assertEquals(1, w.house[house].toInt())
        assertEquals(w.price(Prices.TRACK) / 2, w.companies[0].money)
    }

    @Test
    fun removeLeavesAnotherCompanysTrack() {
        val w = flat()
        w.companies += Company(1, "Other", 1_000_000)
        w.apply(Command.Rail(1, 10, 10, 14, 10, Track.X, remove = false))
        val before = w.companies[0].money
        w.apply(Command.Remove(0, 12, 10))
        assertTrue(w.track[w.index(12, 10)].toInt() != 0)
        assertEquals(1, w.owner[w.index(12, 10)].toInt())
        assertEquals(before, w.companies[0].money)
    }

    @Test
    fun removeSellsABuoy() {
        val w = World(64, 64, 1, startYear = 1900)
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 10..20 && y in 10..20) 0 else 1
        w.shoreChanged()
        w.companies[0].money = 1_000_000
        w.apply(Command.Buoy(0, 15, 15))
        val i = w.index(15, 15)
        assertTrue(w.buoys.containsKey(i))
        val before = w.companies[0].money
        val area = Command.RemoveArea(0, 15, 15, 15, 15)
        val (cost, n) = w.removeCost(0, area)
        assertEquals(-(w.price(BUOY_COST) / 2), cost)
        assertEquals(1, n)
        w.apply(Command.Remove(0, 15, 15))
        assertTrue(!w.buoys.containsKey(i))
        assertEquals(before + w.price(BUOY_COST) / 2, w.companies[0].money)
    }

    @Test
    fun removeSellsASaltDepotAndLeavesTheRoadBesideIt() {
        val w = flat()
        w.apply(Command.Road(0, 10, 10, 12, 10, Surface.DIRT, remove = false))
        w.apply(Command.SaltDepot(0, 11, 11))
        val i = w.index(11, 11)
        assertTrue(w.saltDepots.contains(i))
        val before = w.companies[0].money
        val (cost, n) = w.removeCost(0, Command.RemoveArea(0, 11, 11, 11, 11))
        assertEquals(-(w.price(w.saltDepotSale()) / 2), cost)
        assertEquals(1, n)
        w.apply(Command.Remove(0, 11, 11))
        assertTrue(!w.saltDepots.contains(i))
        assertEquals(before + w.price(w.saltDepotSale()) / 2, w.companies[0].money)
        assertTrue(w.road[w.index(11, 10)].toInt() != 0)
    }

    @Test
    fun aSandDepotBoughtIn1949SellsForNoMoreThanHalfItsPriceIn1950() {
        val w = World(64, 64, 1, startYear = 1949)
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 1_000_000
        w.apply(Command.Road(0, 10, 10, 12, 10, Surface.DIRT, remove = false))
        val paid = w.companies[0].money
        w.apply(Command.SaltDepot(0, 11, 11))
        assertEquals(w.price(LATE_SAND_DEPOT_COST), paid - w.companies[0].money)
        w.tick = Calendar.TICKS_PER_DAY * 366L
        assertEquals(1950, Calendar.year(w.startYear, w.day))
        val before = w.companies[0].money
        w.apply(Command.Remove(0, 11, 11))
        assertTrue(!w.saltDepots.contains(w.index(11, 11)))
        assertTrue(w.companies[0].money - before in 1..w.price(LATE_SAND_DEPOT_COST) / 2)
    }

    @Test
    fun aPlatformSellsForHalfItsPriceAndNotTheTrack() {
        val w = flat()
        w.apply(Command.Station(0, 10, 10, 10, 10, Track.X))
        val i = w.index(10, 10)
        assertTrue(w.isPlatform(i))
        val before = w.companies[0].money
        w.apply(Command.Remove(0, 10, 10))
        assertEquals(0, w.track[i].toInt())
        assertEquals(before + w.price(Prices.PLATFORM) / 2, w.companies[0].money)
    }
}
