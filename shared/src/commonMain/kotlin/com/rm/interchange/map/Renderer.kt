package com.rm.interchange.map

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.Workers
import com.rm.interchange.sim.CurveTiles
import com.rm.interchange.sim.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the land and track in chunks, each baked into a bitmap at one zoom
 * level for one view direction. A chunk is a [CHUNK_PIXELS] by [CHUNK_ROWS]
 * rectangle of the map as drawn at its level, with view point 0, 0 at height 0
 * at the picture's origin, so chunks meet without overlapping and four of one
 * level make one of the next out. Each holds every tile that reaches into it.
 *
 * Chunks bake on background threads, the screen first, and a chunk whose part
 * of the world has changed goes on showing until its new bitmap is ready. A
 * chunk not baked at this level yet shows from another level, scaled.
 *
 * Everything but the baking itself runs on the main thread.
 */
internal class Renderer(private val world: World, private val sheets: Sheets, private val scope: CoroutineScope) {
    private class Key(val level: Int, val turn: Int, val cx: Int, val cy: Int) {
        override fun equals(other: Any?) = other is Key && other.level == level && other.turn == turn && other.cx == cx && other.cy == cy
        override fun hashCode() = ((level * 4 + turn) * 8191 + cx) * 8191 + cy
    }

    /**
     * A baked chunk: its picture, the world's version, the [WeatherCopy] it shows and that copy's [WeatherCopy.blocks]
     * summed over it, when it went in and was last drawn. [old] is the picture it replaced for the weather, faded out
     * over [WEATHER_FADE_MILLIS] and then let go.
     */
    private class Entry(val image: ImageBitmap?, val top: Int, val version: Long, val weather: Int, val weatherSum: Long, var baked: Long, var used: Long, val fades: Boolean = false) {
        var old: Entry? = null
        val bytes get() = if (image == null) 0L else image.width.toLong() * image.height * 4
    }

    private val cache = HashMap<Key, Entry>()

    init {
        CurveArt.install()
        if (Probe.ON) Probe.log("config threads=${Workers.threads} BAKERS=$BAKERS BACKGROUND=$BACKGROUND SHARPEST=$SHARPEST heapMb=${heapBytes() / 1048576}")
    }

    /** When the weather batch started baking, for [Probe]. */
    private var batchStart = 0L

    /** The overlays baked in, as [ChunkBaker.GRID], [ChunkBaker.HEIGHTS] and [ChunkBaker.SNOW] bits. A change bakes every chunk again, the old ones drawn meanwhile. */
    var overlays = 0
    private val baking = HashSet<Key>()
    private var bytes = 0L
    private var frame = 0L

    /** Goes up as each chunk is baked, so the map draws again. */
    val revision = mutableIntStateOf(0)

    /** Two lanes: what's on screen now, and what's baked ahead or for the weather, so the first never waits on the second. */
    private val bakers = Dispatchers.Default.limitedParallelism(BAKERS)
    private val background = Dispatchers.Default.limitedParallelism(BACKGROUND)
    /** What's baking in the background lane. */
    private val behind = HashSet<Key>()
    private val clock = kotlin.time.TimeSource.Monotonic.markNow()
    /** When the first screen was all baked, or -1 till it is. */
    private var readyAt = -1L

    /** How much the map is still hidden behind the page as it first comes up: 1 till it's ready, fading to 0 just after. */
    fun cover(): Float = if (readyAt < 0) 1f else (1f - (clock.elapsedNow().inWholeMilliseconds - readyAt) / FADE_MILLIS.toFloat()).coerceIn(0f, 1f)

    /** Stand-ins for missing chunks baking on the screen lane. */
    private val standInsBaking = HashSet<Key>()

    /** The level zooming is heading for and since when, to stop waiting on chunks of older weather after a while. */
    private var waitTarget = -1
    private var waitSince = 0L

    /** The level on screen and the view's turn then, which zooming changes only once the next level's ready. */
    private var shownLevel = -1
    private var shownTurn = -1
    /** A weather batch's chunks baked so far and how many are still to come. */
    private val held = HashMap<Key, Entry>()
    private var batch = 0
    /** The chunks in the weather batch, those not started yet, and the sheet and copy they bake from. */
    private val batchKeys = HashSet<Key>()
    private val batchQueue = ArrayDeque<Key>()
    private var batchSheet: LevelSheets? = null
    private var batchWeather: WeatherCopy? = null
    /** Chunks in the cache, off screen when the weather last moved on, that show older weather, baked again nearest first. */
    private val refresh = HashSet<Key>()
    /** Chunks still fading from their [Entry.old] picture. */
    private val crossfading = HashSet<Key>()
    /**
     * The weather as the map shows it, every chunk baked from the same copy so neighbours agree; the next copy, while
     * the chunks on screen bake from it to be swapped in together; and when the last copy was asked for.
     */
    private var shown: WeatherCopy? = null
    private var next: WeatherCopy? = null
    private var copying = false
    private var lastCopy = 0L
    /** Where the screen's middle was last frame, in view tiles, and how it's drifting. */
    private var lastX = 0f
    private var lastY = 0f
    private var lastTurn = -1
    private var driftX = 0f
    private var driftY = 0f

