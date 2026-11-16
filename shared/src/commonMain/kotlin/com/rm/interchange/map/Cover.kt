package com.rm.interchange.map

import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.World
import kotlin.math.floor

/**
 * Where the buildings in the static map stand in front of something moving, so it can be drawn with those parts cut
 * away: the buildings are drawn under everything that moves, and would otherwise be drawn over by a
 * bus going behind them. Made for one frame at [level], whose sheets are [sheets], with the map showing [look].
 * Signals are cut by everything standing in front, trees too ([standing]), on the CPU.
 *
 * A building's shape is its sprite's columns, each from its first solid pixel down to its last.
 */
internal class Cover(private val world: World, private val view: View, private val level: Int, private val sheets: LevelSheets, private val look: Int = Climate.SUMMER) {
    private val hw = Zoom.LEVELS[level]
    private val shrink = 1 shl level
    private val year = Calendar.year(world.startYear, world.day)
    private val picker = TileRecorder(world, view, level, shown = EMPTY_WEATHER, year = year)

    /** Each view tile's building as [TileRecorder.building] gives it, worked out the first time a frame asks. */
    private val buildings = HashMap<Int, Long>()

    private fun buildingAt(vx: Int, vy: Int): Long = buildings.getOrPut(vy * view.viewWidth + vx) { picker.building(vx, vy) }

    /**
     * As [rects], for everything that stands on the tiles in front: buildings, trees, depots, platforms, etc., all
     * nearer than the point, into [add] as left, top, right, bottom on screen. For cutting signals on the CPU, apart
     * from the depth buffer the vehicles are cut by.
     */
    fun standing(vx: Float, vy: Float, nl: Float, nt: Float, nr: Float, nb: Float, width: Float, height: Float, add: (Float, Float, Float, Float) -> Unit) {
        val tx = floor(vx).toInt()
        val ty = floor(vy).toInt()
        val scale = view.halfWidth / hw
        val ox = view.screenX(0f, 0f, width)
        val oy = view.screenY(0f, 0f, 0f, height)
        val cache = StandingCache.of(world, view, level, look, year)
        for ((dx, dy) in FRONT) {
            val bx = tx + dx
            val by = ty + dy
            if (bx < 0 || by < 0 || bx >= view.viewWidth || by >= view.viewHeight) continue
            val at = cache.find(bx, by)
            val first = cache.first(at)
            val sprites = cache.data
            for (k in first until first + cache.count(at) * 3 step 3) {
                val cell = sprites[k].toInt()
                val r = cell * 6
                val w = StaticCells.rects[r + 2] / shrink
                val h = StaticCells.rects[r + 3] / shrink
                if (w == 0 || h == 0) continue
                // As the tile recorder lays it, from its anchor, the recorder's view point 0, 0 being the screen's.
                val left = ox + (sprites[k + 1] + StaticCells.rects[r + 4] / shrink) * scale
                val top = oy + (sprites[k + 2] + StaticCells.rects[r + 5] / shrink) * scale
                if (left > nr || left + w * scale < nl || top > nb || top + h * scale < nt) continue
                columnRects(cell, w, h, left, top, scale, 0) { l, t, rr, bb, _ -> add(l, t, rr, bb) }
            }
        }
    }

    /**
     * The columns of the buildings in front of view point ([vx], [vy]), within [nl], [nt], [nr], [nb] on screen, as
     * rectangles on a screen [width] by [height], for the GPU map: [take] is asked
     * for each building's view tile (y * view width + x) first and its rectangles are skipped if it says no; [add]
     * gets each as left, top, right, bottom and the building tile's view depth (x + y).
     */
    fun rects(vx: Float, vy: Float, nl: Float, nt: Float, nr: Float, nb: Float, width: Float, height: Float, take: (Int) -> Boolean, add: (Float, Float, Float, Float, Int) -> Unit) =
        each(vx, vy, nl, nt, nr, nb, width, height, take, add)

