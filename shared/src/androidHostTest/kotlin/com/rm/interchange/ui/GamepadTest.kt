package com.rm.interchange.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GamepadTest {
    @Test
    fun stickStillInsideTheDeadZone() {
        assertEquals(0f, stickSpeed(0f, 0f))
        assertEquals(0f, stickSpeed(0.1f, -0.1f))
        assertEquals(0f, stickSpeed(STICK_DEAD, 0f))
        assertEquals(Offset.Zero, stickMove(0.15f, 0.1f, 1000f, 0.016f))
    }

    @Test
    fun stickSpeedRisesWithThePush() {
        val pushes = listOf(0.3f, 0.5f, 0.7f, 0.9f, 1f)
        val speeds = pushes.map { stickSpeed(it, 0f) }
        for (i in 1 until speeds.size) assertTrue(speeds[i] > speeds[i - 1], "speed at ${pushes[i]} not above ${pushes[i - 1]}")
        assertEquals(1f, stickSpeed(1f, 0f), 1e-6f)
        // Half way past the dead zone goes a quarter speed, for fine moves.
        assertEquals(0.25f, stickSpeed(STICK_DEAD + (1f - STICK_DEAD) / 2, 0f), 1e-5f)
        // A corner push isn't faster than full.
        assertEquals(1f, stickSpeed(1f, 1f), 1e-6f)
    }

    @Test
    fun stickMovesTheWayItsPushed() {
        val d = stickMove(0f, -1f, 600f, 0.5f)
        assertEquals(0f, d.x, 1e-4f)
        assertEquals(-300f, d.y, 1e-3f)
        val diag = stickMove(0.7f, 0.7f, 600f, 1f)
        assertEquals(diag.x, diag.y, 1e-4f)
        assertTrue(diag.x > 0f)
    }

    @Test
    fun cursorStopsAtTheMarginAndMovesTheMap() {
        // Inside, the cursor goes and the map stays.
        val a = cursorMove(500f, 400f, 30f, -20f, 1000f, 800f, 50f)
        assertEquals(listOf(530f, 380f, 0f, 0f), a.toList())
        // Past the right edge, the rest moves the map left under it, as a drag left would.
        val b = cursorMove(940f, 400f, 30f, 0f, 1000f, 800f, 50f)
        assertEquals(950f, b[0])
        assertEquals(-20f, b[2])
        // Past the top, the map moves down.
        val c = cursorMove(500f, 60f, 0f, -40f, 1000f, 800f, 50f)
        assertEquals(50f, c[1])
        assertEquals(30f, c[3])
    }

    @Test
    fun turnLatchPushesOnceTillItRests() {
        val l = TurnLatch()
        assertEquals(0, l.update(0.2f))
        assertEquals(1, l.update(0.8f))
        assertEquals(0, l.update(1f))
        assertEquals(0, l.update(0.4f))
        assertEquals(0, l.update(0.9f))
        assertEquals(0, l.update(0.1f))
        assertEquals(-1, l.update(-0.7f))
        assertTrue(abs(TURN_REST) < TURN_PUSH)
    }

    @Test
    fun padButtonsDontClashWithKeys() {
        val pad = KeyMap.DEFAULT.filter { KeyMap.isPad(it.first) }
        val keys = KeyMap.DEFAULT.filterNot { KeyMap.isPad(it.first) }
        assertTrue(pad.isNotEmpty())
        val padNames = pad.map { it.first }
        assertEquals(padNames.size, padNames.toSet().size)
        assertTrue(padNames.none { name -> keys.any { it.first == name } })
        // A gamepad's d-pad steps the cursor, the keyboard's arrows move the map.
        for (a in listOf(KeyAction.CursorUp, KeyAction.CursorDown, KeyAction.CursorLeft, KeyAction.CursorRight))
            assertTrue(KeyMap.keysOf(a).all { KeyMap.isPad(it) }, "$a on a keyboard key")
    }

    @Test
    fun padReachesWhatItNeeds() {
        val wanted = listOf(
            KeyAction.Tap, KeyAction.Close, KeyAction.Pause, KeyAction.ZoomIn, KeyAction.ZoomOut, KeyAction.TurnLeft,
            KeyAction.TurnRight, KeyAction.Tools, KeyAction.CursorUp, KeyAction.CursorDown, KeyAction.CursorLeft, KeyAction.CursorRight,
        )
        for (a in wanted) assertTrue(KeyMap.keysOf(a).any { KeyMap.isPad(it) }, "$a has no gamepad button")
        assertEquals(KeyAction.Tap, KeyMap.action("pad_a"))
        assertEquals(KeyAction.Close, KeyMap.action("pad_b"))
        assertEquals(KeyAction.Pause, KeyMap.action("pad_start"))
    }

    @Test
    fun cursorKeptOnAMapThatShrinks() {
        // Turning a wide screen upright leaves the cursor past the new right edge.
        assertEquals(Offset(470f, 300f), clampCursor(Offset(900f, 300f), 500f, 1000f, 30f))
        assertEquals(Offset(30f, 30f), clampCursor(Offset(-5f, 10f), 500f, 1000f, 30f))
        val inside = Offset(200f, 400f)
        assertEquals(inside, clampCursor(inside, 500f, 1000f, 30f))
    }

    @Test
    fun heldKeyRepeatsAfterTheDelayAtTheRate() {
        val r = KeyRepeat(400, 50)
        assertFalse(r.due(0))
        r.start(1000)
        assertFalse(r.due(1200))
        assertFalse(r.due(1399))
        assertTrue(r.due(1400))
        assertFalse(r.due(1420))
        assertTrue(r.due(1450))
        assertTrue(r.due(1500))
        // A late frame gives one repeat, then the rate again from then.
        assertTrue(r.due(1700))
        assertFalse(r.due(1720))
        assertTrue(r.due(1750))
        r.stop()
        assertFalse(r.due(5000))
    }

    @Test
    fun rightStickOnZUnlessZIsATrigger() {
        // Z only, or Z and RX with Z resting in the middle: the stick's on Z.
        assertTrue(stickOnZ(hasZ = true, hasRx = false, zMin = -1f, zFirst = -1f))
        assertTrue(stickOnZ(hasZ = true, hasRx = true, zMin = -1f, zFirst = 0.02f))
        // A trigger on Z, resting at -1 or going from 0 up: the stick's on RX.
        assertFalse(stickOnZ(hasZ = true, hasRx = true, zMin = -1f, zFirst = -1f))
        assertFalse(stickOnZ(hasZ = true, hasRx = true, zMin = 0f, zFirst = 0f))
        assertFalse(stickOnZ(hasZ = false, hasRx = true, zMin = 0f, zFirst = 0f))
    }
}
