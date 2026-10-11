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

    private class Entry(val image: ImageBitmap, val version: Int, var used: Long) {
        val bytes get() = image.width.toLong() * image.height * 4
    }

    private val cache = HashMap<Key, Entry>()

    /** The overlays baked in, as [ChunkBaker.GRID] and [ChunkBaker.HEIGHTS] bits. A change bakes every chunk again, the old ones drawn meanwhile. */
    var overlays = 0
    private val baking = HashSet<Key>()
    private var bytes = 0L
    private var frame = 0L

    /** Goes up as each chunk is baked, so the map draws again. */
    val revision = mutableIntStateOf(0)

    private val bakers = Dispatchers.Default.limitedParallelism(BAKERS)

    /** Draws the chunks that are on screen and asks for any that are missing or out of date. */
    fun DrawScope.draw(view: View) {
        frame++
        val level = levelFor(view.halfWidth)
        val wanted = ArrayList<Pair<Key, Float>>()
        forEachVisible(view, level) { key, left, top, scale ->
            val entry = cache[key]
            val version = versionOf(view, level, key.cx, key.cy)
            if (entry != null) {
                entry.used = frame
                drawChunk(entry.image, left, top, scale)
                if (entry.version != version) wanted += key to 0f
            } else {
                drawFallback(view, level, key)
                wanted += key to 1f
            }
        }
        // The next level out too, so zooming out has it ready.
        if (level + 1 < LEVELS.size) forEachVisible(view, level + 1) { key, _, _, _ ->
            val entry = cache[key]
            if (entry != null) entry.used = frame
            if (entry == null || entry.version != versionOf(view, level + 1, key.cx, key.cy)) wanted += key to 2f
        }
        for ((key, _) in wanted.sortedBy { it.second }) {
            if (baking.size >= BAKERS * 2) break
            if (key in baking) continue
            // Waits for its level's sheet to be unpacked; asking starts that.
            val sheet = sheets.get(key.level) ?: continue
            bake(key, sheet)
        }
        trim()
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
                placeChunk(view, other, ok.cx, ok.cy) { l, t, s -> drawChunk(e.image, l, t, s) }
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
                    placeChunk(view, other, k.cx, k.cy) { l, t, s -> drawChunk(e.image, l, t, s) }
                }
                return
            }
        }
    }

    private fun DrawScope.drawChunk(image: ImageBitmap, left: Float, top: Float, scale: Float) {
        val l = left.roundToInt()
        val t = top.roundToInt()
        val r = (left + image.width * scale).roundToInt()
        val b = (top + image.height * scale).roundToInt()
        drawImage(
            image,
            dstOffset = IntOffset(l, t),
            dstSize = IntSize(r - l, b - t),
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
    private inline fun DrawScope.forEachVisible(view: View, level: Int, block: (Key, left: Float, top: Float, scale: Float) -> Unit) {
        val tiles = chunkTiles(level)
        val hw = LEVELS[level]
        val scale = view.halfWidth / hw
        val w = CHUNK_PIXELS * scale
        val h = chunkHeight(level, hw) * scale
        // The screen's corners on the map at sea level and at the highest land, which bound what can show.
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (px in floatArrayOf(0f, size.width)) for (py in floatArrayOf(0f, size.height)) for (ht in floatArrayOf(0f, World.MAX_HEIGHT.toFloat())) {
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
                if (left < size.width && top < size.height && left + w > 0 && top + h > 0) block(Key(level, view.turn, cx, cy), left, top, s)
            }
        }
    }

    /** How many times the world under a chunk has changed, from the world's own blocks. */
    private fun versionOf(view: View, level: Int, cx: Int, cy: Int): Int {
        val tiles = chunkTiles(level)
        // The chunk's own tiles and the ring round it it draws too.
        val vx0 = max(0, cx * tiles - 1)
        val vy0 = max(0, cy * tiles - 1)
        val vx1 = min(view.viewWidth, cx * tiles + tiles + 1) - 1
        val vy1 = min(view.viewHeight, cy * tiles + tiles + 1) - 1
        val x0 = min(view.worldX(vx0, vy0), view.worldX(vx1, vy1)) / World.CHUNK
        val x1 = max(view.worldX(vx0, vy0), view.worldX(vx1, vy1)) / World.CHUNK
        val y0 = min(view.worldY(vx0, vy0), view.worldY(vx1, vy1)) / World.CHUNK
        val y1 = max(view.worldY(vx0, vy0), view.worldY(vx1, vy1)) / World.CHUNK
        val across = world.width / World.CHUNK
        var sum = 0
        for (y in y0..y1) for (x in x0..x1) sum += world.chunkVersions[y * across + x]
        return sum * 4 + overlays
    }

    private fun bake(key: Key, sheet: LevelSheets) {
        // The view as it is now, kept apart so turning while this bakes doesn't change it.
        val bakeView = View(world.width, world.height).also { it.turnBy(key.turn) }
        val version = versionOf(bakeView, key.level, key.cx, key.cy)
        baking += key
        // The scope is the composition's, on the main thread; only the baking goes off it.
        scope.launch {
            val o = overlays
            val image = withContext(bakers) { bakeChunk(bakeView, key, sheet, o) }
            baking -= key
            cache.put(key, Entry(image, version, frame))?.let { bytes -= it.bytes }
            bytes += image.width.toLong() * image.height * 4
            revision.intValue++
        }
    }

    private fun bakeChunk(view: View, key: Key, sheet: LevelSheets, overlays: Int): ImageBitmap {
        val hw = LEVELS[key.level]
        val tiles = chunkTiles(key.level)
        val height = chunkHeight(key.level, hw)
        val baker = ChunkBaker(world, view, key.level, sheet, CHUNK_PIXELS, height, overlays)
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
        return imageBitmapOf(baker.finish(), CHUNK_PIXELS, height)
    }

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
        const val CHUNK_PIXELS = 1024
        /** Chunks baking at once, leaving cores for the screen and the game. */
        val BAKERS = (Workers.threads - 1).coerceIn(2, 6)
        /** Bitmap memory for chunks, in bytes. */
        const val BUDGET = 192L * 1024 * 1024

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
