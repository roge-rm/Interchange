package com.rm.interchange.map

import kotlin.test.Test
import kotlin.test.assertEquals

class ZoomAtTest {
    /** The view point at height 0 under screen point ([px], [py]). */
    private fun under(v: View, px: Float, py: Float, w: Float, h: Float): Pair<Float, Float> {
        val a = (px - w / 2) / v.halfWidth
        val b = (py - h / 2) / v.halfHeight
        return (v.focusViewX() + (a + b) / 2) to (v.focusViewY() + (b - a) / 2)
    }

    @Test
    fun thePointUnderThePointerStays() {
        for (turn in 0 until 4) for (by in listOf(1.15f, 1f / 1.15f, 2f, 0.5f)) {
            val v = View(256, 256)
            v.turnBy(turn)
            val w = 1200f
            val h = 800f
            val px = 900f
            val py = 220f
            val before = under(v, px, py, w, h)
            v.zoomAt(by, px, py, w, h)
            val after = under(v, px, py, w, h)
            assertEquals(before.first, after.first, 1e-3f, "turn $turn by $by")
            assertEquals(before.second, after.second, 1e-3f, "turn $turn by $by")
        }
    }

    @Test
    fun zoomingAtTheCentreKeepsTheFocus() {
        val v = View(256, 256)
        val x = v.focusX
        val y = v.focusY
        v.zoomAt(1.25f, 600f, 400f, 1200f, 800f)
        assertEquals(x, v.focusX, 1e-4f)
        assertEquals(y, v.focusY, 1e-4f)
    }
}