    private var lookDay = -1
    private var look = Climate.SUMMER
    private var year = 0

    /** How the land looks at this time of year; every chunk bakes again when it turns. */
    private fun look(): Int {
        val d = world.day
        if (d != lookDay) {
            lookDay = d
            year = Calendar.year(world.startYear, d)
            look = Climate.look(world.settings.region, year, Calendar.dayOfYear(world.startYear, d))
        }
        return look
    }

    /** Draws the chunks that are on screen and asks for any that are missing or out of date. */
    fun DrawScope.draw(view: View) {
        frame++
        if (Probe.ON) Probe.frameStart()
        // Zooming across a level, the one shown stays on screen, scaled, till every chunk of the next is baked with the
        // weather showing, or for a moment if it can stand in for the rest; then the screen changes over at once.
        val target = levelFor(view.halfWidth)
        var level = if (shownLevel >= 0 && view.turn == shownTurn && kotlin.math.abs(shownLevel - target) == 1) shownLevel else target
        val wanted = ArrayList<Pair<Key, Float>>()
        val standIns = HashSet<Key>()
        var zoomMissing = 0
        val now = clock.elapsedNow().inWholeMilliseconds
        if (target != waitTarget) { waitTarget = target; waitSince = now }
        if (level != target) {
            var ready = true
            // After a moment it switches anyway if what's still to come has the level shown to stand in, so it looks
            // just as it did, and chunks of older weather show and fade to the new.
            val waited = now - waitSince
            val current = shown.takeIf { waited < ZOOM_WAIT_MILLIS }
            forEachVisible(view, target) { key, _, _, _, _ ->
                val entry = cache[key]
                if (entry == null || (current != null && entry.weatherSum != weatherSum(view, key, current))) {
                    if (entry != null || waited < ZOOM_WAIT_MILLIS || !covers(level, key)) ready = false
                    wanted += key to 1f
                    zoomMissing++
                }
            }
            if (ready) level = target
        }
        shownLevel = level
        shownTurn = view.turn
        if (Probe.ON) { Probe.zoom(level, target, zoomMissing); Probe.level = level; Probe.covered = readyAt < 0 }
        // Hidden till the first screen is all baked, then shown at once.
        if (readyAt < 0) {
            var all = true
            forEachVisible(view, level) { key, _, _, _, _ -> if (key !in cache) all = false }
            if (all) {
                readyAt = clock.elapsedNow().inWholeMilliseconds
                if (Probe.ON) Probe.log("ready")
            }
        }
        // Which way the map's moving, in view tiles a frame, to bake ahead that way first.
        val fx = view.focusViewX()
        val fy = view.focusViewY()
        if (view.turn == lastTurn) { driftX = driftX * 0.8f + (fx - lastX) * 0.2f; driftY = driftY * 0.8f + (fy - lastY) * 0.2f } else { driftX = 0f; driftY = 0f }
        lastX = fx; lastY = fy; lastTurn = view.turn
        // A copy of the weather to bake from: the first, then a new one every while if it's changed, counted from when
        // the map first shows so the first screen is all one copy.
        val weather = shown
        if (readyAt < 0) lastCopy = now
        if (!copying && next == null && (weather == null || (now - lastCopy > WEATHER_MILLIS && weatherVersions() != weather.versions))) {
            copying = true
            lastCopy = now
            val id = (weather?.id ?: 0) + 1
            scope.launch {
                val started = if (Probe.ON) Probe.now() else 0L
                // Not on the background lane, so it never waits behind the bakes there.
                val copy = withContext(Dispatchers.Default) { WeatherCopy.of(world, id, weatherVersions(), weather) }
                if (Probe.ON) Probe.weatherDiff(weather, copy, Probe.now() - started)
                copying = false
                if (shown == null) shown = copy else next = copy
                revision.intValue++
            }
        }
        if (weather == null) return
        // Chunks on screen from another copy of the weather than the one they'll show with, to bake again together.
        val showing = next ?: weather
        val stale = ArrayList<Key>()
        var missing = false
        forEachVisible(view, level) { key, left, top, right, bottom ->
            val entry = cache[key]
            val version = versionOf(view, level, key.cx, key.cy)
            if (entry != null) {
                entry.used = frame
                // A chunk just baked where nothing was fades in over what stood in for it, and one baked again for the
                // weather fades in over its old picture.
                val alpha = if (entry.fades) ((now - entry.baked) / FADE_MILLIS.toFloat()).coerceIn(0f, 1f) else 1f
                if (alpha < 1f) drawFallback(view, level, key)
                val old = entry.old
                if (old != null) {
                    drawChunk(old, left, top, right, bottom)
                    drawChunk(entry, left, top, right, bottom, ((now - entry.baked) / WEATHER_FADE_MILLIS.toFloat()).coerceIn(0f, 1f))
                } else drawChunk(entry, left, top, right, bottom, alpha)
                val sum = weatherSum(view, key, showing)
                if (Probe.ON) {
                    if (alpha < 1f) Probe.fading++ else Probe.ok++
                    if (old != null) Probe.crossfading++
                    if (entry.weatherSum != weatherSum(view, key, weather)) { if (entry.weather <= weather.id) Probe.staleOld++ else Probe.staleNew++ }
                }
                if (entry.version != version) wanted += key to editDistance(view, key)
                else if (entry.weatherSum != sum && key !in baking && key !in batchKeys) {
                    // Behind the new copy waiting: baked again with the rest of the screen and shown together. Behind
                    // the copy showing, as when it comes back on screen: baked again alone.
                    if (next != null && batch == 0) stale += key
                    else if (next == null || entry.weatherSum != weatherSum(view, key, weather)) wanted += key to 1.2f
                }
            } else {
                val other = drawFallback(view, level, key)
                if (Probe.ON) when {
                    other < 0 -> Probe.nothing++
                    other < level -> Probe.finer++
                    else -> { Probe.coarser++; if (other - level >= 2) Probe.far++ }
                }
                // Zooming out, the level going bakes after the one coming in.
                wanted += key to if (target > level) 1.7f else 1f
                missing = true
                // With nothing close to stand in, the next level out bakes first, a quarter the chunks for the same
                // area, and with nothing at all the coarsest too, which covers the most.
                if (readyAt >= 0) {
                    if (other < 0 && level != COARSE) { wanted += outer(key, COARSE) to 0.5f; standIns += outer(key, COARSE) }
                    if ((other < 0 || other - level >= 2) && level + 1 < COARSE) { wanted += outer(key, level + 1) to 0.9f; standIns += outer(key, level + 1) }
                }
            }
        }
        val cx = size.width / 2 + (driftX - driftY) * view.halfWidth * AHEAD
        val cy = size.height / 2 + (driftX + driftY) * view.halfHeight * AHEAD
        // The coarsest level, for the whole map when that's small or else round about, so there's always something to show
        // while sharper ones bake.
        forEachVisible(view, COARSE, margin = if (coarseWhole) 1e9f else COARSE_REACH * view.halfWidth) { key, left, top, right, bottom ->
            val entry = cache[key]
            if (entry != null) entry.used = frame
            // Once the screen's up: those round about right after it, so a jump or a fast pan finds them there, the rest of
            // the map in the background along with the ring, nearest first by how far its edge is from the screen's
            // middle, as its chunks are many screens across. One with much on it can take a second or two.
            val m = COARSE_REACH * view.halfWidth
            val near = right > -m && bottom > -m && left < size.width + m && top < size.height + m
            val mx = max(0f, max(left - cx, cx - right))
            val my = max(0f, max(top - cy, cy - bottom))
            val d = kotlin.math.sqrt(mx * mx + my * my) / (size.width + size.height)
            if (entry == null) { if (readyAt >= 0) wanted += key to if (near) 1.5f else 2f + d }
            else if (entry.version != versionOf(view, COARSE, key.cx, key.cy)) wanted += key to 4f + d
            // In step with the weather near the screen; the rest once there's nothing else to bake.
            else if (entry.weatherSum != weatherSum(view, key, weather)) wanted += key to if (d < 1f) 2f + d else 5f + d
        }
        // The next level out, kept baked as the stand-in for this one and so zooming out switches at once: over the screen
        // right after it, round it with the ring. Kept in step with the weather and building in the background.
        if (readyAt >= 0 && (level + 1 < COARSE || (level + 1 == COARSE && !coarseWhole))) {
            val room = bytes < BUDGET * 3 / 4
            forEachVisible(view, level + 1, margin = size.width * RING) { key, left, top, right, bottom ->
                val entry = cache[key]
                if (entry != null) entry.used = frame
                val onScreen = right > 0f && bottom > 0f && left < size.width && top < size.height
                val mx = (left + right) / 2 - cx
                val my = (top + bottom) / 2 - cy
                val d = kotlin.math.sqrt(mx * mx + my * my) / (size.width + size.height)
                if (entry == null) { if (onScreen) wanted += key to 1.6f else if (room) wanted += key to 2f + d }
                else if (entry.version != versionOf(view, level + 1, key.cx, key.cy) || entry.weatherSum != weatherSum(view, key, weather)) {
                    if (onScreen || room) wanted += key to 2f + d
                }
            }
        }
        // Once the screen has all it needs: a ring round it at this level, the way it's moving first, and the next level
        // in where zooming in would show it, once the screen is near the size that switches to it, while there's room.
        if (wanted.none { it.second < 2f } && bytes < BUDGET * 3 / 4) {
            val near = view.halfWidth / LEVELS[level]
            if (level - 1 >= SHARPEST && near > FINER_FROM) {
                forEachVisible(view, level - 1, margin = -(1f - near) / 2 * min(size.width, size.height)) { key, left, top, right, bottom ->
                    val entry = cache[key]
                    if (entry != null) entry.used = frame
                    if (entry == null || entry.version != versionOf(view, level - 1, key.cx, key.cy) || entry.weatherSum != weatherSum(view, key, weather)) {
                        val mx = (left + right - size.width) / 2
                        val my = (top + bottom - size.height) / 2
                        wanted += key to 2.4f + kotlin.math.sqrt(mx * mx + my * my) / (size.width + size.height)
                    }
                }
            }
            forEachVisible(view, level, margin = size.width * (if (level >= FAR_LEVEL) FAR_RING else RING)) { key, left, top, right, bottom ->
                val entry = cache[key]
                if (entry != null) entry.used = frame
                if (entry == null) {
                    val mx = (left + right) / 2 - cx
                    val my = (top + bottom) / 2 - cy
                    wanted += key to 2f + kotlin.math.sqrt(mx * mx + my * my) / (size.width + size.height)
                }
            }
        }
        // Cached chunks off screen with older weather, the nearest first along with the ring, the rest after it. A level
        // away counts as a third of a screen further.
        if (refresh.isNotEmpty() && wanted.none { it.second < 2f }) {
            val gone = ArrayList<Key>()
            for (key in refresh) {
                val entry = cache[key]
                if (entry == null || key.turn != view.turn || entry.weatherSum == weatherSum(view, key, weather)) { gone += key; continue }
                placeChunk(view, key.level, key.cx, key.cy) { left, top, right, bottom ->
                    val mx = (left + right - size.width) / 2
                    val my = (top + bottom - size.height) / 2
                    val d = kotlin.math.sqrt(mx * mx + my * my) / (size.width + size.height) + kotlin.math.abs(key.level - level) / 3f
                    wanted += key to if (d < 2f) 2f + d else 3.5f + d
                }
            }
            refresh -= gone.toSet()
        }
        // Once every chunk on screen is there: every one from another copy of the weather, shown all at once when the
        // last is done, so no seams show between chunks of different days. A new copy is shown from then on. Chunks
        // baking again for building don't hold it up, or a busy screen would keep old weather beside new for good.
        if (held.isEmpty() && batch == 0 && !missing) {
            val sheet = sheets.get(level)
            if (sheet != null) {
                if (stale.isEmpty()) {
                    next?.let { advance(it) }
                } else {
                    if (Probe.ON) { batchStart = Probe.now(); Probe.log("batchStart L=$level chunks=${stale.size} w=${showing.id}") }
                    batch = stale.size
                    batchKeys += stale
                    batchQueue += stale
                    batchSheet = sheet
                    batchWeather = showing
                }
            }
        }
        val sorted = wanted.sortedBy { it.second }
        // At most one stand-in baking on the screen lane at a time, so the sharp chunks keep coming after a turn or a
        // fast pan leaves the whole screen without one.
        standInsBaking.retainAll(baking)
        for ((key, priority) in sorted) if (priority < 2f) {
            if (priority < 1f && key in standIns) {
                if (standInsBaking.isNotEmpty() || key in baking) continue
                want(key, priority)
                if (key in baking) standInsBaking += key
            } else want(key, priority)
        }
        // The weather batch next, on every baker once nothing on screen is missing, or else only in the background.
        while (batchQueue.isNotEmpty()) {
            val onScreenLane = !missing && baking.size - behind.size < BAKERS
            if (!onScreenLane && behind.size >= BACKGROUND) break
            bake(batchQueue.removeFirst(), batchSheet!!, hold = true, ahead = !onScreenLane, weather = batchWeather!!)
        }
        for ((key, priority) in sorted) if (priority >= 2f) want(key, priority)
        if (crossfading.isNotEmpty()) revision.intValue++
        // The levels either side unpacked ahead, so zooming finds them ready, once this one's in and the screen's baked.
        if (readyAt >= 0 && sheets.get(target) != null) {
            if (target + 1 < LEVELS.size) sheets.get(target + 1)
            if (target > 0) sheets.get(target - 1)
        }
        trim()
        if (Probe.ON) Probe.frameEnd()
    }

