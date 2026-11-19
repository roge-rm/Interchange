package com.rm.interchange.map.gl

import com.rm.interchange.map.View
import com.rm.interchange.map.ViewAt
import kotlin.math.sqrt

/**
 * The cloud shades and fog banks over the map in one frame, [FLOATS] each in [data]: world centre x and y in tiles,
 * height drawn at, radius in tiles, and colour as red, green, blue and alpha from 0 to 1, unpremultiplied.
 */
internal class ShadeBatch(val data: FloatArray, val count: Int, val mapWidth: Int, val mapHeight: Int) {
    fun sameAs(o: ShadeBatch?): Boolean {
        if (o == null || o.count != count || o.mapWidth != mapWidth || o.mapHeight != mapHeight) return false
        for (i in 0 until count * FLOATS) if (data[i] != o.data[i]) return false
        return true
    }

    private var view: View? = null

    /**
     * Puts the shades on a [w] by [h] screen at [at], into [out] as [PLACED] floats each: centre x and y and radii
     * across and down in screen pixels, then the colour. A circle on the map is an ellipse on screen, twice as wide
     * as tall. Returns how many went, those off screen left out.
     */
    fun place(at: ViewAt, w: Int, h: Int, out: FloatArray): Int {
        val v = view ?: View(mapWidth, mapHeight).also { view = it }
        v.moveTo(at)
        var n = 0
        for (i in 0 until count) {
            val o = i * FLOATS
            val x = data[o]
            val y = data[o + 1]
            val vx = v.toViewX(x, y)
            val vy = v.toViewY(x, y)
            val cx = v.screenX(vx, vy, w.toFloat())
            val cy = v.screenY(vx, vy, data[o + 2], h.toFloat())
            val rx = data[o + 3] * v.halfWidth * SQRT2
            val ry = data[o + 3] * v.halfHeight * SQRT2
            if (cx + rx < 0 || cx - rx > w || cy + ry < 0 || cy - ry > h) continue
            val q = n * PLACED
            out[q] = cx
            out[q + 1] = cy
            out[q + 2] = rx
            out[q + 3] = ry
            for (c in 0 until 4) out[q + 4 + c] = data[o + 4 + c]
            n++
        }
        return n
    }

    companion object {
        const val FLOATS = 8
        const val PLACED = 8
        private val SQRT2 = sqrt(2f)
    }
}

/**
 * Takes the cloud shades and fog for a frame, between [GlScene.show] and [GlScene.finish], which hands them to the GL
 * thread with the frame. Main thread.
 */
internal class MapShades(private val mapWidth: Int, private val mapHeight: Int) {
    private var data = FloatArray(16 * ShadeBatch.FLOATS)
    private var count = 0

    /** Whether any were taken since [reset]; a frame that took none keeps the last. */
    var taken = false
        private set

    fun reset() {
        count = 0
        taken = false
    }

    /** Marks the frame's shades as taken, even if there are none, as with no weather. */
    fun take() {
        taken = true
    }

    /** Adds a shade round world point ([x], [y]) at [height], [radius] tiles out, in [rgb] at [alpha] fading out to its edge. */
    fun add(x: Float, y: Float, height: Float, radius: Float, rgb: Int, alpha: Float) {
        taken = true
        if ((count + 1) * ShadeBatch.FLOATS > data.size) data = data.copyOf(data.size * 2)
        val o = count * ShadeBatch.FLOATS
        data[o] = x
        data[o + 1] = y
        data[o + 2] = height
        data[o + 3] = radius
        data[o + 4] = ((rgb shr 16) and 255) / 255f
        data[o + 5] = ((rgb shr 8) and 255) / 255f
        data[o + 6] = (rgb and 255) / 255f
        data[o + 7] = alpha
        count++
    }

    /** The frame's shades, or [last] if they're the same or none were taken, so a still map hands nothing new. */
    fun batch(last: ShadeBatch?): ShadeBatch? {
        if (!taken) return last
        if (count == 0) return null
        val b = ShadeBatch(data.copyOf(count * ShadeBatch.FLOATS), count, mapWidth, mapHeight)
        return if (b.sameAs(last)) last else b
    }
}
