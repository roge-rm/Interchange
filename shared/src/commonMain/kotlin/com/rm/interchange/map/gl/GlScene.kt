package com.rm.interchange.map.gl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.rm.interchange.map.Atlas
import com.rm.interchange.map.CurveArt
import com.rm.interchange.map.LevelSheets
import com.rm.interchange.map.Renderer
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
import kotlin.time.TimeSource

/** Whether the switch asks for the GPU map in place of the chunk map: on Android, the system property debug.interchange.gl set to 1. */
internal expect fun glMapWanted(): Boolean

/**
 * The GPU map's surface, drawing [scene] with [page] (ARGB) round the map. It goes under the Compose map, which
 * draws overlays, labels and selection over it.
 */
@Composable
internal expect fun GlMapSurface(scene: GlScene, page: Int, modifier: Modifier)

/** What the GPU draws next: the frame, the regions recorded so far for its turn, null where still recording, and what moves. */
internal class GlShown(val frame: GlFrame, val regions: Array<RegionSprites?>, val live: LiveBatch?)

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
 * what to draw through [shown]. Regions are recorded again where the world under them changes ([regionVersion]); the
 * weather goes to the GL thread as [weather] and the time of year as [look], which the ground shader draws in place.
 * The world is read as a bake reads it.
 *
 * What moves goes in [live] each frame and out with the frame in [shown], drawn over the static map.
 *
 * Main thread only, but for [shown], [levels], [weather], [look], [requestRender] and [atlas]'s uploads, which the GL
 * thread reads, and [uploaded], [liveUploaded], [lost] and [uploadFailed], which it calls.
 */
internal class GlScene(private val world: World, private val scope: CoroutineScope) {
    /** [pending] holds the version each region is being recorded at, or -1. */
    private class Turn(val turn: Int, val regions: Array<RegionSprites?>, val jobs: MutableList<Job>) {
        val pending = LongArray(regions.size) { -1L }
    }

    private val turns = arrayOfNulls<Turn>(4)
    /** Two at a time, as the chunk bakers are. */
    private val lane = Dispatchers.Default.limitedParallelism(2)

    @Volatile
    var shown: GlShown? = null
        private set

    /**
     * Each level's sheets, from [Renderer.SHARPEST] on, once unpacked, for the GL thread to upload; it draws at
     * [Renderer.levelFor] the zoom, as the chunks do. All are held once in, so a zoom never waits on one. A new
     * array each time one comes.
     */
    @Volatile
    var levels: Array<LevelSheets?> = arrayOfNulls(Atlas.LEVELS)
        private set

    /** The [levels] array the GL thread last finished uploading. */
    @Volatile
    private var onGpu: Array<LevelSheets?>? = null

    /** The weather for the ground shader, once the first copy's made. */
    @Volatile
    var weather: GroundWeather? = null
        private set

    /** The [Climate] look of the year now, for the ground shader's grass. */
    @Volatile
    var look = Climate.SUMMER
        private set
    private var year = 0
    private var lookDay = -1

    /** The weather regions are recorded with, and when and at what versions it was last copied. */
    private var copy: WeatherCopy? = null
    private var copying = false
    private val clock = TimeSource.Monotonic
    private var lastCopy = clock.markNow()
    private var copiedAt = Long.MIN_VALUE
    private var lastCheck = clock.markNow()

    /** Asks the surface for a frame; set by it, safe from any thread. */
    @Volatile
    var requestRender: () -> Unit = {}

    /** Goes up as each region's recorded, so the Compose map draws again and hands it on. */
    val revision = mutableIntStateOf(0)

    var far: FarRegions? = null

    /** Where the live sprites go on the GPU, for the GL thread through [LiveAtlas.uploads] and [liveUploaded]. */
    val atlas = LiveAtlas()

    /** Takes what moves each frame, between [show] and [finish]. */
    val live = LiveBuilder(atlas, object : LiveSource {
        override fun image(source: Int, level: Int, ask: Boolean): ImageBitmap? =
            if (source == SIGNALS) levels[level]?.signals else sheetsSeen?.vehicle(source, level, ask)

        override fun rects(source: Int, level: Int): IntArray? =
            if (source == SIGNALS) signalRects[level] else sheetsSeen?.vehicleRects(source, level)

        override fun room() = sheetsSeen?.vehicleRoom() ?: false
    })