    /** Starts baking [key] if its lane has room: on screen under priority 2, ahead from there. */
    private fun want(key: Key, priority: Float) {
        if (key in baking) return
        // Building on a chunk in the weather batch waits till the batch is in, so the batch always ends.
        if (key in batchKeys) return
        val ahead = priority >= 2f
        if (if (ahead) behind.size >= BACKGROUND else baking.size - behind.size >= BAKERS) return
        // Waits for its level's sheet to be unpacked; asking starts that.
        val sheet = sheets.get(key.level) ?: return
        bake(key, sheet, ahead = ahead)
    }

    /** Shows [copy] from now on, and lists the chunks cached off screen that show older weather, to bake again. */
    private fun advance(copy: WeatherCopy) {
        if (Probe.ON) Probe.log("weatherShown id=${copy.id}")
        shown = copy
        next = null
        refresh.clear()
        val views = HashMap<Int, View>()
        for ((key, entry) in cache) {
            if (key in baking) continue
            val v = views.getOrPut(key.turn) { View(world.width, world.height).also { it.turnBy(key.turn) } }
            if (entry.weatherSum != weatherSum(v, key, copy)) refresh += key
        }
        if (Probe.ON) Probe.log("refresh chunks=${refresh.size} of=${cache.size}")
    }

    /** [copy]'s [WeatherCopy.blocks] summed over chunk [key], which only changes where its weather looks different. */
    private fun weatherSum(view: View, key: Key, copy: WeatherCopy): Long =
        if (copy.blocks.isEmpty()) copy.id.toLong() else sumOf(copy.blocks, view, key.level, key.cx, key.cy).toLong()

