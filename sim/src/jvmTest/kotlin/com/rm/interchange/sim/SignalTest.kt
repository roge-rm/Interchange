package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalTest {
    /**
     * A line east along y = 10 from a depot at x = 2, splitting at x = 20 into two tracks, y = 10 and y = 11,
     * that run on side by side into a station of two platforms at x = 26 to 29.
     */
    private fun twoPlatforms(): World {
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 30, 10, Track.X, remove = false))
        w.apply(Command.Rail(0, 20, 10, 20, 10, Track.W, remove = false))
        w.apply(Command.Rail(0, 20, 11, 20, 11, Track.E, remove = false))
        w.apply(Command.Rail(0, 21, 11, 30, 11, Track.X, remove = false))
        w.apply(Command.Station(0, 26, 10, 29, 11, Track.X))
        return w
    }

    private fun buy(w: World, start: Boolean) {
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:american" }.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 2))
        val id = w.trains.size - 1
        w.apply(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, intArrayOf(Order.withWait(0, 20))))
        if (start) w.apply(Command.TrainOrder(0, id, Command.TrainOrder.START_STOP))
    }

    @Test
    fun aPathSignalLetsASecondTrainIntoTheOtherPlatform() {
        val w = twoPlatforms()
        assertEquals(1, w.stations.size)
        assertEquals(8, w.stations[0]!!.tiles.size)
        w.apply(Command.Signal(0, 15, 10, Track.X, Track.XM, Signals.PATH))
        assertTrue(Signals.path(w.signals.getValue(pieceKey(w.index(15, 10), Track.X))))
        buy(w, true)
        var both = false
        repeat(30_000) { k ->
            if (k == 400) {
                buy(w, true)
            }
            w.step()
            val at = w.trains.filterNotNull().filter { t -> !t.inDepot && t.waiting > 0 && w.stationTiles[stepTile(t.steps.last())] == 0 }
            if (at.size == 2 && at.map { stepTile(it.steps.last()) / w.width }.toSet().size == 2) both = true
        }
        assertTrue(both, "never both at the station, one at each platform")
        // Nothing's left held that no train stands on or is heading through.
        for ((key, id) in w.reserved) assertTrue(w.trains[id] != null)
    }

    @Test
    fun signalKindsChangeAndGoOneWayThenAway() {
        val w = twoPlatforms()
        val key = pieceKey(w.index(10, 10), Track.X)
        w.apply(Command.Signal(0, 10, 10, Track.X, Track.XM, Signals.ENTRY))
        assertEquals(Signals.ENTRY, Signals.kind(w.signals.getValue(key)))
        w.apply(Command.Signal(0, 10, 10, Track.X, Track.XM, Signals.PATH))
        assertEquals(Signals.PATH, Signals.kind(w.signals.getValue(key)))
        w.apply(Command.Signal(0, 10, 10, Track.X, Track.XM, Signals.PATH))
        assertEquals(Signals.ONE_WAY_PATH, Signals.kind(w.signals.getValue(key)))
        w.apply(Command.Signal(0, 10, 10, Track.X, Track.XM, Signals.PATH))
        assertTrue(key !in w.signals)
    }

    @Test
    fun anEntrySignalHoldsATrainWhileTheWayOutIsBlocked() {
        val w = twoPlatforms()
        // Entry at x = 8, exits at x = 16 on to the junction: a block between with no way on.
        w.apply(Command.Signal(0, 8, 10, Track.X, Track.XM, Signals.ENTRY))
        w.apply(Command.Signal(0, 16, 10, Track.X, Track.XM, Signals.EXIT))
        buy(w, true)
        buy(w, false)
        val first = w.trains[0]!!
        // The first train stops past the exit signal, holding the block beyond it.
        repeat(6_000) {
            w.step()
            if (!first.inDepot && stepTile(first.steps.last()) % w.width == 18 && first.running) w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        }
        assertTrue(!first.running && stepTile(first.steps.last()) % w.width >= 18)
        w.apply(Command.TrainOrder(0, 1, Command.TrainOrder.START_STOP))
        val second = w.trains[1]!!
        repeat(3_000) { w.step() }
        // Held at the entry signal, short of the block between, though that block is empty.
        val x = stepTile(second.steps.last()) % w.width
        assertTrue(x in 3..8, "second train got to $x")
    }
}
