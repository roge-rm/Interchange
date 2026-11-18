package com.rm.interchange.map.gl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.rm.interchange.map.Atlas
import com.rm.interchange.map.Cover
import com.rm.interchange.map.CurveArt
import com.rm.interchange.map.LevelSheets
import com.rm.interchange.map.LoadStep
import com.rm.interchange.map.MapOverlays
import com.rm.interchange.map.Zoom
import com.rm.interchange.map.Sheets
import com.rm.interchange.map.StaticCells
import com.rm.interchange.map.View
import com.rm.interchange.map.TileRecorder
import com.rm.interchange.map.WeatherCopy
import com.rm.interchange.map.heapBytes
import com.rm.interchange.map.zoomAt
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.VERY_WORN
import com.rm.interchange.sim.WORN
import com.rm.interchange.sim.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * The GPU map's surface, drawing [scene] with [page] (ARGB) round the map. It goes under the Compose map, which
 * draws overlays, labels and selection over it.
 */
@Composable
internal expect fun GlMapSurface(scene: GlScene, page: Int, modifier: Modifier)

/**
 * What the GPU draws next: the frame, the regions recorded so far for its turn, null where still recording, what moves,
 * the [Climate] look the regions are drawn with, and a count that goes up with each one made.
 */
internal class GlShown(val frame: GlFrame, val regions: Array<RegionSprites?>, val live: LiveBatch?, val look: Int, val seq: Long)

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
 * screen first, and the other directions ahead once the one showing is whole, as many as fit ([turnsAhead]). It
 * takes asked for turns once the new direction's regions on screen are in, and hands the GL thread what to draw
 * through [shown]. Regions are recorded again where the world under them changes ([regionVersion]); the
 * weather goes to the GL thread as [weather] and the time of year with each [GlShown], which the ground shader draws
 * in place. A new time of year shows once the regions on screen are recorded with it.
 * The world is read off the main thread, as [TileRecorder] reads it.
 *
 * What moves goes in [live] each frame and out with the frame in [shown], drawn over the static map.
 *
 * Main thread only, but for [shown], [levels], [weather], [overlays], [wear], [requestRender] and [atlas]'s
 * uploads, which the GL thread reads, and [uploaded], [liveUploaded], [lost], [uploadFailed] and [drew], which it calls.
 */
internal class GlScene(private val world: World, parent: CoroutineScope) {
    /** The scene's own jobs under [parent]'s, all cancelled by [close]. */
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    /**
     * [pending] holds the version each region is being recorded at, or -1. [due] marks regions found changed while
     * the turn was hidden and [jobs] is what's recording for it. [published] is what's drawn, recorded with the look
     * [shownLook] where it can be ([publishRegions]); the look moves on once the screen's regions all have the new one.
     */
    private class Turn(val turn: Int, val regions: Array<RegionSprites?>) {
        val pending = LongArray(regions.size) { -1L }
        val due = BooleanArray(regions.size)
        val jobs = ArrayList<Job>()
        /** What's recording each region, so one started again lets the last go. */
        val regionJobs = arrayOfNulls<Job>(regions.size)
        val published = arrayOfNulls<RegionSprites>(regions.size)
        var shownLook = -1
        /**
         * The view tiles kept recorded, columns by view row as [visibleColumns] gives them, round the screen
         * ([keepFrame]); regions outside are let go of, those inside recorded only there. Null till first set.
         */
        var keep: IntArray? = null
    }

    private val turns = arrayOfNulls<Turn>(4)
    /** Two at a time, leaving cores for the screen and the game. */
    private val lane = Dispatchers.Default.limitedParallelism(2)

    @Volatile
    var shown: GlShown? = null
        private set

