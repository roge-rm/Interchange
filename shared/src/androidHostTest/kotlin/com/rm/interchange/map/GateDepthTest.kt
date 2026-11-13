package com.rm.interchange.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GateDepthTest {
    // Turn 0: a road along x over a double track at x 10 and 11, y 5; the track runs along y. The near gate's post is
    // at (11.82, 5.18) and its depth is the tile's middle, 17, plus 0.9.
    private val base = 17.9f
    private val none = FloatArray(0)

    private fun gate(cars: FloatArray, roads: FloatArray = none) = gateDepth(base, 11.82f, 5.18f, true, 10, 11, cars, cars.size / 4, roads, roads.size / 3)

    private fun car(x: Float, y: Float, depth: Float) = floatArrayOf(x, y, 0.49f, depth)

    @Test
    fun nearGateGoesInFrontOfCarsPassingItsPost() {
        // A coach lifted past its neighbour at a join, its end just past the post, and one on the other track of the run.
        assertTrue(gate(car(11.5f, 5.6f, 18.6f)) > 18.6f)
        assertTrue(gate(car(10.5f, 5.0f, 17.95f)) > 17.95f)
    }

    @Test
    fun carsNotPassingThePostLeaveTheGateAlone() {
        // Further along the track than its half length, and on a track beside the run.
        assertEquals(base, gate(car(11.5f, 6.4f, 19f)))
        assertEquals(base, gate(car(12.5f, 5.3f, 18.6f)))
    }

    @Test
    fun gateStaysBehindARoadVehicleWaitingAtIt() {
        val coach = car(11.5f, 5.6f, 18.6f)
        // Waiting on the next road tile at the near gate.
        val d = gate(coach, floatArrayOf(12.3f, 5.25f, 18.1f))
        assertTrue(d < 18.1f)
        assertTrue(d >= base)
        // One behind the gate already, on the far lane, doesn't hold it back.
        assertTrue(gate(coach, floatArrayOf(10.7f, 5.8f, 16.8f)) > 18.6f)
        // Nor one further down the road.
        assertTrue(gate(coach, floatArrayOf(14.5f, 5.25f, 20f)) > 18.6f)
    }

    @Test
    fun roadAlongY() {
        // The road runs along y over one track at y 3, x 7; the track runs along x. The near post is at (7.82, 3.82).
        val cars = floatArrayOf(7.5f, 3.5f, 0.49f, 11.2f, 6.5f, 3.5f, 0.49f, 12f)
        assertTrue(gateDepth(10.9f, 7.82f, 3.82f, false, 3, 3, cars, 1, none, 0) > 11.2f)
        assertEquals(10.9f, gateDepth(10.9f, 7.82f, 3.82f, false, 3, 3, cars.copyOfRange(4, 8), 1, none, 0))
    }
}
