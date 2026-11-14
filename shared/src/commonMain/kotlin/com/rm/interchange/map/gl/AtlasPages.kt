package com.rm.interchange.map.gl

import com.rm.interchange.map.Atlas

/**
 * A sheet [width] by [height] cut into the layers of a texture array no bigger than [maxSize] each way. Pages
 * overlap by [tallest], the tallest sprite on the sheet, so every sprite lies whole in one: layer l holds sheet rows
 * l * [step] until l * step + [pageHeight].
 */
internal class AtlasPages(val width: Int, val height: Int, val tallest: Int, maxSize: Int) {
    val layers: Int
    val step: Int
    val pageHeight: Int

    init {
        require(width <= maxSize) { "sheet $width wide, textures up to $maxSize" }
        require(tallest < maxSize) { "sprite $tallest tall, textures up to $maxSize" }
        if (height <= maxSize) {
            layers = 1
            step = height
            pageHeight = height
        } else {
            layers = (height - tallest + (maxSize - tallest) - 1) / (maxSize - tallest)
            step = (height - tallest + layers - 1) / layers
            pageHeight = step + tallest
        }
    }

    /** The layer holding the sprite whose top is sheet row [sy]. */
    fun layerOf(sy: Int) = minOf(sy / step, layers - 1)

    /** Sheet row [sy] in its sprite's layer. */
    fun rowIn(sy: Int) = sy - layerOf(sy) * step

    /** The rows of the sheet layer [layer] takes, the first and how many. */
    fun rowsOf(layer: Int): Pair<Int, Int> {
        val y0 = layer * step
        return y0 to minOf(pageHeight, height - y0)
    }

    companion object {
        /** The tallest static sprite at [level], in that level's pixels. */
        fun tallestStatic(level: Int): Int {
            var m = 0
            for (cell in 0 until Atlas.STATIC_LEVEL_RECTS.size / (6 * Atlas.LEVELS)) m = maxOf(m, Atlas.STATIC_LEVEL_RECTS[(cell * Atlas.LEVELS + level) * 6 + 3])
            return m
        }

        /** The tallest shadow at [level], as the baker shrinks them. */
        fun tallestShadow(level: Int): Int {
            var m = 0
            for (cell in 0 until Atlas.SHADOW_RECTS.size / 6) m = maxOf(m, Atlas.SHADOW_RECTS[cell * 6 + 3] / (1 shl level))
            return m
        }
    }
}
