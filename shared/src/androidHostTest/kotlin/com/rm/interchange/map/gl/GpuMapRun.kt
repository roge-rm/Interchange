package com.rm.interchange.map.gl

import com.rm.interchange.map.View
import com.rm.interchange.map.WeatherCopy
import com.rm.interchange.map.Zoom
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.RivalLevel
import com.rm.interchange.sim.World
import kotlin.test.Test

/**
 * The heap the GPU map's regions hold for one view direction on a big map, on the Fire's screen (1280 by 800): every
 * region recorded whole, as before, against those kept round the screen ([keepFrame]) at the widest and closest
 * zooms. Slow, so it runs only when asked:
 * `./gradlew :shared:testAndroidHostTest --tests '*GpuMapRun*' -Pinterchange.gpumap=1024,2048`.
 */
class GpuMapRun {
    @Test
    fun measure() {
        val sizes = (System.getProperty("interchange.gpumap") ?: return).split(",").map { it.trim().toInt() }
        for (size in sizes) {
            val w = World(NewGame(size, size, 1, rivals = 6, rivalLevel = RivalLevel.HARD))
            val weather = WeatherCopy.of(w, 0)
            val year = Calendar.year(w.startYear, w.day)
            val look = Climate.look(w.settings.region, year, Calendar.dayOfYear(w.startYear, w.day))
            var whole = 0L
            var packed = 0L
            var sprites = 0L
            val t0 = System.nanoTime()
            for (i in 0 until regionCount(w, 0)) {
                val r = recordRegion(w, 0, i, look, year, weather, keepRecord = true)
                whole += r.heapInts * 4L
                packed += (packedInts(r) + r.starts.size) * 4L
                sprites += r.count
            }
            val ms = (System.nanoTime() - t0) / 1e6
            val tiles = size.toLong() * size
            println("gpumap $size: whole map ${mb(whole)} MB as recorded before (${whole / tiles} B a tile), ${mb(packed)} MB packed (${packed / tiles} B a tile), ${"%.2f".format(sprites.toDouble() / tiles)} sprites a tile, ${"%.1f".format(ms * 1000 / tiles)} us a tile")
            val closest = View.MAX_HALF_WIDTH * Zoom.LEVELS[1] / Zoom.LEVELS[0]
            for ((name, hw) in listOf("widest" to View.MIN_HALF_WIDTH, "default" to View.START_HALF_WIDTH, "closest" to closest)) {
                val view = View(w.width, w.height).also { it.halfWidth = hw; it.focusX = size / 2f; it.focusY = size / 2f }
                val f = GlFrame.of(view, 1280, 800)
                val keep = if (keepsWholeMap(f)) wholeColumns(f) else visibleColumns(keepFrame(f))
                var held = 0L
                var kept = 0L
                for (i in 0 until regionCount(w, 0)) {
                    if (!regionKept(keep, i, f.viewHeight)) continue
                    val r = recordRegion(w, 0, i, look, year, weather, cols = keepSlice(keep, i, f.viewWidth, f.viewHeight))
                    held += r.heapInts * 4L
                    kept++
                }
                var keptTiles = 0L
                for (vy in 0 until f.viewHeight) if (keep[2 * vy] <= keep[2 * vy + 1]) keptTiles += keep[2 * vy + 1] - keep[2 * vy] + 1
                println("gpumap $size $name: kept ${mb(held)} MB, $kept regions, $keptTiles tiles")
            }
            rekeep(w, look, year, weather)
            direct(w, look, year, weather)
        }
    }

