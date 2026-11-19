package com.rm.interchange.map.gl

import com.rm.interchange.map.View
import com.rm.interchange.map.ViewAt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class ShadesTest {
    private val w = 1280
    private val h = 800

    private fun shades() = MapShades(256, 192).also { it.reset() }

    @Test
    fun aShadeSitsOnItsCentreAtTheFramesView() {
        val s = shades()
        s.add(100.5f, 80.25f, 4f, 30f, 0x384450, 0.12f)
        val b = s.batch(null)!!
        for (turn in 0 until 4) {
            for (half in floatArrayOf(4f, 24f, 96f)) {
                val at = ViewAt(turn, 104f, 78f, half)
                val out = FloatArray(ShadeBatch.PLACED)
                assertEquals(1, b.place(at, w, h, out), "turn $turn zoom $half")
                val v = View(256, 192)
                v.moveTo(at)
                val vx = v.toViewX(100.5f, 80.25f)
                val vy = v.toViewY(100.5f, 80.25f)
                assertEquals(v.screenX(vx, vy, w.toFloat()), out[0])
                assertEquals(v.screenY(vx, vy, 4f, h.toFloat()), out[1])
                assertEquals(30f * v.halfWidth * sqrt(2f), out[2])
                assertEquals(30f * v.halfHeight * sqrt(2f), out[3])
                assertEquals(0x38 / 255f, out[4])
                assertEquals(0x44 / 255f, out[5])
                assertEquals(0x50 / 255f, out[6])
                assertEquals(0.12f, out[7])
            }
        }
    }

    @Test
    fun itMovesWithAPan() {
        val s = shades()
        s.add(100f, 80f, 4f, 20f, 0xeef1f3, 0.55f)
        val b = s.batch(null)!!
        val a = FloatArray(ShadeBatch.PLACED)
        val c = FloatArray(ShadeBatch.PLACED)
        b.place(ViewAt(0, 100f, 80f, 16f), w, h, a)
        b.place(ViewAt(0, 110f, 80f, 16f), w, h, c)
        assertEquals(w / 2f, a[0], 0.5f)
        // Panned ten tiles along x at turn 0: five tile widths left, as a tile is two half widths across.
        assertEquals(-10f * 16f, c[0] - a[0], 0.5f)
        assertEquals(a[2], c[2])
    }

    @Test
    fun oneOffScreenIsLeftOut() {
        val s = shades()
        s.add(10f, 10f, 4f, 14f, 0x283038, 0.22f)
        s.add(128f, 96f, 4f, 14f, 0x283038, 0.22f)
        val b = s.batch(null)!!
        val out = FloatArray(2 * ShadeBatch.PLACED)
        assertEquals(1, b.place(ViewAt(0, 128f, 96f, 32f), w, h, out))
        assertEquals(w / 2f, out[0], 1f)
        // Far enough out, both show.
        assertEquals(2, b.place(ViewAt(0, 128f, 96f, 4f), w, h, out))
    }

    @Test
    fun aStillSkyHandsTheSameBatch() {
        val s = shades()
        s.add(50f, 50f, 4f, 20f, 0x384450, 0.12f)
        val first = s.batch(null)
        s.reset()
        s.add(50f, 50f, 4f, 20f, 0x384450, 0.12f)
        assertSame(first, s.batch(first))
        s.reset()
        s.add(51f, 50f, 4f, 20f, 0x384450, 0.12f)
        assertNotSame(first, s.batch(first))
    }

    @Test
    fun aFrameThatTookNoneKeepsTheLast() {
        val s = shades()
        s.add(50f, 50f, 4f, 20f, 0x384450, 0.12f)
        val first = s.batch(null)
        s.reset()
        assertSame(first, s.batch(first))
        // Taken with no fronts, as when the weather's cleared: none.
        s.take()
        assertNull(s.batch(first))
    }
}
