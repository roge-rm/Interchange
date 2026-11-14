package com.rm.interchange.map

import com.rm.interchange.sim.World

/** The levels the map's sheets come in, one for each halving of the zoom, and which one a zoom draws from. */
internal object Zoom {
    /** Half a tile's width at each level, sharpest first. */
    val LEVELS = floatArrayOf(128f, 64f, 32f, 16f, 8f, 4f)

    /** The sharpest level drawn. Level 0's sheets take some 130 MB, more than a small heap holds with everything else. */
    val SHARPEST = if (heapBytes() >= 400L * 1024 * 1024) 0 else 1

    /** How far above its place at height 0 a tile can draw: the highest land and what stands on it, trees and roofs. */
    fun above(hw: Float) = World.MAX_HEIGHT * hw / 4 + 3 * hw + 2

    /** The level to draw at: the sharpest one no bigger than needed, so sheets only ever scale down, except past the sharpest. */
    fun levelFor(halfWidth: Float): Int {
        for (i in LEVELS.indices.reversed()) if (LEVELS[i] >= halfWidth) return maxOf(i, SHARPEST)
        return SHARPEST
    }
}
