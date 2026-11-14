package com.rm.interchange.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewFingerTest {
    @Test
    fun tellsAFingersMoveFromAJump() {
        val v = View(64, 64)
        val x = v.focusX
        val y = v.focusY
        val hw = v.halfWidth
        assertFalse(v.movedOnlyByFinger(x, y, hw))
        v.pan(10f, 4f)
        v.zoomAt(1.2f, 100f, 50f, 400f, 300f)
        assertTrue(v.movedOnlyByFinger(x, y, hw))
        // A jump after the finger's move, or before it, isn't the finger's.
        v.focusX = 3f
        assertFalse(v.movedOnlyByFinger(x, y, hw))
        v.gestured = false
        v.focusX = 20f
        val jx = 30f
        v.pan(5f, 0f)
        assertFalse(v.movedOnlyByFinger(jx, y, hw))
    }
}
