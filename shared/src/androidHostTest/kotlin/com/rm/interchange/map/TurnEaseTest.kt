package com.rm.interchange.map

import kotlin.test.Test
import kotlin.test.assertEquals

class TurnEaseTest {
    @Test
    fun turnsTheShorterWayRound() {
        assertEquals(1.5f, TurnEase.toward(1f, 3f, 0.5f), 1e-5f)
        assertEquals(0.5f, TurnEase.toward(1f, 7f, 0.5f), 1e-5f)
        // Across 0 both ways, kept in 0 up to 8.
        assertEquals(7.5f, TurnEase.toward(0f, 6f, 0.5f), 1e-5f)
        assertEquals(0.25f, TurnEase.toward(7.75f, 1f, 0.5f), 1e-5f)
    }

    @Test
    fun reachesTheTargetAndStays() {
        assertEquals(3f, TurnEase.toward(2.8f, 3f, 0.5f))
        assertEquals(0f, TurnEase.toward(7.9f, 0f, 0.5f))
    }

    @Test
    fun easesByKeyAndForgetsWhatIsGone() {
        val ease = TurnEase()
        val ship = -(1 * 256 + 1)
        val plane = -(2 * 256 + 2)
        ease.begin(0.0, 2)
        assertEquals(0f, ease.angle(ship, 0f, 0.1f, jumped = false))
        assertEquals(0f, ease.angle(plane, 0f, 0.1f, jumped = false))
        ease.end()
        // A half turn of the ship sweeps through the sixteenths over 40 ticks.
        val seen = HashSet<Int>()
        for (t in 1..40) {
            ease.begin(t.toDouble(), 1)
            val a = ease.angle(ship, 4f, 0.1f, jumped = false)
            seen += kotlin.math.round(a * 2).toInt() % 16
            ease.end()
        }
        assertEquals(9, seen.size)
        ease.begin(41.0, 2)
        assertEquals(4f, ease.angle(ship, 4f, 0.1f, jumped = false))
        // The plane wasn't drawn last frame, so it starts at its heading.
        assertEquals(6f, ease.angle(plane, 6f, 0.1f, jumped = false))
        ease.end()
        // A jump starts at the heading too, as does time going back.
        ease.begin(42.0, 1)
        assertEquals(2f, ease.angle(ship, 2f, 0.1f, jumped = true))
        ease.end()
        ease.begin(1.0, 1)
        assertEquals(5f, ease.angle(ship, 5f, 0.1f, jumped = false))
        ease.end()
    }

    @Test
    fun holdsManyVehicles() {
        val ease = TurnEase()
        ease.begin(0.0, 500)
        for (n in 0 until 500) ease.angle(-(n * 256 + 1), (n % 8).toFloat(), 0.5f, jumped = false)
        ease.end()
        ease.begin(1.0, 500)
        for (n in 0 until 500) assertEquals(((n % 8) + 0.5f) % 8f, ease.angle(-(n * 256 + 1), ((n % 8) + 1) % 8f, 0.5f, jumped = false), 1e-5f)
        ease.end()
    }
}
