package com.rm.interchange.map

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.Workers
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
 * level for one view direction. A chunk is [CHUNK_PIXELS] wide at every level,
 * so it holds more tiles the further out the level.
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

    /** A baked chunk: its picture, the world's and the weather's versions it shows, and when it was baked and last drawn. */
    private class Entry(val image: ImageBitmap, val top: Int, val version: Int, val weather: Int, val baked: Long, var used: Long, val fades: Boolean = false) {
        val bytes get() = image.width.toLong() * image.height * 4
    }

    private val cache = HashMap<Key, Entry>()

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
    /** A weather batch's chunks baked so far, how many are still to come, and when the last batch began. */
    private val held = HashMap<Key, Entry>()
    private var batch = 0
    private var lastWeather = 0L
    /** Where the screen's middle was last frame, in view tiles, and how it's drifting. */
    private var lastX = 0f
    private var lastY = 0f
    private var lastTurn = -1
    private var driftX = 0f
    private var driftY = 0f

    private var lookDay = -1
    private var look = Climate.SUMMER

    /** How the land looks at this time of year; every chunk bakes again when it turns. */
    private fun look(): Int {
        val d = world.day
        if (d != lookDay) {
            lookDay = d
            look = Climate.look(world.settings.region, Calendar.year(world.startYear, d), Calendar.dayOfYear(world.startYear, d))
        }
        return look
    }

    /** Draws the chunks that are on screen and asks for any that are missing or out of date. */
    fun DrawScope.draw(view: View) {
        frame++
        val level = levelFor(view.halfWidth)
        val wanted = ArrayList<Pair<Key, Float>>()
        val now = clock.elapsedNow().inWholeMilliseconds
        // Which way the map's moving, in view tiles a frame, to bake ahead that way first.
        val fx = view.focusViewX()
        val fy = view.focusViewY()
        if (view.turn == lastTurn) { driftX = driftX * 0.8f + (fx - lastX) * 0.2f; driftY = driftY * 0.8f + (fy - lastY) * 0.2f } else { driftX = 0f; driftY = 0f }
        lastX = fx; lastY = fy; lastTurn = view.turn
        // Chunks whose weather is out of date, to bake again together now and then.
        val stale = ArrayList<Key>()
        forEachVisible(view, level) { key, left, top, scale ->
            val entry = cache[key]
            val version = versionOf(view, level, key.cx, key.cy)
            if (entry != null) {
                entry.used = frame
                // A chunk just baked where nothing was fades in over what stood in for it.
                val alpha = if (entry.fades) ((now - entry.baked) / FADE_MILLIS.toFloat()).coerceIn(0f, 1f) else 1f
                if (alpha < 1f) drawFallback(view, level, key)
                drawChunk(entry, left, top, scale, alpha)
                if (entry.version != version) wanted += key to editDistance(view, key)
                else if (entry.weather != weatherOf(view, level, key.cx, key.cy)) stale += key
            } else {
                drawFallback(view, level, key)
                wanted += key to 1f
            }
        }
        // The coarsest level round about, a few chunks that always have something to show while sharper ones bake.
        forEachNear(view, COARSE, COARSE_REACH) { key ->
            val entry = cache[key]
            if (entry != null) entry.used = frame
            if (entry == null) wanted += key to 1.5f
            else if (entry.version != versionOf(view, COARSE, key.cx, key.cy)) wanted += key to 4f
        }
        // Once the screen has all it needs: a ring round it at this level, the way it's moving first, then the next level
        // out for zooming out, while there's room for them.
        if (wanted.none { it.second < 2f } && bytes < BUDGET * 3 / 4) {
            val cx = size.width / 2 + (driftX - driftY) * view.halfWidth * AHEAD
            val cy = size.height / 2 + (driftX + driftY) * view.halfHeight * AHEAD
            forEachVisible(view, level, margin = size.width * RING) { key, left, top, scale ->
                val entry = cache[key]
                if (entry != null) entry.used = frame
                if (entry == null) {
                    val mx = left + CHUNK_PIXELS * scale / 2 - cx
                    val my = top + chunkHeight(level, LEVELS[level]) * scale / 2 - cy
                    wanted += key to 2f + kotlin.math.sqrt(mx * mx + my * my) / (size.width + size.height)
                }
            }
            if (level + 1 < LEVELS.size) forEachVisible(view, level + 1) { key, _, _, _ ->
                val entry = cache[key]
                if (entry != null) entry.used = frame
                if (entry == null || entry.version != versionOf(view, level + 1, key.cx, key.cy)) wanted += key to 3f
            }
        }
        // The weather every while, when nothing else is baking: every chunk on screen that needs it, shown all at once
        // when the last is done, so no seams show between chunks of different days.
        if (stale.isNotEmpty() && wanted.none { it.second < 2f } && behind.isEmpty() && held.isEmpty() && now - lastWeather > WEATHER_MILLIS) {
            val sheet = sheets.get(level)
            if (sheet != null) {
                lastWeather = now
                batch = stale.size
                for (key in stale) bake(key, sheet, hold = true)
            }
        }
        for ((key, priority) in wanted.sortedBy { it.second }) {
            if (key in baking) continue
            val ahead = priority >= 2f
            if (if (ahead) behind.size >= BACKGROUND else baking.size - behind.size >= BAKERS) continue
            // Waits for its level's sheet to be unpacked; asking starts that.
            val sheet = sheets.get(key.level) ?: continue
            bake(key, sheet, ahead = ahead)
        }
        trim()
    }

    /**
     * How soon a chunk changed by building should bake again, from 0 up to under 1: the chunk with the tile last built
     * on first, then those round it, which only share its edge.
     */
    private fun editDistance(view: View, key: Key): Float {
        val x = lastEdit.first
        val y = lastEdit.second
        if (x < 0) return 0.5f
        val tiles = chunkTiles(key.level)
        val vx = view.toViewX(x + 0.5f, y + 0.5f) / tiles
        val vy = view.toViewY(x + 0.5f, y + 0.5f) / tiles
        val d = kotlin.math.abs(vx - (key.cx + 0.5f)) + kotlin.math.abs(vy - (key.cy + 0.5f))
        return (d / 8f).coerceAtMost(0.9f)
    }

    /** The tile last built on, so its chunk is baked again before the others its building touched. */
    var lastEdit = -1 to -1

    /** Each chunk at [level] within [reach] view tiles of the screen's middle, on or off the screen. */
    private inline fun forEachNear(view: View, level: Int, reach: Int, block: (Key) -> Unit) {
        val tiles = chunkTiles(level)
        val fx = view.focusViewX()
        val fy = view.focusViewY()
        val cx0 = max(0, floor((fx - reach) / tiles).toInt())
        val cy0 = max(0, floor((fy - reach) / tiles).toInt())
        val cx1 = min(ceil(view.viewWidth / tiles.toFloat()).toInt() - 1, floor((fx + reach) / tiles).toInt())
        val cy1 = min(ceil(view.viewHeight / tiles.toFloat()).toInt() - 1, floor((fy + reach) / tiles).toInt())
        for (cy in cy0..cy1) for (cx in cx0..cx1) block(Key(level, view.turn, cx, cy))
    }

    /** A chunk missing at this level, drawn from whichever other level has it. */
    private fun DrawScope.drawFallback(view: View, level: Int, key: Key) {
        val tiles = chunkTiles(level)
        for (other in LEVELS.indices.sortedBy { kotlin.math.abs(it - level) }) {
            if (other == level) continue
            val otherTiles = chunkTiles(other)
            if (otherTiles >= tiles) {
                // A coarser level: one chunk covers this one and more.
                val ok = Key(other, key.turn, key.cx * tiles / otherTiles, key.cy * tiles / otherTiles)
                val e = cache[ok] ?: continue
                e.used = frame
                placeChunk(view, other, ok.cx, ok.cy) { l, t, s -> drawChunk(e, l, t, s) }
                return
            } else {
                // A finer level: draw what of its chunks are here, if all are.
                val n = tiles / otherTiles
                val parts = ArrayList<Pair<Key, Entry>>()
                for (j in 0 until n) for (i in 0 until n) {
                    val k = Key(other, key.turn, key.cx * n + i, key.cy * n + j)
                    cache[k]?.let { parts += k to it }
                }
                if (parts.size < n * n) continue
                for ((k, e) in parts) {
                    e.used = frame
                    placeChunk(view, other, k.cx, k.cy) { l, t, s -> drawChunk(e, l, t, s) }
                }
                return
            }
        }
    }

    /** Draws [entry]'s picture with its chunk's top left at ([left], [top]), its trimmed rows left out above. */
    private fun DrawScope.drawChunk(entry: Entry, left: Float, chunkTop: Float, scale: Float, alpha: Float = 1f) {
        val image = entry.image
        val top = chunkTop + entry.top * scale
        val l = left.roundToInt()
        val t = top.roundToInt()
        val r = (left + image.width * scale).roundToInt()
        val b = (top + image.height * scale).roundToInt()
        drawImage(
            image,
            dstOffset = IntOffset(l, t),
            dstSize = IntSize(r - l, b - t),
            alpha = alpha,
            filterQuality = if (scale == 1f) FilterQuality.None else FilterQuality.Low,
        )
    }

    /** Calls [block] with where chunk ([cx], [cy]) at [level] goes on screen and how much it's scaled. */
    private inline fun DrawScope.placeChunk(view: View, level: Int, cx: Int, cy: Int, block: (left: Float, top: Float, scale: Float) -> Unit) {
        val hw = LEVELS[level]
        val tiles = chunkTiles(level)
        val scale = view.halfWidth / hw
        val vx = (cx * tiles).toFloat()
        val vy = (cy * tiles).toFloat()
        val sx = view.screenX(vx, vy, size.width)
        val sy = view.screenY(vx, vy, 0f, size.height)
        block(sx - tiles * hw * scale, sy - topPad(hw) * scale, scale)
    }

    /** Each chunk at [level] that's at least partly on screen, back to front. */
    private inline fun DrawScope.forEachVisible(view: View, level: Int, margin: Float = 0f, block: (Key, left: Float, top: Float, scale: Float) -> Unit) {
        val tiles = chunkTiles(level)
        val hw = LEVELS[level]
        val scale = view.halfWidth / hw
        val w = CHUNK_PIXELS * scale
        val h = chunkHeight(level, hw) * scale
        // The screen's corners on the map at sea level and at the highest land, which bound what can show.
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (px in floatArrayOf(-margin, size.width + margin)) for (py in floatArrayOf(-margin, size.height + margin)) for (ht in floatArrayOf(0f, World.MAX_HEIGHT.toFloat())) {
            val a = (px - size.width / 2) / view.halfWidth
            val b = (py - size.height / 2 + ht * view.step) / view.halfHeight
            val vx = view.focusViewX() + (a + b) / 2
            val vy = view.focusViewY() + (b - a) / 2
            minX = min(minX, vx); maxX = max(maxX, vx); minY = min(minY, vy); maxY = max(maxY, vy)
        }
        val cx0 = max(0, floor(minX / tiles).toInt() - 1)
        val cy0 = max(0, floor(minY / tiles).toInt() - 1)
        val cx1 = min(ceil(view.viewWidth / tiles.toFloat()).toInt() - 1, ceil(maxX / tiles).toInt())
        val cy1 = min(ceil(view.viewHeight / tiles.toFloat()).toInt() - 1, ceil(maxY / tiles).toInt())
        for (cy in cy0..cy1) for (cx in cx0..cx1) {
            placeChunk(view, level, cx, cy) { left, top, s ->
                if (left < size.width + margin && top < size.height + margin && left + w > -margin && top + h > -margin) block(Key(level, view.turn, cx, cy), left, top, s)
            }
        }
    }

    /** How many times the world under a chunk has changed, from the world's own blocks. */
    private fun versionOf(view: View, level: Int, cx: Int, cy: Int): Int = (sumOf(world.chunkVersions, view, level, cx, cy) * 8 + overlays) * 8 + look()

    /** How many times the weather under a chunk has changed. */
    private fun weatherOf(view: View, level: Int, cx: Int, cy: Int): Int = sumOf(world.weatherVersions, view, level, cx, cy)

    /** The sum of [versions] over the world's blocks under chunk ([cx], [cy]) at [level]. */
    private fun sumOf(versions: IntArray, view: View, level: Int, cx: Int, cy: Int): Int {
        val tiles = chunkTiles(level)
        // The chunk's own tiles and the ring round it it draws too.
        val vx0 = max(0, cx * tiles - 1)
        val vy0 = max(0, cy * tiles - 1)
        val vx1 = min(view.viewWidth, cx * tiles + tiles + 1) - 1
        val vy1 = min(view.viewHeight, cy * tiles + tiles + 1) - 1
        val x0 = min(view.worldX(vx0, vy0), view.worldX(vx1, vy1)) / World.BLOCK
        val x1 = max(view.worldX(vx0, vy0), view.worldX(vx1, vy1)) / World.BLOCK
        val y0 = min(view.worldY(vx0, vy0), view.worldY(vx1, vy1)) / World.BLOCK
        val y1 = max(view.worldY(vx0, vy0), view.worldY(vx1, vy1)) / World.BLOCK
        val across = world.width / World.BLOCK
        var sum = 0
        for (y in y0..y1) for (x in x0..x1) sum += versions[y * across + x]
        return sum
    }

    /** Bakes [key] off the main thread and puts it in the cache, or with [hold] keeps it till the rest of its batch is done. */
    private fun bake(key: Key, sheet: LevelSheets, hold: Boolean = false, ahead: Boolean = hold) {
        // The view as it is now, kept apart so turning while this bakes doesn't change it.
        val bakeView = View(world.width, world.height).also { it.turnBy(key.turn) }
        val version = versionOf(bakeView, key.level, key.cx, key.cy)
        val weather = weatherOf(bakeView, key.level, key.cx, key.cy)
        baking += key
        if (ahead) behind += key
        // The scope is the composition's, on the main thread; only the baking goes off it.
        scope.launch {
            val o = overlays
            val l = look()
            val (image, top) = withContext(if (ahead) background else bakers) { bakeChunk(bakeView, key, sheet, o, l) }
            baking -= key
            behind -= key
            val entry = Entry(image, top, version, weather, clock.elapsedNow().inWholeMilliseconds, frame, fades = key !in cache && !hold)
            if (!hold) {
                install(key, entry)
            } else {
                held[key] = entry
                if (--batch == 0) {
                    for ((k, e) in held) install(k, e)
                    held.clear()
                }
            }
            revision.intValue++
        }
    }

    private fun install(key: Key, entry: Entry) {
        cache.put(key, entry)?.let { bytes -= it.bytes }
        bytes += entry.bytes
    }

    /** Bakes chunk [key]: its picture with the clear rows above whatever's highest on it left off, and how many rows those were. */
    private fun bakeChunk(view: View, key: Key, sheet: LevelSheets, overlays: Int, look: Int): Pair<ImageBitmap, Int> {
        val hw = LEVELS[key.level]
        val tiles = chunkTiles(key.level)
        val height = chunkHeight(key.level, hw)
        val buffer = takeBuffer(CHUNK_PIXELS * height)
        val baker = ChunkBaker(world, view, key.level, sheet, CHUNK_PIXELS, height, overlays, look, buffer)
        val vx0 = key.cx * tiles
        val vy0 = key.cy * tiles
        baker.originX = tiles * hw - (vx0 - vy0) * hw
        baker.originY = topPad(hw) - (vx0 + vy0) * hw / 2
        // A ring of the neighbours' tiles is drawn too, in order with the chunk's own, so where chunks meet both
        // have the same solid pixels and no seam shows when they're drawn scaled. Ground first, then every
        // shadow over it, then what stands up, so shadows fall across tile edges.
        val ys = max(0, vy0 - 1) until min(vy0 + tiles + 1, view.viewHeight)
        val xs = max(0, vx0 - 1) until min(vx0 + tiles + 1, view.viewWidth)
        for (vy in ys) for (vx in xs) baker.ground(vx, vy)
        for (vy in ys) for (vx in xs) baker.shadows(vx, vy)
        for (vy in ys) for (vx in xs) baker.objects(vx, vy)
        val pixels = baker.finish()
        var top = 0
        while (top < height - 1 && (top * CHUNK_PIXELS until (top + 1) * CHUNK_PIXELS).all { pixels[it] ushr 24 == 0 }) top++
        val image = chunkBitmapOf(pixels, CHUNK_PIXELS, top, height - top)
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

    /** Lets go of the chunks used longest ago, past the memory budget. */
    private fun trim() {
        if (bytes <= BUDGET) return
        val old = cache.entries.filter { it.value.used < frame }.sortedBy { it.value.used }
        for ((k, e) in old) {
            if (bytes <= BUDGET) break
            cache.remove(k)
            bytes -= e.bytes
        }
    }

    companion object {
        /** Half a tile's width at each level, sharpest first. */
        val LEVELS = floatArrayOf(128f, 64f, 32f, 16f, 8f, 4f)
        /** Small enough that a chunk bakes again quickly when something's built on it. */
        const val CHUNK_PIXELS = 512
        /** Chunks baking at once, leaving cores for the screen and the game. */
        val BAKERS = (Workers.threads - 1).coerceIn(2, 6)
        val BACKGROUND = (BAKERS / 2).coerceAtLeast(1)
        /** How often the chunks on screen are baked again for the weather, at most, in milliseconds. */
        const val WEATHER_MILLIS = 15_000L
        /** Bitmap memory for chunks, in bytes. */
        const val BUDGET = 192L * 1024 * 1024
        /** How long a newly baked chunk takes to fade in, in milliseconds. */
        const val FADE_MILLIS = 180
        /** The coarsest level, kept baked this far round the screen's middle, in view tiles, as a stand-in for any other. */
        val COARSE = LEVELS.size - 1
        const val COARSE_REACH = 128
        /** How far round the screen chunks are baked ahead, as a share of its width, and how many frames of drift ahead the nearest are counted from. */
        const val RING = 0.4f
        const val AHEAD = 30f

        fun chunkTiles(level: Int) = (CHUNK_PIXELS / (2 * LEVELS[level])).toInt()

        /** Room above the chunk's diamond for the highest land and what stands on it: trees, roofs. */
        fun topPad(hw: Float) = World.MAX_HEIGHT * hw / 4 + 3 * hw + 2

        fun chunkHeight(level: Int, hw: Float) = (chunkTiles(level) * hw + topPad(hw) + 2 * hw / 4 + 2).toInt()

        /** The level to draw at: the sharpest one no bigger than needed, so chunks only ever scale down, except past the sharpest. */
        fun levelFor(halfWidth: Float): Int {
            for (i in LEVELS.indices.reversed()) if (LEVELS[i] >= halfWidth) return i
            return 0
        }
    }
}
