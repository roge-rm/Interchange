package com.rm.interchange.map

import androidx.compose.ui.graphics.ImageBitmap
import com.rm.interchange.sim.Ground
import com.rm.interchange.sim.Trees
import com.rm.interchange.sim.World

/**
 * A small picture of a whole map as the game first shows it: a diamond, each
 * pixel a tile or a few, coloured by its ground and lit by its slope from the
 * top left. Clear outside the diamond. For the new game screen's preview and
 * the title screen. At most [most] pixels across.
 */
fun mapPicture(world: World, most: Int = 640): ImageBitmap {
    val w = world.width
    val h = world.height
    // Tiles to a pixel across, so the picture fits.
    val k = maxOf(1, (w + h + most - 1) / most)
    val pw = (w + h) / k
    val ph = maxOf(1, (w + h) / (2 * k))
    val out = IntArray(pw * ph)
    for (py in 0 until ph) for (px in 0 until pw) {
        // Screen x runs with view x less view y, screen y with half their sum.
        val sx = (px * k - h).toFloat()
        val sy = (py * 2 * k).toFloat() / 2f
        val vx = (sy + sx / 2).toInt()
        val vy = (sy - sx / 2).toInt()
        if (vx < 0 || vy < 0 || vx >= w || vy >= h) continue
        out[py * pw + px] = colourOf(world, vx, vy)
    }
    return imageBitmapOf(out, pw, ph)
}

private fun colourOf(world: World, x: Int, y: Int): Int {
    val t = world.heightAt(x, y)
    val r = world.heightAt(x + 1, y)
    val b = world.heightAt(x + 1, y + 1)
    val l = world.heightAt(x, y + 1)
    if (t == 0 && r == 0 && b == 0 && l == 0) return WATER
    val base = if (minOf(minOf(t, r), minOf(b, l)) == 0) SAND else when (world.ground[world.index(x, y)].toInt()) {
        Ground.MEADOW -> MEADOW
        Ground.PRAIRIE -> PRAIRIE
        Ground.FOREST -> FOREST
        Ground.ROCK -> ROCK
        Ground.TUNDRA -> TUNDRA
        else -> SNOW
    }
    // Woods a shade darker, and the slope lit as the map is: from lower x and a little lower y, so land rising
    // that way faces the light.
    val trees = Trees.count(world.trees[world.index(x, y)].toInt())
    val slope = (r + b) - (t + l) + 0.5f * ((l + b) - (t + r))
    var f = 1f + slope * 0.09f - trees * 0.05f
    f = f.coerceIn(0.6f, 1.3f)
    val rr = minOf(255, (((base shr 16) and 255) * f).toInt())
    val gg = minOf(255, (((base shr 8) and 255) * f).toInt())
    val bb = minOf(255, ((base and 255) * f).toInt())
    return (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb
}

private const val WATER = 0xff3669aa.toInt()
private const val SAND = 0xffccb884.toInt()
private const val MEADOW = 0xff4a7a2e.toInt()
private const val PRAIRIE = 0xff968e50.toInt()
private const val FOREST = 0xff3c522a.toInt()
private const val ROCK = 0xff8c847e.toInt()
private const val TUNDRA = 0xff7e7a52.toInt()
private const val SNOW = 0xffe4ebf2.toInt()
