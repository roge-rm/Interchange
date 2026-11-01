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
        // The screen's list of held pieces is only made when asked for.
        assertEquals(0, w.view().reserved.size)
        assertEquals(w.reserved.keys.sorted(), w.view(withReserved = true).reserved.sorted())
        // Nothing's left held that no train stands on or is heading through.
        for ((key, id) in w.reserved) assertTrue(w.trains[id] != null)
    }

    @Test
    fun heldPiecesInTunnelsArentListedForTheScreen() {
        val w = twoPlatforms()
        val bridge = Span(w.spans.size, 0, tunnel = false, Spans.TRUSS, 40, 5, 44, 5, 3).also { w.spans += it }
        val tunnel = Span(w.spans.size, 0, tunnel = true, Spans.TUNNEL, 40, 8, 44, 8, 3).also { w.spans += it }
        w.deck[w.index(41, 5)] = bridge.id
        w.deck[w.index(41, 8)] = tunnel.id
        val onBridge = pieceKey(w.index(41, 5), Track.X, 1)
        val inTunnel = pieceKey(w.index(41, 8), Track.X, 1)
        val ground = pieceKey(w.index(12, 10), Track.X)
        for (k in intArrayOf(onBridge, inTunnel, ground)) w.reserved[k] = 0
        assertEquals(listOf(onBridge, ground).sorted(), w.view(withReserved = true).reserved.sorted())
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

    private fun signalXs(w: World, y: Int = 10) = (0 until w.width).filter { w.signalAt(w.index(it, y), Track.X) >= 0 }

    @Test
    fun aDragPlacesSignalsEveryNTilesSkippingJunctionsAndStationsFacingTheWayItGoes() {
        val w = twoPlatforms()
        val before = w.companies[0].money
        w.apply(Command.SignalRun(0, 4, 10, 30, 10, Track.X, Signals.PATH, 2, false))
        // Every second tile from 4, but not the junction at 20 or the platforms at 26 to 29.
        assertEquals(listOf(4, 6, 8, 10, 12, 14, 16, 18, 22, 24, 30), signalXs(w))
        for (x in signalXs(w)) {
            val s = w.signalAt(w.index(x, 10), Track.X)
            assertEquals(Track.XM, Signals.facing(s), "facing at $x")
            assertEquals(Signals.PATH, Signals.kind(s))
        }
        assertEquals(before - 11 * w.price(Prices.SIGNAL), w.companies[0].money)
    }

    @Test
    fun aDragTheOtherWayFacesTheOtherWayAndSpacingCounts() {
        val w = twoPlatforms()
        w.apply(Command.SignalRun(0, 18, 10, 4, 10, Track.X, Signals.TWO_WAY, 4, false))
        assertEquals(listOf(6, 10, 14, 18), signalXs(w))
        for (x in signalXs(w)) assertEquals(Track.XP, Signals.facing(w.signalAt(w.index(x, 10), Track.X)))
        // Again over the same spots costs nothing and changes the kind.
        val money = w.companies[0].money
        w.apply(Command.SignalRun(0, 18, 10, 4, 10, Track.X, Signals.ENTRY, 4, false))
        assertEquals(money, w.companies[0].money)
        assertEquals(Signals.ENTRY, Signals.kind(w.signalAt(w.index(6, 10), Track.X)))
    }

    @Test
    fun aDragWithoutTheMoneyDoesNothing() {
        val w = twoPlatforms()
        w.companies[0].money = 2 * w.price(Prices.SIGNAL)
        w.apply(Command.SignalRun(0, 4, 10, 18, 10, Track.X, Signals.TWO_WAY, 2, false))
        assertTrue(signalXs(w).isEmpty())
        assertEquals(2 * w.price(Prices.SIGNAL), w.companies[0].money)
    }

    @Test
    fun removingAlongARunTakesTheSignalsAway() {
        val w = twoPlatforms()
        w.apply(Command.SignalRun(0, 4, 10, 18, 10, Track.X, Signals.TWO_WAY, 2, false))
        w.apply(Command.SignalRun(0, 3, 10, 10, 10, Track.X, Signals.TWO_WAY, 2, true))
        assertEquals(listOf(12, 14, 16, 18), signalXs(w))
    }

    @Test
    fun aDiagonalRunFacesEdgesItsPiecesHave() {
        val w = twoPlatforms()
        w.apply(Command.Rail(0, 40, 20, 46, 26, Track.E, remove = false))
        assertTrue(Track.line(w, 40, 20, 46, 26, Track.E) != null)
        w.apply(Command.SignalRun(0, 40, 20, 46, 26, Track.E, Signals.TWO_WAY, 2, false))
        var n = 0
        for ((key, packed) in w.signals) {
            val tile = keyTile(key)
            if (tile % w.width < 40) continue
            n++
            val piece = keyPiece(key)
            val facing = Signals.facing(packed)
            assertTrue(Track.has(piece, facing), "facing $facing on piece $piece at tile $tile")
        }
        assertTrue(n >= 3, "$n signals")
    }

    @Test
    fun removingAlongARunLeavesOtherPiecesOnTheTile() {
        val w = twoPlatforms()
        val i = w.index(20, 10)
        w.signals[pieceKey(i, Track.X)] = Signals.pack(Track.XP, Signals.TWO_WAY)
        w.signals[pieceKey(i, Track.W)] = Signals.pack(Track.XP, Signals.TWO_WAY)
        w.apply(Command.SignalRun(0, 19, 10, 21, 10, Track.X, Signals.TWO_WAY, 2, true))
        assertTrue(w.signals[pieceKey(i, Track.X)] == null)
        assertTrue(w.signals[pieceKey(i, Track.W)] != null)
    }

    @Test
    fun aSignalRunReadsBackFromBytes() {
        val c = Command.SignalRun(0, 4, 10, 30, 10, Track.X, Signals.COMBO, 5, true)
        val w = ByteWriter()
        Codec.write(w, c)
        assertEquals(c, Codec.read(ByteReader(w.toByteArray())))
    }

    private fun place(w: World, depot: Int, orders: IntArray): Train {
        w.apply(Command.BuyTrain(0, depot, w.roster.engines.first { it.key == "base:american" }.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 2))
        val id = w.trains.size - 1
        w.apply(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, orders))
        w.apply(Command.TrainOrder(0, id, Command.TrainOrder.START_STOP))
        return w.trains[id]!!
    }

    @Test
    fun aTrainLeavingItsDepotForAPlatformDoesntHoldTheTrackPastIt() {
        // Two depots at x = 2 on y = 10 and 11, each straight into a platform of one station at x = 6 to 8; y = 11 turns
        // up into y = 10 at x = 10, then a path signal at x = 12 and a second station at x = 26 to 28.
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Depot(0, 2, 11, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 30, 10, Track.X, remove = false))
        w.apply(Command.Rail(0, 3, 11, 9, 11, Track.X, remove = false))
        w.apply(Command.Rail(0, 10, 11, 10, 11, Track.N, remove = false))
        w.apply(Command.Rail(0, 10, 10, 10, 10, Track.S, remove = false))
        w.apply(Command.Station(0, 6, 10, 8, 11, Track.X))
        w.apply(Command.Station(0, 26, 10, 28, 10, Track.X))
        w.apply(Command.Signal(0, 12, 10, Track.X, Track.XM, Signals.PATH))
        assertEquals(2, w.stations.size)
        // The first waits for a full load on its platform; the second, from the other platform's track, gets by it.
        val a = place(w, 0, intArrayOf(Order.FULL_LOAD or 0, 1))
        var ticks = 0
        while (a.waiting == 0) { w.step(); assertTrue(ticks++ < Calendar.TICKS_PER_DAY * 20, "the first never got to its platform") }
        assertTrue(!a.inDepot && a.waiting > 0 && stepTile(a.steps.last()) == w.index(8, 10), "first at ${a.steps.map { stepTile(it) % w.width to stepTile(it) / w.width }} in ${a.inDepot} waiting ${a.waiting}")
        val b = place(w, 1, intArrayOf(1, 0))
        ticks = 0
        while (b.inDepot || stepTile(b.steps.last()) % w.width < 14) {
            w.step()
            assertTrue(ticks++ < Calendar.TICKS_PER_DAY * 20, "the second never got by: in ${b.inDepot}")
        }
        assertTrue(a.waiting > 0 && stepTile(a.steps.last()) == w.index(8, 10), "first left its platform")
    }

    @Test
    fun aTrainOnAOneWaySignalWithNoWayAheadTurnsRound() {
        // A line east from a depot at x = 2 through a one-way path signal at x = 15, stations at x = 5 to 7 and 24 to 26.
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 30, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 5, 10, 7, 10, Track.X))
        w.apply(Command.Station(0, 24, 10, 26, 10, Track.X))
        repeat(2) { w.apply(Command.Signal(0, 15, 10, Track.X, Track.XM, Signals.PATH)) }
        assertEquals(Signals.ONE_WAY_PATH, Signals.kind(w.signals.getValue(pieceKey(w.index(15, 10), Track.X))))
        val t = place(w, 0, intArrayOf(Order.withWait(1, 2)))
        val signal = w.index(15, 10)
        var ticks = 0
        while (t.inDepot || t.steps.none { stepTile(it) == signal }) { w.step(); assertTrue(ticks++ < Calendar.TICKS_PER_DAY * 30) }
        // The track ahead taken up and sent back the way it came, with some of it still on the signal's piece.
        w.apply(Command.Rail(0, 18, 10, 18, 10, Track.X, remove = true))
        w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.ORDERS, intArrayOf(Order.withWait(0, 2))))
        assertTrue(t.steps.any { stepTile(it) == signal })
        ticks = 0
        while (!(t.waiting > 0 && w.stationTiles[stepTile(t.steps.last())] == 0)) {
            w.step()
            assertTrue(ticks++ < Calendar.TICKS_PER_DAY * 30, "never got back: at ${t.steps.map { stepTile(it) % w.width }} speed ${t.speed}")
        }
    }
}
