package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StationTurnTest {
    /** Flat land, with the company's money set high. */
    private fun land(): World {
        val w = World(64, 64, 1, startYear = 1900)
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = 4
        w.companies[0].money = 100_000_000
        return w
    }

    @Test
    fun turningAStationKeepsItsTilesAndCostsNothing() {
        val w = land()
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        val station = w.stationAt(w.index(10, 10))!!
        val other = w.stationAt(w.index(36, 10))!!
        val tiles = station.tiles.toList()
        assertEquals(4, tiles.size)
        assertTrue(station.id != other.id)
        assertEquals(2, w.stations.size)
        val money = w.companies[0].money

        // The same axis again changes nothing.
        val touches = w.touches
        w.apply(Command.Station(0, 11, 10, 11, 10, Track.X))
        assertEquals(money, w.companies[0].money)
        assertEquals(touches, w.touches)
        for (x in 10..13) assertEquals(Track.X, w.trackAt(x, 10))

        // A platform and empty land in one rectangle stays refused.
        w.apply(Command.Station(0, 13, 10, 16, 10, Track.Y))
        assertEquals(Track.X, w.trackAt(13, 10))
        assertEquals(0, w.trackAt(14, 10))
        assertEquals(money, w.companies[0].money)
        assertEquals(station.id, w.stationAt(w.index(13, 10))!!.id)

        // One tile is already on Y, and keeps the signal there. The others lose theirs.
        val keep = w.index(13, 10)
        w.track[keep] = Track.Y.toByte()
        w.signals[pieceKey(keep, Track.Y)] = Signals.pack(Track.YP, Signals.TWO_WAY)
        val drop = w.index(10, 10)
        w.signals[pieceKey(drop, Track.X)] = Signals.pack(Track.XP, Signals.TWO_WAY)
        w.signals[pieceKey(drop, Track.N)] = Signals.pack(Track.YM, Signals.TWO_WAY)

        w.apply(Command.Station(0, 11, 10, 11, 10, Track.Y))
        assertEquals(station.id, w.stationAt(w.index(12, 10))!!.id)
        assertEquals(tiles, w.stations[station.id]!!.tiles)
        for (x in 10..13) assertEquals(Track.Y, w.trackAt(x, 10))
        for (x in 36..39) assertEquals(Track.X, w.trackAt(x, 10))
        assertEquals(other.id, w.stationAt(w.index(39, 10))!!.id)
        assertEquals(other.tiles.toList(), w.stations[other.id]!!.tiles)
        assertEquals(money, w.companies[0].money)
        assertEquals(2, w.stations.size)
        assertEquals(null, w.signals[pieceKey(drop, Track.X)])
        assertEquals(null, w.signals[pieceKey(drop, Track.N)])
        assertEquals(Signals.pack(Track.YP, Signals.TWO_WAY), w.signals[pieceKey(keep, Track.Y)])

        w.apply(Command.Station(0, 10, 10, 10, 10, Track.X))
        for (x in 10..13) assertEquals(Track.X, w.trackAt(x, 10))
        for (x in 36..39) assertEquals(Track.X, w.trackAt(x, 10))
        assertEquals(tiles, w.stations[station.id]!!.tiles)
        assertEquals(station.id, w.stationAt(w.index(13, 10))!!.id)
        assertEquals(money, w.companies[0].money)
        assertEquals(null, w.signals[pieceKey(keep, Track.Y)])
    }

    @Test
    fun aTrainOnTheStationKeepsItFromTurning() {
        val w = land()
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        val money = w.companies[0].money
        val touches = w.touches
        w.tileTrains[w.index(12, 10)] = 1
        w.apply(Command.Station(0, 12, 10, 12, 10, Track.Y))
        for (x in 10..13) assertEquals(Track.X, w.trackAt(x, 10))
        for (x in 36..39) assertEquals(Track.X, w.trackAt(x, 10))
        assertEquals(money, w.companies[0].money)
        assertEquals(touches, w.touches)
        assertEquals(2, w.stations.size)
    }
}