    /**
     * How soon a chunk changed by building should bake again, from 0 up to under 1: the chunk with the tile last built
     * on first, then those round it, which only share its edge.
     */
    private fun editDistance(view: View, key: Key): Float {
        val x = lastEdit.first
        val y = lastEdit.second
        if (x < 0) return 0.5f
        val hw = LEVELS[key.level]
        val vx = view.toViewX(x + 0.5f, y + 0.5f)
        val vy = view.toViewY(x + 0.5f, y + 0.5f)
        val d = kotlin.math.abs((vx - vy) * hw / CHUNK_PIXELS - (key.cx + 0.5f)) + kotlin.math.abs((vx + vy) * hw / 2 / CHUNK_ROWS - (key.cy + 0.5f))
        return (d / 8f).coerceAtMost(0.9f)
    }

    /** The chunk at coarser [level] that covers [key]. */
    private fun outer(key: Key, level: Int): Key {
        val n = 1 shl (level - key.level)
        return Key(level, key.turn, key.cx.floorDiv(n), key.cy.floorDiv(n))
    }

    /** Whether the coarsest level for the whole map fits in [COARSE_BUDGET], counting every chunk that could hold some of it. */
    private val coarseWhole: Boolean = run {
        val hw = LEVELS[COARSE]
        val across = floor((max(world.width, world.height) + 1) * hw / CHUNK_PIXELS).toInt() - floor(-(max(world.width, world.height) + 1) * hw / CHUNK_PIXELS).toInt() + 1
        val down = floor(((world.width + world.height) * hw / 2 + hw) / CHUNK_ROWS).toInt() - floor(-above(hw) / CHUNK_ROWS).toInt() + 1
        val whole = across.toLong() * down * CHUNK_PIXELS * CHUNK_ROWS * 4 <= COARSE_BUDGET
        if (Probe.ON) Probe.log("coarse whole=$whole chunks=${across * down}")
        whole
    }

