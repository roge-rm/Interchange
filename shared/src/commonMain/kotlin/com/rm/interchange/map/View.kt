package com.rm.interchange.map

/**
 * Where the camera looks from: one of four directions, turned a quarter at a
 * time. View coordinates are the world's turned so that view x runs down to
 * the right on screen and view y down to the left.
 */
class View(val mapWidth: Int, val mapHeight: Int) {
    /** 0 to 3, a quarter turn each. */
    var turn = 0
        private set

    /** The world point at the centre of the screen, in tiles. */
    var focusX = mapWidth / 2f
    var focusY = mapHeight / 2f

    /** Half a tile's width on screen, in pixels. A tile is twice as wide as it is tall. */
    var halfWidth = 32f
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

    /** The focus in view coordinates. */
    fun focusViewX() = when (turn) {
        0 -> focusX
        1 -> mapHeight - focusY
        2 -> mapWidth - focusX
        else -> focusY
    }

    fun focusViewY() = when (turn) {
        0 -> focusY
        1 -> focusX
        2 -> mapHeight - focusY
        else -> mapWidth - focusX
    }

    /** Moves the focus by a drag of ([dx], [dy]) pixels, so the map follows the finger. */
    fun pan(dx: Float, dy: Float) {
        val a = -dx / halfWidth
        val b = -dy / halfHeight
        val dvx = (a + b) / 2
        val dvy = (b - a) / 2
        when (turn) {
            0 -> { focusX += dvx; focusY += dvy }
            1 -> { focusX += dvy; focusY -= dvx }
            2 -> { focusX -= dvx; focusY -= dvy }
            else -> { focusX -= dvy; focusY += dvx }
        }
        focusX = focusX.coerceIn(0f, mapWidth.toFloat())
        focusY = focusY.coerceIn(0f, mapHeight.toFloat())
    }

    fun zoom(by: Float) {
        halfWidth = (halfWidth * by).coerceIn(4f, 128f)
    }
}
