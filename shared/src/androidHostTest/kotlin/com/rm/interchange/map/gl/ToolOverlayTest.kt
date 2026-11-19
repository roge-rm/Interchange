package com.rm.interchange.map.gl

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.rm.interchange.map.View
import com.rm.interchange.map.ViewAt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolOverlayTest {
    private val w = 1280
    private val h = 800

    private fun place(at: ViewAt) = OverlayPlace(at, w, h, ToolOverlay.marginFor(w), ToolOverlay.marginFor(h))

    private fun view(at: ViewAt) = View(64, 48).also { it.moveTo(at) }

    /** Where world point ([x], [y]) at ground height [g] shows on the screen at [at]. */
    private fun screen(at: ViewAt, x: Float, y: Float, g: Float): Pair<Float, Float> {
        val v = view(at)
        val vx = v.toViewX(x, y)
        val vy = v.toViewY(x, y)
        return v.screenX(vx, vy, w.toFloat()) to v.screenY(vx, vy, g, h.toFloat())
    }

    /** Checks a ground point drawn into the image at [drawn] lands on it in the frame at [frame], within [slack] pixels. */
    private fun assertLands(drawn: ViewAt, frame: ViewAt, slack: Float) {
        val p = place(drawn)
        val out = FloatArray(4)
        assertTrue(p.mapTo(frame, w, h, out))
        val bw = w + p.marginX * 2
        val bh = h + p.marginY * 2
        for ((x, y, g) in listOf(Triple(20.5f, 10.5f, 3f), Triple(30f, 22f, 0f), Triple(18f, 30f, 7f))) {
            val (dx, dy) = screen(drawn, x, y, g)
            val (fx, fy) = screen(frame, x, y, g)
            val ix = dx + p.marginX
            val iy = dy + p.marginY
            val mx = out[0] + ix / bw * (out[2] - out[0])
            val my = out[1] + iy / bh * (out[3] - out[1])
            assertTrue(abs(mx - fx) <= slack, "x $mx against $fx at turn ${frame.turn}")
            assertTrue(abs(my - fy) <= slack, "y $my against $fy at turn ${frame.turn}")
        }
    }

    @Test
    fun atItsOwnViewTheImageCoversTheScreenAndItsMargin() {
        val at = ViewAt(1, 30f, 20f, 24f)
        val p = place(at)
        val out = FloatArray(4)
        assertTrue(p.mapTo(at, w, h, out))
        assertEquals(-p.marginX.toFloat(), out[0])
        assertEquals(-p.marginY.toFloat(), out[1])
        assertEquals((w + p.marginX).toFloat(), out[2])
        assertEquals((h + p.marginY).toFloat(), out[3])
    }

    @Test
    fun aPanMovesItOnWholePixelsWithTheGround() {
        for (turn in 0 until 4) {
            val drawn = ViewAt(turn, 30f, 20f, 24f)
            val frame = ViewAt(turn, 31.37f, 19.21f, 24f)
            assertLands(drawn, frame, 0.5f)
            val out = FloatArray(4)
            place(drawn).mapTo(frame, w, h, out)
            assertEquals(out[0], kotlin.math.round(out[0]))
            assertEquals(out[1], kotlin.math.round(out[1]))
        }
    }

    @Test
    fun aPinchScalesItWithTheGround() {
        for (turn in 0 until 4) assertLands(ViewAt(turn, 30f, 20f, 24f), ViewAt(turn, 30.6f, 19.5f, 31f), 0.01f)
    }

    @Test
    fun anotherTurnDoesntDrawIt() {
        assertFalse(place(ViewAt(0, 30f, 20f, 24f)).mapTo(ViewAt(1, 30f, 20f, 24f), w, h, FloatArray(4)))
    }

    @Test
    fun itsStaleOnZoomTurnSizeOrAPanPastHalfItsMargin() {
        val at = ViewAt(2, 30f, 20f, 24f)
        val p = place(at)
        assertFalse(p.staleFor(at, w, h))
        assertTrue(p.staleFor(at.copy(halfWidth = 25f), w, h))
        assertFalse(p.staleFor(at.copy(halfWidth = 25f), w, h, zoomHeld = 0L))
        assertTrue(p.staleFor(at.copy(turn = 3), w, h))
        assertTrue(p.staleFor(at, w, h + 1))
        // Half the margin is 80 pixels across and 50 down, a tile 48 by 24 here: a tenth of one stays, five go.
        assertFalse(p.staleFor(at.copy(focusX = 30.1f), w, h))
        assertTrue(p.staleFor(at.copy(focusX = 35f), w, h))
    }

    /** An image with an id for its pixels, as the fake draw sets them. */
    private class Fake(override val width: Int, override val height: Int) : ImageBitmap {
        var pixels = 0
        override val colorSpace: ColorSpace get() = ColorSpaces.Srgb
        override val hasAlpha get() = true
        override val config get() = ImageBitmapConfig.Argb8888
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) {}
        override fun prepareToDraw() {}
    }

    private class Painted {
        var paints = 0
        var pixels = 1
        val made = ArrayList<Fake>()
        val overlay = ToolOverlay(
            newImage = { w, h -> Fake(w, h).also { made += it } },
            same = { a, b -> (a as Fake).pixels == (b as Fake).pixels },
            paint = { image, _, _, _ ->
                paints++
                (image as Fake).pixels = pixels
            },
        )
    }

    private fun Painted.shot(key: Any, at: ViewAt, now: Long = 0L) =
        overlay.shot(Density(1f), LayoutDirection.Ltr, key, view(at), w, h, now) { _, _ -> }

    @Test
    fun itsDrawnAgainOnlyForANewOverlayOrStaleView() {
        val t = Painted()
        val at = ViewAt(0, 30f, 20f, 24f)
        val key = Any()
        val a = t.shot(key, at)!!
        assertEquals(1, t.paints)
        assertSame(a, t.overlay.take())
        assertSame(a, t.shot(key, at))
        assertSame(a, t.shot(key, at.copy(focusX = 30.1f)))
        assertEquals(1, t.paints)
        assertNull(t.overlay.take())
        t.overlay.taken(a)
        val b = t.shot(key, at.copy(halfWidth = 50f))!!
        assertEquals(2, t.paints)
        assertNotSame(a, b)
    }

    @Test
    fun aKeyThatsEqualKeepsIt() {
        val t = Painted()
        val at = ViewAt(0, 30f, 20f, 24f)
        val a = t.shot(listOf(1, intArrayOf(2).toList()), at)!!
        assertSame(a, t.shot(listOf(1, intArrayOf(2).toList()), at))
        assertEquals(1, t.paints)
    }

    @Test
    fun aPinchKeepsItTillTheZoomHoldsOrScalesTooFar() {
        val t = Painted()
        val key = Any()
        val at = ViewAt(0, 30f, 20f, 24f)
        val a = t.shot(key, at, now = 0L)!!
        // Each frame of a pinch at another zoom keeps it, the GL thread scaling it.
        var now = 0L
        for (z in listOf(26f, 29f, 33f, 40f)) {
            now += 16L
            assertSame(a, t.shot(key, at.copy(halfWidth = z), now))
        }
        assertEquals(1, t.paints)
        // Held still long enough, it's drawn at the new zoom.
        assertSame(a, t.shot(key, at.copy(halfWidth = 40f), now + 100L))
        val b = t.shot(key, at.copy(halfWidth = 40f), now + OverlayPlace.ZOOM_HELD_MILLIS)!!
        assertNotSame(a, b)
        // Past twice the size, at once.
        val c = t.shot(key, at.copy(halfWidth = 81f), now + OverlayPlace.ZOOM_HELD_MILLIS + 16L)!!
        assertNotSame(b, c)
        assertEquals(3, t.paints)
    }

    @Test
    fun aNewOverlayThatLooksTheSameHandsNothingNew() {
        val t = Painted()
        val at = ViewAt(0, 30f, 20f, 24f)
        val a = t.shot(Any(), at)!!
        t.overlay.take()
        t.overlay.taken(a)
        assertSame(a, t.shot(Any(), at))
        assertEquals(2, t.paints)
        assertNull(t.overlay.take())
        t.pixels = 2
        val b = t.shot(Any(), at)!!
        assertNotSame(a, b)
        assertSame(b, t.overlay.take())
    }

    @Test
    fun itNeverDrawsIntoTheLastImageOrOneBeingTaken() {
        val t = Painted()
        val at = ViewAt(0, 30f, 20f, 24f)
        var last = t.shot(Any(), at)!!
        for (i in 2..8) {
            // The GL thread is still reading the last one when the next is drawn.
            val reading = t.overlay.take()
            t.pixels = i
            val next = t.shot(Any(), at)!!
            assertNotSame(last.image, next.image)
            if (reading != null) {
                assertNotSame(reading.image, next.image)
                t.overlay.taken(reading)
            }
            last = next
        }
        assertTrue(t.made.size <= 3, "made ${t.made.size}")
    }

    @Test
    fun aLostContextTakesTheLastAgain() {
        val t = Painted()
        val a = t.shot(Any(), ViewAt(0, 30f, 20f, 24f))!!
        t.overlay.take()
        t.overlay.taken(a)
        t.overlay.lose()
        assertSame(a, t.overlay.take())
    }
}
