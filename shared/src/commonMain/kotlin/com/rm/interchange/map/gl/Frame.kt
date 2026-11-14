package com.rm.interchange.map.gl

import com.rm.interchange.map.Zoom
import com.rm.interchange.map.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the GPU map puts things this frame, worked out from [View]: a [SOURCE_LEVEL] pixel p lands on screen at
 * origin + p * [scale]. [originX] and [originY] are where view point 0, 0 at height 0 lands, rounded to whole pixels. Screen sizes are in surface pixels.
 */
internal class GlFrame(
    val turn: Int,
    val originX: Float,
    val originY: Float,
    val scale: Float,
    val width: Int,
    val height: Int,
    val viewWidth: Int,
    val viewHeight: Int,
) {
    fun screenX(px: Float) = originX + px * scale
    fun screenY(py: Float) = originY + py * scale

    override fun equals(other: Any?) = other is GlFrame && other.turn == turn && other.originX == originX && other.originY == originY &&
        other.scale == scale && other.width == width && other.height == height && other.viewWidth == viewWidth && other.viewHeight == viewHeight

    override fun hashCode() = ((turn * 31 + originX.hashCode()) * 31 + originY.hashCode()) * 31 + scale.hashCode()

    companion object {
        /** The frame for [view] on a screen [width] by [height]. */
        fun of(view: View, width: Int, height: Int) = GlFrame(
            view.turn,
            view.screenX(0f, 0f, width.toFloat()).roundToInt().toFloat(),
            view.screenY(0f, 0f, 0f, height.toFloat()).roundToInt().toFloat(),
            view.halfWidth / SOURCE_HALF_WIDTH,
            width, height, view.viewWidth, view.viewHeight,
        )
    }
}

/**
 * The view tiles whose sprites can reach onto the screen, as a column range for each view row: columns[2 * vy] to
 * columns[2 * vy + 1], inclusive, empty when the first is the greater. A tile reaches three half widths either
 * side, the highest land and tallest sprite above, the earth below.
 */
internal fun visibleColumns(frame: GlFrame, into: IntArray = IntArray(frame.viewHeight * 2)): IntArray {
    val hw = SOURCE_HALF_WIDTH
    val hh = hw / 2
    val x0 = (0f - frame.originX) / frame.scale
    val x1 = (frame.width - frame.originX) / frame.scale
    val y0 = (0f - frame.originY) / frame.scale
    val y1 = (frame.height - frame.originY) / frame.scale
    // The range of vx - vy and of vx + vy.
    val a0 = floor(x0 / hw).toInt() - 3
    val a1 = ceil(x1 / hw).toInt() + 3
    val b0 = floor(y0 / hh).toInt() - 4
    val b1 = ceil((y1 + Zoom.above(hw)) / hh).toInt() + 1
    for (vy in 0 until frame.viewHeight) {
        into[2 * vy] = max(0, max(a0 + vy, b0 - vy))
        into[2 * vy + 1] = min(frame.viewWidth - 1, min(a1 + vy, b1 - vy))
    }
    return into
}

/**
 * The runs of sprites to draw from each region for one frame, in drawing order: for each pass, region by region,
 * the visible tiles of each row, with runs in a region joined where fewer than [GAP_SPRITES] lie between them
 * (drawing a few off screen costs less than another draw call). Each run is region index, first sprite, count.
 */
internal class DrawRuns {
    var size = 0
        private set
    var runs = IntArray(3 * 256)
        private set
    /** Where each pass's runs start in [runs], in runs, with [size] at the end. */
    val passStart = IntArray(PASSES + 1)

    fun build(regions: Array<RegionSprites?>, columns: IntArray) {
        size = 0
        for (pass in 0 until PASSES) {
            passStart[pass] = size
            for (r in regions) {
                if (r == null) continue
                var open = false
                var first = 0
                var end = 0
                for (vy in r.row0 until r.row1) {
                    val c0 = columns[2 * vy]
                    val c1 = columns[2 * vy + 1]
                    if (c0 > c1) continue
                    val a = r.start(pass, vy, c0)
                    val b = r.start(pass, vy, c1 + 1)
                    if (b <= a) continue
                    if (open && a - end < GAP_SPRITES) {
                        end = b
                    } else {
                        if (open) add(r.index, first, end - first)
                        open = true
                        first = a
                        end = b
                    }
                }
                if (open) add(r.index, first, end - first)
            }
        }
        passStart[PASSES] = size
    }

    private fun add(region: Int, first: Int, count: Int) {
        if ((size + 1) * 3 > runs.size) runs = runs.copyOf(runs.size * 2)
        runs[size * 3] = region
        runs[size * 3 + 1] = first
        runs[size * 3 + 2] = count
        size++
    }

    fun region(i: Int) = runs[i * 3]
    fun first(i: Int) = runs[i * 3 + 1]
    fun count(i: Int) = runs[i * 3 + 2]

    companion object {
        const val GAP_SPRITES = 64
    }
}

/** The side of a square of the static map drawn ahead, in the pixels of the level it's drawn at. */
internal const val SQUARE_SIDE = 512

/** Level [level]'s pixels to [SOURCE_LEVEL]'s. */
internal fun toSource(level: Int) = SOURCE_HALF_WIDTH / Zoom.LEVELS[level]

