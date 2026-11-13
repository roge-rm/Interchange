package com.rm.interchange.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GateDepthTest {
    // A road along x over a double track at x 10 and 11, y 5; the track runs along y.
    private fun car(x: Float, y: Float, depth: Float) = floatArrayOf(x, y, 0.5f, depth)

    @Test
    fun nearGateGoesInFrontOfCarsOnItsCrossing() {
        // A coach whose sort key is past the gate's, on either track of the run.
        assertTrue(gateDepth(16.4f, true, true, 10, 5, 10, 11, car(10.5f, 5.6f, 16.8f)) > 16.8f)
        assertTrue(gateDepth(16.4f, true, true, 10, 5, 10, 11, car(11.5f, 6.3f, 18.1f)) > 18.1f)
    }

    @Test
    fun farGateGoesBehindCarsOnItsCrossing() {
        assertTrue(gateDepth(14.6f, false, true, 11, 5, 10, 11, car(10.5f, 4.9f, 14.2f)) < 14.2f)
    }

    @Test
    fun carsOffTheCrossingLeaveTheGateAlone() {
        // Further along the track than its half length, and on a track beside the run.
        assertEquals(16.4f, gateDepth(16.4f, true, true, 10, 5, 10, 11, car(10.5f, 7.2f, 18f)))
        assertEquals(16.4f, gateDepth(16.4f, true, true, 10, 5, 10, 11, car(12.5f, 5.5f, 18f)))
    }

    @Test
    fun roadAlongY() {
        // The road runs along y over one track at y 3, x 7; the track runs along x.
        assertTrue(gateDepth(10.9f, true, false, 7, 3, 3, 3, car(7.9f, 3.5f, 11.2f)) > 11.2f)
        assertEquals(10.9f, gateDepth(10.9f, true, false, 7, 3, 3, 3, car(7.5f, 4.5f, 12f)))
    }
}