    /**
     * Each level's sheets, from [Zoom.SHARPEST] on, once unpacked, for the GL thread to upload; it draws at
     * [Zoom.levelFor] the zoom. All are held once in, so a zoom never waits on one. A new
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

    /** The overlays drawn into the ground, as [MapOverlays] bits; a change draws again at once. */
    @Volatile
    var overlays = 0
        set(value) {
            if (field == value) return
            field = value
            if (value and MapOverlays.CONDITION == 0) wear = null
            requestRender()
        }

    /** How worn each track tile is, for the condition overlay, while it's on. */
    @Volatile
    var wear: TrackWear? = null
        private set
    private var lastWear = clock.markNow()
    /** Where [TrackWear.of] works the bands out, so a second with no change allocates nothing. */
    private var wearScratch: ByteArray? = null

    /** The tile last tapped or built on, so the region holding it is recorded again first. */
    var lastEdit = -1 to -1

    /** The player's last edit, while it waits to be drawn. */
    private val edit = EditHold()
    private var editChanged = false
    private var editCurrent = false
    private var editTicker: Job? = null

    /** Whether the player's last edit isn't drawn yet, so its preview should stay on screen. */
    val holdingEdit get() = edit.holding

    /**
     * Whether the held edit isn't on the GPU map yet, by the last [show]: what it adds to the moving layer, like a
     * tunnel's portals, waits till then.
     */
    val editWaiting get() = edit.holding && !(editChanged && editCurrent)

    /** The versions of [turn]'s regions now, to hand [edited] for a command sent before it's called; null if none. */
    fun editMark(turn: Int): LongArray? = turns[turn]?.let { versions(it) }

    /**
     * The player has just sent a build or remove command touching world tiles [x0], [y0] to [x1], [y1] while the
     * view's turn is [turn], the regions being at [before] when it went if given. From now till it's drawn the turn
     * showing is looked at for changes every frame and what's changed is recorded at once.
     */
    fun edited(turn: Int, x0: Int, y0: Int, x1: Int, y1: Int, before: LongArray? = null) {
        val t = turns[turn] ?: return
        val base = before?.takeIf { it.size == t.regions.size } ?: versions(t)
        edit.start(nowMillis(), turn, regionsTouching(world, turn, x0, y0, x1, y1), base)
        editChanged = false
        editCurrent = false
        if (editTicker?.isActive == true) return
        // The map may be still, as when paused, so it's drawn again each frame while the edit's held. With no draws,
        // as in the background, the hold runs out here.
        editTicker = scope.launch {
            while (edit.holding) {
                delay(EDIT_TICK_MILLIS)
                if (edit.expired(nowMillis())) stopEdit()
                revision.intValue++
            }
        }
    }

    private fun stopEdit() {
        edit.stop()
        drawWanted = 0L
    }

    /** Each region of [t]'s version now. */
    private fun versions(t: Turn) = LongArray(t.regions.size) { regionVersion(world, t.turn, it, look, year) }

    private val started = clock.markNow()
    private fun nowMillis() = started.elapsedNow().inWholeMilliseconds

    /** The [GlShown.seq] of the last one made, and of the last the GL thread drew, set by it through [drew]. */
    private var seq = 0L
    @Volatile
    private var drawnSeq = 0L
    /** The seq the held edit waits on the GL thread drawing, 0 for none. */
    @Volatile
    private var drawWanted = 0L

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
    private var nextLook = Climate.SUMMER

