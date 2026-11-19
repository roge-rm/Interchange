package com.rm.interchange.map.gl

import com.rm.interchange.map.View
import com.rm.interchange.map.ViewAt
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LabelsTest {
    private val w = 1280
    private val h = 800

    private fun labels(atlas: LabelAtlas = LabelAtlas()) = MapLabels(atlas, 64, 48).also { it.reset() }

    @Test
    fun aLabelSitsAboveItsAnchorAtTheFramesView() {
        val l = labels()
        l.add(20.5f, 10.5f, 3f, 1.4f, intArrayOf(8, 16, 100, 40), 1f, LabelBatch.TOWN)
        val b = l.batch(null)!!
        for (turn in 0 until 4) {
            val at = ViewAt(turn, 22f, 12f, 24f)
            val out = FloatArray(LabelBatch.QUAD)
            assertEquals(1, b.place(at, w, h, out))
            val v = View(64, 48)
            v.moveTo(at)
            val vx = v.toViewX(20.5f, 10.5f)
            val vy = v.toViewY(20.5f, 10.5f)
            val sx = v.screenX(vx, vy, w.toFloat())
            val sy = v.screenY(vx, vy, 3f, h.toFloat()) - 24f * 1.4f
            assertEquals((sx - 50f).roundToInt().toFloat(), out[0], "left at turn $turn")
            assertEquals((sy - 40f).roundToInt().toFloat(), out[1], "top at turn $turn")
            assertEquals(100f, out[2])
            assertEquals(40f, out[3])
            assertEquals(8f, out[4])
            assertEquals(16f, out[5])
            assertEquals(1f, out[8])
        }
    }

    @Test
    fun theSizeOnScreenStaysAsTheMapZooms() {
        val l = labels()
        l.add(32f, 24f, 0f, 0.8f, intArrayOf(0, 0, 60, 20), 1f, LabelBatch.SIGN)
        val b = l.batch(null)!!
        val near = FloatArray(LabelBatch.QUAD)
        val far = FloatArray(LabelBatch.QUAD)
        b.place(ViewAt(0, 32f, 24f, 64f), w, h, near)
        b.place(ViewAt(0, 32f, 24f, 8f), w, h, far)
        assertEquals(near[2], far[2])
        assertEquals(near[3], far[3])
        // Centred over the anchor at the screen's middle, its bottom lifted by 0.8 half widths.
        assertEquals(w / 2f - 30f, near[0])
        assertEquals((h / 2f - 64f * 0.8f - 20f).roundToInt().toFloat(), near[1])
        assertEquals((h / 2f - 8f * 0.8f - 20f).roundToInt().toFloat(), far[1])
    }

    @Test
    fun signsKeepOffNamesAndEachOther() {
        val l = labels()
        val at = ViewAt(0, 32f, 24f, 32f)
        // A name, a sign under it, a sign far off, one on top of that, and a payment over the name.
        l.add(32f, 24f, 0f, 0.8f, intArrayOf(0, 0, 120, 40), 1f, LabelBatch.TOWN)
        l.add(32f, 24f, 0f, 0.8f, intArrayOf(200, 0, 60, 20), 1f, LabelBatch.SIGN)
        l.add(40f, 20f, 0f, 0.8f, intArrayOf(300, 0, 60, 20), 1f, LabelBatch.SIGN)
        l.add(40f, 20f, 0f, 0.9f, intArrayOf(400, 0, 60, 20), 1f, LabelBatch.SIGN)
        l.add(32f, 24f, 0f, 0.8f, intArrayOf(500, 0, 60, 20), 0.5f, LabelBatch.PAYMENT)
        val out = FloatArray(5 * LabelBatch.QUAD)
        val n = l.batch(null)!!.place(at, w, h, out)
        assertEquals(3, n)
        assertEquals(0f, out[4], "the name goes")
        assertEquals(300f, out[LabelBatch.QUAD + 4], "the first sign clear of the name goes")
        assertEquals(500f, out[2 * LabelBatch.QUAD + 4], "the payment goes over anything")
        assertEquals(0.5f, out[2 * LabelBatch.QUAD + 8])
    }

    @Test
    fun labelsOffScreenAreLeftOut() {
        val l = labels()
        l.add(2f, 2f, 0f, 1.4f, intArrayOf(0, 0, 50, 20), 1f, LabelBatch.TOWN)
        assertEquals(0, l.batch(null)!!.place(ViewAt(0, 60f, 40f, 64f), w, h, FloatArray(LabelBatch.QUAD)))
    }

    @Test
    fun theSameLabelsHandNothingNew() {
        val l = labels()
        l.add(5f, 5f, 1f, 1.4f, intArrayOf(0, 0, 50, 20), 1f, LabelBatch.TOWN)
        val first = l.batch(null)
        l.reset()
        l.add(5f, 5f, 1f, 1.4f, intArrayOf(0, 0, 50, 20), 1f, LabelBatch.TOWN)
        assertSame(first, l.batch(first))
        l.reset()
        l.add(5f, 5f, 1f, 1.4f, intArrayOf(0, 0, 50, 20), 0.9f, LabelBatch.TOWN)
        assertNotEquals(first, l.batch(first))
        l.reset()
        assertNull(l.batch(null), "no labels and none before hands none")
    }

    @Test
    fun anImageIsDrawnAndSentUpOnce() {
        val atlas = LabelAtlas(256)
        var drawn = 0
        val a = atlas.get("Smallville", 40, 10) { drawn++; IntArray(400) }!!
        val again = atlas.get("Smallville", 40, 10) { drawn++; IntArray(400) }!!
        assertSame(a, again)
        assertEquals(1, drawn)
        val b = atlas.get("Bigton", 50, 12) { drawn++; IntArray(600) }!!
        assertEquals(2, drawn)
        assertTrue(b[0] >= a[0] + a[2] || b[1] >= a[1] + a[3], "placed clear of the first")
        val ups = atlas.take(atlas.gen)!!
        assertEquals(listOf(a[0] to a[1], b[0] to b[1]), ups.list.map { it.x to it.y })
        assertNull(atlas.take(atlas.gen), "taken once")
        assertFalse(atlas.hasUploads)
    }

    @Test
    fun aFullPageStartsAgainThenTurnsAway() {
        val atlas = LabelAtlas(64)
        atlas.begin()
        atlas.begin()
        // Four 30 by 30 fit on 64 with their gaps; the fifth can't start again, as all four were used this frame.
        for (i in 0 until 4) atlas.get("l$i", 30, 30) { IntArray(900) }
        assertFalse(atlas.overflowed)
        assertEquals(4, atlas.size)
        assertNull(atlas.get("l4", 30, 30) { IntArray(900) })
        assertFalse(atlas.overflowed)
        // Two frames on, with only one of them used, it starts again.
        atlas.begin()
        atlas.begin()
        atlas.get("l0", 30, 30) { IntArray(900) }
        val before = atlas.gen
        val fifth = atlas.get("l4", 30, 30) { IntArray(900) }!!
        assertTrue(atlas.overflowed)
        assertEquals(before + 1, atlas.gen)
        assertEquals(0, fifth[0])
        assertEquals(0, fifth[1])
        assertEquals(1, atlas.size)
        assertEquals(1, atlas.take(atlas.gen)!!.list.size, "what was waiting before is dropped")
        atlas.again()
        for (i in 5 until 8) atlas.get("l$i", 30, 30) { IntArray(900) }
        assertNull(atlas.get("l8", 30, 30) { IntArray(900) }, "placed again, a full page leaves the rest out")
        assertFalse(atlas.overflowed)
        assertNull(atlas.get("huge", 80, 10) { IntArray(800) })
    }

    /** Places labels [name]0 on, [n] of them 30 by 30, as TownLabels does: once more if the page starts again. */
    private fun frame(atlas: LabelAtlas, n: Int, name: String = "l"): Int {
        atlas.begin()
        var drawn = 0
        val pass = { for (i in 0 until n) atlas.get("$name$i", 30, 30) { drawn++; IntArray(900) } }
        pass()
        if (atlas.overflowed) {
            atlas.again()
            pass()
        }
        return drawn
    }

    @Test
    fun morePlacedThanFitDoesNotStartAgainEachFrame() {
        val atlas = LabelAtlas(64)
        frame(atlas, 2, "m")
        frame(atlas, 0)
        val gen = atlas.gen
        // Two fit, then the page starts again part way and the first two are left out.
        assertEquals(6, frame(atlas, 6))
        assertEquals(gen + 1, atlas.gen, "starts again once")
        for (f in 0 until 5) assertEquals(0, frame(atlas, 6), "then draws nothing new in frame $f")
        assertEquals(gen + 1, atlas.gen)
        assertEquals(4, atlas.size)
    }

    @Test
    fun aFullPageGrowsUpToTheLimit() {
        val atlas = LabelAtlas(64)
        atlas.limit(100)
        assertEquals(4, frame(atlas, 4))
        val gen = atlas.gen
        assertEquals(9, frame(atlas, 9), "grown to 100, nine fit")
        assertEquals(100, atlas.side)
        assertEquals(gen + 1, atlas.gen)
        assertEquals(100, atlas.take(atlas.gen)!!.side)
        assertEquals(0, frame(atlas, 9))
        atlas.failed(100)
        assertEquals(4, frame(atlas, 9), "made smaller after it couldn't be made")
        assertEquals(64, atlas.side)
        assertEquals(gen + 2, atlas.gen)
    }

    @Test
    fun aRestartsImagesWaitForItsBatch() {
        val atlas = LabelAtlas(64)
        val l = MapLabels(atlas, 64, 48)
        l.reset()
        l.add(5f, 5f, 0f, 1f, atlas.get("a", 30, 30) { IntArray(900) }!!, 1f, LabelBatch.TOWN)
        val old = l.batch(null)!!
        assertEquals(1, atlas.take(old.gen)!!.list.size)
        // The page starts again while the GL thread still draws the old batch: nothing goes up for it.
        atlas.lose()
        l.reset()
        l.add(5f, 5f, 0f, 1f, atlas.get("b", 30, 30) { IntArray(900) }!!, 1f, LabelBatch.TOWN)
        assertNull(atlas.take(old.gen))
        assertTrue(atlas.hasUploads)
        val next = l.batch(old)!!
        assertNotEquals(old.gen, next.gen)
        assertEquals(1, atlas.take(next.gen)!!.list.size)
    }

    @Test
    fun aLostPageIsDrawnAgain() {
        val atlas = LabelAtlas(256)
        var drawn = 0
        atlas.get("Smallville", 40, 10) { drawn++; IntArray(400) }
        atlas.take(atlas.gen)
        atlas.lose()
        atlas.begin()
        atlas.get("Smallville", 40, 10) { drawn++; IntArray(400) }
        assertEquals(2, drawn)
        assertEquals(1, atlas.take(atlas.gen)!!.list.size)
    }
}
