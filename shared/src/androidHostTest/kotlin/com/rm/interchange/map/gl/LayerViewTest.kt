package com.rm.interchange.map.gl

import com.rm.interchange.map.View
import com.rm.interchange.map.ViewAt
import com.rm.interchange.map.zoomAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayerViewTest {
    private val w = 1280
    private val h = 800

    /** The frame what's drawn over the map lands on, drawn at [layers]'s view the way MapView draws it. */
    private fun layersFrame(view: View, layers: LayerView): GlFrame {
        var f: GlFrame? = null
        view.drawAt(layers.take()) { f = GlFrame.of(view, w, h) }
        return f!!
    }

    @Test
    fun theLayersDrawAtTheViewOfTheGlFrameOnScreen() {
        val view = View(1024, 1024)
        view.halfWidth = 4f
        val layers = LayerView()
        assertNull(layers.take(), "before any GL frame the layers draw at the view as it is")
        // The GL thread draws the first frame.
        var onScreen = GlFrame.of(view, w, h)
        assertTrue(layers.drew(view.at()))
        assertEquals(onScreen, layersFrame(view, layers))
        assertFalse(layers.drew(view.at()), "the same view again doesn't draw the layers again")

        // A pan the scene holds: the view's put back where the regions hold the screen, so nothing moves.
        val held = view.at()
        view.pan(300f, -120f)
        view.focusX = held.focusX; view.focusY = held.focusY; view.halfWidth = held.halfWidth
        assertEquals(onScreen, layersFrame(view, layers))

        // A pan the GL thread hasn't drawn yet: the layers stay with the ground on screen, not the finger.
        view.pan(-200f, 90f)
        view.zoomAt(1.3f, 400f, 300f, w.toFloat(), h.toFloat())
        val ahead = GlFrame.of(view, w, h)
        assertTrue(ahead != onScreen)
        assertEquals(onScreen, layersFrame(view, layers))
        assertEquals(ahead, GlFrame.of(view, w, h), "the view as it is comes back after the layers are drawn")

        // Once it's drawn, they move with it.
        assertTrue(layers.drew(view.at()))
        onScreen = ahead
        assertEquals(onScreen, layersFrame(view, layers))

        // A turn shows on the layers when the GL frame takes it.
        val before = view.at()
        view.turnBy(1)
        assertEquals(onScreen, layersFrame(view, layers))
        assertEquals(1, view.turn)
        assertTrue(layers.drew(view.at()))
        assertEquals(GlFrame.of(view, w, h), layersFrame(view, layers))
        assertTrue(before != view.at())
    }

    @Test
    fun theLayersDrawAtTheFrameJustHandedWhileTheGlThreadKeepsUp() {
        val view = View(1024, 1024)
        val layers = LayerView()
        val first = view.at()
        layers.handed(1L, first, held = true)
        assertNull(layers.take(), "before any GL frame the layers draw at the view as it is")
        layers.drew(first, 1L)
        view.pan(-200f, 90f)
        val next = view.at()
        // Frame 1 drawn, so frame 2 shows with this Compose frame: the layers draw at it now.
        layers.handed(2L, next, held = true)
        assertEquals(next, layers.take())
        assertFalse(layers.drew(next, 2L), "once drawn it doesn't draw the layers again")

        // Frame 4 handed with 3 not drawn: the GL thread's behind, so the layers stay at the frame it last drew.
        view.pan(50f, 50f)
        layers.handed(3L, view.at(), held = true)
        layers.take()
        view.pan(50f, 50f)
        val behind = view.at()
        layers.handed(4L, behind, held = true)
        assertEquals(next, layers.take())
        // It catches up: the layers move to the frame it drew, and from the next one handed they're in step again.
        val third = ViewAt(next.turn, next.focusX + 1f, next.focusY, next.halfWidth)
        assertTrue(layers.drew(third, 3L))
        assertEquals(third, layers.take())
        assertTrue(layers.drew(behind, 4L))
        layers.handed(5L, first, held = true)
        assertEquals(first, layers.take())

        // Where the GL thread can't hold its frame, the layers stay at the frame it last drew.
        layers.drew(first, 5L)
        view.pan(30f, 30f)
        layers.handed(6L, view.at(), held = false)
        assertEquals(first, layers.take())
    }

    @Test
    fun aFrameGivenADisplayTimeKeepsUpWithOneMoreOnItsWay() {
        val view = View(1024, 1024)
        val layers = LayerView()
        val first = view.at()
        layers.handed(1L, first, held = true, slack = 2)
        layers.drew(first, 1L)
        view.pan(-40f, 20f)
        layers.handed(2L, view.at(), held = true, slack = 2)
        view.pan(-40f, 20f)
        val third = view.at()
        // Frame 2 not drawn yet: with a display time frame 3 still shows with this Compose frame.
        layers.handed(3L, third, held = true, slack = 2)
        assertEquals(third, layers.take())
        // Two behind and it's fallen behind: the layers stay at the frame it last drew.
        view.pan(-40f, 20f)
        layers.handed(4L, view.at(), held = true, slack = 2)
        assertEquals(first, layers.take())
    }
}
