package com.rm.interchange.map.gl

import com.rm.interchange.map.TileRecorder
import com.rm.interchange.map.View
import com.rm.interchange.map.WeatherCopy
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.World
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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

        /** A map big enough for a small screen to keep only part of it ([keepsWholeMap]). */
        const val SIZE = 128
        const val SW = 160
        const val SH = 100
        val big by lazy { World(NewGame(SIZE, SIZE, 7, 1930, Region.MIXED, towns = 2, industries = 2, rivals = 0)) }
        val bigWeather by lazy { WeatherCopy.of(big, 0) }
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

    private fun small(turn: Int, hw: Float, fx: Float, fy: Float) = GlFrame.of(View(SIZE, SIZE).also {
        it.turnBy(turn)
        it.halfWidth = hw
        it.focusX = fx
        it.focusY = fy
    }, SW, SH)

    private fun keptTurn(turn: Int) = KeptTurn(turn, if (turn % 2 == 0) big.width else big.height, if (turn % 2 == 0) big.height else big.width)

    /** Records the regions of [t] that are short, as [GlScene] does but at once. Returns which. */
    private fun recordShort(t: KeptTurn): List<Int> {
        val n = ArrayList<Int>()
        for (i in t.regions.indices) {
            if (!t.short(i)) continue
            val v = regionVersion(big, t.turn, i, look, year)
            val base = t.base(i, v, look, year, bigWeather.baked)
            t.begin(i, v)
            assertTrue(t.put(i, v, recordKept(big, t.turn, i, look, year, bigWeather, null, v, t.slice(i), base), base))
            n += i
        }
        return n
    }

    /** Whether the regions of [t] hold every tile of [f]'s screen. */
    private fun regionsHoldScreen(t: KeptTurn, f: GlFrame): Boolean {
        val screen = visibleColumns(f)
        for (vy in 0 until f.viewHeight) {
            if (screen[2 * vy] > screen[2 * vy + 1]) continue
            val r = t.regions[vy / REGION_ROWS] ?: return false
            if (!r.holds(vy, screen[2 * vy], screen[2 * vy + 1])) return false
        }
        return true
    }

    /** Tiles of [b] not in [a], both columns by view row. */
    private fun newTiles(a: IntArray, b: IntArray): Long {
        var n = 0L
        for (vy in 0 until b.size / 2) for (vx in b[2 * vy]..b[2 * vy + 1]) if (vx < a[2 * vy] || vx > a[2 * vy + 1]) n++
        return n
    }

    @Test
    fun aPanAddsOnlyTheNewTilesToTheRegionsKept() {
        for (turn in 0 until 4) {
            val t = keptTurn(turn)
            var x = 40f
            var y = 40f
            var f = small(turn, 32f, x, y)
            assertFalse(keepsWholeMap(f))
            assertTrue(t.keepRound(f))
            recordShort(t)
            assertTrue(t.whole())
            val before = t.regions.copyOf()
            val keep0 = t.keep!!.copyOf()
            var moved = false
            for (k in 0 until 200) {
                x += 0.5f
                y += 0.25f
                f = small(turn, 32f, x, y)
                if (t.keepRound(f)) {
                    moved = true
                    break
                }
                assertTrue(regionsHoldScreen(t, f))
            }
            assertTrue(moved, "turn $turn never kept again")
            // Till the new tiles are in, the old regions still hold the screen, and those let go of are gone.
            assertTrue(regionsHoldScreen(t, f), "turn $turn blank on the re-keep")
            for (i in t.regions.indices) if (!t.kept(i)) assertNull(t.regions[i])
            val keep1 = t.keep!!
            assertTrue(newTiles(keep0, keep1) * 3 < tiles(keep1), "turn $turn: ${newTiles(keep0, keep1)} of ${tiles(keep1)} tiles new")
            val recorded = recordShort(t)
            assertTrue(recorded.isNotEmpty())
            assertTrue(t.whole())
            assertTrue(regionsHoldScreen(t, f))
            val contents = Array<Any?>(t.regions.size) { before[it]?.content }
            for (i in t.regions.indices) {
                val r = t.regions[i] ?: continue
                assertTrue(holdsKept(r, keep1))
                val was = before[i]
                // Added to, not recorded again: a square drawn from it before is still drawn from it.
                if (was != null) {
                    assertSame(was.content, r.content, "turn $turn region $i recorded again")
                    assertTrue(squareRegionsSame(arrayOf(contents[i]), i, t.regions))
                }
                // And it's what recording it afresh gives.
                if (i !in recorded) continue
                val fresh = recordRegion(big, turn, i, look, year, bigWeather, version = r.version, cols = t.slice(i))
                assertTrue(fresh.packed!!.contentEquals(r.packed), "turn $turn region $i")
                assertTrue(fresh.starts.contentEquals(r.starts), "turn $turn region $i starts")
                assertEquals(fresh.firstPass, r.firstPass)
            }
        }
    }

    @Test
    fun aRegionLetGoOfWhileRecordingIsntPutBack() {
        val t = keptTurn(0)
        val here = small(0, 32f, 30f, 30f)
        t.keepRound(here)
        recordShort(t)
        val i = t.regions.indices.first { t.regions[it] != null }
        val v = regionVersion(big, 0, i, look, year)
        val base = t.base(i, v, look, year, bigWeather.baked)
        t.begin(i, v)
        val job = Job()
        t.regionJobs[i] = job
        t.jobs += job
        val r = recordKept(big, 0, i, look, year, bigWeather, null, v, t.slice(i), base)
        // A jump to the far corner lets it go while it records.
        assertTrue(t.keepRound(small(0, 32f, 110f, 110f)))
        assertFalse(t.kept(i))
        assertTrue(job.isCancelled)
        assertFalse(t.put(i, v, r, base))
        assertNull(t.regions[i])
        assertFalse(t.short(i))
    }

    @Test
    fun aRegionRecordingWhenTheKeptAreaMovesFinishesThenHasTheRestAdded() {
        val t = keptTurn(0)
        var x = 40f
        var f = small(0, 32f, x, x)
        t.keepRound(f)
        // Every region kept starts recording the slice kept now.
        val recording = t.regions.indices.filter { t.kept(it) }
        val versions = LongArray(t.regions.size) { regionVersion(big, 0, it, look, year) }
        val slices = Array(t.regions.size) { t.slice(it) }
        for (i in recording) t.begin(i, versions[i])
        while (!t.keepRound(f)) {
            x += 0.5f
            f = small(0, 32f, x, x)
        }
        val done = HashMap<Int, RegionSprites>()
        for (i in recording) {
            val r = recordKept(big, 0, i, look, year, bigWeather, null, versions[i], slices[i], null)
            assertEquals(t.kept(i), t.put(i, versions[i], r, null))
            if (t.kept(i)) done[i] = r
        }
        val short = done.keys.filter { t.short(it) }
        assertTrue(short.isNotEmpty())
        for (i in short) assertSame(done[i], t.base(i, versions[i], look, year, bigWeather.baked))
        recordShort(t)
        for (i in short) {
            assertSame(done[i]!!.content, t.regions[i]!!.content)
            assertTrue(holdsKept(t.regions[i]!!, t.keep!!))
        }
        assertTrue(regionsHoldScreen(t, f))
    }

    /** Whether [regions] hold every tile of [f]'s screen. */
    private fun holdScreen(regions: Array<RegionSprites?>, f: GlFrame): Boolean {
        val screen = visibleColumns(f)
        for (vy in 0 until f.viewHeight) {
            if (screen[2 * vy] > screen[2 * vy + 1]) continue
            val r = regions[vy / REGION_ROWS] ?: return false
            if (!r.holds(vy, screen[2 * vy], screen[2 * vy + 1])) return false
        }
        return true
    }

    @Test
    fun aRegionRecordedForAKeptAreaSinceMovedDoesntBlankTheScreen() {
        for (turn in 0 until 4) {
            val t = keptTurn(turn)
            var x = 40f
            var y = 40f
            var f = small(turn, 32f, x, y)
            t.keepRound(f)
            recordShort(t)
            // A pan till the kept area moves; the regions start having the new tiles added.
            while (!t.keepRound(f)) {
                x += 0.5f
                y += 0.25f
                f = small(turn, 32f, x, y)
            }
            val started = t.regions.indices.filter { t.short(it) }
            val versions = LongArray(t.regions.size) { regionVersion(big, turn, it, look, year) }
            val bases = Array(t.regions.size) { t.base(it, versions[it], look, year, bigWeather.baked) }
            val done = arrayOfNulls<RegionSprites>(t.regions.size)
            for (i in started) {
                t.begin(i, versions[i])
                done[i] = recordKept(big, turn, i, look, year, bigWeather, null, versions[i], t.slice(i), bases[i])
            }
            // While they record, the view goes back past where they were started from, which the old regions still hold.
            val would = t.regions.copyOf()
            for (i in started) would[i] = done[i]
            var past = false
            for (k in 0 until 400) {
                x -= 0.5f
                y -= 0.25f
                f = small(turn, 32f, x, y)
                t.keepRound(f)
                if (!regionsHoldScreen(t, f)) break
                if (!holdScreen(would, f)) {
                    past = true
                    break
                }
            }
            assertTrue(past, "turn $turn never left what the new regions hold")
            // They finish: none goes in that would leave the screen blank.
            for (i in started) t.put(i, versions[i], done[i]!!, bases[i])
            assertTrue(regionsHoldScreen(t, f), "turn $turn blank when the regions came in")
            // Then what's kept now is recorded, and holds it.
            recordShort(t)
            assertTrue(t.whole())
            assertTrue(regionsHoldScreen(t, f))
            for (r in t.regions) if (r != null) assertTrue(holdsKept(r, t.keep!!))
        }
    }

    /** Has the tiles each region of [t] but [except] is short of added at [lk], as [GlScene] does but at once. */
    private fun extendShort(t: KeptTurn, lk: Int, except: Int) {
        for (i in t.regions.indices) {
            if (i == except || !t.short(i)) continue
            val v = regionVersion(big, t.turn, i, lk, year)
            val base = t.base(i, v, lk, year, bigWeather.baked)
            t.begin(i, v)
            assertTrue(t.put(i, v, recordKept(big, t.turn, i, lk, year, bigWeather, null, v, t.slice(i), base), base))
        }
    }

    @Test
    fun aChangeOfSeasonWhilePanningDoesntBlankTheScreen() {
        val next = if (look == Climate.WINTER) Climate.SUMMER else Climate.WINTER
        for (turn in 0 until 4) {
            var reached = false
            val start = small(turn, 32f, 40f, 40f)
            val first = visibleColumns(start)
            val onScreen = (0 until regionCount(big, turn)).filter { i ->
                (i * REGION_ROWS until minOf(start.viewHeight, (i + 1) * REGION_ROWS)).any { first[2 * it] <= first[2 * it + 1] }
            }
            // Each region on screen in turn is the one still recording the new look, so the look shown stays the old one;
            // a pan each way.
            for (j in onScreen) for ((dx, dy) in listOf(0.5f to 0.25f, -0.5f to -0.25f, 0.5f to -0.25f, -0.5f to 0.25f)) {
                val t = keptTurn(turn)
                var x = 40f
                var y = 40f
                var f = small(turn, 32f, x, y)
                t.keepRound(f)
                recordShort(t)
                val plain = arrayOfNulls<RegionSprites>(t.regions.size)
                publishRegions(t.regions, t.published, look, visibleColumns(f))
                publishRegions(t.regions, plain, look)
                // The season changes: every region but j is recorded with the new look, j is still recording.
                for (i in t.regions.indices) {
                    if (!t.kept(i) || i == j) continue
                    val v = regionVersion(big, turn, i, next, year)
                    t.begin(i, v)
                    assertTrue(t.put(i, v, recordKept(big, turn, i, next, year, bigWeather, null, v, t.slice(i), null), null))
                }
                t.begin(j, regionVersion(big, turn, j, next, year))
                for (k in 0 until 200) {
                    x += dx
                    y += dy
                    f = small(turn, 32f, x, y)
                    t.keepRound(f)
                    extendShort(t, next, j)
                    // Where the regions don't hold the screen the pan is held, and once j is off it the look moves on.
                    if (!regionsHoldScreen(t, f)) break
                    val screen = visibleColumns(f)
                    if ((j * REGION_ROWS until minOf(f.viewHeight, (j + 1) * REGION_ROWS)).none { screen[2 * it] <= screen[2 * it + 1] }) break
                    publishRegions(t.regions, t.published, look, screen)
                    publishRegions(t.regions, plain, look)
                    assertTrue(holdScreen(t.published, f), "turn $turn region $j pan $dx, $dy blank at step $k")
                    if (!holdScreen(plain, f)) reached = true
                }
            }
            assertTrue(reached, "turn $turn never left what the old look's regions hold")
        }
    }

    @Test
    fun aRegionIsAddedToOnlyWithTheSnowAndIceItWasRecordedWith() {
        val t = keptTurn(0)
        t.keepRound(small(0, 32f, 40f, 40f))
        recordShort(t)
        val i = t.regions.indices.first { t.regions[it] != null }
        val v = regionVersion(big, 0, i, look, year)
        // A new copy with the same snow and ice adds to it.
        val same = WeatherCopy.of(big, bigWeather.id + 1, bigWeather)
        assertEquals(bigWeather.baked, same.baked)
        assertNotNull(t.base(i, v, look, year, same.baked))
        // One with snow fallen records it whole, and so does the copy after it once the snow's gone.
        val snow = bigWeather.snow.copyOf().also { it[0] = (it[0] + 20).toByte() }
        val snowy = WeatherCopy.of(big, same.id + 1, same).let { WeatherCopy(it.id, snow, it.ice, it.mud) }
        assertNull(t.base(i, v, look, year, snowy.baked))
        val thawed = WeatherCopy.of(big, snowy.id + 1, snowy)
        assertEquals(thawed.id, thawed.baked)
        assertNull(t.base(i, v, look, year, thawed.baked))
    }

    @Test
    fun anEditToARegionLetGoOfDoesntWaitOnIt() {
        val t = keptTurn(0)
        t.keepRound(small(0, 32f, 20f, 20f))
        recordShort(t)
        publishRegions(t.regions, t.published, look)
        val gone = t.regions.indices.last { !t.kept(it) }
        val kept = t.regions.indices.first { t.regions[it] != null }
        val before = LongArray(t.regions.size) { regionVersion(big, 0, it, look, year) }
        val now = before.copyOf()
        // A change under each, one world change more.
        now[gone] += 8L * TileRecorder.PART_ERA_COUNT
        now[kept] += 8L * TileRecorder.PART_ERA_COUNT
        val off = EditHold().apply { start(0L, 0, gone..gone, before) }
        assertTrue(off.changed(now))
        assertTrue(off.current(now, t.drawnVersions()), "waits on a region let go of")
        val on = EditHold().apply { start(0L, 0, kept..kept, before) }
        assertFalse(on.current(now, t.drawnVersions()), "the kept one isn't drawn with the edit yet")
    }
}
