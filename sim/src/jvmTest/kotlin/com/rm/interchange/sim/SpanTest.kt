package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpanTest {
    /** Land at height [h] of x, y, a depot at (2, 10) and stations at both ends of y = 10. */
    private fun land(h: (Int, Int) -> Int): World {
        val w = World(64, 64, 1, startYear = 1900)
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = h(x, y).toByte()
        w.companies[0].money = 100_000_000
        return w
    }

    private val valley = { x: Int, _: Int -> (6 - minOf(x - 19, 31 - x)).coerceIn(2, 6) }

    private fun runTrainTo(w: World, far: Int): Boolean {
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Station(0, 3, 10, 5, 10, Track.X))
        w.apply(Command.Station(0, far, 10, far + 2, 10, Track.X))
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:american" }.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 2))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        var arrived = false
        repeat(20_000) {
            w.step()
            if (t.waiting > 0 && w.stationTiles[stepTile(t.steps.last())] == 1) arrived = true
        }
        return arrived
    }

    @Test
    fun aTrainCrossesAValleyOnABridge() {
        // Height 6, with a valley between x = 19 and 31 down to 2.
        val w = land(valley)
        w.apply(Command.Rail(0, 3, 10, 19, 10, Track.X, remove = false))
        w.apply(Command.Rail(0, 30, 10, 45, 10, Track.X, remove = false))
        val c = Command.Bridge(0, 19, 10, 30, 10, Spans.TRUSS)
        assertTrue(w.bridgeCost(w.companies[0], c) > 0)
        w.apply(c)
        assertEquals(1, w.spans.size)
        assertEquals(10, w.deck.size)
        assertEquals(5, w.spans[0]!!.height)
        // Track can run under it.
        w.apply(Command.Rail(0, 25, 3, 25, 20, Track.Y, remove = false))
        assertTrue(w.trackAt(25, 10) == Track.Y)
        assertTrue(runTrainTo(w, 43), "never got across")
        // Saved and loaded, it's still there and the game goes on the same.
        val again = Save.read(Save.write(w))
        assertEquals(w.checksum(), again.checksum())
        repeat(2000) { w.step(); again.step() }
        assertEquals(w.checksum(), again.checksum())
    }

    @Test
    fun aBridgeNeedsClearanceAndHeadsLevelWithEachOther() {
        val w = land(valley)
        val company = w.companies[0]
        assertTrue(w.bridgeCost(company, Command.Bridge(0, 19, 10, 30, 10, Spans.TRUSS)) > 0)
        assertEquals(-1L, w.bridgeCost(company, Command.Bridge(0, 19, 10, 30, 11, Spans.TRUSS)))
        // Heads at different heights.
        assertEquals(-1L, w.bridgeCost(company, Command.Bridge(0, 19, 10, 31, 10, Spans.TRUSS)))
        // Concrete isn't built yet in 1900.
        assertEquals(-1L, w.bridgeCost(company, Command.Bridge(0, 19, 10, 30, 10, Spans.CONCRETE)))
        // Too long for a trestle.
        val wide = land { x, _ -> (6 - minOf(x - 9, 31 - x)).coerceIn(2, 6) }
        assertEquals(-1L, wide.bridgeCost(wide.companies[0], Command.Bridge(0, 9, 10, 30, 10, Spans.TRESTLE)))
        // Land in the way.
        val hill = land { x, _ -> if (x in 21..22) 7 else 6 }
        assertEquals(Refusal.HILL, hill.bridgeRefusal(hill.companies[0], Command.Bridge(0, 19, 10, 25, 10, Spans.TRUSS)))
        // Track can't go in under a deck with no room.
        w.apply(Command.Bridge(0, 19, 10, 30, 10, Spans.TRUSS))
        w.apply(Command.Rail(0, 20, 3, 20, 20, Track.Y, remove = false))
        assertEquals(0, w.trackAt(20, 10))
    }

    @Test
    fun aTrainCrossesFlatLandOnABridgeWithRampHeads() {
        val w = land { _, _ -> 4 }
        w.apply(Command.Rail(0, 3, 10, 14, 10, Track.X, remove = false))
        w.apply(Command.Rail(0, 26, 10, 45, 10, Track.X, remove = false))
        // Track under it first; the bridge goes over with a step of room.
        w.apply(Command.Rail(0, 20, 3, 20, 20, Track.Y, remove = false))
        val c = Command.Bridge(0, 14, 10, 26, 10, Spans.TRUSS)
        assertTrue(w.bridgeCost(w.companies[0], c) > 0)
        w.apply(c)
        assertEquals(1, w.spans.size)
        assertEquals(11, w.deck.size)
        val s = w.spans[0]!!
        assertEquals(5, s.height)
        // Each head is a ramp from the ground up to the deck.
        assertEquals(Track.XP, w.rampEdge(14, 10))
        assertEquals(Track.XM, w.rampEdge(26, 10))
        assertEquals(4, w.edgeHeight(14, 10, Track.X, Track.XM))
        assertEquals(5, w.edgeHeight(14, 10, Track.X, Track.XP))
        assertEquals(5, w.edgeHeight(26, 10, Track.X, Track.XM))
        assertEquals(4, w.edgeHeight(26, 10, Track.X, Track.XP))
        // More track and a road can go under it.
        w.apply(Command.Rail(0, 22, 3, 22, 20, Track.Y, remove = false))
        w.apply(Command.Road(0, 24, 3, 24, 20, Surface.DIRT, remove = false))
        assertEquals(Track.Y, w.trackAt(20, 10))
        assertEquals(Track.Y, w.trackAt(22, 10))
        assertTrue(w.road[w.index(24, 10)].toInt() != 0)
        assertTrue(runTrainTo(w, 43), "never got across")
        val again = Save.read(Save.write(w))
        assertEquals(w.checksum(), again.checksum())
        assertEquals(Track.XP, again.rampEdge(14, 10))
        repeat(2000) { w.step(); again.step() }
        assertEquals(w.checksum(), again.checksum())
    }

    @Test
    fun flatEndsAreRefusedForWhatElseIsWrong() {
        val company = { w: World -> w.companies[0] }
        // Flat land the length of a bridge: it builds a step up.
        val flat = land { _, _ -> 4 }
        assertEquals(Refusal.NONE, flat.bridgeRefusal(company(flat), Command.Bridge(0, 10, 10, 16, 10, Spans.TRESTLE)))
        // A head on a slope down to flat land isn't flat, so there's still nothing to span.
        val tilted = land { x, _ -> if (x <= 10) 5 else 4 }
        assertEquals(Refusal.NOTHING_TO_SPAN, tilted.bridgeRefusal(company(tilted), Command.Bridge(0, 10, 10, 16, 10, Spans.TRESTLE)))
        // Ends side by side, not straight, too long, a station on an end, a house in the way: refused as before.
        assertEquals(Refusal.NOTHING_TO_SPAN, flat.bridgeRefusal(company(flat), Command.Bridge(0, 10, 10, 11, 10, Spans.TRESTLE)))
        assertEquals(Refusal.NOT_STRAIGHT, flat.bridgeRefusal(company(flat), Command.Bridge(0, 10, 10, 16, 12, Spans.TRESTLE)))
        assertEquals(Refusal.TOO_LONG, flat.bridgeRefusal(company(flat), Command.Bridge(0, 10, 10, 30, 10, Spans.TRESTLE)))
        flat.apply(Command.Station(0, 16, 10, 18, 10, Track.X))
        assertEquals(Refusal.ENDS, flat.bridgeRefusal(company(flat), Command.Bridge(0, 10, 10, 16, 10, Spans.TRESTLE)))
        flat.house[flat.index(13, 20)] = 1
        assertEquals(Refusal.IN_THE_WAY, flat.bridgeRefusal(company(flat), Command.Bridge(0, 10, 20, 16, 20, Spans.TRESTLE)))
        // Heads at different heights.
        val step = land { x, _ -> if (x >= 16) 5 else 4 }
        assertEquals(Refusal.NOT_LEVEL, step.bridgeRefusal(company(step), Command.Bridge(0, 10, 10, 16, 10, Spans.TRESTLE)))
    }

    @Test
    fun aTrainRunsThroughATunnel() {
        // Height 3, with a ridge up to 7 from x = 20 to 30, a step a tile up and down.
        val ridge = { x: Int, _: Int -> 3 + (minOf(x - 16, 34 - x)).coerceIn(0, 4) }
        val w = land(ridge)
        val portal = w.tunnelFrom(w.companies[0], 16, 10)
        assertTrue(portal != null, "no way through")
        assertEquals(33, portal.x1)
        w.apply(Command.Tunnel(0, 16, 10))
        assertEquals(1, w.spans.size)
        w.apply(Command.Rail(0, 3, 10, 15, 10, Track.X, remove = false))
        w.apply(Command.Rail(0, 34, 10, 45, 10, Track.X, remove = false))
        assertTrue(runTrainTo(w, 43), "never got through")
        // Taken away from a head, the whole tunnel goes.
        w.trains[0]!!.let { w.apply(Command.TrainOrder(0, it.id, Command.TrainOrder.TO_DEPOT)) }
        repeat(6000) { w.step() }
        w.apply(Command.Clear(0, 33, 10))
        assertTrue(w.deck.isEmpty() && w.spanHeads.isEmpty())
    }
}
