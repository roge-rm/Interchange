package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoadTurnTest {
    /** A paved road along y = 20 from x = 5 to 40, a branch down from x = 10 and a garage below the west end. */
    private fun road(): World {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 10, 20, 10, 22, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        return w
    }

    private fun World.truck(at: Int, along: Int): RoadVehicle {
        apply(Command.BuyRoadVehicle(0, 0, roster.engines.first { it.key == "base:semi_van" }.id))
        val v = roadVehicles.last()!!
        v.inDepot = false
        v.at = at
        v.along = along
        return v
    }

    @Test
    fun turningBackKeepsItsFraction() {
        // Three quarters across a straight, turned into a turn-round: a quarter of the loop, short of its middle.
        val along = reversedAlong(3 * 4 * FIX, 16 * FIX, U_TURN_LENGTH)
        assertEquals(U_TURN_LENGTH / 4, along)
        assertTrue(along < U_TURN_LENGTH / 2)
        assertEquals(16 * FIX, reversedAlong(0, 16 * FIX, 16 * FIX))
        assertEquals(0, reversedAlong(U_TURN_LENGTH, U_TURN_LENGTH, 11 * FIX))
    }

    @Test
    fun turnCommandOnALoop() {
        val w = road()
        val v = w.truck(roadStep(w.index(40, 20), Track.XM, Track.XM), 1000)
        w.apply(Command.RoadOrder(0, v.id, Command.TrainOrder.TURN))
        assertEquals(roadStep(w.index(40, 20), Track.XM, Track.XM), v.at)
        assertEquals(U_TURN_LENGTH - 1000, v.along)
    }

    @Test
    fun oldSavesScaleTurnRounds() {
        val w = road()
        val loop = w.truck(roadStep(w.index(40, 20), Track.XM, Track.XM), 4 * FIX)
        val straight = w.truck(roadStep(w.index(20, 20), Track.XM, Track.XP), 4 * FIX)
        val old = Save.read(Save.write(w, version = 51))
        assertEquals(U_TURN_LENGTH / 2, old.roadVehicles[loop.id]!!.along)
        assertEquals(4 * FIX, old.roadVehicles[straight.id]!!.along)
        val now = Save.read(Save.write(w))
        assertEquals(4 * FIX, now.roadVehicles[loop.id]!!.along)
    }

    @Test
    fun aheadIsNearerTheExit() {
        val w = road()
        val t = w.index(10, 20)
        // Round the corner from the branch with a quarter tile to go; behind it, straight along with more to go.
        val corner = w.truck(roadStep(t, Track.YP, Track.XP), 10 * FIX)
        val straight = w.truck(roadStep(t, Track.XM, Track.XP), 11 * FIX)
        val on = hashMapOf(t to arrayListOf(corner, straight))
        assertTrue(w.roadFreeAhead(straight, on) < 4 * FIX, "the straight one doesn't see the corner one ahead")
        assertTrue(w.roadFreeAhead(corner, on) >= FIX, "the corner one is held by the one behind")
    }
}