    /** The [LiveBatch.seq] of the last batch the GL thread copied out, set by it. */
    @Volatile
    var liveTaken = 0L

    /** What [show] worked out, published by [finish] with what moves. */
    private var nextFrame: GlFrame? = null
    private var nextRegions: Array<RegionSprites?>? = null
    private var staticReady = false

    private var columns = IntArray(0)
    private var sharp = false
    var onSharp: () -> Unit = {}

    /**
     * Shows [view] on a screen [width] by [height], from the Compose map's draw, which then puts what moves into
     * [live] and calls [finish].
     */
    fun show(view: View, sheets: Sheets, width: Int, height: Int) {
        sheetsSeen = sheets
        sheets.keepSharpest = true
        takeLevels(sheets)
        season()
        copyWeather()
        if (lastCheck.elapsedNow().inWholeMilliseconds >= CHECK_MILLIS) {
            lastCheck = clock.markNow()
            turns[view.turn]?.let { recheck(it) }
        }
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
        live.reset()
        // Nothing recording till the first weather copy's in.
        val regions = turns[view.turn]?.regions
        nextFrame = frame
        nextRegions = regions
        val lv = levels
        staticReady = regions != null && onGpu === lv && (Renderer.SHARPEST until Atlas.LEVELS).all { lv[it] != null } && weather != null && covers(view.turn, frame)
    }

    /**
     * Hands the GL thread the frame [show] worked out with what [live] took since, together so they never part.
     * Returns whether the screen has everything it needs, so the cover over it can lift: the first time, that's every
     * sheet showing on the GPU at every level, vehicles' too; after that a sheet new to the screen shows once it's up.
     */
    fun finish(): Boolean {
        val frame = nextFrame ?: return false
        val regions = nextRegions ?: return false
        val last = shown
        val batch = live.batch(last?.live, liveTaken)
        if (last == null || last.frame != frame || !last.regions.contentEquals(regions) || last.live !== batch) {
            shown = GlShown(frame, regions.copyOf(), batch)
            requestRender()
        }
        // Sheets go up in the GL thread's frames, which a still view doesn't otherwise ask for.
        if (atlas.uploads.isNotEmpty()) requestRender()
        if (staticReady && !live.missing) atlas.ready = true
        val ready = staticReady && (atlas.ready || !live.begun)
        if (ready && !sharp) { sharp = true; onSharp() }
        return ready
    }

    /** From the GL thread: [done] are on the GPU, so the vehicle sheets they came from can go. */
    fun liveUploaded(done: List<LiveUpload>) {
        scope.launch {
            atlas.uploaded(done)
            for (u in done) if (u.source != SIGNALS && u.generation == atlas.generation) sheetsSeen?.dropVehicle(u.source, u.level)
            revision.intValue++
        }
    }

    /** From the GL thread: [from] is all on the GPU. Draws again, so the cover can lift. */
    fun uploaded(from: Array<LevelSheets?>) {
        onGpu = from
        scope.launch { revision.intValue++ }
    }

    /** From the GL thread: new context [context], with nothing on the GPU. Draws again, so the cover comes back. */
    fun lost(context: Int) {
        onGpu = null
        scope.launch {
            atlas.reset(context)
            revision.intValue++
        }
    }

    /** From the GL thread: a level wouldn't go on the GPU, so the map goes back to the chunks, which let go of level 0 as usual. */
    fun uploadFailed() {
        scope.launch {
            failed = true
            levels = arrayOfNulls(Atlas.LEVELS)
            sheetsSeen?.keepSharpest = false
        }
    }

    /** The sheets [show] was last given. */
    private var sheetsSeen: Sheets? = null

    /** Whether the GPU couldn't take the sheets; the map draws with the chunks instead. */
    var failed by mutableStateOf(false)
        private set

