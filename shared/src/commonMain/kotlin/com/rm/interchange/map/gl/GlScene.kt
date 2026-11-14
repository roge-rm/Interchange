package com.rm.interchange.map.gl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.rm.interchange.map.Atlas
import com.rm.interchange.map.Cover
import com.rm.interchange.map.CurveArt
import com.rm.interchange.map.LevelSheets
import com.rm.interchange.map.LoadStep
import com.rm.interchange.map.Renderer
import com.rm.interchange.map.Sheets
import com.rm.interchange.map.View
import com.rm.interchange.map.WeatherCopy
import com.rm.interchange.map.zoomAt
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
            if (source == SIGNALS) levels[level]?.signals ?: sheetsSeen?.signals(level) else sheetsSeen?.vehicle(source, level, ask)

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
    private var nextLevel = 0

    /** How far the work under the cover has got, 0 to 1, and its [LoadStep]; worked out by [finish] while it's up. */
    var progress = 0f
        private set
    var step = LoadStep.UNPACKING
        private set

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
        val gesture = view.movedOnlyByFinger(lastX, lastY, lastHalfWidth)
        view.gestured = false
        holdZoom(view, width, height)
        view.gestured = false
        holdView(view, width, height, gesture)
        lastX = view.focusX
        lastY = view.focusY
        lastHalfWidth = view.halfWidth
        takeLevels(sheets, Renderer.levelFor(view.halfWidth))
        season()
        copyWeather()
        if (lastCheck.elapsedNow().inWholeMilliseconds >= CHECK_MILLIS) {
            lastCheck = clock.markNow()
            turns[view.turn]?.let { recheck(it) }
        }
        want(view.turn, view, GlFrame.of(view, width, height))
        if (view.turnsAsked != 0) {
            val asked = view.askedTurn
            val turned = View(view.mapWidth, view.mapHeight).also {
                it.turnBy(asked)
                it.focusX = view.focusX
                it.focusY = view.focusY
                it.halfWidth = view.halfWidth
            }
            val turnedFrame = GlFrame.of(turned, width, height)
            want(asked, view, turnedFrame)
            if (covers(asked, turnedFrame)) view.takeTurns()
        }
        for (t in 0 until 4) if (t != view.turn && t != view.askedTurn) drop(t)
        val frame = GlFrame.of(view, width, height)
        turns[view.turn]?.let { t ->
            // Once the map shows, or if the parts recorded first don't hold the screen, the rest of each region follows.
            if (sharp || (t.pending.all { it < 0L } && !covers(view.turn, frame))) fill(t, view)
        }
        live.reset()
        // Nothing recording till the first weather copy's in.
        val regions = turns[view.turn]?.regions
        nextFrame = frame
        nextRegions = regions
        val lv = levels
        val g = onGpu
        val level = Renderer.levelFor(view.halfWidth)
        nextLevel = level
        staticReady = regions != null && g != null && g[level] != null && g[level] === lv[level] && weather != null && covers(view.turn, frame)
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
        // A frame that skipped the moving drawing keeps the last batch, so it doesn't flip to none and back.
        val batch = if (movingWanted) live.batch(last?.live, liveTaken) else last?.live
        if (last == null || last.frame != frame || !last.regions.contentEquals(regions) || last.live !== batch) {
            shown = GlShown(frame, regions.copyOf(), batch)
            requestRender()
        }
        // Sheets go up in the GL thread's frames, which a still view doesn't otherwise ask for.
        if (atlas.uploads.isNotEmpty()) requestRender()
        if (staticReady && !live.missing) atlas.ready = true
        val ready = staticReady && (atlas.ready || !live.begun)
        if (ready && !sharp) { sharp = true; onSharp() }
        if (ready) progress = 1f else measure(frame)
        return ready
    }

    /**
     * Works out [progress] and [step] from the work the cover waits on. Before the map first shows the weights follow
     * the Fire's times for each, so the train keeps an even pace; after, only the regions on screen are waited on.
     */
    private fun measure(frame: GlFrame) {
        val held = regionsHeld(frame.turn, frame)
        if (sharp) {
            progress = 0.85f * held + if (staticReady) 0.15f else 0f
            step = LoadStep.DRAWING
            return
        }
        val l = nextLevel
        val unpacked = sheetsSeen?.unpacked(l) ?: 0f
        val lv = levels[l]
        val up = lv != null && onGpu?.get(l) === lv
        val weathered = (if (copy != null) 0.5f else 0f) + (if (weather != null) 0.5f else 0f)
        val moving = if (staticReady) 0.5f else 0f
        progress = W_UNPACK * unpacked + W_WEATHER * weathered + W_REGIONS * held + W_UPLOAD * (if (up) 1f else 0f) + W_MOVING * moving
        step = when {
            unpacked < 1f -> LoadStep.UNPACKING
            held < 1f || weather == null -> LoadStep.DRAWING
            !up -> LoadStep.SENDING
            else -> LoadStep.TRAINS
        }
    }

    private var skipped = 0

    /**
     * Whether the Compose map should put what moves in this frame: always once the static map's ready, else every
     * fourth frame, enough to ask for the vehicle sheets, as it takes the main thread from the work under the cover.
     */
    fun wantsMoving(): Boolean {
        movingWanted = staticReady || ++skipped % 4 == 0
        return movingWanted
    }

    private var movingWanted = true

    /** From the GL thread: [done] are on the GPU, so the vehicle sheets they came from can go. */
    fun liveUploaded(done: List<LiveUpload>) {
        scope.launch {
            atlas.uploaded(done)
            for (u in done) if (u.source != SIGNALS && u.generation == atlas.generation) sheetsSeen?.dropVehicle(u.source, u.level)
            revision.intValue++
        }
    }

    /** From the GL thread: [from] is what's on the GPU, a level a slot. Draws again, so the cover can lift. */
    fun uploaded(from: Array<LevelSheets?>) {
        onGpu = from
        scope.launch {
            revision.intValue++
            releasePixels(from)
        }
    }

    private val releasing = HashSet<LevelSheets>()

    /**
     * Lets go of the pixels of the levels in [from], which are on the GPU, once the building columns that cut what
     * moves are worked out from them, off the main thread. Only the signals stay on the heap; a lost context unpacks
     * the levels again.
     */
    private fun releasePixels(from: Array<LevelSheets?>) {
        for (l in from.indices) {
            val s = from[l] ?: continue
            if (s.released || !releasing.add(s)) continue
            scope.launch {
                val columns = try {
                    withContext(Dispatchers.Default) { Cover.columnsAhead(l, s) }
                } finally {
                    releasing.remove(s)
                }
                if (failed || onGpu?.getOrNull(l) !== s) return@launch
                Cover.takeColumns(l, columns)
                s.release()
            }
        }
    }

    /** Forgets the levels whose pixels were let go of, so they're unpacked again. */
    private fun unpackReleased() {
        var next: Array<LevelSheets?>? = null
        for (l in levels.indices) {
            if (levels[l]?.released != true) continue
            if (next == null) next = levels.copyOf()
            next[l] = null
            sheetsSeen?.forget(l)
        }
        if (next != null) levels = next
    }

    /** From the GL thread: new context [context], with nothing on the GPU. Draws again, so the cover comes back. */
    fun lost(context: Int) {
        onGpu = null
        scope.launch {
            atlas.reset(context)
            unpackReleased()
            revision.intValue++
        }
    }

    /** From the GL thread: a level wouldn't go on the GPU, so the map goes back to the chunks, which let go of level 0 as usual. */
    fun uploadFailed() {
        scope.launch {
            failed = true
            unpackReleased()
            levels = arrayOfNulls(Atlas.LEVELS)
            sheetsSeen?.keepSharpest = false
        }
    }

    /** The sheets [show] was last given. */
    private var sheetsSeen: Sheets? = null

    /** Whether the GPU couldn't take the sheets; the map draws with the chunks instead. */
    var failed by mutableStateOf(false)
        private set

    /**
     * Once the map shows, holds the zoom to the levels on the GPU, at the edge of the nearest, so a zoom never shows
     * another level standing in or nothing; the rest go up a moment after the screen's own.
     */
    private fun holdZoom(view: View, width: Int, height: Int) {
        val g = onGpu ?: return
        if (!sharp) return
        val want = Renderer.levelFor(view.halfWidth)
        if (g[want] != null) return
        val near = (Renderer.SHARPEST until Atlas.LEVELS).filter { g[it] != null }.minByOrNull { abs(it - want) } ?: return
        // Zooming in, the nearest is coarser: its sharpest zoom. Zooming out: just past the next one's.
        val held = if (near > want) Renderer.LEVELS[near] else Renderer.LEVELS[near + 1] * 1.001f
        // About the pinch's centre, so the point under the fingers stays put.
        if (!view.zoomedX.isNaN()) view.zoomAt(held / view.halfWidth, view.zoomedX, view.zoomedY, width.toFloat(), height.toFloat())
        view.halfWidth = held
    }

    /** Where [show] last left the view, to tell a finger's move from a jump. */
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastHalfWidth = Float.NaN

    /** The last place, zoom and screen size the recorded regions held the screen at, in [heldTurn], or -1 for none. */
    private var heldTurn = -1
    private var heldX = 0f
    private var heldY = 0f
    private var heldHalfWidth = 0f
    private var heldWidth = 0
    private var heldHeight = 0

    /**
     * Once the map shows, while some of the regions of the turn showing aren't recorded whole, puts a finger's move
     * ([gesture], from where the last frame left the view) back where they held the screen, so a pan or pinch never
     * shows a gap or the cover. Any other move, a jump to a vehicle or a new screen size, goes through, and the cover
     * shows till the regions there are in.
     */
    private fun holdView(view: View, width: Int, height: Int, gesture: Boolean) {
        if (!sharp) return
        val t = turns[view.turn] ?: return
        if (t.regions.all { it != null && it.cols == null }) {
            heldTurn = -1
            return
        }
        if (covers(view.turn, GlFrame.of(view, width, height))) {
            heldTurn = view.turn
            heldX = view.focusX
            heldY = view.focusY
            heldHalfWidth = view.halfWidth
            heldWidth = width
            heldHeight = height
        } else if (gesture && heldTurn == view.turn && width == heldWidth && height == heldHeight) {
            view.focusX = heldX
            view.focusY = heldY
            view.halfWidth = heldHalfWidth
        } else heldTurn = -1
    }

    /**
     * Asks [sheets] for every level the GPU map can draw at and takes those unpacked; the screen's level [first] alone
     * till the map shows, so it has the decoders to itself. Sheets let go of for another scene come back unpacked again.
     */
    private fun takeLevels(sheets: Sheets, first: Int) {
        var next: Array<LevelSheets?>? = null
        for (l in Renderer.SHARPEST until Atlas.LEVELS) {
            if (l != first && !sharp) continue
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
            val r = t.regions[i]
            if (r != null && r.cols == null) continue
            val row0 = i * REGION_ROWS
            for (vy in row0 until minOf(frame.viewHeight, row0 + REGION_ROWS)) {
                if (columns[2 * vy] > columns[2 * vy + 1]) continue
                if (r == null || !r.holds(vy, columns[2 * vy], columns[2 * vy + 1])) return false
            }
        }
        return true
    }

    /** How many of the regions reaching onto [frame]'s screen are recorded there for [turn], 0 to 1. */
    private fun regionsHeld(turn: Int, frame: GlFrame): Float {
        val t = turns[turn] ?: return 0f
        if (columns.size < frame.viewHeight * 2) columns = IntArray(frame.viewHeight * 2)
        visibleColumns(frame, columns)
        var need = 0
        var held = 0
        for (i in t.regions.indices) {
            val r = t.regions[i]
            val row0 = i * REGION_ROWS
            var on = false
            var holds = true
            for (vy in row0 until minOf(frame.viewHeight, row0 + REGION_ROWS)) {
                if (columns[2 * vy] > columns[2 * vy + 1]) continue
                on = true
                if (r == null || !r.holds(vy, columns[2 * vy], columns[2 * vy + 1])) holds = false
            }
            if (on) need++
            if (on && holds) held++
        }
        return if (need == 0) 1f else held.toFloat() / need
    }

    /**
     * Starts recording [turn]'s regions if they aren't, the nearest [view]'s focus first, once there's weather to record
     * with. Before the map first shows, only the tiles round [frame] are recorded, so the cover lifts sooner; the rest
     * of each region follows once it has ([fill]).
     */
    private fun want(turn: Int, view: View, frame: GlFrame) {
        if (turns[turn] != null || copy == null) return
        val n = regionCount(world, turn)
        val t = Turn(turn, arrayOfNulls(n), ArrayList())
        turns[turn] = t
        if (!sharp) {
            // A quarter of the screen round it, so a small move just after the cover lifts needn't wait.
            val mx = frame.width / 4f
            val my = frame.height / 4f
            val round = GlFrame(frame.turn, frame.originX + mx, frame.originY + my, frame.scale, frame.width + 2 * mx.toInt(), frame.height + 2 * my.toInt(), frame.viewWidth, frame.viewHeight)
            val shown = visibleColumns(round)
            for (i in regionOrder(t, view)) {
                val row0 = i * REGION_ROWS
                val rows = minOf(frame.viewHeight, row0 + REGION_ROWS) - row0
                if ((0 until rows).none { shown[2 * (row0 + it)] <= shown[2 * (row0 + it) + 1] }) continue
                record(t, i, shown.copyOfRange(2 * row0, 2 * (row0 + rows)))
            }
            return
        }
        for (i in regionOrder(t, view)) record(t, i)
    }

    /** [t]'s regions, the nearest [view]'s focus first. */
    private fun regionOrder(t: Turn, view: View): List<Int> {
        val turned = View(world.width, world.height).also { it.turnBy(t.turn) }
        val focusRow = (turned.toViewY(view.focusX, view.focusY) / REGION_ROWS).toInt()
        return (0 until t.regions.size).sortedBy { abs(it - focusRow) }
    }

    /** Records whole the regions of [t] recorded only in part or not at all, and not recording now. */
    private fun fill(t: Turn, view: View) {
        if (copy == null) return
        var any = false
        for (i in t.regions.indices) if ((t.regions[i] == null || t.regions[i]!!.cols != null) && t.pending[i] < 0L) any = true
        if (!any) return
        for (i in regionOrder(t, view)) if ((t.regions[i] == null || t.regions[i]!!.cols != null) && t.pending[i] < 0L) record(t, i)
    }

    /** Records region [i] of [t] at the world's version now, in the background, and puts it in when it's done; only [cols] of it if given. */
    private fun record(t: Turn, i: Int, cols: IntArray? = null) {
        val lk = look
        val yr = year
        val version = regionVersion(world, t.turn, i, lk, yr)
        val weather = copy!!
        t.pending[i] = version
        t.jobs.removeAll { it.isCompleted }
        t.jobs += scope.launch {
            val r = withContext(lane) { recordRegion(world, t.turn, i, lk, yr, weather, curves(), version, cols = cols) }
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
            try {
                val c = withContext(Dispatchers.Default) { WeatherCopy.of(world, (last?.id ?: 0) + 1, 0, last) }
                // Regions can record with the copy while the ground shader's weather is made from it.
                copy = c
                revision.intValue++
                val g = withContext(Dispatchers.Default) { GroundWeather.of(world, c, ground) }
                weather = g
                revision.intValue++
                requestRender()
            } finally {
                copying = false
            }
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

        /**
         * Each part's share of the train's run before the map first shows, adding to 1, from the Fire as installed
         * (lift 3.5 s): the screen's level unpacked by 1.8 s, the regions on screen by 2.5 s, on the GPU by 2.8 s, then
         * the first frame with what moves. They overlap, so these are rough.
         */
        const val W_UNPACK = 0.4f
        const val W_WEATHER = 0.05f
        const val W_REGIONS = 0.25f
        const val W_UPLOAD = 0.15f
        const val W_MOVING = 0.15f

        /** The signals sheet's rects at each level, in that level's pixels, as LandPainter.spriteAt divides them. */
        val signalRects = Array(Atlas.LEVELS) { l -> IntArray(Atlas.SIGNAL_RECTS.size) { Atlas.SIGNAL_RECTS[it] / (1 shl l) } }
    }
}
