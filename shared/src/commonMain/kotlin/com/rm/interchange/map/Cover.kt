package com.rm.interchange.map

import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.World
import kotlin.math.floor

/**
 * Where the buildings in the static map stand in front of something moving, so it can be drawn with those parts cut
 * away: the buildings are drawn under everything that moves, and would otherwise be drawn over by a
 * bus going behind them. Made for one frame at [level], whose sheets are [sheets], with the map showing [look].
 * Signals are cut by everything standing in front, trees too ([standing]).
 *
 * A building's shape is its sprite's columns, each from its first solid pixel down to its last.
 */
internal class Cover(private val world: World, private val view: View, private val level: Int, private val sheets: LevelSheets, look: Int = Climate.SUMMER) {
    private val hw = Zoom.LEVELS[level]
    private val shrink = 1 shl level
    private val picker = TileRecorder(world, view, level, look = look, shown = EMPTY_WEATHER, year = Calendar.year(world.startYear, world.day))

    /** Each view tile's building as [TileRecorder.building] gives it, worked out the first time a frame asks. */
    private val buildings = HashMap<Int, Long>()

    private fun buildingAt(vx: Int, vy: Int): Long = buildings.getOrPut(vy * view.viewWidth + vx) { picker.building(vx, vy) }

    /** Each view tile's static sprites as [TileRecorder.objects] lays them, three floats each: cell and anchor x, y. */
    private val stood = HashMap<Int, FloatArray>()
    private val laid = ArrayList<Float>()

    private fun standingAt(vx: Int, vy: Int): FloatArray = stood.getOrPut(vy * view.viewWidth + vx) {
        laid.clear()
        picker.onStatic = { cell, ax, ay -> laid += cell.toFloat(); laid += ax; laid += ay }
        picker.objects(vx, vy)
        picker.onStatic = null
        laid.toFloatArray()
    }

    /**
     * As [rects], for everything that stands on the tiles in front: buildings, trees, depots, platforms, etc. [take]
     * is asked with the view tile plus the view's tile count, so these go in apart from [rects]' buildings.
     */
    fun standing(vx: Float, vy: Float, nl: Float, nt: Float, nr: Float, nb: Float, width: Float, height: Float, take: (Int) -> Boolean, add: (Float, Float, Float, Float, Int) -> Unit) {
        val tx = floor(vx).toInt()
        val ty = floor(vy).toInt()
        val scale = view.halfWidth / hw
        val ox = view.screenX(0f, 0f, width)
        val oy = view.screenY(0f, 0f, 0f, height)
        for ((dx, dy) in FRONT) {
            val bx = tx + dx
            val by = ty + dy
            if (bx < 0 || by < 0 || bx >= view.viewWidth || by >= view.viewHeight) continue
            val sprites = standingAt(bx, by)
            // The tile's sprites go in all together once any of them is near, as later items look for them too.
            var near = false
            for (k in 0 until sprites.size / 3) if (standingRect(sprites, k, ox, oy, scale, nl, nt, nr, nb)) near = true
            if (!near || !take(view.viewWidth * view.viewHeight + by * view.viewWidth + bx)) continue
            for (k in 0 until sprites.size / 3) {
                val cell = sprites[k * 3].toInt()
                val r = cell * 6
                val w = Atlas.STATIC_RECTS[r + 2] / shrink
                val h = Atlas.STATIC_RECTS[r + 3] / shrink
                if (w == 0 || h == 0) continue
                // As the tile recorder lays it, from its anchor, the recorder's view point 0, 0 being the screen's.
                val left = ox + (sprites[k * 3 + 1] + Atlas.STATIC_RECTS[r + 4] / shrink) * scale
                val top = oy + (sprites[k * 3 + 2] + Atlas.STATIC_RECTS[r + 5] / shrink) * scale
                columnRects(cell, w, h, left, top, scale, bx + by, add)
            }
        }
    }

    /** Whether sprite [k] of [sprites] reaches into [nl], [nt], [nr], [nb] on screen. */
    private fun standingRect(sprites: FloatArray, k: Int, ox: Float, oy: Float, scale: Float, nl: Float, nt: Float, nr: Float, nb: Float): Boolean {
        val r = sprites[k * 3].toInt() * 6
        val w = Atlas.STATIC_RECTS[r + 2] / shrink
        val h = Atlas.STATIC_RECTS[r + 3] / shrink
        if (w == 0 || h == 0) return false
        val left = ox + (sprites[k * 3 + 1] + Atlas.STATIC_RECTS[r + 4] / shrink) * scale
        val top = oy + (sprites[k * 3 + 2] + Atlas.STATIC_RECTS[r + 5] / shrink) * scale
        return !(left > nr || left + w * scale < nl || top > nb || top + h * scale < nt)
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
            val w = Atlas.STATIC_RECTS[r + 2] / shrink
            val h = Atlas.STATIC_RECTS[r + 3] / shrink
            if (w == 0 || h == 0) continue
            // As the tile recorder lays it: from the tile's top corner at its height.
            val left = view.screenX(bx.toFloat(), by.toFloat(), width) + Atlas.STATIC_RECTS[r + 4] / shrink * scale
            val top = view.screenY(bx.toFloat(), by.toFloat(), base.toFloat(), height) + Atlas.STATIC_RECTS[r + 5] / shrink * scale
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
            for (cell in 0 until Atlas.STATIC_RECTS.size / 6) {
                val w = Atlas.STATIC_RECTS[cell * 6 + 2] / shrink
                val h = Atlas.STATIC_RECTS[cell * 6 + 3] / shrink
                if (w > 0 && h > 0) out[cell] = columnsOf(level, sheets, cell, w, h)
            }
            return out
        }

        /** Keeps [columns] from [columnsAhead], so [level]'s sheet pixels needn't be read again. Main thread. */
        internal fun takeColumns(level: Int, columns: Map<Int, IntArray>) = COLUMNS[level].putAll(columns)

        private fun columnsOf(level: Int, sheets: LevelSheets, cell: Int, w: Int, h: Int): IntArray {
            // The sprite is packed trimmed, dx and dy in from its box of w by h.
            val lr = (cell * Atlas.LEVELS + level) * 6
            val sx = Atlas.STATIC_LEVEL_RECTS[lr]
            val sy = Atlas.STATIC_LEVEL_RECTS[lr + 1]
            val tw = Atlas.STATIC_LEVEL_RECTS[lr + 2]
            val th = Atlas.STATIC_LEVEL_RECTS[lr + 3]
            val dx = Atlas.STATIC_LEVEL_RECTS[lr + 4]
            val dy = Atlas.STATIC_LEVEL_RECTS[lr + 5]
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
        private val EMPTY_WEATHER = WeatherCopy(0, ByteArray(0), ByteArray(0), ByteArray(0))
    }
}
