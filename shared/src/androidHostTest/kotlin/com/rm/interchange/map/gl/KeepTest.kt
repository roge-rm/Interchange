package com.rm.interchange.map.gl

import com.rm.interchange.map.View
import com.rm.interchange.map.WeatherCopy
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.World
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The GPU map keeps regions only round the screen on big maps; see [keepFrame]. */
class KeepTest {
    private companion object {
        const val W = 1280
        const val H = 800
        val world by lazy { World(NewGame(64, 32, 7, 1930, Region.MIXED, towns = 2, industries = 2, rivals = 0)) }
        val weather by lazy { WeatherCopy.of(world, 0) }
        val year by lazy { Calendar.year(world.startYear, world.day) }
        val look by lazy { Climate.look(world.settings.region, year, Calendar.dayOfYear(world.startYear, world.day)) }
    }

    private fun frame(size: Int, turn: Int, hw: Float, fx: Float, fy: Float) = GlFrame.of(View(size, size).also {
        it.turnBy(turn)
        it.halfWidth = hw
        it.focusX = fx
        it.focusY = fy
    }, W, H)

    /** Whether [keep] has every tile [visibleColumns] gives for [f]. */
    private fun holdsScreen(keep: IntArray, f: GlFrame): Boolean {
        val screen = visibleColumns(f)
        for (vy in 0 until f.viewHeight) {
            if (screen[2 * vy] > screen[2 * vy + 1]) continue
            if (keep[2 * vy] > screen[2 * vy] || keep[2 * vy + 1] < screen[2 * vy + 1]) return false
        }
        return true
    }

    private fun tiles(keep: IntArray): Long {
        var n = 0L
        for (vy in 0 until keep.size / 2) if (keep[2 * vy] <= keep[2 * vy + 1]) n += keep[2 * vy + 1] - keep[2 * vy] + 1
        return n
    }

    @Test
    fun farRegionsAreLetGoOfAndKeptAgainWhenTheViewComesNear() {
        val near = frame(1024, 0, 32f, 100f, 100f)
        val keep = visibleColumns(keepFrame(near))
        // The region round the far corner isn't kept.
        val far = 1024 / REGION_ROWS - 2
        assertFalse(regionKept(keep, far, near.viewHeight))
        val there = frame(1024, 0, 32f, 1000f, 1000f)
        assertFalse(keepHolds(keep, there, IntArray(2 * there.viewHeight)))
        val again = visibleColumns(keepFrame(there))
        assertTrue(regionKept(again, far, there.viewHeight))
        assertTrue(holdsScreen(again, there))
    }

    @Test
    fun aPanInStepsUnderAQuarterScreenNeverLeavesWhatsKept() {
        for (turn in 0 until 4) for (hw in floatArrayOf(View.MIN_HALF_WIDTH, 8f, 32f, 96f)) {
            var f = frame(1024, turn, hw, 200f, 300f)
            var keep = visibleColumns(keepFrame(f))
            var kept = 1
            val scratch = IntArray(2 * f.viewHeight)
            // A fifth of the screen a step, diagonally across the map and back up.
            val step = W / 5f / hw
            var x = 200f
            var y = 300f
            for (k in 0 until 400) {
                x = (x + if (k < 200) step / 2 else -step / 3).coerceIn(0f, 1024f)
                y = (y + if (k < 200) step / 3 else -step / 2).coerceIn(0f, 1024f)
                f = frame(1024, turn, hw, x, y)
                // The regions kept before still hold the screen: the new ones are recorded behind them.
                assertTrue(holdsScreen(keep, f), "turn $turn half width $hw step $k left what's kept")
                if (!keepHolds(keep, f, scratch)) {
                    keep = visibleColumns(keepFrame(f))
                    kept++
                    assertTrue(keepHolds(keep, f, scratch))
                }
            }
            assertTrue(kept > 1, "turn $turn half width $hw never kept again")
        }
    }

    @Test
    fun aZoomOutOfOneLevelStaysInWhatsKept() {
        for (turn in 0 until 4) {
            val keep = visibleColumns(keepFrame(frame(1024, turn, 32f, 500f, 400f)))
            assertTrue(holdsScreen(keep, frame(1024, turn, 16.5f, 500f, 400f)))
        }
    }

    @Test
    fun whatsKeptIsBoundedByTheScreenNotTheMap() {
        assertTrue(keepsWholeMap(frame(128, 0, 32f, 64f, 64f)))
        assertTrue(keepsWholeMap(frame(512, 1, 32f, 64f, 64f)))
        assertFalse(keepsWholeMap(frame(1024, 0, 32f, 64f, 64f)))
        val at1024 = tiles(visibleColumns(keepFrame(frame(1024, 0, View.MIN_HALF_WIDTH, 512f, 512f))))
        val at2048 = tiles(visibleColumns(keepFrame(frame(2048, 0, View.MIN_HALF_WIDTH, 1024f, 1024f))))
        val at4096 = tiles(visibleColumns(keepFrame(frame(4096, 0, View.MIN_HALF_WIDTH, 2048f, 2048f))))
        assertTrue(at1024 < 1024L * 1024 * 6 / 10, "$at1024 tiles kept at 1024")
        assertEquals(at2048, at4096)
        assertTrue(at1024 <= at2048)
        // Closer in, far fewer.
        assertTrue(tiles(visibleColumns(keepFrame(frame(2048, 0, 32f, 1024f, 1024f)))) < at2048 / 20)
    }

    @Test
    fun aKeptRegionRecordsOnlyItsSliceAndPacksAsTheWholeRecord() {
        for (turn in 0 until 4) {
            val vh = if (turn % 2 == 0) world.height else world.width
            val vw = if (turn % 2 == 0) world.width else world.height
            val f = GlFrame.of(View(world.width, world.height).also { it.turnBy(turn); it.halfWidth = 96f; it.focusX = 20f; it.focusY = 10f }, 400, 300)
            val keep = visibleColumns(keepFrame(f))
            var any = false
            for (i in 0 until regionCount(world, turn)) {
                val whole = recordRegion(world, turn, i, look, year, weather, keepRecord = true)
                assertNull(whole.packed)
                assertContentEquals(packRegion(whole), recordRegion(world, turn, i, look, year, weather).packed)
                if (!regionKept(keep, i, vh)) continue
                val slice = keepSlice(keep, i, vw, vh) ?: continue
                any = true
                val full = recordRegion(world, turn, i, look, year, weather, cols = slice, keepRecord = true)
                val part = recordRegion(world, turn, i, look, year, weather, cols = slice)
                assertTrue(holdsKept(part, keep))
                assertEquals(0, part.data.size)
                assertContentEquals(packRegion(full), part.packed)
                assertContentEquals(packRegion(full).copyOf(packedInts(full)), packRegion(part, IntArray(packedInts(part) + 7)).copyOf(packedInts(part)))
                // Its starts hold only the kept columns and one more a row.
                assertTrue(part.starts.size < whole.starts.size)
                for (pass in 0 until PASSES) for (vy in whole.row0 until whole.row1) {
                    val k = 2 * (vy - whole.row0)
                    if (slice[k] > slice[k + 1]) continue
                    for (vx in slice[k]..slice[k + 1]) {
                        assertEquals(whole.start(pass, vy, vx + 1) - whole.start(pass, vy, vx), part.start(pass, vy, vx + 1) - part.start(pass, vy, vx))
                    }
                }
            }
            assertTrue(any, "turn $turn kept no region in part")
        }
    }
}