    /** The look the frame [show] worked out draws its regions with, for what moves to be cut by its trees. */
    val showingLook get() = nextLook
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
        val gesture = view.movedOnlyByFinger(lastX, lastY, lastHalfWidth)
        view.gestured = false
        holdZoom(view, width, height)
        view.gestured = false
        holdView(view, width, height, gesture)
        lastX = view.focusX
        lastY = view.focusY
        lastHalfWidth = view.halfWidth
        lastWidth = width
        lastHeight = height
        takeLevels(sheets, Zoom.levelFor(view.halfWidth))
        season()
        copyWeather()
        copyWear()
        watchEdit(view)
        if (lastCheck.elapsedNow().inWholeMilliseconds >= CHECK_MILLIS) {
            lastCheck = clock.markNow()
            turns[view.turn]?.let { recheck(it) }
            if (view.turnsAsked != 0) turns[view.askedTurn]?.let { recheck(it, view) }
            checkHidden(view)
        }
        want(view.turn, view, GlFrame.of(view, width, height))
        turns[view.turn]?.let { keepRound(it, GlFrame.of(view, width, height)) }
        if (view.turnsAsked != 0) {
            val asked = view.askedTurn
            val turnedFrame = turnedFrame(asked, view, width, height)
            turns[asked]?.let { keepRound(it, turnedFrame) }
            // A turn recorded ahead swaps at once; what changed in it since shows a moment later, but for a new
            // look of the year on screen, which it waits for.
            turns[asked]?.let {
                if (askedSeen != asked) recheck(it, view)
                if (sharp) fill(it, view)
            }
            askedSeen = asked
            want(asked, view, turnedFrame)
            turns[asked]?.let { publish(it, turnedFrame) }
            if (covers(asked, turnedFrame) && lookShown(asked, turnedFrame)) view.takeTurns()
        }
        if (view.turnsAsked == 0) askedSeen = -1
        for (t in 0 until 4) if (t != view.turn && t != view.askedTurn && !keptAhead(t, view.turn)) drop(t)
        val frame = GlFrame.of(view, width, height)
        turns[view.turn]?.let { t ->
            // Once the map shows, or if the parts recorded first don't hold the screen, the rest of each region follows.
            if (sharp || (t.pending.all { it < 0L } && !covers(view.turn, frame))) fill(t, view)
            if (sharp && whole(t)) {
                aheadCount = turnsAhead(t)
                ahead(view)
            }
        }
        live.reset()
        // Nothing recording till the first weather copy's in.
        val shownTurn = turns[view.turn]
        val regions = shownTurn?.let { publish(it, frame) }
        shownTurn?.let { measureEdit(it) }
        nextLook = shownTurn?.shownLook?.takeIf { it >= 0 } ?: look
        nextFrame = frame
        nextRegions = regions
        val lv = levels
        val g = onGpu
        val level = Zoom.levelFor(view.halfWidth)
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
        if (last == null || last.frame != frame || !last.regions.contentEquals(regions) || last.live !== batch || last.look != nextLook) {
            shown = GlShown(frame, regions.copyOf(), batch, nextLook, ++seq)
            requestRender()
        }
        if (edit.holding) {
            val holds = edit.step(nowMillis(), editChanged, editCurrent, seq, drawnSeq)
            drawWanted = if (holds) edit.waitSeq else 0L
        }
        // Sheets go up in the GL thread's frames, which a still view doesn't otherwise ask for.
        if (atlas.uploads.isNotEmpty()) requestRender()
        if (failed) {
            step = LoadStep.FAILED
            return false
        }
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
            val rects = StaticCells.rects
            val levelRects = StaticCells.levelRects
            scope.launch {
                val columns = try {
                    withContext(Dispatchers.Default) { Cover.columnsAhead(l, s, rects, levelRects) }
                } finally {
                    releasing.remove(s)
                }
                if (failed || onGpu?.getOrNull(l) !== s || StaticCells.rects !== rects) return@launch
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
            dropped.fill(false)
            atlas.reset(context)
            unpackReleased()
            revision.intValue++
        }
    }

    /**
     * From the GL thread: level [level] wouldn't go on the GPU. If it's the one the screen needs, the map can't draw
     * and the cover says so; any other is dropped, and the zoom stays off it.
     */
    fun uploadFailed(level: Int) {
        scope.launch {
            if (level == nextLevel) failed = true
            else {
                dropped[level] = true
                if (levels[level] != null) levels = levels.copyOf().also { it[level] = null }
                sheetsSeen?.forget(level)
            }
            revision.intValue++
            requestRender()
        }
    }

    /** The levels the GPU wouldn't take that the screen doesn't need, which aren't asked for again. */
    private val dropped = BooleanArray(Atlas.LEVELS)

    /** The sheets [show] was last given. */
    private var sheetsSeen: Sheets? = null

    /** Whether the GPU couldn't take the sheets; their pixels are kept from then on. */
    private var failed = false

    /**
     * Once the map shows, holds the zoom to the levels on the GPU, at the edge of the nearest, so a zoom never shows
     * another level standing in or nothing; the rest go up a moment after the screen's own.
     */
    private fun holdZoom(view: View, width: Int, height: Int) {
        val g = onGpu ?: return
        if (!sharp) return
        val want = Zoom.levelFor(view.halfWidth)
        if (g[want] != null) return
        val near = (Zoom.SHARPEST until Atlas.LEVELS).filter { g[it] != null }.minByOrNull { abs(it - want) } ?: return
        // Zooming in, the nearest is coarser: its sharpest zoom. Zooming out: just past the next one's.
        val held = if (near > want) Zoom.LEVELS[near] else Zoom.LEVELS[near + 1] * 1.001f
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
        // A sheet let go of before it went up is unpacked again; one on the GPU keeps its slot.
        for (l in levels.indices) {
            if (levels[l]?.released != true || onGpu?.getOrNull(l) === levels[l]) continue
            levels = levels.copyOf().also { it[l] = null }
            sheets.forget(l)
        }
        val next = takeLevels(levels, first, sharp, sheets::get, sheets::forget, dropped) ?: return
        levels = next
        requestRender()
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
        val t = Turn(turn, arrayOfNulls(n))
        turns[turn] = t
        keepRound(t, frame)
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
        for (i in regionOrder(t, view)) if (kept(t, i)) record(t, i)
    }

    /** [t]'s regions, the nearest [view]'s focus first. */
    private fun regionOrder(t: Turn, view: View): List<Int> {
        val turned = View(world.width, world.height).also { it.turnBy(t.turn) }
        val focusRow = (turned.toViewY(view.focusX, view.focusY) / REGION_ROWS).toInt()
        return (0 until t.regions.size).sortedBy { abs(it - focusRow) }
    }

    /** Records the regions of [t] that don't hold what's kept of them ([Turn.keep]) and aren't recording now. */
    private fun fill(t: Turn, view: View) {
        if (copy == null) return
        var any = false
        for (i in t.regions.indices) if (short(t, i)) any = true
        if (!any) return
        for (i in regionOrder(t, view)) if (short(t, i)) record(t, i)
    }

    /** Whether region [i] of [t] is kept but isn't recorded there, nor recording. */
    private fun short(t: Turn, i: Int): Boolean {
        if (t.pending[i] >= 0L || !kept(t, i)) return false
        val r = t.regions[i] ?: return true
        val k = t.keep ?: return r.cols != null
        return !holdsKept(r, k)
    }

    /** Whether region [i] of [t] has tiles kept. */
    private fun kept(t: Turn, i: Int): Boolean = t.keep?.let { regionKept(it, i, viewHeight(t.turn)) } ?: true

    /** What's kept of region [i] of [t], for [recordRegion]: null for the whole of it. */
    private fun slice(t: Turn, i: Int): IntArray? = t.keep?.let { keepSlice(it, i, viewWidth(t.turn), viewHeight(t.turn)) }

    private fun viewWidth(turn: Int) = if (turn % 2 == 0) world.width else world.height
    private fun viewHeight(turn: Int) = if (turn % 2 == 0) world.height else world.width

    /** Scratch for [keepHolds]. */
    private var keepScratch = IntArray(0)

    /**
     * Keeps [t]'s regions round [frame] ([keepFrame]), or the whole map if it's small ([keepsWholeMap]), once what's
     * kept no longer holds the screen with some to spare: those outside are let go of, on the GPU too, and the rest
     * are recorded there by [fill] and [ahead], the old ones showing till then.
     */
    private fun keepRound(t: Turn, frame: GlFrame) {
        val k = t.keep
        if (keepScratch.size < 2 * frame.viewHeight) keepScratch = IntArray(2 * frame.viewHeight)
        if (k != null && keepHolds(k, frame, keepScratch)) return
        val next = if (keepsWholeMap(frame)) wholeColumns(frame) else visibleColumns(keepFrame(frame))
        t.keep = next
        for (i in t.regions.indices) {
            if (regionKept(next, i, frame.viewHeight)) continue
            if (t.pending[i] >= 0L) t.regionJobs[i]?.cancel()
            t.pending[i] = -1L
            t.due[i] = false
            t.regions[i] = null
            t.published[i] = null
        }
        t.jobs.removeAll { it.isCompleted || it.isCancelled }
    }

    /** The frame [view] would show turned to [turn], on a screen [width] by [height]. */
    private fun turnedFrame(turn: Int, view: View, width: Int, height: Int): GlFrame {
        val turned = View(view.mapWidth, view.mapHeight).also {
            it.turnBy(turn)
            it.focusX = view.focusX
            it.focusY = view.focusY
            it.halfWidth = view.halfWidth
        }
        return GlFrame.of(turned, width, height)
    }

    /** The screen size [show] was last given. */
    private var lastWidth = 0
    private var lastHeight = 0

    /**
     * Records region [i] of [t] at the world's version now, in the background, and puts it in when it's done; only
     * [cols] of it if given, by default what's kept of it. An [urgent] one, for the player's edit, doesn't wait behind
     * the others.
     */
    private fun record(t: Turn, i: Int, cols: IntArray? = slice(t, i), urgent: Boolean = false) {
        val lk = look
        val yr = year
        val version = regionVersion(world, t.turn, i, lk, yr)
        val weather = copy!!
        // Only an urgent recheck records a region already recording; the old recording's result would be dropped.
        if (t.pending[i] >= 0L) t.regionJobs[i]?.cancel()
        t.pending[i] = version
        t.due[i] = false
        t.jobs.removeAll { it.isCompleted }
        t.jobs += scope.launch {
            val r = withContext(if (urgent) Dispatchers.Default else lane) { recordRegion(world, t.turn, i, lk, yr, weather, curves(), version, cols = cols) }
            if (turns[t.turn] !== t || t.pending[i] != version) return@launch
            t.pending[i] = -1L
            t.regions[i] = r
            if (t.shownLook < 0) t.shownLook = lk
            revision.intValue++
        }.also { t.regionJobs[i] = it }
    }

    /** Stops what's recording for [turn] and lets it go. */
    private fun drop(turn: Int) {
        val t = turns[turn] ?: return
        for (j in t.jobs) j.cancel()
        turns[turn] = null
    }

    /** Cancels everything the scene has running, once it's no longer shown. */
    fun close() {
        stopEdit()
        for (t in 0 until 4) drop(t)
        scope.cancel()
    }

    /**
     * Whether the regions drawn on [frame]'s screen for [turn] all have the look it shows and the part eras now, after
     * [publish]. A turn kept ahead from before a change of season swaps in with its old look, then takes the new one
     * as the rest of its regions are recorded again.
     */
    private fun lookShown(turn: Int, frame: GlFrame): Boolean {
        val t = turns[turn] ?: return false
        if (columns.size < frame.viewHeight * 2) columns = IntArray(frame.viewHeight * 2)
        visibleColumns(frame, columns)
        val eras = TileRecorder.partEras(year)
        for (i in t.published.indices) {
            val r = t.published[i]
            if (r != null && r.look == t.shownLook && TileRecorder.partEras(r.year) == eras) continue
            if (onScreen(i, frame)) return false
        }
        return true
    }

    /** Whether region [i] has tiles on [frame]'s screen, from [columns] as [visibleColumns] left them for it. */
    private fun onScreen(i: Int, frame: GlFrame): Boolean {
        for (vy in i * REGION_ROWS until minOf(frame.viewHeight, (i + 1) * REGION_ROWS)) {
            if (columns[2 * vy] <= columns[2 * vy + 1]) return true
        }
        return false
    }

    /**
     * Works out what's drawn of [t] on [frame]'s screen: its look moves on to the look now once every region on screen
     * is recorded with it, and till then the regions recorded again with the new look wait. Returns [Turn.published].
     */
    private fun publish(t: Turn, frame: GlFrame): Array<RegionSprites?> {
        if (t.shownLook != look) {
            if (columns.size < frame.viewHeight * 2) columns = IntArray(frame.viewHeight * 2)
            visibleColumns(frame, columns)
            var all = true
            for (i in t.regions.indices) if (t.regions[i]?.look != look && onScreen(i, frame)) all = false
            if (all) t.shownLook = look
        }
        return publishRegions(t.regions, t.published, t.shownLook)
    }

    /** While the player's edit is held, looks at the turn showing for changes every frame and records them at once. */
    private fun watchEdit(view: View) {
        if (!edit.holding) return
        val t = turns[view.turn]
        if (t == null || view.turn != edit.turn) {
            stopEdit()
            return
        }
        recheck(t, urgent = true)
    }

    /** After [publish], whether the world under the edit's regions has moved on and what moved is drawn since. */
    private fun measureEdit(t: Turn) {
        if (!edit.holding || t.turn != edit.turn) return
        val v = versions(t)
        editChanged = edit.changed(v)
        // A region let go of, off round the screen, has nothing to show the edit in.
        editCurrent = edit.current(v, LongArray(t.published.size) { t.published[it]?.version ?: if (kept(t, it)) Long.MIN_VALUE else Long.MAX_VALUE })
    }

    /** From the GL thread: it has drawn the [GlShown] with [seq]. Draws again if the held edit was waiting on it. */
    fun drew(seq: Long) {
        drawnSeq = seq
        val wanted = drawWanted
        if (wanted in 1L..seq) {
            drawWanted = 0L
            scope.launch { revision.intValue++ }
        }
    }

    /**
     * Records again the regions of [t] whose world has changed since they were recorded, the one holding [lastEdit]
     * first and then out from it, or the nearest [near]'s focus first if given; the old ones show till then.
     */
    private fun recheck(t: Turn, near: View? = null, urgent: Boolean = false) {
        if (copy == null) return
        val changed = ArrayList<Int>()
        for (i in t.regions.indices) {
            val r = t.regions[i] ?: continue
            val v = regionVersion(world, t.turn, i, look, year)
            // An urgent one also starts again a region that began recording before the edit came in, but not one
            // after, so a region the game keeps changing still comes in.
            if (t.pending[i] >= 0L && (!urgent || t.pending[i] == v || t.pending[i] != edit.before(i))) continue
            if (v != r.version) changed += i else t.due[i] = false
        }
        if (changed.isEmpty()) return
        val (ex, ey) = lastEdit
        if (near != null) {
            val order = regionOrder(t, near)
            changed.sortBy { order.indexOf(it) }
        } else if (ex in 0 until world.width && ey in 0 until world.height) {
            val turned = View(world.width, world.height).also { it.turnBy(t.turn) }
            val row = (turned.toViewY(ex + 0.5f, ey + 0.5f) / REGION_ROWS).toInt()
            changed.sortBy { abs(it - row) }
        }
        for (i in changed) record(t, i, urgent = urgent)
    }

    /** Works out [wear] again every [WEAR_MILLIS] while the condition overlay's on, keeping the last if nothing changed. */
    private fun copyWear() {
        if (overlays and MapOverlays.CONDITION == 0) return
        val last = wear
        if (last != null && lastWear.elapsedNow().inWholeMilliseconds < WEAR_MILLIS) return
        lastWear = clock.markNow()
        val scratch = wearScratch?.takeIf { it.size == world.width * world.height } ?: ByteArray(world.width * world.height)
        wearScratch = scratch
        val next = TrackWear.of(world, last, scratch)
        if (next !== last) {
            wear = next
            requestRender()
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
     * Copies the weather the first time, then every [WEATHER_MILLIS] if it or the world has changed, and makes
     * the ground shader's from it.
     */
    private fun copyWeather() {
        if (copying) return
        val last = copy
        if (last != null && lastCopy.elapsedNow().inWholeMilliseconds < WEATHER_MILLIS) return
        val at = world.weatherVersions.fold(0L) { s, v -> s + v } * 31 + world.chunkVersions.fold(0L) { s, v -> s + v }
        if (last != null && at == copiedAt) return
        copying = true
        lastCopy = clock.markNow()
        copiedAt = at
        val ground = weather
        scope.launch {
            // Cleared however the job ends, so a failed or cancelled copy doesn't stop later ones.
            try {
                val c = withContext(Dispatchers.Default) { WeatherCopy.of(world, (last?.id ?: 0) + 1) }
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

    /** Whether every region of [t] holds what's kept of it and none is recording. */
    private fun whole(t: Turn) = t.pending.all { it < 0L } && t.regions.indices.none { short(t, it) }

    /** The turn asked for that was last looked at for changes, or -1. */
    private var askedSeen = -1

    /** How many other turns are recorded ahead and kept, from [AHEAD_ORDER]; set once the turn showing is whole. */
    private var aheadCount = 0

    /** How many other turns as big as what's kept of [t] fit in a fifth of the heap with it: all three on a 128 map, about 8 MB each. */
    private fun turnsAhead(t: Turn): Int {
        var bytes = 0L
        for (r in t.regions) if (r != null) bytes += r.heapInts * 4L
        if (bytes == 0L) return 3
        return (heapBytes() / 5 / bytes - 1).toInt().coerceIn(0, 3)
    }

    /** Whether [turn] is one of those kept ahead while [shownTurn] shows. */
    private fun keptAhead(turn: Int, shownTurn: Int): Boolean {
        for (k in 0 until aheadCount) if ((shownTurn + AHEAD_ORDER[k]) % 4 == turn) return true
        return false
    }

    /** Whether [t] is whole with nothing changed waiting. */
    private fun done(t: Turn) = whole(t) && t.due.none { it }

    /**
     * Once the turn showing is whole, records the turns kept ahead, the next quarter turn either way first, so a turn
     * to one of them swaps at once. Only [AHEAD_JOBS] regions at a time and only while the turns showing and asked
     * for have nothing recording, so theirs never wait behind more than those.
     */
    private fun ahead(view: View) {
        if (copy == null) return
        val asked = if (view.turnsAsked != 0) view.askedTurn else -1
        var free = AHEAD_JOBS
        for (t in 0 until 4) {
            val turn = turns[t] ?: continue
            val recording = turn.pending.count { it >= 0L }
            if (recording > 0 && (t == view.turn || t == asked)) return
            if (t != view.turn && t != asked) free -= recording
        }
        for (k in 0 until aheadCount) {
            if (free <= 0) return
            val turn = (view.turn + AHEAD_ORDER[k]) % 4
            if (turn == asked) continue
            val t = turns[turn] ?: Turn(turn, arrayOfNulls(regionCount(world, turn))).also { turns[turn] = it }
            if (lastWidth > 0) keepRound(t, turnedFrame(turn, view, lastWidth, lastHeight))
            if (done(t)) continue
            for (i in regionOrder(t, view)) {
                if (free <= 0) return
                if (t.pending[i] >= 0L || !kept(t, i)) continue
                if (!short(t, i) && !t.due[i]) continue
                record(t, i)
                free--
            }
            if (!done(t)) return
        }
    }

    private var hiddenCheck = 0

    /** Marks what's changed in one of the turns not showing, for [ahead] to record again. */
    private fun checkHidden(view: View) {
        hiddenCheck = (hiddenCheck + 1) % 4
        if (hiddenCheck == view.turn) return
        val t = turns[hiddenCheck] ?: return
        for (i in t.regions.indices) {
            val r = t.regions[i] ?: continue
            if (t.pending[i] < 0L && regionVersion(world, t.turn, i, look, year) != r.version) t.due[i] = true
        }
    }

    private fun curves(): CurveArt = CurveArt(world).also { it.refresh() }

    private companion object {
        /** The turns recorded ahead, in quarter turns from the one showing, nearest first. */
        val AHEAD_ORDER = intArrayOf(1, 3, 2)

        /** How many regions of the turns kept ahead record at once, at most. */
        const val AHEAD_JOBS = 2

        /** How often the weather is copied again, at most, in milliseconds. */
        const val WEATHER_MILLIS = 15_000L

        /** How often the track condition is looked at again while its overlay's on. */
        const val WEAR_MILLIS = 1_000L

        /** How often the world under the regions showing is looked at for changes, but for the player's edits. */
        const val CHECK_MILLIS = 250L

        /** How often the map's drawn again while an edit's held, about a frame. */
        const val EDIT_TICK_MILLIS = 16L

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

        /** The signals sheet's rects at each level, in that level's pixels: level 0's halved for each level. */
        val signalRects = Array(Atlas.LEVELS) { l -> IntArray(Atlas.SIGNAL_RECTS.size) { Atlas.SIGNAL_RECTS[it] / (1 shl l) } }
    }
}

/**
 * The levels the GPU map takes next from [levels], from [Zoom.SHARPEST] on: each it hasn't got yet that [get] has
 * unpacked, only [first] unless [all]. Sheets whose pixels another scene let go of are [forget]ten and asked for
 * again, so they're unpacked once more. Returns the new array, or null if nothing changed.
 */
internal fun takeLevels(levels: Array<LevelSheets?>, first: Int, all: Boolean, get: (Int) -> LevelSheets?, forget: (Int) -> Unit, skip: BooleanArray? = null): Array<LevelSheets?>? {
    var next: Array<LevelSheets?>? = null
    for (l in Zoom.SHARPEST until Atlas.LEVELS) {
        if (l != first && !all) continue
        if (skip != null && skip[l]) continue
        val had = levels[l]
        var got = had ?: get(l)
        if (had == null && got != null && got.released) {
            forget(l)
            got = get(l)?.takeUnless { it.released }
        }
        if (got !== had) {
            if (next == null) next = levels.copyOf()
            next[l] = got
        }
    }
    return next
}

/**
 * How worn each world tile's track is, for the condition overlay: a byte a tile, 0 for no track, then 1 very worn,
 * 2 worn and 3 fine, as the tile recorder's colours go. [version] goes up with each change.
 */
internal class TrackWear(val width: Int, val height: Int, val bands: ByteArray, val version: Int) {
    companion object {
        /** [world]'s wear now, or [last] if it's the same. */
        fun of(world: World, last: TrackWear?, scratch: ByteArray? = null): TrackWear {
            val size = world.width * world.height
            val bands = if (scratch != null && scratch.size == size) scratch.also { it.fill(0) } else ByteArray(size)
            for (i in bands.indices) {
                if (world.track[i].toInt() == 0) continue
                val c = world.condition[i].toInt() and 255
                bands[i] = (if (c < VERY_WORN) 1 else if (c < WORN) 2 else 3).toByte()
            }
            if (last != null && last.width == world.width && last.height == world.height && last.bands.contentEquals(bands)) return last
            // The GL thread reads a kept one, so a changed one is a copy.
            return TrackWear(world.width, world.height, if (bands === scratch) bands.copyOf() else bands, (last?.version ?: 0) + 1)
        }
    }
}