    /** The tile last built on, so its chunk is baked again before the others its building touched. */
    var lastEdit = -1 to -1

    /** A chunk missing at this level, drawn from whichever other level has it, kept to the chunk's own rectangle. Returns that level, or -1 if none had it. */
    private fun DrawScope.drawFallback(view: View, level: Int, key: Key): Int {
        var kind = -1
        placeChunk(view, level, key.cx, key.cy) { left, top, right, bottom ->
            clipRect(left, top, right, bottom) { kind = drawStandIn(view, level, key) }
        }
        return kind
    }

    /** Whether [level], a level out or in from chunk [key]'s, has all of [key] baked: the chunk over it or the four under it. */
    private fun covers(level: Int, key: Key): Boolean {
        if (level > key.level) return outer(key, level) in cache
        for (j in 0 until 2) for (i in 0 until 2) if (Key(level, key.turn, key.cx * 2 + i, key.cy * 2 + j) !in cache) return false
        return true
    }

    private fun DrawScope.drawStandIn(view: View, level: Int, key: Key): Int {
        for (other in LEVELS.indices.sortedBy { kotlin.math.abs(it - level) }) {
            if (other == level) continue
            if (other > level) {
                // A coarser level: one chunk covers this one and more.
                val n = 1 shl (other - level)
                val ok = Key(other, key.turn, key.cx.floorDiv(n), key.cy.floorDiv(n))
                val e = cache[ok] ?: continue
                e.used = frame
                placeChunk(view, other, ok.cx, ok.cy) { l, t, r, b -> drawChunk(e, l, t, r, b) }
                if (Probe.ON && shown != null && e.weatherSum != weatherSum(view, ok, shown!!)) Probe.standInOtherWeather++
                return other
            } else {
                // A finer level: draw what of its chunks are here, if all are.
                val n = 1 shl (level - other)
                val parts = ArrayList<Pair<Key, Entry>>()
                for (j in 0 until n) for (i in 0 until n) {
                    val k = Key(other, key.turn, key.cx * n + i, key.cy * n + j)
                    cache[k]?.let { parts += k to it }
                }
                if (parts.size < n * n) continue
                for ((k, e) in parts) {
                    e.used = frame
                    placeChunk(view, other, k.cx, k.cy) { l, t, r, b -> drawChunk(e, l, t, r, b) }
                    if (Probe.ON && shown != null && e.weatherSum != weatherSum(view, k, shown!!)) Probe.standInOtherWeather++
                }
                return other
            }
        }
        return -1
    }

    /** Draws [entry]'s picture into its chunk's screen rectangle, its trimmed rows left out at the top. */
    private fun DrawScope.drawChunk(entry: Entry, left: Float, top: Float, right: Float, bottom: Float, alpha: Float = 1f) {
        val image = entry.image ?: return
        val l = left.roundToInt()
        val t = (top + entry.top * (bottom - top) / CHUNK_ROWS).roundToInt()
        val r = right.roundToInt()
        val b = bottom.roundToInt()
        drawImage(
            image,
            dstOffset = IntOffset(l, t),
            dstSize = IntSize(r - l, b - t),
            alpha = alpha,
            filterQuality = if (r - l == image.width) FilterQuality.None else FilterQuality.Low,
        )
    }