    /**
     * What the direct passes draw at the widest zoom on the Fire's screen (800 by 1280) while some squares aren't drawn
     * ahead: every sprite on screen, as before, against the box round the squares left ([squaresBoxColumns]).
     */
    private fun direct(w: World, look: Int, year: Int, weather: WeatherCopy) {
        val view = View(w.width, w.height).also { it.halfWidth = View.MIN_HALF_WIDTH; it.focusX = w.width / 2f; it.focusY = w.height / 2f }
        val f = GlFrame.of(view, 800, 1280)
        val keep = visibleColumns(keepFrame(f))
        val regions = Array(regionCount(w, 0)) { i ->
            if (regionKept(keep, i, f.viewHeight)) recordRegion(w, 0, i, look, year, weather, cols = keepSlice(keep, i, f.viewWidth, f.viewHeight)) else null
        }
        val lv = Zoom.levelFor(f.scale * SOURCE_HALF_WIDTH)
        val on = squaresOn(f, lv)
        val runs = DrawRuns()
        fun sprites(cols: IntArray): String {
            runs.build(regions, cols)
            var n = 0L
            for (k in 0 until runs.size) n += runs.count(k)
            return "$n sprites in ${runs.size} draws"
        }
        println("gpumap ${w.width} direct passes at the widest, ${on[1] - on[0] + 1} by ${on[3] - on[2] + 1} squares: whole screen ${sprites(visibleColumns(f))}")
        println("gpumap ${w.width} direct passes, a column of squares left: ${sprites(squaresBoxColumns(f, lv, on[1], on[1], on[2], on[3]))}")
        println("gpumap ${w.width} direct passes, a row of squares left: ${sprites(squaresBoxColumns(f, lv, on[0], on[1], on[3], on[3]))}")
        println("gpumap ${w.width} direct passes, one square left: ${sprites(squaresBoxColumns(f, lv, on[1], on[1], on[3], on[3]))}")
    }

    private fun mb(bytes: Long) = "%.1f".format(bytes / 1e6)

    /**
     * The cost of a re-keep at the widest zoom: a pan across the screen till what's kept moves, then the regions
     * short of it recorded again whole, as before, against only their new tiles added ([extendRegion]), one thread.
     */
    private fun rekeep(w: World, look: Int, year: Int, weather: WeatherCopy) {
        val t = KeptTurn(0, w.width, w.height)
        val view = View(w.width, w.height).also { it.halfWidth = View.MIN_HALF_WIDTH; it.focusX = w.width / 2f; it.focusY = w.height / 2f }
        t.keepRound(GlFrame.of(view, 1280, 800))
        for (i in t.regions.indices) if (t.short(i)) {
            val v = regionVersion(w, 0, i, look, year)
            t.begin(i, v)
            t.put(i, v, recordRegion(w, 0, i, look, year, weather, version = v, cols = t.slice(i)), null)
        }
        do {
            // Across the screen, a world tile right and one up.
            view.focusX += 1f
            view.focusY -= 1f
        } while (!t.keepRound(GlFrame.of(view, 1280, 800)))
        val short = t.regions.indices.filter { t.short(it) }
        var newTiles = 0L
        var keptTiles = 0L
        for (i in short) {
            val r = t.regions[i]
            val c = t.slice(i) ?: continue
            for (k in 0 until c.size / 2) for (vx in c[2 * k]..c[2 * k + 1]) {
                keptTiles++
                if (r == null || !r.holds(i * REGION_ROWS + k, vx, vx)) newTiles++
            }
        }
        // Twice each, the second timed, so both run warm.
        var whole = 0.0
        var extended = 0.0
        var merging = 0.0
        var added = 0
        for (round in 0 until 2) {
            val t0 = System.nanoTime()
            for (i in short) recordRegion(w, 0, i, look, year, weather, version = t.regions[i]?.version ?: 0, cols = t.slice(i))
            whole = (System.nanoTime() - t0) / 1e6
            val t1 = System.nanoTime()
            added = 0
            merging = 0.0
            for (i in short) {
                val v = regionVersion(w, 0, i, look, year)
                val base = t.base(i, v, look, year, weather.baked)
                if (base == null) {
                    recordRegion(w, 0, i, look, year, weather, version = v, cols = t.slice(i))
                    continue
                }
                added++
                val piece = recordRegion(w, 0, i, look, year, weather, version = v, cols = t.slice(i), skip = base)
                val m0 = System.nanoTime()
                mergeRegion(base, piece)
                merging += (System.nanoTime() - m0) / 1e6
            }
            extended = (System.nanoTime() - t1) / 1e6
        }
        println("gpumap ${w.width} re-keep at the widest: $newTiles new of $keptTiles tiles in the regions short")
        println("gpumap ${w.width} re-keep at the widest: ${short.size} regions, recorded whole ${"%.0f".format(whole)} ms, new tiles added ($added) ${"%.0f".format(extended)} ms, of which merging ${"%.0f".format(merging)} ms")
    }
}
