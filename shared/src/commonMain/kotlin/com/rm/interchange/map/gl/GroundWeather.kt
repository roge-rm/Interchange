package com.rm.interchange.map.gl

import com.rm.interchange.map.WeatherCopy
import com.rm.interchange.sim.World

/**
 * The weather as the GPU map's shaders read it: four bytes for each world corner, [width] + 1 by [height] + 1,
 * row by row ([TEXEL] bytes each):
 * - 0: the snow on the four tiles round the corner summed, in cm, up to 255 (the tile recorder's corner snow times 4)
 * - 1: the ice round the corner as the tile recorder counts it, the shore as thick as the thickest ice there, times 4, up to 255
 * - 2: the mud on the tile whose top corner it is (world x and y the corner's), 0 to 255
 * - 3: the snow on that tile in cm, up to 255, for the snow on sprites standing there
 * Past 255 the shaders can't tell: full snow cover is reached by 18 cm and the thickest ice look by 26 cm.
 * [rowVersions] holds the [version] each corner row last changed in, so the GL thread uploads only the rows that
 * changed since the copy it last took.
 */
internal class GroundWeather(val width: Int, val height: Int, val texels: ByteArray, val version: Int, val rowVersions: IntArray) {
    companion object {
        const val TEXEL = 4

        /** The ground weather for [world] from [copy], counting rows that differ from [last] as changed. Off the main thread. */
        fun of(world: World, copy: WeatherCopy, last: GroundWeather?): GroundWeather {
            val w = world.width + 1
            val h = world.height + 1
            val texels = ByteArray(w * h * TEXEL)
            for (cy in 0 until h) for (cx in 0 until w) texel(world, copy, cx, cy, texels, (cy * w + cx) * TEXEL)
            val same = last != null && last.width == world.width && last.height == world.height
            val version = if (same) last!!.version + 1 else 0
            val rows = IntArray(h) { version }
            if (same) {
                for (cy in 0 until h) {
                    var changed = false
                    val o = cy * w * TEXEL
                    for (i in o until o + w * TEXEL) if (texels[i] != last!!.texels[i]) { changed = true; break }
                    if (!changed) rows[cy] = last!!.rowVersions[cy]
                }
            }
            return GroundWeather(world.width, world.height, texels, version, rows)
        }

        /** Corner ([cx], [cy])'s four bytes into [out] at [o]. */
        fun texel(world: World, copy: WeatherCopy, cx: Int, cy: Int, out: ByteArray, o: Int) {
            var snow = 0
            var ice = 0
            var most = 0
            var shore = 0
            for (dy in -1..0) for (dx in -1..0) {
                val x = (cx + dx).coerceIn(0, world.width - 1)
                val y = (cy + dy).coerceIn(0, world.height - 1)
                val i = world.index(x, y)
                snow += copy.snow[i].toInt() and 255
                if (world.isWater(x, y) || world.canals.contains(i)) {
                    val got = copy.ice[i].toInt() and 255
                    ice += got
                    most = maxOf(most, got)
                } else shore++
            }
            out[o] = minOf(255, snow).toByte()
            out[o + 1] = minOf(255, ice + shore * most).toByte()
            out[o + 2] = if (cx < world.width && cy < world.height) copy.mud[world.index(cx, cy)] else 0
            out[o + 3] = if (cx < world.width && cy < world.height) copy.snow[world.index(cx, cy)] else 0
        }
    }
}
