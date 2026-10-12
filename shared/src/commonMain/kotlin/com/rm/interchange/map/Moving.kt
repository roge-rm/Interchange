package com.rm.interchange.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.rm.interchange.sim.Signals
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
internal fun DrawScope.drawMoving(world: World, view: View, sheets: Sheets, moving: Moving) {
    // The level's sheet, or the nearest one unpacked while it isn't yet.
    val level = sheets.nearest(Renderer.levelFor(view.halfWidth)) ?: return
    val hw = Renderer.LEVELS[level]
    val painter = LandPainter(world, view, hw, sheets.get(level)?.signals)
    val scale = view.halfWidth / hw
    // Everything to draw, as depth, x, y, sprite, and which vehicle's sheet it's on (-1 for the signals').
    val items = ArrayList<FloatArray>()
    for (s in moving.now.signals) {
        val p = signalPoint(world, s.tile, s.piece, s.facing)
        add(items, world, view, p.first, p.second, (if (s.red) Atlas.SIGNAL[1] else Atlas.SIGNAL[0]).toFloat())
        // A plate at its foot for the kinds that aren't plain block signals, just in front of it.
        if (s.kind != Signals.TWO_WAY && s.kind != Signals.ONE_WAY) add(items, world, view, p.first, p.second, s.kind.toFloat(), 0.01f, MARK)
    }
    for (tile in moving.now.waypoints) add(items, world, view, tile % world.width + 0.5f, tile / world.width + 0.5f, 0f, 0f, WAYPOINT)
    // Engine houses go just in front of everything on their own tile and behind anything on the tile in front.
    for (d in moving.now.depots) {
        val wx = d.tile % world.width
        val wy = d.tile / world.width
        val vx = view.toViewX(wx + 0.5f, wy + 0.5f)
        val vy = view.toViewY(wx + 0.5f, wy + 0.5f)
        val sx = view.screenX(vx - 0.5f, vy - 0.5f, size.width)
        val base = world.heightAt(wx, wy).toFloat()
        // The sprite is anchored on the tile's top corner, half a tile above where a standing thing's would be.
        val sy = view.screenY(vx - 0.5f, vy - 0.5f, base, size.height) + view.halfHeight
        val margin = view.halfWidth * 3
        if (sx < -margin || sx > size.width + margin || sy < -margin || sy > size.height + margin * 2) continue
        items += floatArrayOf(vx + vy + DEPOT_DEPTH, sx, sy, Atlas.DEPOT_OVER[painter.viewEdge(d.edge)].toFloat(), -1f)
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
        val heading = painter.viewHeading(v.heading(i))
        // On a slope, the straight headings have drawings tilted up or down: sprites 8 on in the vehicle's sheet.
        val slope = v.slope(i)
        val cell = if (slope != 0 && heading % 2 == 0) 8 + heading / 2 * 2 + (if (slope > 0) 0 else 1) else heading
        // Sorted by its nearest end rather than its middle, so a long car in front covers what's behind it; but a car in
        // an engine house stays behind the house.
        var reach = v.length(i) / 2 * NEAR[heading]
        if (world.depotTiles.containsKey(world.index(x.toInt().coerceIn(0, world.width - 1), y.toInt().coerceIn(0, world.height - 1)))) {
            reach = minOf(reach, DEPOT_DEPTH - 0.1f - (view.toViewX(x, y) + view.toViewY(x, y) - (view.toViewX(x.toInt() + 0.5f, y.toInt() + 0.5f) + view.toViewY(x.toInt() + 0.5f, y.toInt() + 0.5f))))
        }
        add(items, world, view, x, y, cell.toFloat(), reach, v.sprite(i))
    }
    items.sortBy { it[0] }
    with(painter) {
        for (it in items) {
            val model = it[4].toInt()
            if (model == MARK || model == WAYPOINT) {
                drawMark(model, it[3].toInt(), it[1], it[2], view.halfWidth)
            } else if (model < 0) {
                spriteAt(it[3].toInt(), it[1], it[2], scale)
            } else {
                // Its own sheet at this level, unpacked the first time it's on screen; it shows a frame or two later.
                val sheet = sheets.vehicle(model, level) ?: continue
                spriteAt(sheet, sheets.vehicleRects(model) ?: continue, it[3].toInt(), it[1], it[2], scale)
            }
        }
    }
}

/** Items that aren't sprites: a signal's kind plate, and a waypoint's post. */
private const val MARK = -2
private const val WAYPOINT = -3

/** A signal kind's plate at the foot of its mast, or a waypoint's post with a diamond on top, standing at ([x], [y]) on screen. */
private fun DrawScope.drawMark(what: Int, kind: Int, x: Float, y: Float, hw: Float) {
    val u = hw / 16f
    if (what == WAYPOINT) {
        drawLine(Color(0xff3a3a3a), Offset(x, y), Offset(x, y - 7 * u), 1.2f * u)
        val d = Path().apply { moveTo(x, y - 11 * u); lineTo(x + 2.5f * u, y - 8.5f * u); lineTo(x, y - 6 * u); lineTo(x - 2.5f * u, y - 8.5f * u); close() }
        drawPath(d, Color(0xfff0c030))
        drawPath(d, Color(0xff3a3a3a), style = Stroke(0.6f * u))
        return
    }
    val colour = when (kind) {
        Signals.ENTRY -> Color(0xfff0c030)
        Signals.EXIT -> Color(0xff40a050)
        Signals.COMBO -> Color(0xff3a78d0)
        else -> Color(0xfff4f4f0)
    }
    val r = 1.6f * u
    drawRect(colour, Offset(x - r, y - r * 1.6f), Size(2 * r, 1.6f * r))
    drawRect(Color(0xff2a2a2a), Offset(x - r, y - r * 1.6f), Size(2 * r, 1.6f * r), style = Stroke(0.5f * u))
}

/** How far in front of its tile's middle an engine house is drawn, in view depth: past anything on the tile, short of the tile in front. */
private const val DEPOT_DEPTH = 0.95f

/** How much nearer the camera a car's front end is than its middle, per tile of half length, by heading in the view. */
private val NEAR = floatArrayOf(1f, 1.414f, 1f, 0f, 1f, 1.414f, 1f, 0f)

private fun DrawScope.add(items: ArrayList<FloatArray>, world: World, view: View, x: Float, y: Float, cell: Float, reach: Float = 0f, model: Int = -1) {
    val vx = view.toViewX(x, y)
    val vy = view.toViewY(x, y)
    val sx = view.screenX(vx, vy, size.width)
    val sy = view.screenY(vx, vy, groundHeight(world, x, y), size.height)
    val margin = view.halfWidth * 2
    if (sx < -margin || sx > size.width + margin || sy < -margin || sy > size.height + margin * 2) return
    items += floatArrayOf(vx + vy + reach, sx, sy, cell, model.toFloat())
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
