package com.rm.interchange.map.gl

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.rm.interchange.map.Atlas
import com.rm.interchange.map.Zoom
import com.rm.interchange.map.gateDepth
import com.rm.interchange.map.liveItems
import com.rm.interchange.map.waitingDepth
import com.rm.interchange.sim.Signals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LiveTest {
    private object Blank : ImageBitmap {
        override val width get() = 1024
        override val height get() = 1024
        override val colorSpace get() = ColorSpaces.Srgb
        override val hasAlpha get() = true
        override val config get() = ImageBitmapConfig.Argb8888
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) {}
        override fun prepareToDraw() {}
    }

    /** Model 0 with two sprites, 40 by 20 and 30 by 50, offsets -20, -10 and -15, -40; the signals with one. */
    private val source = object : LiveSource {
        override fun image(source: Int, level: Int, ask: Boolean): ImageBitmap = Blank
        override fun rects(source: Int, level: Int): IntArray =
            if (source == SIGNALS) intArrayOf(0, 0, 16, 32, -8, -24) else intArrayOf(0, 0, 40, 20, -20, -10, 40, 0, 30, 50, -15, -40)
        override fun room() = true
    }

    private fun builder(): Pair<LiveAtlas, LiveBuilder> {
        val atlas = LiveAtlas()
        val b = LiveBuilder(atlas, source)
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        b.sprite(0, 0, 0f, 0f)
        atlas.uploaded(atlas.uploads)
        return atlas to b
    }

    /** As a 16 bit depth buffer stores it. */
    private fun q(d: Float) = kotlin.math.round(d * 65535f).toInt()

    private fun kinds(batch: LiveBatch) = (0 until batch.count).map { batch.data[(batch.covers + it) * LIVE_FLOATS].toInt() }

    @Test
    fun shelvesFillRowsThenPages() {
        val s = Shelf(100)
        val out = IntArray(3)
        assertTrue(s.place(60, 30, out, 0))
        assertEquals(listOf(0, 0, 0), out.toList())
        assertTrue(s.place(30, 20, out, 0))
        assertEquals(listOf(0, 61, 0), out.toList())
        // No room left on the row: the next row starts under the tallest.
        assertTrue(s.place(50, 40, out, 0))
        assertEquals(listOf(0, 0, 31), out.toList())
        // No room left on the page.
        assertTrue(s.place(50, 40, out, 0))
        assertEquals(listOf(1, 0, 0), out.toList())
        assertEquals(2, s.pages)
        assertFalse(s.place(100, 10, out, 0))
    }

    @Test
    fun spritesWaitTillTheirSheetsAreUpAtEveryLevel() {
        val atlas = LiveAtlas()
        val b = LiveBuilder(atlas, source)
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        b.sprite(0, 0, 100f, 100f)
        assertTrue(b.missing)
        assertEquals(0, b.batch()!!.count)
        // The signals and the model, at every level the map draws at.
        assertEquals(2 * (Atlas.LEVELS - Zoom.SHARPEST), atlas.uploads.size)
        atlas.uploaded(atlas.uploads)
        assertTrue(atlas.uploads.isEmpty())
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        b.sprite(0, 0, 100f, 100f)
        assertFalse(b.missing)
        assertEquals(1, b.batch()!!.count)
    }

    @Test
    fun spritesStandWhereTheCanvasPutsThem() {
        val (_, b) = builder()
        val hw = Zoom.LEVELS[Zoom.SHARPEST]
        b.begin(Zoom.SHARPEST, hw, 1.5f)
        b.sprite(0, 1, 100.3f, 200.6f)
        val d = b.batch()!!.data
        // As LandPainter.spriteAt: left = x + dx * scale, top = y - half height * scale + dy * scale, rounded down.
        val left = 100.3f - 15 * 1.5f
        val top = 200.6f - hw / 2 * 1.5f - 40 * 1.5f
        assertEquals(left.toInt().toFloat(), d[4])
        assertEquals(top.toInt().toFloat(), d[5])
        assertEquals((left + 30 * 1.5f).toInt().toFloat(), d[6])
        assertEquals((top + 50 * 1.5f).toInt().toFloat(), d[7])
        assertEquals(30f, d[10] - d[8])
        assertEquals(50f, d[11] - d[9])
        // Uncut, and a shadow is black at 0.3.
        assertEquals(1f, d[2])
        b.begin(Zoom.SHARPEST, hw, 1f)
        b.sprite(0, 1, 0f, 0f, LIVE_SHADOW)
        assertEquals(1f, b.batch()!!.data[3])
        assertEquals(0.3f, b.batch()!!.data[15])
    }

    @Test
    fun cutItemsHideOnlyBehindBuildingsOnNearerTiles() {
        // A car at view (5.4, 5.8), on tile 5, 5: hidden by a building on 6, 5 or 5, 6; one on 6, 4 or 4, 5 leaves it.
        // On the biggest map the 16 bit buffer still tells them apart.
        for (span in floatArrayOf(depthSpan(64, 64), depthSpan(16000, 16000))) {
            val car = q(cutDepth(5.4f, 5.8f, span))
            assertTrue(q(liveDepth(11f, span)) >= car)
            assertTrue(q(liveDepth(12f, span)) > car)
            assertTrue(q(liveDepth(10f, span)) < car)
            assertTrue(q(liveDepth(9f, span)) < car)
            // Uncut items pass everything the buildings put in.
            assertTrue(liveDepth(span - 33f, span) < 1f)
        }
    }

    @Test
    fun aBusWaitingAtAGateDrawsInFrontOfItAndTheCoachBehind() {
        // GateDepthTest's turn 0 crossing: the near post at (11.82, 5.18), 17 deep, the gate 17.9; a coach passing the
        // post at 18.6 and a bus waiting on the lane at 17.76, which the draw loop moves in front of the gate.
        val coachDepth = 18.6f
        val gate = gateDepth(17.9f, 11.82f, 5.18f, true, 10, 11, floatArrayOf(11.5f, 5.6f, 0.49f, coachDepth), 1)
        val bus = waitingDepth(17.76f, 12f, 5.39f, gate, 11.82f, 5.18f, 17f, true, 10, 11)
        val items = arrayListOf(
            floatArrayOf(bus, 300f, 200f, 0f, 0f, 0f, 12f, 5.39f),
            floatArrayOf(gate, 280f, 190f, 1f, -11f, 0f, -1f, -1f, 10f, 4f, 1f, 20f, 0f),
            floatArrayOf(coachDepth, 260f, 180f, 0f, 0f, 0f, 11.5f, 5.6f),
        )
        items.sortBy { it[0] }
        val (_, b) = builder()
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        val cuts = ArrayList<Pair<Float, Float>>()
        liveItems(items, emptyList(), 64f, b) { vx, vy, _, _, _, _ -> cuts += vx to vy }
        val k = kinds(b.batch()!!)
        // The coach, the post and arm's lines and lamps, then the bus.
        assertEquals(LIVE_SPRITE, k.first())
        assertEquals(LIVE_SPRITE, k.last())
        assertTrue(k.subList(1, k.size - 1).all { it == LIVE_LINE })
        assertEquals(260f - 20f, b.batch()!!.data[4])
        assertEquals(300f - 20f, b.batch()!!.data[(k.size - 1) * LIVE_FLOATS + 4])
        // Both vehicles are on the ground, so both are cut by what's in front of them.
        assertEquals(listOf(11.5f to 5.6f, 12f to 5.39f), cuts)
    }

    @Test
    fun aSignalsBandGoesInRightAfterItAndOnlyForItsKinds() {
        val (_, b) = builder()
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        val items = arrayListOf(
            floatArrayOf(1f, 10f, 10f, 0f, -1f, 0f, 1f, 1f),
            floatArrayOf(2f, 40f, 10f, 0f, -1f, Signals.ENTRY + 1f, 2f, 2f),
            // A cell the sheet doesn't have: no sprite, so no band either.
            floatArrayOf(2.5f, 55f, 10f, 5f, -1f, Signals.EXIT + 1f, 2f, 2f),
            floatArrayOf(3f, 70f, 10f, 0f, 0f, 0f, -1f, -1f),
        )
        val hides = ArrayList<Pair<Float, Float>>()
        liveItems(items, emptyList(), 128f, b, hide = { vx, vy, _, _, _, _ -> hides += vx to vy }) { _, _, _, _, _, _ -> }
        val batch = b.batch()!!
        assertEquals(listOf(LIVE_SPRITE, LIVE_SPRITE, LIVE_SHAPE, LIVE_SHAPE, LIVE_SPRITE), kinds(batch))
        // On the mast, 4 sharpest pixels wide on the stand point, at the depth the signal drew at.
        val o = (batch.covers + 2) * LIVE_FLOATS
        assertEquals(38f, batch.data[o + 4])
        assertEquals(batch.data[(batch.covers + 1) * LIVE_FLOATS + 2], batch.data[o + 2])
        // Each signal on the ground asks for what hides it, and goes in uncut by the depth buffer.
        assertEquals(listOf(1f to 1f, 2f to 2f, 2f to 2f), hides)
        assertEquals(1f, batch.data[(batch.covers + 1) * LIVE_FLOATS + 2])
    }

    @Test
    fun aHiddenRectLeavesTheRestOfASpriteAndRectShowing() {
        val (_, b) = builder()
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        b.sprite(0, 1, 0f, 0f)
        val whole = b.batch()!!.data.copyOf(LIVE_FLOATS)
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        val l = whole[4]
        val t = whole[5]
        val r = whole[6]
        val bottom = whole[7]
        // A rect over the middle of the sprite's height, from its left to two thirds across.
        val hx = l + (r - l) * 2f / 3f
        val ht = t + (bottom - t) / 3f
        val hb = t + (bottom - t) * 2f / 3f
        b.hide(l - 5f, ht, hx, hb)
        b.sprite(0, 1, 0f, 0f)
        b.clearHidden()
        b.rect(androidx.compose.ui.graphics.Color.Red, l, t, r - l, bottom - t)
        val batch = b.batch()!!
        // Above and below it on the left, and the right strip whole; then the rect after uncut.
        assertEquals(listOf(LIVE_SPRITE, LIVE_SPRITE, LIVE_SPRITE, LIVE_SHAPE), kinds(batch))
        val d = batch.data
        val pieces = (0 until 3).map { k -> (0 until 8).map { d[k * LIVE_FLOATS + 4 + it] } }
        assertEquals(listOf(l, t, hx, ht), pieces[0].subList(0, 4))
        assertEquals(listOf(l, hb, hx, bottom), pieces[1].subList(0, 4))
        assertEquals(listOf(hx, t, r, bottom), pieces[2].subList(0, 4))
        // The page rect follows: the right strip ends where the sprite did, and starts two thirds across.
        assertEquals(whole[10], pieces[2][6], 1e-3f)
        assertEquals(whole[8] + (whole[10] - whole[8]) * 2f / 3f, pieces[2][4], 1e-3f)
        assertEquals(whole[9] + (whole[11] - whole[9]) / 3f, pieces[0][7], 1e-3f)
    }

    @Test
    fun aircraftAndShadowsAreNotCutAndBuildingsGoInOnce() {
        val (_, b) = builder()
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        val items = arrayListOf(floatArrayOf(3f, 10f, 10f, 0f, 0f, 1f, -1f, -1f), floatArrayOf(1000f, 10f, 10f, 0f, 0f, 0f, -1f, -1f))
        var cuts = 0
        liveItems(items, emptyList(), 64f, b) { _, _, _, _, _, _ -> cuts++ }
        assertEquals(0, cuts)
        val d = b.batch()!!.data
        assertEquals(1f, d[2])
        assertEquals(1f, d[LIVE_FLOATS + 2])
        assertTrue(b.takeCover(7))
        assertFalse(b.takeCover(7))
        b.cover(0f, 0f, 4f, 4f, 9)
        val batch = b.batch()!!
        assertEquals(1, batch.covers)
        assertEquals(LIVE_COVER, batch.data[0].toInt())
    }

    @Test
    fun aLostContextPlacesEverythingAgain() {
        val atlas = LiveAtlas()
        val b = LiveBuilder(atlas, source)
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        b.sprite(0, 0, 0f, 0f)
        atlas.ready = true
        val old = atlas.uploads
        atlas.reset()
        assertFalse(atlas.ready)
        assertNull(atlas.get(0, Zoom.SHARPEST))
        // Answers for the last lot are let go.
        atlas.uploaded(old)
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        b.sprite(0, 0, 0f, 0f)
        assertTrue(b.missing)
        assertFalse(atlas.get(0, Zoom.SHARPEST)!!.onGpu)
        assertEquals(2 * (Atlas.LEVELS - Zoom.SHARPEST), atlas.uploads.size)
    }

    @Test
    fun batchesAreFilledAgainOnlyOnceTheGlThreadIsPastThem() {
        val (_, b) = builder()
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        b.sprite(0, 0, 10f, 10f)
        val first = b.batch(null, 0L)!!
        // The same again is the same batch.
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        b.sprite(0, 0, 10f, 10f)
        assertSame(first, b.batch(first, 0L))
        // Moved: the GL thread hasn't copied the first out, so a new one.
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        b.sprite(0, 0, 20f, 20f)
        val second = b.batch(first, 0L)!!
        assertNotSame(first, second)
        // It has now, and the second is shown: the first is filled again.
        b.begin(Zoom.SHARPEST, Zoom.LEVELS[Zoom.SHARPEST], 1f)
        b.sprite(0, 0, 30f, 30f)
        val third = b.batch(second, second.seq)!!
        assertSame(first, third)
        assertEquals(30f - 20f, third.data[4])
    }

    @Test
    fun pastTheBudgetOnlyTheLevelDrawnIsUnpacked() {
        val asked = ArrayList<Int>()
        val atlas = LiveAtlas()
        val b = LiveBuilder(atlas, object : LiveSource {
            override fun image(source: Int, level: Int, ask: Boolean): ImageBitmap? {
                if (source != SIGNALS && ask) asked += level
                return if (source == SIGNALS) Blank else null
            }
            override fun rects(source: Int, level: Int) = this@LiveTest.source.rects(source, level)
            override fun room() = false
        })
        b.begin(3, Zoom.LEVELS[3], 1f)
        b.sprite(0, 0, 0f, 0f)
        assertEquals(listOf(3), asked)
        assertTrue(b.missing)
    }
}