    private inline fun each(vx: Float, vy: Float, nl: Float, nt: Float, nr: Float, nb: Float, width: Float, height: Float, take: (Int) -> Boolean, add: (Float, Float, Float, Float, Int) -> Unit) {
        val tx = floor(vx).toInt()
        val ty = floor(vy).toInt()
        val scale = view.halfWidth / hw
        for ((dx, dy) in FRONT) {
            val bx = tx + dx
            val by = ty + dy
            if (bx < 0 || by < 0 || bx >= view.viewWidth || by >= view.viewHeight) continue
            val b = buildingAt(bx, by)
            if (b < 0) continue
            val cell = (b shr 32).toInt()
            val base = b.toInt()
            val r = cell * 6
            val w = StaticCells.rects[r + 2] / shrink
            val h = StaticCells.rects[r + 3] / shrink
            if (w == 0 || h == 0) continue
            // As the tile recorder lays it: from the tile's top corner at its height.
            val left = view.screenX(bx.toFloat(), by.toFloat(), width) + StaticCells.rects[r + 4] / shrink * scale
            val top = view.screenY(bx.toFloat(), by.toFloat(), base.toFloat(), height) + StaticCells.rects[r + 5] / shrink * scale
            if (left > nr || left + w * scale < nl || top > nb || top + h * scale < nt) continue
            if (!take(by * view.viewWidth + bx)) continue
            columnRects(cell, w, h, left, top, scale, bx + by, add)
        }
    }

    /** Sprite [cell]'s columns, [w] by [h] at this level, with its box's top left at [left], [top] on screen, into [add] at [depth]. */
    private inline fun columnRects(cell: Int, w: Int, h: Int, left: Float, top: Float, scale: Float, depth: Int, add: (Float, Float, Float, Float, Int) -> Unit) {
        val columns = columns(cell, w, h)
        var i = 0
        while (i < w) {
            val from = columns[i * 2]
            if (from < 0) {
                i++
                continue
            }
            // Runs of columns of much the same top and bottom go in as one rectangle.
            val to = columns[i * 2 + 1]
            var j = i + 1
            while (j < w && j - i < RUN && columns[j * 2] >= 0 && kotlin.math.abs(columns[j * 2] - from) <= 1 && kotlin.math.abs(columns[j * 2 + 1] - to) <= 1) j++
            add(left + i * scale, top + from * scale, left + j * scale, top + (to + 1) * scale, depth)
            i = j
        }
    }

    /** For each column of sprite [cell], its first and last solid rows, or -1 for an empty column; kept for each level. */
    private fun columns(cell: Int, w: Int, h: Int): IntArray = COLUMNS[level].getOrPut(cell) { columnsOf(level, sheets, cell, w, h) }

    companion object {
        /** Every static cell's columns at [level] from [sheets], worked out ahead off the main thread, for [takeColumns]. */
        internal fun columnsAhead(level: Int, sheets: LevelSheets): HashMap<Int, IntArray> {
            val out = HashMap<Int, IntArray>()
            val shrink = 1 shl level
            for (cell in 0 until StaticCells.rects.size / 6) {
                val w = StaticCells.rects[cell * 6 + 2] / shrink
                val h = StaticCells.rects[cell * 6 + 3] / shrink
                if (w > 0 && h > 0) out[cell] = columnsOf(level, sheets, cell, w, h)
            }
            return out
        }

        /** Lets go of the columns of the cells a game's buildings add, for the next game's. */
        internal fun forgetColumns() {
            for (m in COLUMNS) m.keys.removeAll { it >= StaticCells.BASE }
        }

        /** Keeps [columns] from [columnsAhead], so [level]'s sheet pixels needn't be read again. Main thread. */
        internal fun takeColumns(level: Int, columns: Map<Int, IntArray>) = COLUMNS[level].putAll(columns)

        private fun columnsOf(level: Int, sheets: LevelSheets, cell: Int, w: Int, h: Int): IntArray {
            // The sprite is packed trimmed, dx and dy in from its box of w by h.
            val lr = (cell * Atlas.LEVELS + level) * 6
            val sx = StaticCells.levelRects[lr]
            val sy = StaticCells.levelRects[lr + 1]
            val tw = StaticCells.levelRects[lr + 2]
            val th = StaticCells.levelRects[lr + 3]
            val dx = StaticCells.levelRects[lr + 4]
            val dy = StaticCells.levelRects[lr + 5]
            val src = sheets.static
            val sw = sheets.staticWidth
            return IntArray(w * 2) { k ->
                val i = k / 2 - dx
                var first = -1
                var last = -1
                if (i in 0 until tw) {
                    for (j in 0 until minOf(th, h - dy)) {
                        if (src[(sy + j) * sw + sx + i] ushr 24 >= SOLID) {
                            if (first < 0) first = j + dy
                            last = j + dy
                        }
                    }
                }
                if (k % 2 == 0) first else last
            }
        }

        /** The view tiles in front of a point's that can stand over it: nearer the camera, below it on screen. */
        private val FRONT = listOf(1 to 0, 0 to 1, 1 to 1, 2 to 1, 1 to 2, 2 to 2, 2 to 0, 0 to 2, 3 to 2, 2 to 3, 3 to 3)
        /** How opaque a pixel has to be to hide what's behind it. */
        private const val SOLID = 160
        /** The most columns put in one rectangle. */
        private const val RUN = 6
        private val COLUMNS = Array(Zoom.LEVELS.size) { HashMap<Int, IntArray>() }
        internal val EMPTY_WEATHER = WeatherCopy(0, ByteArray(0), ByteArray(0), ByteArray(0))
    }
}

