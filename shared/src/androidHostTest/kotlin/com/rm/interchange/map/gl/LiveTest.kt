package com.rm.interchange.map.gl

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.rm.interchange.map.Atlas
import com.rm.interchange.map.Renderer
import com.rm.interchange.map.gateDepth
import com.rm.interchange.map.liveItems
import com.rm.interchange.map.waitingDepth
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
        b.begin(Renderer.SHARPEST, Renderer.LEVELS[Renderer.SHARPEST], 1f)
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
        b.begin(Renderer.SHARPEST, Renderer.LEVELS[Renderer.SHARPEST], 1f)
        b.sprite(0, 0, 100f, 100f)
        assertTrue(b.missing)
        assertEquals(0, b.batch()!!.count)
        // The signals and the model, at every level the map draws at.
        assertEquals(2 * (Atlas.LEVELS - Renderer.SHARPEST), atlas.uploads.size)
        atlas.uploaded(atlas.uploads)
        assertTrue(atlas.uploads.isEmpty())
        b.begin(Renderer.SHARPEST, Renderer.LEVELS[Renderer.SHARPEST], 1f)
        b.sprite(0, 0, 100f, 100f)
        assertFalse(b.missing)
        assertEquals(1, b.batch()!!.count)
    }

    @Test
    fun spritesStandWhereTheCanvasPutsThem() {
        val (_, b) = builder()
        val hw = Renderer.LEVELS[Renderer.SHARPEST]
        b.begin(Renderer.SHARPEST, hw, 1.5f)
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
        b.begin(Renderer.SHARPEST, hw, 1f)
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
        b.begin(Renderer.SHARPEST, Renderer.LEVELS[Renderer.SHARPEST], 1f)
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
    fun aircraftAndShadowsAreNotCutAndBuildingsGoInOnce() {
        val (_, b) = builder()
        b.begin(Renderer.SHARPEST, Renderer.LEVELS[Renderer.SHARPEST], 1f)
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
        b.begin(Renderer.SHARPEST, Renderer.LEVELS[Renderer.SHARPEST], 1f)
        b.sprite(0, 0, 0f, 0f)
        atlas.ready = true
        val old = atlas.uploads
        atlas.reset()
        assertFalse(atlas.ready)
        assertNull(atlas.get(0, Renderer.SHARPEST))
        // Answers for the last lot are let go.
        atlas.uploaded(old)
        b.begin(Renderer.SHARPEST, Renderer.LEVELS[Renderer.SHARPEST], 1f)
        b.sprite(0, 0, 0f, 0f)
        assertTrue(b.missing)
        assertFalse(atlas.get(0, Renderer.SHARPEST)!!.onGpu)
        assertEquals(2 * (Atlas.LEVELS - Renderer.SHARPEST), atlas.uploads.size)
    }

    @Test
    fun batchesAreFilledAgainOnlyOnceTheGlThreadIsPastThem() {
        val (_, b) = builder()
        b.begin(Renderer.SHARPEST, Renderer.LEVELS[Renderer.SHARPEST], 1f)
        b.sprite(0, 0, 10f, 10f)
        val first = b.batch(null, 0L)!!
        // The same again is the same batch.
        b.begin(Renderer.SHARPEST, Renderer.LEVELS[Renderer.SHARPEST], 1f)
        b.sprite(0, 0, 10f, 10f)
        assertSame(first, b.batch(first, 0L))
        // Moved: the GL thread hasn't copied the first out, so a new one.
        b.begin(Renderer.SHARPEST, Renderer.LEVELS[Renderer.SHARPEST], 1f)
        b.sprite(0, 0, 20f, 20f)
        val second = b.batch(first, 0L)!!
        assertNotSame(first, second)
        // It has now, and the second is shown: the first is filled again.
        b.begin(Renderer.SHARPEST, Renderer.LEVELS[Renderer.SHARPEST], 1f)
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
        b.begin(3, Renderer.LEVELS[3], 1f)
        b.sprite(0, 0, 0f, 0f)
        assertEquals(listOf(3), asked)
        assertTrue(b.missing)
    }
}
