package com.rm.interchange.map.gl

import com.rm.interchange.map.Renderer
import com.rm.interchange.map.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the GPU map puts things this frame, worked out from [View] as the chunk renderer places its chunks: a
 * [SOURCE_LEVEL] pixel p lands on screen at origin + p * [scale]. [originX] and [originY] are where view point 0, 0
 * at height 0 lands, rounded to whole pixels as chunks are. Screen sizes are in surface pixels.
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
 * columns[2 * vy + 1], inclusive, empty when the first is the greater. The same reach the chunk renderer bakes a
 * chunk's tiles by: three half widths either side, the highest land and tallest sprite above, the earth below.
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
    val b1 = ceil((y1 + Renderer.above(hw)) / hh).toInt() + 1
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
