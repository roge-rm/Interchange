package com.rm.interchange.map.gl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import com.rm.interchange.map.CurveArt
import com.rm.interchange.map.LevelSheets
import com.rm.interchange.map.Sheets
import com.rm.interchange.map.View
import com.rm.interchange.map.WeatherCopy
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.math.abs

/** Whether the switch asks for the GPU map in place of the chunk map: on Android, the system property debug.interchange.gl set to 1. */
internal expect fun glMapWanted(): Boolean

/**
 * The GPU map's surface, drawing [scene] with [page] (ARGB) round the map. It goes under the Compose map, which
 * still draws vehicles, overlays, labels and selection over it.
 */
@Composable
internal expect fun GlMapSurface(scene: GlScene, page: Int, modifier: Modifier)

/** What the GPU draws next: the frame and the regions recorded so far for its turn, null where still recording. */
internal class GlShown(val frame: GlFrame, val regions: Array<RegionSprites?>)

/**
 * Sprites that move every frame (vehicles, gates, signals, for G4), to merge into the static order. Each goes in
 * with the pass, view row and column it draws with, and the batcher splits the static runs there, so no sort of the
 * static sprites is needed. Unused so far.
 */
internal interface LiveSprites {
    fun collect(frame: GlFrame, into: LiveSpriteList)
}

/** One frame's [LiveSprites], kept in the order they're added, which is drawing order within a tile. */
internal class LiveSpriteList {
    var count = 0
        private set
    /** Pass, row, column, then the [SpriteRecorder] fields from sheet on: [STRIDE] ints a sprite. */
    var data = IntArray(STRIDE * 64)
        private set

    fun clear() { count = 0 }

    fun add(pass: Int, row: Int, column: Int, sheet: Int, left: Int, top: Int, w: Int, h: Int, sx: Int, sy: Int) {
        if ((count + 1) * STRIDE > data.size) data = data.copyOf(data.size * 2)
        val o = count * STRIDE
        data[o] = pass; data[o + 1] = row; data[o + 2] = column; data[o + 3] = sheet
        data[o + 4] = left; data[o + 5] = top; data[o + 6] = w; data[o + 7] = h; data[o + 8] = sx; data[o + 9] = sy
        count++
    }

    companion object { const val STRIDE = 10 }
}

/**
 * Far zooms on big maps (256 tiles and up), where every sprite on screen is more than the GPU can take a frame: a
 * region drawn from a picture of it rendered once on the GPU, in place of its sprites. Unused so far; see
 * docs/GPU_PLAN.md.
 */
internal interface FarRegions {
    /** Whether region [region] is drawn from its picture this frame, so its sprites are left out. */
    fun covers(region: RegionSprites, frame: GlFrame): Boolean
}

/**
 * The GPU map's side on the main thread: records each view direction's regions in the background, the nearest the
 * screen first, takes asked for turns once the new direction's regions on screen are in, and hands the GL thread
 * what to draw through [shown]. The world is read as a bake reads it.
 *
 * Main thread only, but for [shown], [sheets] and [requestRender], which the GL thread reads.
 */
internal class GlScene(private val world: World, private val scope: CoroutineScope) {
    private class Turn(val turn: Int, val regions: Array<RegionSprites?>, val jobs: MutableList<Job>)

    private val turns = arrayOfNulls<Turn>(4)
    /** Two at a time, as the chunk bakers are. */
    private val lane = Dispatchers.Default.limitedParallelism(2)

    @Volatile
    var shown: GlShown? = null
        private set

    /** The sheets at [SOURCE_LEVEL], for the GL thread to upload, once they're unpacked. */
    @Volatile
    var sheets: LevelSheets? = null
        private set

    /** Asks the surface for a frame; set by it, safe from any thread. */
    @Volatile
    var requestRender: () -> Unit = {}

    /** Goes up as each region's recorded, so the Compose map draws again and hands it on. */
    val revision = mutableIntStateOf(0)

    var live: LiveSprites? = null
    var far: FarRegions? = null

    private var columns = IntArray(0)
    private var sharp = false
    var onSharp: () -> Unit = {}

    /**
     * Shows [view] on a screen [width] by [height], from the Compose map's draw. Returns whether the screen has
     * everything it needs, so the cover over it can lift.
     */
    fun show(view: View, sheets: Sheets, width: Int, height: Int): Boolean {
        if (this.sheets == null) sheets.get(SOURCE_LEVEL)?.let { this.sheets = it; requestRender() }
        want(view.turn, view)
        if (view.turnsAsked != 0) {
            val asked = view.askedTurn
            want(asked, view)
            val turned = View(view.mapWidth, view.mapHeight).also {
                it.turnBy(asked)
                it.focusX = view.focusX
                it.focusY = view.focusY
                it.halfWidth = view.halfWidth
            }
            if (covers(asked, GlFrame.of(turned, width, height))) view.takeTurns()
        }
        for (t in 0 until 4) if (t != view.turn && t != view.askedTurn) drop(t)
        val frame = GlFrame.of(view, width, height)
        val regions = turns[view.turn]!!.regions
        val last = shown
        if (last == null || last.frame != frame || !last.regions.contentEquals(regions)) {
            shown = GlShown(frame, regions.copyOf())
            requestRender()
        }
        val ready = this.sheets != null && covers(view.turn, frame)
        if (ready && !sharp) { sharp = true; onSharp() }
        return ready
    }

    /** Whether every region with tiles reaching onto [frame]'s screen is recorded for [turn]. */
    private fun covers(turn: Int, frame: GlFrame): Boolean {
        val t = turns[turn] ?: return false
        if (columns.size < frame.viewHeight * 2) columns = IntArray(frame.viewHeight * 2)
        visibleColumns(frame, columns)
        for (i in t.regions.indices) {
            if (t.regions[i] != null) continue
            val row0 = i * REGION_ROWS
            for (vy in row0 until minOf(frame.viewHeight, row0 + REGION_ROWS)) if (columns[2 * vy] <= columns[2 * vy + 1]) return false
        }
        return true
    }

    /** Starts recording [turn]'s regions if they aren't, the nearest [view]'s focus first. */
    private fun want(turn: Int, view: View) {
        if (turns[turn] != null) return
        val n = regionCount(world, turn)
        val t = Turn(turn, arrayOfNulls(n), ArrayList())
        turns[turn] = t
        val turned = View(world.width, world.height).also { it.turnBy(turn) }
        val focusRow = (turned.toViewY(view.focusX, view.focusY) / REGION_ROWS).toInt()
        val d = world.day
        val year = Calendar.year(world.startYear, d)
        val look = Climate.look(world.settings.region, year, Calendar.dayOfYear(world.startYear, d))
        t.jobs += scope.launch {
            val weather = withContext(Dispatchers.Default) { WeatherCopy.of(world, 0, 0, null) }
            for (i in (0 until n).sortedBy { abs(it - focusRow) }) {
                t.jobs += scope.launch {
                    val r = withContext(lane) { recordRegion(world, turn, i, look, year, weather, curves()) }
                    if (turns[turn] !== t) return@launch
                    t.regions[i] = r
                    revision.intValue++
                }
            }
        }
    }

    private fun drop(turn: Int) {
        val t = turns[turn] ?: return
        for (j in t.jobs) j.cancel()
        turns[turn] = null
    }

    private fun curves(): CurveArt = CurveArt(world).also { it.refresh() }
}
