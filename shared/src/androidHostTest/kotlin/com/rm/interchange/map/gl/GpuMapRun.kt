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
        }
    }

    private fun mb(bytes: Long) = "%.1f".format(bytes / 1e6)
}
