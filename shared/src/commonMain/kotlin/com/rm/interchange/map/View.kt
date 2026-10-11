package com.rm.interchange.map

import com.rm.interchange.sim.CORNER_E
import com.rm.interchange.sim.CORNER_N
import com.rm.interchange.sim.CORNER_S
import com.rm.interchange.sim.CORNER_W
import com.rm.interchange.sim.World
import com.rm.interchange.sim.surfaceHeight
import kotlin.math.floor

/**
 * Where the camera looks from: one of four directions, turned a quarter at a
 * time. View coordinates are the world's turned so that view x runs down to
 * the right on screen and view y down to the left. Points are continuous, in
 * tiles; tile (vx, vy) covers vx to vx + 1.
 */
class View(val mapWidth: Int, val mapHeight: Int) {
    /** 0 to 3, a quarter turn each. */
    var turn = 0
        private set

    /** The world point at the centre of the screen, in tiles. */
    var focusX = mapWidth / 2f
    var focusY = mapHeight / 2f

    /** Half a tile's width on screen, in pixels. A tile is twice as wide as it is tall. */
    var halfWidth = 64f
    val halfHeight get() = halfWidth / 2
    /** One step of height on screen. */
    val step get() = halfWidth / 4

    val viewWidth get() = if (turn % 2 == 0) mapWidth else mapHeight
    val viewHeight get() = if (turn % 2 == 0) mapHeight else mapWidth

    fun turnBy(quarters: Int) {
        turn = (turn + quarters).mod(4)
    }

    /** The world x of view tile ([vx], [vy]). */
    fun worldX(vx: Int, vy: Int) = when (turn) {
        0 -> vx
        1 -> vy
        2 -> mapWidth - 1 - vx
        else -> mapWidth - 1 - vy
    }

    fun worldY(vx: Int, vy: Int) = when (turn) {
        0 -> vy
        1 -> mapHeight - 1 - vx
        2 -> mapHeight - 1 - vy
        else -> vx
    }

    /** The world corner x of view corner ([vx], [vy]). */
    fun cornerX(vx: Int, vy: Int) = when (turn) {
        0 -> vx
        1 -> vy
        2 -> mapWidth - vx
        else -> mapWidth - vy
    }

    fun cornerY(vx: Int, vy: Int) = when (turn) {
        0 -> vy
        1 -> mapHeight - vx
        2 -> mapHeight - vy
        else -> vx
    }

    /** A world point in view coordinates. */
    fun toViewX(x: Float, y: Float) = when (turn) {
        0 -> x
        1 -> mapHeight - y
        2 -> mapWidth - x
        else -> y
    }

    fun toViewY(x: Float, y: Float) = when (turn) {
        0 -> y
        1 -> x
        2 -> mapHeight - y
        else -> mapWidth - x
    }

    /** A view point in world coordinates. */
    fun toWorldX(vx: Float, vy: Float) = when (turn) {
        0 -> vx
        1 -> vy
        2 -> mapWidth - vx
        else -> mapWidth - vy
    }

    fun toWorldY(vx: Float, vy: Float) = when (turn) {
        0 -> vy
        1 -> mapHeight - vx
        2 -> mapHeight - vy
        else -> vx
    }

    fun focusViewX() = toViewX(focusX, focusY)
    fun focusViewY() = toViewY(focusX, focusY)

    /** Screen position of view point ([vx], [vy]) at height [h], for a screen [w] by [hgt] pixels. */
    fun screenX(vx: Float, vy: Float, w: Float) = w / 2 + ((vx - vy) - (focusViewX() - focusViewY())) * halfWidth
    fun screenY(vx: Float, vy: Float, h: Float, hgt: Float) = hgt / 2 + ((vx + vy) - (focusViewX() + focusViewY())) * halfHeight - h * step

    /** Moves the focus by a drag of ([dx], [dy]) pixels, so the map follows the finger. */
    fun pan(dx: Float, dy: Float) {
        val a = -dx / halfWidth
        val b = -dy / halfHeight
        val vx = focusViewX() + (a + b) / 2
        val vy = focusViewY() + (b - a) / 2
        focusX = toWorldX(vx, vy).coerceIn(0f, mapWidth.toFloat())
        focusY = toWorldY(vx, vy).coerceIn(0f, mapHeight.toFloat())
    }

    fun zoom(by: Float) {
        halfWidth = (halfWidth * by).coerceIn(MIN_HALF_WIDTH, MAX_HALF_WIDTH)
    }

    /**
     * The world point under screen point ([px], [py]), on the land: the
     * frontmost point whose ground is as high as the line of sight there.
     * Null off the map.
     */
    fun pick(world: World, px: Float, py: Float, w: Float, hgt: Float): Pick? {
        val a = (px - w / 2) / halfWidth
        var h = World.MAX_HEIGHT.toFloat()
        while (h >= 0f) {
            val b = (py - hgt / 2 + h * step) / halfHeight
            val vx = focusViewX() + (a + b) / 2
            val vy = focusViewY() + (b - a) / 2
            if (vx >= 0 && vy >= 0 && vx < viewWidth && vy < viewHeight) {
                val x = toWorldX(vx, vy)
                val y = toWorldY(vx, vy)
                if (groundHeight(world, x, y) >= h - 0.125f) return Pick(x, y, px, py)
            }
            h -= 0.125f
        }
        return null
    }

    companion object {
        const val MIN_HALF_WIDTH = 3f
        const val MAX_HALF_WIDTH = 96f
    }
}

/** A world point picked on the land, in tiles, and the screen point it was picked at. */
class Pick(val x: Float, val y: Float, val screenX: Float = 0f, val screenY: Float = 0f) {
    val tileX get() = floor(x).toInt()
    val tileY get() = floor(y).toInt()
    /** Where in its tile, 0 to 1. */
    val fx get() = x - floor(x)
    val fy get() = y - floor(y)
}

/** The height at world point ([x], [y]) of the land, or of the foundation built on it. */
fun groundHeight(world: World, x: Float, y: Float): Float {
    val tx = floor(x).toInt().coerceIn(0, world.width - 1)
    val ty = floor(y).toInt().coerceIn(0, world.height - 1)
    val fx = (x - tx).coerceIn(0f, 1f)
    val fy = (y - ty).coerceIn(0f, 1f)
    val p = world.trackAt(tx, ty)
    val n = world.surfaceHeight(tx, ty, p, CORNER_N)
    val e = world.surfaceHeight(tx, ty, p, CORNER_E)
    val s = world.surfaceHeight(tx, ty, p, CORNER_S)
    val w = world.surfaceHeight(tx, ty, p, CORNER_W)
    return (n * (1 - fx) + e * fx) * (1 - fy) + (w * (1 - fx) + s * fx) * fy
}

/** Zooms by [by] keeping the point under ([px], [py]) where it is, on a screen [w] by [h]. */
fun View.zoomAt(by: Float, px: Float, py: Float, w: Float, h: Float) {
    val a0 = (px - w / 2) / halfWidth
    val b0 = (py - h / 2) / halfHeight
    zoom(by)
    val a1 = (px - w / 2) / halfWidth
    val b1 = (py - h / 2) / halfHeight
    // The view point under the finger before, less where it would be now.
    val dvx = ((a0 + b0) - (a1 + b1)) / 2
    val dvy = ((b0 - a0) - (b1 - a1)) / 2
    val vx = focusViewX() + dvx
    val vy = focusViewY() + dvy
    focusX = toWorldX(vx, vy).coerceIn(0f, mapWidth.toFloat())
    focusY = toWorldY(vx, vy).coerceIn(0f, mapHeight.toFloat())
}
