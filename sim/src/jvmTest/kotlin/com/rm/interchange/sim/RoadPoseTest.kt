package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoadPoseTest {
    private val width = 10
    private fun tile(x: Int, y: Int) = y * width + x

    /** Along x, a right and a left corner, turning round at a dead end, and back the same way. */
    private val route = listOf(
        roadStep(tile(2, 2), Track.XM, Track.XP),
        roadStep(tile(3, 2), Track.XM, Track.YP),
        roadStep(tile(3, 3), Track.YM, Track.XP),
        roadStep(tile(4, 3), Track.XM, Track.XM),
        roadStep(tile(3, 3), Track.XP, Track.YM),
        roadStep(tile(3, 2), Track.YP, Track.XM),
        roadStep(tile(2, 2), Track.XP, Track.XM),
    )

    private fun turnOf(a: Float, b: Float): Float {
        var d = b - a
        if (d > 4f) d -= 8f
        if (d < -4f) d += 8f
        return kotlin.math.abs(d)
    }

    /** Drives the route [move] units a tick, carrying what's left over into the next step, and returns the biggest move and turn a tick. */
    private fun drive(move: Int): Pair<Float, Float> {
        var most = 0f
        var turned = 0f
        var last: FloatArray? = null
        for (s in route) {
            var along = 0
            val len = roadStepLength(s, garage = false)
            while (along <= len) {
                val p = roadPose(s, along, width)
                assertEquals((kotlin.math.round(p[3]).toInt() % 8).toFloat(), p[2])
                last?.let {
                    most = maxOf(most, kotlin.math.hypot(p[0] - it[0], p[1] - it[1]))
                    turned = maxOf(turned, turnOf(it[3], p[3]))
                }
                last = p
                along += move
            }
        }
        return most to turned
    }

    @Test
    fun smoothAtSpeed() {
        // About 110 km/h, the fastest road vehicle; the map's glide only turns under 1.5 eighths a tick.
        val (most, turned) = drive(110 * 4096 / 2700)
        // A straight tile takes 16 * FIX; corners' outer lane and the turn-round loop go a little faster.
        assertTrue(most < 0.07f, "moved $most tiles in a tick")
        assertTrue(turned < 1.0f, "turned $turned eighths in a tick")
    }

    @Test
    fun noJumps() {
        val (most, turned) = drive(8)
        assertTrue(most < 0.02f, "moved $most tiles in a small step")
        assertTrue(turned < 0.05f, "turned $turned eighths in a small step")
    }

    @Test
    fun turnRoundKeepsRight() {
        val s = route[3]
        val start = roadPose(s, 0, width)
        val end = roadPose(s, roadStepLength(s, garage = false), width)
        // In along the lane below the road's middle heading +x, out along the one above heading -x.
        assertEquals(3.5f + LANE, start[1], 0.001f)
        assertEquals(3.5f - LANE, end[1], 0.001f)
        assertEquals(0f, start[2])
        assertEquals(4f, end[2])
        val half = roadPose(s, roadStepLength(s, garage = false) / 2, width)
        assertTrue(half[0] > 4.5f && half[0] < 5f && kotlin.math.abs(half[1] - 3.5f) < 0.01f, "loop stays in its tile: ${half.toList()}")
    }

    @Test
    fun turnRoundLengthMatchesItsLoop() {
        val s = route[3]
        var drawn = 0f
        var last = roadPose(s, 0, width)
        for (along in 1..U_TURN_LENGTH) {
            val p = roadPose(s, along, width)
            drawn += kotlin.math.hypot(p[0] - last[0], p[1] - last[1])
            last = p
        }
        assertEquals(U_TURN_LENGTH / (16f * FIX), drawn, 0.002f)
    }

    @Test
    fun garageGoesStraightInAndOut() {
        val s = roadStep(tile(4, 3), Track.XM, Track.XM)
        val len = roadStepLength(s, garage = true)
        assertEquals(8 * FIX, len)
        for (along in 0..len step 64) {
            val p = roadPose(s, along, width, garage = true)
            // Along the road's line, never off to the side the loop would take it.
            assertTrue(p[0] in 4f..4.5f && kotlin.math.abs(p[1] - 3.5f) <= LANE + 0.001f, "at ${p.toList()}")
            assertEquals(if (along < len / 2) 0f else 4f, p[2])
        }
        // Leaving starts halfway, in the middle facing the door.
        val out = roadPose(s, len / 2, width, garage = true)
        assertEquals(4.5f, out[0], 0.001f)
        assertEquals(4f, out[2])
    }
}