    /** Calls [block] with the screen edges of chunk ([cx], [cy]) at [level], each worked out the same way for every chunk so neighbours meet exactly. */
    private inline fun DrawScope.placeChunk(view: View, level: Int, cx: Int, cy: Int, block: (left: Float, top: Float, right: Float, bottom: Float) -> Unit) {
        val scale = view.halfWidth / LEVELS[level]
        val w = CHUNK_PIXELS * scale
        val h = CHUNK_ROWS * scale
        val ox = view.screenX(0f, 0f, size.width)
        val oy = view.screenY(0f, 0f, 0f, size.height)
        block(ox + cx * w, oy + cy * h, ox + (cx + 1) * w, oy + (cy + 1) * h)
    }

    /** Each chunk at [level] that's at least partly within [margin] pixels of the screen and could hold some of the map. */
    private inline fun DrawScope.forEachVisible(view: View, level: Int, margin: Float = 0f, block: (Key, left: Float, top: Float, right: Float, bottom: Float) -> Unit) {
        val hw = LEVELS[level]
        val scale = view.halfWidth / hw
        val ox = view.screenX(0f, 0f, size.width)
        val oy = view.screenY(0f, 0f, 0f, size.height)
        // The map as drawn at this level, from the left and top of its far corners and highest land to its front edges.
        val cx0 = max(floor((-margin - ox) / scale / CHUNK_PIXELS).toInt(), floor(-(view.viewHeight + 1) * hw / CHUNK_PIXELS).toInt())
        val cx1 = min(floor((size.width + margin - ox) / scale / CHUNK_PIXELS).toInt(), floor((view.viewWidth + 1) * hw / CHUNK_PIXELS).toInt())
        val cy0 = max(floor((-margin - oy) / scale / CHUNK_ROWS).toInt(), floor(-above(hw) / CHUNK_ROWS).toInt())
        val cy1 = min(floor((size.height + margin - oy) / scale / CHUNK_ROWS).toInt(), floor(((view.viewWidth + view.viewHeight) * hw / 2 + hw) / CHUNK_ROWS).toInt())
        for (cy in cy0..cy1) for (cx in cx0..cx1) {
            placeChunk(view, level, cx, cy) { left, top, right, bottom -> block(Key(level, view.turn, cx, cy), left, top, right, bottom) }
        }
    }

    /**
     * The view tiles that can reach into chunk ([cx], [cy]) at [level], as a view rectangle vx0, vy0, vx1, vy1 (inclusive,
     * possibly empty); [reaches] says which in it do. A tile can reach up from below by the highest land and what stands
     * on it, and down by its earth.
     */
    private fun tileRange(view: View, level: Int, cx: Int, cy: Int): IntArray {
        val r = reach(level, cx, cy)
        return intArrayOf(
            max(0, (r[0] + r[2]) / 2 - 1), max(0, (r[2] - r[1]) / 2 - 1),
            min(view.viewWidth - 1, (r[1] + r[3]) / 2 + 1), min(view.viewHeight - 1, (r[3] - r[0]) / 2 + 1),
        )
    }

    /** The range of vx - vy and of vx + vy of the tiles that can reach into chunk ([cx], [cy]) at [level]: a0, a1, b0, b1. */
    private fun reach(level: Int, cx: Int, cy: Int): IntArray {
        val hw = LEVELS[level]
        val hh = hw / 2
        return intArrayOf(
            floor(cx * CHUNK_PIXELS / hw).toInt() - 3, ceil((cx + 1) * CHUNK_PIXELS / hw).toInt() + 3,
            floor(cy * CHUNK_ROWS / hh).toInt() - 4, ceil(((cy + 1) * CHUNK_ROWS + above(hw)) / hh).toInt() + 1,
        )
    }

    /** How many times the world under a chunk has changed, from the world's own blocks. */
    private fun versionOf(view: View, level: Int, cx: Int, cy: Int): Long =
        ((sumOf(world.chunkVersions, view, level, cx, cy) * 8L + overlays) * 8 + look()) * ChunkBaker.PART_ERA_COUNT + ChunkBaker.partEras(year())

    /** The game's year now, worked out with [look] once a day. */
    private fun year(): Int {
        look()
        return year
    }

    /** How many times the weather anywhere has changed. */
    private fun weatherVersions(): Long = world.weatherVersions.fold(0L) { sum, v -> sum + v }

    /** The sum of [versions] over the world's blocks under chunk ([cx], [cy]) at [level], and those within
     *  [CURVE_REACH] tiles of it, as far as a track tile's curved art reads. */
    private fun sumOf(versions: IntArray, view: View, level: Int, cx: Int, cy: Int): Int {
        val t = tileRange(view, level, cx, cy)
        if (t[0] > t[2] || t[1] > t[3]) return 0
        val vx0 = t[0]
        val vy0 = t[1]
        val vx1 = t[2]
        val vy1 = t[3]
        val x0 = max(0, min(view.worldX(vx0, vy0), view.worldX(vx1, vy1)) - CURVE_REACH) / World.BLOCK
        val x1 = min(world.width - 1, max(view.worldX(vx0, vy0), view.worldX(vx1, vy1)) + CURVE_REACH) / World.BLOCK
        val y0 = max(0, min(view.worldY(vx0, vy0), view.worldY(vx1, vy1)) - CURVE_REACH) / World.BLOCK
        val y1 = min(world.height - 1, max(view.worldY(vx0, vy0), view.worldY(vx1, vy1)) + CURVE_REACH) / World.BLOCK
        val across = world.width / World.BLOCK
        var sum = 0
        for (y in y0..y1) for (x in x0..x1) sum += versions[y * across + x]
        return sum
    }