    /** Asks [sheets] for every level the GPU map can draw at and takes those unpacked. */
    private fun takeLevels(sheets: Sheets) {
        var next: Array<LevelSheets?>? = null
        for (l in Renderer.SHARPEST until Atlas.LEVELS) {
            val had = levels[l]
            val got = had ?: sheets.get(l)
            if (got !== had) {
                if (next == null) next = levels.copyOf()
                next[l] = got
            }
        }
        if (next != null) {
            levels = next
            requestRender()
        }
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

    /** Starts recording [turn]'s regions if they aren't, the nearest [view]'s focus first, once there's weather to record with. */
    private fun want(turn: Int, view: View) {
        if (turns[turn] != null || copy == null) return
        val n = regionCount(world, turn)
        val t = Turn(turn, arrayOfNulls(n), ArrayList())
        turns[turn] = t
        val turned = View(world.width, world.height).also { it.turnBy(turn) }
        val focusRow = (turned.toViewY(view.focusX, view.focusY) / REGION_ROWS).toInt()
        for (i in (0 until n).sortedBy { abs(it - focusRow) }) record(t, i)
    }

    /** Records region [i] of [t] at the world's version now, in the background, and puts it in when it's done. */
    private fun record(t: Turn, i: Int) {
        val lk = look
        val yr = year
        val version = regionVersion(world, t.turn, i, lk, yr)
        val weather = copy!!
        t.pending[i] = version
        t.jobs.removeAll { it.isCompleted }
        t.jobs += scope.launch {
            val r = withContext(lane) { recordRegion(world, t.turn, i, lk, yr, weather, curves(), version) }
            if (turns[t.turn] !== t || t.pending[i] != version) return@launch
            t.pending[i] = -1L
            t.regions[i] = r
            revision.intValue++
        }
    }

    /** Records again the regions of [t] whose world has changed since they were recorded; the old ones show till then. */
    private fun recheck(t: Turn) {
        if (copy == null) return
        for (i in t.regions.indices) {
            val r = t.regions[i] ?: continue
            if (t.pending[i] >= 0L) continue
            if (regionVersion(world, t.turn, i, look, year) != r.version) record(t, i)
        }
    }

    /** The look of the year and the year, worked out once a day. */
    private fun season() {
        val d = world.day
        if (d == lookDay) return
        lookDay = d
        year = Calendar.year(world.startYear, d)
        val l = Climate.look(world.settings.region, year, Calendar.dayOfYear(world.startYear, d))
        if (l != look) {
            look = l
            requestRender()
        }
    }

    /**
     * Copies the weather the first time, then every [Renderer.WEATHER_MILLIS] if it or the world has changed, as the
     * chunk renderer does, and makes the ground shader's from it.
     */
    private fun copyWeather() {
        if (copying) return
        val last = copy
        if (last != null && lastCopy.elapsedNow().inWholeMilliseconds < Renderer.WEATHER_MILLIS) return
        val at = world.weatherVersions.fold(0L) { s, v -> s + v } * 31 + world.chunkVersions.fold(0L) { s, v -> s + v }
        if (last != null && at == copiedAt) return
        copying = true
        lastCopy = clock.markNow()
        copiedAt = at
        val ground = weather
        scope.launch {
            // Cleared however the job ends, so a failed or cancelled copy doesn't stop later ones.
            val (c, g) = try {
                withContext(Dispatchers.Default) {
                    val c = WeatherCopy.of(world, (last?.id ?: 0) + 1, 0, last)
                    c to GroundWeather.of(world, c, ground)
                }
            } finally {
                copying = false
            }
            copy = c
            weather = g
            revision.intValue++
            requestRender()
        }
    }

    private fun drop(turn: Int) {
        val t = turns[turn] ?: return
        for (j in t.jobs) j.cancel()
        turns[turn] = null
    }

    private fun curves(): CurveArt = CurveArt(world).also { it.refresh() }

    private companion object {
        /** How often the world under the regions showing is looked at for changes. */
        const val CHECK_MILLIS = 250L

        /** The signals sheet's rects at each level, in that level's pixels, as LandPainter.spriteAt divides them. */
        val signalRects = Array(Atlas.LEVELS) { l -> IntArray(Atlas.SIGNAL_RECTS.size) { Atlas.SIGNAL_RECTS[it] / (1 shl l) } }
    }
}