/**
 * The frame that draws square [cx], [cy] of level [level]'s grid for [f]'s turn: [SQUARE_SIDE] level pixels a side,
 * square 0, 0 having view point 0, 0 at height 0 at its top left corner.
 */
internal fun squareFrame(f: GlFrame, level: Int, cx: Int, cy: Int) =
    GlFrame(f.turn, (-cx * SQUARE_SIDE).toFloat(), (-cy * SQUARE_SIDE).toFloat(), 1f / toSource(level), SQUARE_SIDE, SQUARE_SIDE, f.viewWidth, f.viewHeight)

/** Where square [c] of level [level]'s grid starts on [f]'s screen along x ([y] false) or y, in screen pixels. */
internal fun squareEdge(f: GlFrame, level: Int, c: Int, y: Boolean) =
    (if (y) f.originY else f.originX) + c * SQUARE_SIDE * toSource(level) * f.scale

/** The squares of level [level]'s grid [f]'s screen shows: first and last column, then first and last row, inclusive. */
internal fun squaresOn(f: GlFrame, level: Int): IntArray {
    val side = SQUARE_SIDE * toSource(level) * f.scale
    return intArrayOf(
        floor((0f - f.originX) / side).toInt(), ceil((f.width - f.originX) / side).toInt() - 1,
        floor((0f - f.originY) / side).toInt(), ceil((f.height - f.originY) / side).toInt() - 1,
    )
}

/** Square [cx], [cy] packed into a long, for [squareX] and [squareY]. */
internal fun packSquare(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
internal fun squareX(k: Long) = (k shr 32).toInt()
internal fun squareY(k: Long) = k.toInt()

/**
 * The squares [on] (from [squaresOn]) to draw ahead, packed by [packSquare], the nearest the middle of the screen
 * first: every one [fresh] says isn't drawn ahead from what shows now, missing and stale alike.
 */
internal fun squaresToDraw(on: IntArray, fresh: (cx: Int, cy: Int) -> Boolean): LongArray {
    val mx = (on[0] + on[1]) / 2f
    val my = (on[2] + on[3]) / 2f
    var out = LongArray(0)
    var n = 0
    for (cy in on[2]..on[3]) for (cx in on[0]..on[1]) {
        if (fresh(cx, cy)) continue
        if (n == out.size) out = out.copyOf(maxOf(8, n * 2))
        out[n++] = packSquare(cx, cy)
    }
    fun near(k: Long): Float { val dx = squareX(k) - mx; val dy = squareY(k) - my; return dx * dx + dy * dy }
    return out.copyOf(n).sortedBy { near(it) }.toLongArray()
}

/** GPU memory the squares drawn ahead may take at most, in bytes. */
internal const val SQUARE_MEMORY = 96L * 1024 * 1024

/**
 * How many squares to keep drawn ahead on a [width] by [height] screen: the most it shows at any zoom, the ring
 * round them and the next level out's, up to [SQUARE_MEMORY], but never fewer than the screen shows. A square is
 * from half its side to its side on screen at its own level, and twice that at the next level out.
 */
internal fun squareBudget(width: Int, height: Int): Int {
    fun fit(px: Int, side: Int) = (px + side - 1) / side + 1
    val small = SQUARE_SIDE / 2
    val across = fit(width, small)
    val down = fit(height, small)
    val on = across * down
    val ring = 2 * (across + down) + 4
    val out = fit(width, SQUARE_SIDE) * fit(height, SQUARE_SIDE)
    val cap = (SQUARE_MEMORY / (SQUARE_SIDE * SQUARE_SIDE * 4)).toInt()
    return maxOf(on, minOf(on + ring + out, cap))
}

/**
 * The weather corner rows the view tiles [cols] (from [visibleColumns], [viewHeight] rows) read, seen from [turn] on
 * a map [mapHeight] tiles tall: first and last, with a row to spare each way; the first is the greater if none.
 */
internal fun weatherRows(cols: IntArray, viewHeight: Int, turn: Int, mapHeight: Int): IntArray {
    var vx0 = Int.MAX_VALUE
    var vx1 = -1
    var vy0 = Int.MAX_VALUE
    var vy1 = -1
    for (vy in 0 until viewHeight) if (cols[2 * vy] <= cols[2 * vy + 1]) {
        vx0 = min(vx0, cols[2 * vy])
        vx1 = max(vx1, cols[2 * vy + 1])
        if (vy0 == Int.MAX_VALUE) vy0 = vy
        vy1 = vy
    }
    if (vy1 < 0) return intArrayOf(1, 0)
    // The world rows of those tiles: view y runs along world y for turns 0 and 2, view x for 1 and 3.
    val (t0, t1) = when (turn) {
        0 -> vy0 to vy1
        1 -> mapHeight - 1 - vx1 to mapHeight - 1 - vx0
        2 -> mapHeight - 1 - vy1 to mapHeight - 1 - vy0
        else -> vx0 to vx1
    }
    return intArrayOf(max(0, t0 - 1), min(mapHeight, t1 + 2))
}

/** Whether no row of [rows] (from [weatherRows]) of [w] changed after its version [since]. */
internal fun weatherSame(w: GroundWeather, since: Int, rows: IntArray): Boolean {
    for (r in rows[0]..rows[1]) if (w.rowVersions[r] > since) return false
    return true
}
