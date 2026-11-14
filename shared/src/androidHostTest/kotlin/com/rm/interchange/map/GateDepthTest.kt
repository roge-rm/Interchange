package com.rm.interchange.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GateDepthTest {
    // Turn 0: a road along x over a double track at x 10 and 11, y 5; the track runs along y. The near gate's post is
    // at (11.82, 5.18), 17 deep in the view, and the gate's depth is the tile's middle, 17, plus 0.9.
    private val base = 17.9f
    private val none = FloatArray(0)

    private fun gate(cars: FloatArray) = gateDepth(base, 11.82f, 5.18f, true, 10, 11, cars, cars.size / 4)

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
    fun roadAlongY() {
        // The road runs along y over one track at y 3, x 7; the track runs along x. The near post is at (7.82, 3.82).
        val cars = floatArrayOf(7.5f, 3.5f, 0.49f, 11.2f, 6.5f, 3.5f, 0.49f, 12f)
        assertTrue(gateDepth(10.9f, 7.82f, 3.82f, false, 3, 3, cars, 1) > 11.2f)
        assertEquals(10.9f, gateDepth(10.9f, 7.82f, 3.82f, false, 3, 3, cars.copyOfRange(4, 8), 1))
    }

    /** The gate's and a bus's depths with an optional coach passing the post, as the draw loop sets them. */
    private fun order(gate: Float, postX: Float, postY: Float, postDepth: Float, r0: Int, r1: Int, coach: FloatArray, busX: Float, busY: Float,
                      bus: Float): Pair<Float, Float> {
        val g = gateDepth(gate, postX, postY, true, r0, r1, coach, coach.size / 4)
        return g to waitingDepth(bus, busX, busY, g, postX, postY, postDepth, true, r0, r1)
    }

    private fun assertOrder(coach: Float, gate: Pair<Float, Float>) {
        assertTrue(coach < gate.first, "coach $coach, gate ${gate.first}")
        assertTrue(gate.first < gate.second, "gate ${gate.first}, bus ${gate.second}")
    }

    @Test
    fun busWaitingAtTheGateGoesInFrontAtEveryTurn() {
        // A bus half 0.25 long waits on its lane with its middle on the run's edge, its nearest corner 0.37 past it.
        // Turn 0, depth x + y: at (12, 5.39), 17.76, short of the gate's own 17.9.
        assertTrue(order(base, 11.82f, 5.18f, 17f, 10, 11, none, 12f, 5.39f, 17.76f).let { it.first < it.second })
        assertOrder(18.6f, order(base, 11.82f, 5.18f, 17f, 10, 11, car(11.5f, 5.6f, 18.6f), 12f, 5.39f, 17.76f))
        // Turn 1, depth 64 - y + x: the same gate, post 70.64 deep, gate 70.9, bus 70.98.
        assertOrder(71.5f, order(70.9f, 11.82f, 5.18f, 70.64f, 10, 11, car(11.5f, 5.6f, 71.5f), 12f, 5.39f, 70.98f))
        // Turn 2, depth 128 - x - y: the gate at x 10, post (10.18, 5.82) 112 deep, gate 112.9, bus at (10, 5.61) 112.76.
        assertTrue(order(112.9f, 10.18f, 5.82f, 112f, 10, 11, none, 10f, 5.61f, 112.76f).let { it.first < it.second })
        assertOrder(113f, order(112.9f, 10.18f, 5.82f, 112f, 10, 11, car(10.5f, 5.4f, 113f), 10f, 5.61f, 112.76f))
        // Turn 3, depth y + 64 - x: the gate at x 10 again, post 59.64 deep, gate 59.9, bus 59.98.
        assertOrder(60.4f, order(59.9f, 10.18f, 5.82f, 59.64f, 10, 11, car(10.5f, 5.4f, 60.4f), 10f, 5.61f, 59.98f))
    }

    @Test
    fun otherRoadVehiclesKeepTheirDepth() {
        val g = gate(car(11.5f, 5.6f, 18.6f))
        // Just past the post onto the crossing in the gate's own lane.
        assertEquals(17.64f, waitingDepth(17.64f, 11.88f, 5.39f, g, 11.82f, 5.18f, 17f, true, 10, 11))
        // Behind the post, on the far side of the run.
        assertEquals(16.8f, waitingDepth(16.8f, 10f, 5.61f, g, 11.82f, 5.18f, 17f, true, 10, 11))
        // Further down the road.
        assertEquals(20f, waitingDepth(20f, 14.5f, 5.39f, g, 11.82f, 5.18f, 17f, true, 10, 11))
    }

    @Test
    fun singleTrack() {
        // One track at x 10: the run is just tile 10, the low gate's post at (10.18, 5.82), the high one's at (10.82, 5.18).
        // Turn 2, depth 128 - x - y: post 112 deep, gate 112.9, the bus at (10, 5.61) 112.76.
        assertTrue(order(112.9f, 10.18f, 5.82f, 112f, 10, 10, none, 10f, 5.61f, 112.76f).let { it.first < it.second })
        assertOrder(113f, order(112.9f, 10.18f, 5.82f, 112f, 10, 10, car(10.5f, 5.4f, 113f), 10f, 5.61f, 112.76f))
        // Turn 3, depth y + 64 - x: post 59.64 deep, gate 59.9, bus 59.98.
        assertOrder(60.4f, order(59.9f, 10.18f, 5.82f, 59.64f, 10, 10, car(10.5f, 5.4f, 60.4f), 10f, 5.61f, 59.98f))
        // Turn 0, depth x + y: the high gate, post 16 deep, gate 16.9, the bus at (11, 5.39) 16.76.
        assertOrder(17.5f, order(16.9f, 10.82f, 5.18f, 16f, 10, 10, car(10.5f, 5.6f, 17.5f), 11f, 5.39f, 16.76f))
    }

    @Test
    fun queueKeepsItsOrder() {
        // Two buses waiting one behind the other at turn 0, both lifted past the gate.
        val g = gate(car(11.5f, 5.6f, 18.6f))
        val first = waitingDepth(17.76f, 12f, 5.39f, g, 11.82f, 5.18f, 17f, true, 10, 11)
        val second = waitingDepth(18.36f, 12.6f, 5.39f, g, 11.82f, 5.18f, 17f, true, 10, 11)
        assertTrue(g < first, "gate $g, first $first")
        assertTrue(first < second, "first $first, second $second")
    }
}
