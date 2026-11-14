package com.rm.interchange.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoadingTrainTest {
    @Test
    fun movesTowardsProgressAndNeverBack() {
        val train = LoadingTrain()
        var last = 0f
        var ms = 0L
        for (p in listOf(0.1f, 0.4f, 0.3f, 0.2f, 0.6f, 0.5f)) for (i in 0 until 30) {
            ms += 16
            train.advance(p, 1f, ms)
            assertTrue(train.shown >= last, "went back at $ms")
            assertTrue(train.shown <= 0.6f)
            last = train.shown
        }
        for (i in 0 until 200) train.advance(0.5f, 1f, ms + 16L * i)
        assertEquals(0.6f, train.shown, 1e-3f)
    }

    @Test
    fun startsAgainWhenTheCoverComesBack() {
        val train = LoadingTrain()
        for (i in 1..100) train.advance(1f, 1f, i * 16L)
        assertEquals(1f, train.shown)
        train.advance(1f, 0f, 1700)
        train.advance(0f, 1f, 1716)
        assertEquals(0f, train.shown)
    }

    @Test
    fun startsAgainIfTheWorkRestartsWhileFadingOut() {
        val train = LoadingTrain()
        for (i in 1..100) train.advance(1f, 1f, i * 16L)
        assertEquals(1f, train.shown)
        train.advance(0f, 0.5f, 1616)
        assertEquals(0f, train.shown)
    }

    @Test
    fun aStallDoesNotMoveItAndALongGapDoesNotJumpIt() {
        val train = LoadingTrain()
        train.advance(0.5f, 1f, 0)
        train.advance(0.5f, 1f, 5000)
        // A frame after a long stall moves it at most 100 ms' worth.
        assertTrue(train.shown < 0.5f * 0.4f + 0.02f)
    }
}