    /**
     * Bakes [key] off the main thread and puts it in the cache, or with [hold] keeps it till the rest of its batch is
     * done. It bakes from the copy of the weather showing, so it matches its neighbours; a weather batch bakes from
     * the next.
     */
    private fun bake(key: Key, sheet: LevelSheets, hold: Boolean = false, ahead: Boolean = hold, weather: WeatherCopy = shown!!) {
        // The view as it is now, kept apart so turning while this bakes doesn't change it.
        val bakeView = View(world.width, world.height).also { it.turnBy(key.turn) }
        val version = versionOf(bakeView, key.level, key.cx, key.cy)
        val sum = weatherSum(bakeView, key, weather)
        baking += key
        if (ahead) behind += key
        // The scope is the composition's, on the main thread; only the baking goes off it.
        scope.launch {
            val o = overlays
            val l = look()
            val yr = year()
            val asked = if (Probe.ON) Probe.now() else 0L
            var started = 0L
            var micros = 0L
            val (image, top) = withContext(if (ahead) background else bakers) {
                if (Probe.ON) started = Probe.micros()
                bakeChunk(bakeView, key, sheet, o, l, yr, weather).also { if (Probe.ON) micros = Probe.micros() - started }
            }
            if (Probe.ON) Probe.bakeDone(key.level, if (hold) "batch" else if (ahead) "ahead" else "screen", micros, started / 1000 - asked, weather.id, key !in cache)
            baking -= key
            behind -= key
            val entry = Entry(image, top, version, weather.id, sum, clock.elapsedNow().inWholeMilliseconds, frame, fades = key !in cache && !hold)
            if (!hold) {
                install(key, entry)
                // Baked from a copy no longer showing: baked again before it shows.
                if (shown !== weather && entry.weatherSum != weatherSum(bakeView, key, shown!!)) refresh += key
            } else {
                held[key] = entry
                if (--batch == 0) {
                    if (Probe.ON) Probe.log("batchInstall chunks=${held.size} ms=${Probe.now() - batchStart}")
                    for ((k, e) in held) install(k, e)
                    held.clear()
                    batchKeys.clear()
                    // Shown from now on; chunks that came on screen meanwhile catch up alone.
                    val copy = batchWeather
                    batchSheet = null
                    batchWeather = null
                    if (copy != null && copy === next) advance(copy)
                }
            }
            revision.intValue++
        }
    }

    /** Puts [entry] in the cache. One baked again for the weather alone keeps the picture it replaces to fade from. */
    private fun install(key: Key, entry: Entry) {
        entry.baked = clock.elapsedNow().inWholeMilliseconds
        refresh -= key
        val prev = cache.put(key, entry)
        if (prev != null) {
            prev.old?.let { bytes -= it.bytes }
            prev.old = null
            // Only on screen, drawn last frame; off screen it's swapped.
            if (prev.image != null && prev.used >= frame - 1 && prev.version == entry.version && prev.weatherSum != entry.weatherSum) {
                // Still counted till it's let go, a second or so on.
                entry.old = prev
                crossfading += key
            } else bytes -= prev.bytes
        }
        bytes += entry.bytes
    }

    /** Bakes chunk [key]: its picture with the clear rows above whatever's highest on it left off, and how many rows those were; no picture if it's all clear. */
    private fun bakeChunk(view: View, key: Key, sheet: LevelSheets, overlays: Int, look: Int, year: Int, weather: WeatherCopy): Pair<ImageBitmap?, Int> {
        val buffer = takeBuffer(CHUNK_PIXELS * CHUNK_ROWS)
        val baker = ChunkBaker(world, view, key.level, sheet, CHUNK_PIXELS, CHUNK_ROWS, overlays, look, buffer, weather, year)
        baker.originX = -key.cx * CHUNK_PIXELS.toFloat()
        baker.originY = -key.cy * CHUNK_ROWS.toFloat()
        // Every tile that reaches in, back to front: ground first, then every shadow over it, then what stands up, so
        // shadows fall across tile edges and chunks join with no seam.
        val t = tileRange(view, key.level, key.cx, key.cy)
        val r = reach(key.level, key.cx, key.cy)
        val tiles = ArrayList<Int>()
        if (t[0] <= t[2] && t[1] <= t[3]) baker.prepare(t[0], t[1], t[2] + 1, t[3] + 1)
        for (vy in t[1]..t[3]) for (vx in t[0]..t[2]) {
            if (vx - vy in r[0]..r[1] && vx + vy in r[2]..r[3] && baker.reaches(vx, vy)) tiles += vy * view.viewWidth + vx
        }
        if (tiles.isNotEmpty()) {
            for (i in tiles) baker.ground(i % view.viewWidth, i / view.viewWidth)
            for (i in tiles) baker.shadows(i % view.viewWidth, i / view.viewWidth)
            for (i in tiles) baker.objects(i % view.viewWidth, i / view.viewWidth)
        }
        val pixels = baker.finish()
        var top = 0
        while (top < CHUNK_ROWS && (top * CHUNK_PIXELS until (top + 1) * CHUNK_PIXELS).all { pixels[it] ushr 24 == 0 }) top++
        val image = if (top == CHUNK_ROWS) null else chunkBitmapOf(pixels, CHUNK_PIXELS, top, CHUNK_ROWS - top)
        giveBack(buffer)
        return image to top
    }

