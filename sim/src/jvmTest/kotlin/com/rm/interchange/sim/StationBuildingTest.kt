package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StationBuildingTest {
    /** Flat land at height 4, with a second company, both with plenty of money. */
    private fun land(): World {
        val w = World(64, 64, 1, startYear = 1900)
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = 4
        w.companies[0].money = 100_000_000
        w.companies += Company(1, "Other", 100_000_000)
        return w
    }

    private fun World.station(x: Int, y: Int) = stationAt(index(x, y))!!

    @Test
    fun itGoesInTheMiddleOnTheSideAwayFromTrack() {
        val w = land()
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        val s = w.station(10, 10)
        assertEquals(w.index(11, 9), s.building)
        assertEquals(Track.YP, s.buildingFacing)
        assertEquals(1900, s.buildingYear)
        assertEquals(s.id, w.stationBuildings[s.building])
        assertEquals(-1, w.owner[s.building].toInt())

        // Track along the minus side sends it to the plus side.
        val v = land()
        v.apply(Command.Rail(0, 5, 8, 20, 8, Track.X, false))
        v.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        val t = v.station(10, 10)
        assertEquals(v.index(11, 11), t.building)
        assertEquals(Track.YM, t.buildingFacing)

        // Along y, the middle is down the column and the sides are x.
        val u = land()
        u.apply(Command.Station(0, 20, 20, 20, 24, Track.Y))
        val r = u.station(20, 20)
        assertEquals(u.index(19, 22), r.building)
        assertEquals(Track.XP, r.buildingFacing)
    }

    @Test
    fun aBlockedMiddleMovesItAlong() {
        val w = land()
        w.apply(Command.Rail(0, 11, 5, 11, 9, Track.Y, false))
        w.apply(Command.Rail(0, 11, 11, 11, 15, Track.Y, false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        // The column at 11 is track on both sides, so it steps to 10.
        assertEquals(w.index(10, 9), w.station(10, 10).building)
    }

    @Test
    fun noneWithWaterSlopeOrTrackOnBothSides() {
        // Water on the minus side, flat land level with the platform's top on the plus side.
        val w = land()
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (y <= 10) 0 else 1
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        assertEquals(w.index(11, 11), w.station(10, 10).building)

        // Water one side and a slope the other: none.
        val v = land()
        for (y in 0..64) for (x in 0..64) v.corners[v.corner(x, y)] = if (y <= 10) 0 else if (y == 11) 1 else 2
        v.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        assertEquals(-1, v.station(10, 10).building)
        assertTrue(v.stationBuildings.isEmpty())

        // Track on both sides: none.
        val u = land()
        u.apply(Command.Rail(0, 5, 9, 20, 9, Track.X, false))
        u.apply(Command.Rail(0, 5, 11, 20, 11, Track.X, false))
        u.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        assertEquals(-1, u.station(10, 10).building)

        // A platform on a foundation: the flat tile below its top is skipped for the one level with it.
        val h = land()
        for (y in 11..64) for (x in 0..64) h.corners[h.corner(x, y)] = 5
        h.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        assertEquals(h.index(11, 11), h.station(10, 10).building)
    }

    @Test
    fun theSameCommandsGiveTheSameChecksum() {
        fun run(): World {
            val w = land()
            w.apply(Command.Rail(0, 5, 8, 20, 8, Track.X, false))
            w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
            w.apply(Command.Station(1, 30, 30, 30, 33, Track.Y))
            w.apply(Command.Station(0, 14, 10, 15, 10, Track.X))
            return w
        }
        val a = run()
        val b = run()
        assertEquals(a.checksum(), b.checksum())
        assertTrue(a.stations.all { it!!.building >= 0 })
        // A building moved by hand shows in the checksum.
        b.station(10, 10).buildingFacing = Track.YP
        assertNotEquals(a.checksum(), b.checksum())
    }

    @Test
    fun itGoesWithTheLastPlatform() {
        val w = land()
        w.apply(Command.Station(0, 10, 10, 11, 10, Track.X))
        val s = w.station(10, 10)
        val b = s.building
        assertTrue(b >= 0)
        w.apply(Command.Clear(0, 11, 10))
        assertEquals(b, s.building)
        w.apply(Command.Clear(0, 10, 10))
        assertEquals(-1, s.building)
        assertTrue(w.stationBuildings.isEmpty())
    }

    @Test
    fun clearingItTakesOnlyTheBuildingForNothing() {
        val w = land()
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        val s = w.station(10, 10)
        val money = w.companies[0].money
        // Another company can't clear it.
        w.apply(Command.Clear(1, 11, 9))
        assertEquals(w.index(11, 9), s.building)
        w.apply(Command.Clear(0, 11, 9))
        assertEquals(-1, s.building)
        assertEquals(money, w.companies[0].money)
        assertEquals(4, s.tiles.size)
        assertTrue(w.stationBuildings.isEmpty())
    }

    @Test
    fun theOwnersTrackRemovesItAndARivalsIsRefused() {
        val w = land()
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        val s = w.station(10, 10)
        val b = w.index(11, 9)
        assertEquals(b, s.building)

        w.apply(Command.Rail(1, 5, 9, 20, 9, Track.X, false))
        assertEquals(0, w.track[b].toInt())
        assertEquals(Track.X, w.trackAt(12, 9))
        w.apply(Command.Road(1, 11, 5, 11, 9, Surface.DIRT, false))
        assertEquals(0, w.road[b].toInt())
        w.apply(Command.Depot(1, 11, 9, Track.XP))
        assertFalse(w.depotTiles.containsKey(b))
        w.apply(Command.Station(1, 11, 9, 11, 9, Track.X))
        assertFalse(w.stationTiles.containsKey(b))
        assertEquals(b, s.building)

        w.apply(Command.Rail(0, 11, 5, 11, 9, Track.Y, false))
        assertEquals(-1, s.building)
        assertTrue(w.stationBuildings.isEmpty())
        assertEquals(Track.Y, w.track[b].toInt() and Track.Y)

        // The owner's road and depot remove it too.
        val v = land()
        v.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        v.apply(Command.Road(0, 11, 5, 11, 9, Surface.DIRT, false))
        assertEquals(-1, v.station(10, 10).building)
        val u = land()
        u.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        u.apply(Command.Depot(0, 11, 9, Track.XP))
        assertEquals(-1, u.station(10, 10).building)
        assertTrue(u.depotTiles.containsKey(u.index(11, 9)))
    }

    @Test
    fun townsDontBuildOnIt() {
        val w = land()
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        val t = Town(0, "Town", 11, 5, 4)
        w.towns += t
        assertTrue(w.extendRoad(t, 12, 8, Track.YP, 1900))
        assertFalse(w.extendRoad(t, 11, 8, Track.YP, 1900))
        assertEquals(0, w.road[w.index(11, 9)].toInt())
        assertEquals(w.index(11, 9), w.station(10, 10).building)
    }

    @Test
    fun extendingKeepsItUnlessCovered() {
        val w = land()
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        val s = w.station(10, 10)
        w.apply(Command.Station(0, 14, 10, 17, 10, Track.X))
        assertEquals(8, s.tiles.size)
        assertEquals(w.index(11, 9), s.building)

        w.apply(Command.Station(0, 10, 9, 13, 9, Track.X))
        assertEquals(12, s.tiles.size)
        assertTrue(s.building >= 0)
        assertNotEquals(w.index(11, 9), s.building)
        assertFalse(w.stationTiles.containsKey(s.building))
        assertEquals(1, w.stationBuildings.size)
        assertEquals(s.id, w.stationBuildings[s.building])
    }

    @Test
    fun waypointsGetNoneAndRivalStationsDo() {
        val w = land()
        w.apply(Command.Rail(0, 5, 20, 20, 20, Track.X, false))
        w.apply(Command.Waypoint(0, 10, 20))
        val p = w.station(10, 20)
        assertTrue(p.waypoint)
        assertEquals(-1, p.building)

        w.apply(Command.Station(1, 30, 30, 33, 30, Track.X))
        val s = w.station(30, 30)
        assertEquals(1, s.owner)
        assertEquals(w.index(31, 29), s.building)
    }

    @Test
    fun erasByBuildYear() {
        assertEquals(0, stationEra(1884))
        assertEquals(1, stationEra(1885))
        assertEquals(1, stationEra(1914))
        assertEquals(2, stationEra(1915))
        assertEquals(2, stationEra(1954))
        assertEquals(3, stationEra(1955))
        assertEquals(3, stationEra(1989))
        assertEquals(4, stationEra(1990))
    }

    @Test
    fun itSavesAndAnOldSaveLoadsWithNone() {
        val w = land()
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(1, 30, 30, 30, 33, Track.Y))
        val back = Save.read(Save.write(w))
        for (s in w.stations) {
            val b = back.stations[s!!.id]!!
            assertEquals(s.building, b.building)
            assertEquals(s.buildingYear, b.buildingYear)
            assertEquals(s.buildingFacing, b.buildingFacing)
        }
        assertEquals(w.stationBuildings, back.stationBuildings)
        assertEquals(w.checksum(), back.checksum())

        val old = Save.read(Save.write(w, 46))
        assertTrue(old.stations.all { it!!.building == -1 })
        assertTrue(old.stationBuildings.isEmpty())
        // Extending an old station gives it one.
        old.apply(Command.Station(0, 14, 10, 14, 10, Track.X))
        assertTrue(old.station(10, 10).building >= 0)
    }

    @Test
    fun noSnowFenceOnIt() {
        val w = land()
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        val b = w.index(11, 9)
        w.apply(Command.SnowWorks(1, 11, 9, false))
        w.apply(Command.SnowWorks(0, 11, 9, false))
        assertFalse(w.fences.contains(b))
        w.apply(Command.SnowWorks(0, 11, 8, false))
        assertTrue(w.fences.contains(w.index(11, 8)))
    }

    @Test
    fun clearingThePlatformItFacesMovesIt() {
        val w = land()
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        val s = w.station(10, 10)
        w.apply(Command.Clear(0, 11, 10))
        assertEquals(w.index(10, 9), s.building)
        assertEquals(Track.YP, s.buildingFacing)
        assertEquals(mapOf(w.index(10, 9) to s.id), w.stationBuildings.toMap())
        // Clearing another platform leaves it.
        w.apply(Command.Clear(0, 13, 10))
        assertEquals(w.index(10, 9), s.building)
    }

    @Test
    fun turningTheStationMovesIt() {
        val w = land()
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        val s = w.station(10, 10)
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.Y))
        assertEquals(Track.Y, w.trackAt(10, 10))
        assertEquals(w.index(9, 10), s.building)
        assertEquals(Track.XP, s.buildingFacing)
        assertEquals(mapOf(w.index(9, 10) to s.id), w.stationBuildings.toMap())
    }
}