/**
 * The static sprites standing on view tiles, as [TileRecorder.objects] lays them, kept from frame to frame: three
 * floats each, cell and anchor x, y from view point 0, 0 at height 0. A tile is worked out again once its chunk's
 * [World.chunkVersions] or a neighbouring one moves on; the lot goes when the world, view, level, look or year does,
 * or it grows past [LIMIT]. Main thread only.
 */
internal class StandingCache private constructor(val world: World, val view: View, val level: Int, val look: Int, val year: Int, val turn: Int) {
    private val picker = TileRecorder(world, view, level, look = look, shown = Cover.EMPTY_WEATHER, year = year).also {
        it.onStatic = ::lay
    }

    var data = FloatArray(1024)
        private set
    private var size = 0

    // Open addressing on view tile: the tile (-1 for a free slot), where its sprites start, how many and its chunks' version.
    private var keys = IntArray(1024) { -1 }
    private var starts = IntArray(1024)
    private var counts = IntArray(1024)
    private var versions = IntArray(1024)
    private var used = 0

    private fun lay(cell: Int, ax: Float, ay: Float) {
        if (size + 3 > data.size) data = data.copyOf(data.size * 2)
        data[size] = cell.toFloat()
        data[size + 1] = ax
        data[size + 2] = ay
        size += 3
    }

    /**
     * The versions of the chunk under view tile ([vx], [vy]) and the eight round it summed, as what stands on a tile
     * can hang on its neighbours (a station building behind a platform, a lock's place in a flight). They only go up,
     * so the sum moves on with any of them.
     */
    private fun chunkVersion(vx: Int, vy: Int): Int {
        val across = world.width / World.BLOCK
        val down = world.height / World.BLOCK
        val cx = view.worldX(vx, vy) / World.BLOCK
        val cy = view.worldY(vx, vy) / World.BLOCK
        var sum = 0
        for (y in maxOf(0, cy - 1)..minOf(down - 1, cy + 1)) for (x in maxOf(0, cx - 1)..minOf(across - 1, cx + 1)) sum += world.chunkVersions[y * across + x]
        return sum
    }

    /** The slot holding view tile ([vx], [vy])'s sprites, worked out now if they aren't kept or are out of date. */
    fun find(vx: Int, vy: Int): Int {
        val tile = vy * view.viewWidth + vx
        var i = (tile * -0x61c88647 ushr 8) and (keys.size - 1)
        while (keys[i] != -1 && keys[i] != tile) i = (i + 1) and (keys.size - 1)
        val version = chunkVersion(vx, vy)
        if (keys[i] == tile && versions[i] == version) return i
        if (keys[i] != tile) used++
        keys[i] = tile
        versions[i] = version
        starts[i] = size
        picker.objects(vx, vy)
        counts[i] = (size - starts[i]) / 3
        if (used * 2 <= keys.size) return i
        grow()
        return slotOf(tile)
    }

    fun first(slot: Int) = starts[slot]
    fun count(slot: Int) = counts[slot]

    private fun slotOf(tile: Int): Int {
        var i = (tile * -0x61c88647 ushr 8) and (keys.size - 1)
        while (keys[i] != tile) i = (i + 1) and (keys.size - 1)
        return i
    }

    private fun grow() {
        val k = keys
        val s = starts
        val c = counts
        val v = versions
        keys = IntArray(k.size * 2) { -1 }
        starts = IntArray(k.size * 2)
        counts = IntArray(k.size * 2)
        versions = IntArray(k.size * 2)
        for (j in k.indices) {
            if (k[j] == -1) continue
            var i = (k[j] * -0x61c88647 ushr 8) and (keys.size - 1)
            while (keys[i] != -1) i = (i + 1) and (keys.size - 1)
            keys[i] = k[j]
            starts[i] = s[j]
            counts[i] = c[j]
            versions[i] = v[j]
        }
    }

    companion object {
        /** The most floats kept before starting again. */
        private const val LIMIT = 1 shl 20
        private var last: StandingCache? = null

        fun of(world: World, view: View, level: Int, look: Int, year: Int): StandingCache {
            val c = last
            if (c != null && c.world === world && c.view === view && c.level == level && c.look == look && c.year == year && c.turn == view.turn && c.size < LIMIT) return c
            return StandingCache(world, view, level, look, year, view.turn).also { last = it }
        }
    }
}