    /** Pixel buffers kept between bakes, so baking doesn't make megabytes of garbage each time. */
    private val buffers = ArrayList<IntArray>()

    private fun takeBuffer(size: Int): IntArray = synchronized(buffers) {
        val i = buffers.indexOfFirst { it.size == size }
        if (i >= 0) buffers.removeAt(i).also { it.fill(0) } else IntArray(size)
    }

    private fun giveBack(buffer: IntArray) = synchronized(buffers) { if (buffers.size < BAKERS) buffers += buffer }

    /** Lets go of old pictures done fading, and of the chunks used longest ago past the memory budget. */
    private fun trim() {
        if (crossfading.isNotEmpty()) {
            val now = clock.elapsedNow().inWholeMilliseconds
            crossfading.removeAll { key ->
                val e = cache[key]
                val old = e?.old
                if (e == null || old == null) true
                else if (now - e.baked < WEATHER_FADE_MILLIS) false
                else {
                    bytes -= old.bytes
                    e.old = null
                    true
                }
            }
        }
        if (bytes <= BUDGET) return
        val old = cache.entries.filter { it.value.used < frame }.sortedBy { it.value.used }
        for ((k, e) in old) {
            if (bytes <= BUDGET) break
            cache.remove(k)
            bytes -= e.bytes
            e.old?.let { bytes -= it.bytes; e.old = null }
            refresh -= k
        }
    }

    companion object {
        /** Half a tile's width at each level, sharpest first. */
        val LEVELS = floatArrayOf(128f, 64f, 32f, 16f, 8f, 4f)
        /** A chunk's size, small enough that it bakes again quickly when something's built on it. */
        const val CHUNK_PIXELS = 512
        const val CHUNK_ROWS = 256
        /** How far a track tile's curves read: a change rebakes the chunks of tiles this far away. */
        const val CURVE_REACH = CurveTiles.REACH
        /** The sharpest level drawn. Level 0's sheets take some 130 MB, more than a small heap holds with everything else. */
        val SHARPEST = if (heapBytes() >= 400L * 1024 * 1024) 0 else 1
        /** Chunks baking at once, leaving cores for the screen and the game. */
        val BAKERS = (Workers.threads - 1).coerceIn(2, 6)
        val BACKGROUND = (BAKERS - 1).coerceAtLeast(1)
        /** How often the chunks on screen are baked again for the weather, at most, in milliseconds. */
        const val WEATHER_MILLIS = 15_000L
        /** Bitmap memory for chunks, in bytes. */
        const val BUDGET = 192L * 1024 * 1024
        /** How long a newly baked chunk takes to fade in, and one baked again for the weather, in milliseconds. */
        const val FADE_MILLIS = 180
        const val WEATHER_FADE_MILLIS = 1200
        /** The coarsest level, kept baked this far round the screen's middle, in view tiles, as a stand-in for any other. */
        val COARSE = LEVELS.size - 1
        const val COARSE_REACH = 128
        /** Bitmap memory the coarsest level may take to be kept for the whole map, 48 chunks, which holds a 256 by 256 map. */
        const val COARSE_BUDGET = BUDGET / 8
        /** How long a zoom waits for the next level's chunks the level shown can stand in for, and for those of older weather, in milliseconds. */
        const val ZOOM_WAIT_MILLIS = 400L
        /** How near the screen's scale is to switching to the next level in before that level is baked ahead. */
        const val FINER_FROM = 0.8f
        /** How far round the screen chunks are baked ahead, as a share of its width, and how many frames of drift ahead the nearest are counted from. */
        const val RING = 0.4f
        /** From this level out, the ring goes a screen further, so a slow device has scrolled-to chunks ready. The sharpest level's chunks are too big for it. */
        const val FAR_LEVEL = 1
        const val FAR_RING = 1f
        const val AHEAD = 30f

        /** How far above its place at height 0 a tile can draw: the highest land and what stands on it, trees and roofs. */
        fun above(hw: Float) = World.MAX_HEIGHT * hw / 4 + 3 * hw + 2

        /** The level to draw at: the sharpest one no bigger than needed, so chunks only ever scale down, except past the sharpest. */
        fun levelFor(halfWidth: Float): Int {
            for (i in LEVELS.indices.reversed()) if (LEVELS[i] >= halfWidth) return maxOf(i, SHARPEST)
            return SHARPEST
        }
    }
}
