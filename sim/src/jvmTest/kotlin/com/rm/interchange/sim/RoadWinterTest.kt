package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoadWinterTest {
    private fun World.days(n: Int) = repeat(Calendar.TICKS_PER_DAY * n) { step() }

    @Test
    fun saltClearsTheIceOffRoadsNearItsDepot() {
        val w = World(64, 64, 1, startYear = 1960)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 1_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.SaltDepot(0, 10, 21))
        assertTrue(w.saltDepots.contains(w.index(10, 21)))
        for (x in 5..40) w.weather.ice[w.index(x, 20)] = 20
        w.days(1)
        assertEquals(0, w.weather.ice[w.index(12, 20)].toInt())
        assertTrue(w.weather.ice[w.index(30, 20)].toInt() > 0, "salted out of reach")
        // The salt is paid with the next work day's upkeep.
        w.days(1)
        assertTrue(w.companies[0].thisYear[Books.UPKEEP] < 0)
    }

    @Test
    fun overlappingDepotsSaltATileOnceAndDeepSnowIsntCharged() {
        val w = World(64, 64, 1, startYear = 1960)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 1_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.SaltDepot(0, 14, 21))
        w.apply(Command.SaltDepot(0, 16, 21))
        val c = w.companies[0]
        // Road tiles 6 to 24 are in reach of one depot or both.
        for (x in 5..40) w.weather.ice[w.index(x, 20)] = 20
        var owed = c.upkeepOwed
        w.roadsDay()
        assertEquals(SALT_COST * 19, c.upkeepOwed - owed)
        for (x in 5..40) { w.weather.ice[w.index(x, 20)] = 0; w.weather.snow[w.index(x, 20)] = 50 }
        owed = c.upkeepOwed
        w.roadsDay()
        assertEquals(0, c.upkeepOwed - owed, "paid for salt on deep snow")
    }

    @Test
    fun anIceRoadOpensOnlyOnThickIce() {
        val w = World(64, 64, 1, startYear = 1960)
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 21..29) 0 else 3
        w.companies[0].money = 1_000_000
        val c = Command.Road(0, 15, 20, 35, 20, Surface.GRAVEL, remove = false)
        w.apply(c)
        assertEquals(0, w.roadEdges(w.index(25, 20)), "a road on open water")
        for (x in 20..30) w.weather.ice[w.index(x, 20)] = 60
        w.apply(c)
        assertTrue(w.roadEdges(w.index(25, 20)) != 0)
        assertTrue(w.roadWays(w.index(25, 20), Track.XM).isNotEmpty())
        for (x in 20..30) w.weather.ice[w.index(x, 20)] = 10
        assertTrue(w.roadWays(w.index(25, 20), Track.XM).isEmpty(), "open on thin ice")
    }

    @Test
    fun horsesGoOnRunnersThroughDeepSnow() {
        val w = World(64, 64, 1, startYear = 1850)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 1_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.DIRT, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.DIRT, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        val model = w.roster.availableRoad(1850).first { it.key == "base:horse_omnibus" }
        assertEquals(Traction.HORSE, model.traction)
        w.apply(Command.BuyRoadVehicle(0, 0, model.id))
        val v = w.roadVehicles[0]!!
        val clear = w.index(20, 20)
        val deep = w.index(21, 20)
        w.weather.snow[deep] = 80
        assertEquals(w.roadLimit(v, clear), w.roadLimit(v, deep))
    }

    @Test
    fun trucksGoHalfLoadedInTheThawAndTiresChangeWithTheSeason() {
        val w = World(NewGame(64, 64, 1, 1960, Region.MIXED, towns = 0, industries = 0))
        // Into early spring.
        while (w.lookToday() != Climate.EARLY_SPRING) w.days(1)
        assertTrue(w.loadLimits())
        assertEquals(Tires.WINTER, w.wantedTires())
        while (w.lookToday() != Climate.SUMMER) w.days(1)
        assertTrue(!w.loadLimits())
        assertEquals(Tires.SUMMER, w.wantedTires())
        val old = World(NewGame(64, 64, 1, 1910, Region.MIXED, towns = 0, industries = 0))
        assertEquals(Tires.CHAINS, old.wantedTires())
    }

    @Test
    fun theProvincePlowsACompanysCountryRoad() {
        val w = World(NewGame(64, 64, 1, 1960, Region.MIXED, towns = 0, industries = 0))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 1_000_000
        w.apply(Command.Road(0, 5, 20, 58, 20, Surface.PAVED, remove = false))
        val road = (5..58).map { w.index(it, 20) }
        val ground = (5..58).map { w.index(it, 21) }
        var onRoad = 0
        var offRoad = 0
        repeat(60) {
            w.days(1)
            onRoad += road.count { (w.weather.snow[it].toInt() and 255) >= DEEP_ROAD_SNOW }
            offRoad += ground.count { (w.weather.snow[it].toInt() and 255) >= DEEP_ROAD_SNOW }
        }
        assertTrue(offRoad > 0, "no deep snow")
        assertTrue(onRoad * 2 < offRoad, "road $onRoad, ground $offRoad")
    }
}
