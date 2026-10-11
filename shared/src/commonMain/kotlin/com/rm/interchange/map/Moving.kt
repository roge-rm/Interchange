package com.rm.interchange.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.WorldView
import com.rm.interchange.sim.World
import kotlin.math.sqrt

/** What moves, as the game last published it, the tick before, and how far it is between them. */
class Moving(val now: WorldView, val before: WorldView?, val between: Float)

/**
 * Draws signals and trains over the map, nearest last. Trains are placed
 * between where they were on the last two ticks, so they glide.
 */
internal fun DrawScope.drawMoving(world: World, view: View, sheets: List<ImageBitmap>, moving: Moving) {
    val level = Renderer.levelFor(view.halfWidth)
    val hw = Renderer.LEVELS[level]
    val painter = LandPainter(world, view, hw, sheets[level])
    val scale = view.halfWidth / hw
    // Everything to draw, as depth, x, y and atlas cell.
    val items = ArrayList<FloatArray>()
    for (s in moving.now.signals) {
        val p = signalPoint(world, s.tile, s.piece, s.facing)
        add(items, world, view, p.first, p.second, (if (s.red) Atlas.SIGNAL[1] else Atlas.SIGNAL[0]).toFloat())
    }
    val v = moving.now.vehicles
    val before = moving.before?.vehicles
    // Where each wagon was on the tick before, by its key.
    val was = HashMap<Int, Int>()
    if (before != null) for (i in 0 until before.count) was[before.key(i)] = i
    for (i in 0 until v.count) {
        var x = v.x(i)
        var y = v.y(i)
        val j = was[v.key(i)]
        if (j != null && before != null) {
            val bx = before.x(j)
            val by = before.y(j)
            // Only glide over a short way; a train that's jumped (out of a depot, turned round) just shows where it is.
            if ((x - bx) * (x - bx) + (y - by) * (y - by) < 1f) {
                x = bx + (x - bx) * moving.between
                y = by + (y - by) * moving.between
            }
        }
        val cell = Atlas.VEHICLE.getOrElse(v.sprite(i) * 8 + painter.viewHeading(v.heading(i))) { -1 }
        add(items, world, view, x, y, cell.toFloat())
    }
    items.sortBy { it[0] }
    with(painter) {
        for (it in items) spriteAt(it[3].toInt(), it[1], it[2], scale)
    }
}

private fun DrawScope.add(items: ArrayList<FloatArray>, world: World, view: View, x: Float, y: Float, cell: Float) {
    val vx = view.toViewX(x, y)
    val vy = view.toViewY(x, y)
    val sx = view.screenX(vx, vy, size.width)
    val sy = view.screenY(vx, vy, groundHeight(world, x, y), size.height)
    val margin = view.halfWidth * 2
    if (sx < -margin || sx > size.width + margin || sy < -margin || sy > size.height + margin * 2) return
    items += floatArrayOf(vx + vy, sx, sy, cell)
}

/** Where a signal on [piece] of [tile] facing [facing] stands: just in from that edge, beside the track on the right of trains it faces. */
fun signalPoint(world: World, tile: Int, piece: Int, facing: Int): Pair<Float, Float> {
    val other = Track.other(piece, facing)
    val ax = tile % world.width + Track.edgeX4(facing) / 4f
    val ay = tile / world.width + Track.edgeY4(facing) / 4f
    val bx = tile % world.width + Track.edgeX4(other) / 4f
    val by = tile / world.width + Track.edgeY4(other) / 4f
    val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
    val dx = (bx - ax) / len
    val dy = (by - ay) / len
    return (ax + dx * 0.2f - dy * 0.3f) to (ay + dy * 0.2f + dx * 0.3f)
}

/** The train with a wagon under screen point ([px], [py]), or -1. */
fun trainAt(world: World, view: View, moving: Moving?, px: Float, py: Float, w: Float, h: Float): Int {
    val v = moving?.now?.vehicles ?: return -1
    var best = -1
    var bestD = view.halfWidth * view.halfWidth * 0.5f
    for (i in 0 until v.count) {
        val vx = view.toViewX(v.x(i), v.y(i))
        val vy = view.toViewY(v.x(i), v.y(i))
        val sx = view.screenX(vx, vy, w)
        // Aimed at the wagon's middle, a little above the ground.
        val sy = view.screenY(vx, vy, groundHeight(world, v.x(i), v.y(i)) + 1f, h)
        val d = (sx - px) * (sx - px) + (sy - py) * (sy - py)
        if (d < bestD) {
            bestD = d
            best = v.train(i)
        }
    }
    return best
}
