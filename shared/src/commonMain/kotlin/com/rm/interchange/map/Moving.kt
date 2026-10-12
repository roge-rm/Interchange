package com.rm.interchange.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.rm.interchange.sim.Signals
import com.rm.interchange.sim.Spans
import com.rm.interchange.sim.edgeHeight
import com.rm.interchange.sim.burning
import com.rm.interchange.sim.closedTile
import com.rm.interchange.sim.trackLimit
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
    // Bridges a tile at a time, a step of pier at a time up from the ground, just in front of what's on the tile
    // under them; trains on a bridge are put further forward still. Tunnel mouths like engine houses.
    for (s in moving.now.spans) {
        val dx = (s.x1 - s.x0).coerceIn(-1, 1)
        val dy = (s.y1 - s.y0).coerceIn(-1, 1)
        if (s.tunnel) {
            for ((hx, hy, out) in listOf(Triple(s.x0, s.y0, edgeOf(-dx, -dy)), Triple(s.x1, s.y1, edgeOf(dx, dy)))) {
                tileItem(items, view, hx, hy, s.height.toFloat(), DEPOT_DEPTH, Atlas.PORTAL[painter.viewEdge(out)])
            }
            continue
        }
        val along = if (painter.viewEdge(if (dx != 0) Track.XP else Track.YP) <= Track.XP) 0 else 1
        val len = maxOf(kotlin.math.abs(s.x1 - s.x0), kotlin.math.abs(s.y1 - s.y0)) - 1
        for (k in 1..len) {
            val x = s.x0 + dx * k
            val y = s.y0 + dy * k
            val ground = minOf(minOf(world.heightAt(x, y), world.heightAt(x + 1, y)), minOf(world.heightAt(x + 1, y + 1), world.heightAt(x, y + 1)))
            // A trestle stands on bents every tile; trusses and girders on piers every other.
            if (s.style == Spans.TRESTLE || k % 2 == 1) for (z in ground until s.height) {
                tileItem(items, view, x, y, z.toFloat(), DECK_DEPTH - 0.01f, Atlas.SPAN_PIER[s.style * 2 + along])
            }
            tileItem(items, view, x, y, s.height.toFloat(), DECK_DEPTH, Atlas.SPAN_DECK[s.style * 2 + along])
        }
    }
    // Fires, and boards by worn or closed track, close enough in to make out.
    if (view.halfWidth >= 8f) forTilesOnScreen(world, view) { x, y ->
        val i = world.index(x, y)
        if (world.burning(i)) add(items, world, view, x + 0.5f, y + 0.5f, i.toFloat(), 0.2f, FIRE)
        if (world.track[i].toInt() != 0 && view.halfWidth >= 16f) {
            val sign = when {
                world.closedTile(i) -> 2
                world.trackLimit(i) > 0 -> 1
                else -> 0
            }
            if (sign != 0) add(items, world, view, x + 0.2f, y + 0.2f, sign.toFloat(), 0f, BOARD)
        }
    }
    // Wires over electrified track, close enough in to make out: a mast at one end of each piece, the wire along it.
    if (view.halfWidth >= 12f) forTilesOnScreen(world, view) { x, y ->
        val i = world.index(x, y)
        val wired = world.wires[i].toInt()
        if (wired == 0) return@forTilesOnScreen
        for (p in Track.PIECES) {
            if (wired and p == 0) continue
            val e = Track.edges(p)
            val a = e and 3
            val b = e shr 2
            val ha = world.edgeHeight(x, y, world.track[i].toInt(), a) + WIRE_HEIGHT
            val hb = world.edgeHeight(x, y, world.track[i].toInt(), b) + WIRE_HEIGHT
            val ax = x + Track.edgeX4(a) / 4f
            val ay = y + Track.edgeY4(a) / 4f
            val bx = x + Track.edgeX4(b) / 4f
            val by = y + Track.edgeY4(b) / 4f
            wireItem(items, view, ax, ay, ha, bx, by, hb)
        }
    }
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
        // On a bridge, at its deck's height and in front of the deck.
        val deck = v.deck(i)
        if (deck >= 0f) reach += ON_DECK
        add(items, world, view, x, y, cell.toFloat(), reach, v.sprite(i), deck)
    }
    items.sortBy { it[0] }
    with(painter) {
        for (it in items) {
            val model = it[4].toInt()
            if (model == WIRE) {
                drawWire(it, view.halfWidth)
            } else if (model == FIRE) {
                drawFire(it[3].toInt(), it[1], it[2], view.halfWidth)
            } else if (model == BOARD) {
                drawBoard(it[3].toInt(), it[1], it[2], view.halfWidth)
            } else if (model == MARK || model == WAYPOINT) {
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

/** Items that aren't sprites: a signal's kind plate, a waypoint's post, and wires over a piece of track. */
private const val MARK = -2
private const val WAYPOINT = -3
private const val WIRE = -4
private const val FIRE = -5
private const val BOARD = -6

/** Flames licking up from a burning tile, flickering, and smoke rising off it; [seed] keeps tiles from flickering together. */
private fun DrawScope.drawFire(seed: Int, x: Float, y: Float, hw: Float) {
    val u = hw / 16f
    val t = (flicker.elapsedNow().inWholeMilliseconds % 100_000) / 1000f
    for (k in 0 until 5) {
        val r = ((seed * 31 + k * 17) and 255) / 255f
        val fx = x + (r - 0.5f) * 12f * u
        val tall = (4f + 3f * kotlin.math.sin(t * 9f + k * 1.7f + seed)) * u
        val p = Path().apply { moveTo(fx - 1.6f * u, y); lineTo(fx, y - tall); lineTo(fx + 1.6f * u, y); close() }
        drawPath(p, if (k % 2 == 0) Color(0xe0f06a1a) else Color(0xd0f8c030))
    }
    for (k in 0 until 3) {
        val rise = ((t * 0.6f + k / 3f + (seed and 7) / 8f) % 1f)
        drawCircle(Color(0x50606060).copy(alpha = 0.35f * (1f - rise)), (2f + rise * 6f) * u, Offset(x + rise * 6f * u, y - 6f * u - rise * 22f * u))
    }
}

/** A board beside the track: yellow for a slow order, red and white across it for a closure. */
private fun DrawScope.drawBoard(kind: Int, x: Float, y: Float, hw: Float) {
    val u = hw / 32f
    drawLine(Color(0xff3a3a3a), Offset(x, y), Offset(x, y - 9f * u), 1.2f * u)
    if (kind == 1) {
        drawRect(Color(0xfff0c030), Offset(x - 3f * u, y - 13f * u), Size(6f * u, 5f * u))
        drawRect(Color(0xff2a2a2a), Offset(x - 3f * u, y - 13f * u), Size(6f * u, 5f * u), style = Stroke(0.6f * u))
    } else {
        for (k in 0 until 4) drawRect(if (k % 2 == 0) Color(0xffd03a2a) else Color(0xfff4f4f0), Offset(x - 8f * u + k * 4f * u, y - 9f * u), Size(4f * u, 2.5f * u))
    }
}

private val flicker = kotlin.time.TimeSource.Monotonic.markNow()
/** How high the contact wire hangs over the rails, in steps. */
private const val WIRE_HEIGHT = 2.6f

/** Calls [block] with every tile that could show on screen, from the screen's corners on the map at sea level and the highest land. */
private inline fun DrawScope.forTilesOnScreen(world: World, view: View, block: (Int, Int) -> Unit) {
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (px in floatArrayOf(0f, size.width)) for (py in floatArrayOf(0f, size.height)) for (ht in floatArrayOf(0f, World.MAX_HEIGHT.toFloat())) {
        val a = (px - size.width / 2) / view.halfWidth
        val b = (py - size.height / 2 + ht * view.step) / view.halfHeight
        val vx = view.focusViewX() + (a + b) / 2
        val vy = view.focusViewY() + (b - a) / 2
        val wx = view.toWorldX(vx, vy)
        val wy = view.toWorldY(vx, vy)
        minX = minOf(minX, wx); maxX = maxOf(maxX, wx); minY = minOf(minY, wy); maxY = maxOf(maxY, wy)
    }
    for (y in maxOf(0, minY.toInt() - 1)..minOf(world.height - 1, maxY.toInt() + 1)) for (x in maxOf(0, minX.toInt() - 1)..minOf(world.width - 1, maxX.toInt() + 1)) block(x, y)
}

/** A piece's wire from ([ax], [ay]) at [ha] steps to ([bx], [by]) at [hb], with a mast beside its first end. */
private fun DrawScope.wireItem(items: ArrayList<FloatArray>, view: View, ax: Float, ay: Float, ha: Float, bx: Float, by: Float, hb: Float) {
    fun sx(x: Float, y: Float) = view.screenX(view.toViewX(x, y), view.toViewY(x, y), size.width)
    fun sy(x: Float, y: Float, h: Float) = view.screenY(view.toViewX(x, y), view.toViewY(x, y), h, size.height)
    val x1 = sx(ax, ay)
    val y1 = sy(ax, ay, ha)
    if (x1 < -view.halfWidth * 2 || x1 > size.width + view.halfWidth * 2 || y1 < -view.halfWidth * 2 || y1 > size.height + view.halfWidth * 4) return
    // The mast stands off to one side of the track, square to it.
    val dx = bx - ax
    val dy = by - ay
    val len = sqrt(dx * dx + dy * dy)
    val mx = ax - dy / len * 0.32f
    val my = ay + dx / len * 0.32f
    val depth = view.toViewX((ax + bx) / 2, (ay + by) / 2) + view.toViewY((ax + bx) / 2, (ay + by) / 2) + 0.3f
    items += floatArrayOf(depth, x1, y1, 0f, WIRE.toFloat(), sx(bx, by), sy(bx, by, hb), sx(mx, my), sy(mx, my, ha - WIRE_HEIGHT), sy(mx, my, ha + 0.3f))
}

/** Draws a wire item: the mast from the ground up, its arm out to the wire, and the wire. */
private fun DrawScope.drawWire(it: FloatArray, hw: Float) {
    val u = hw / 40f
    val mast = Color(0xff4a4e52)
    drawLine(mast, Offset(it[7], it[8]), Offset(it[7], it[9]), 2.2f * u)
    drawLine(mast, Offset(it[7], it[9]), Offset(it[1], it[2]), 1.2f * u)
    drawLine(Color(0xff2a2c2e), Offset(it[1], it[2]), Offset(it[5], it[6]), 1f * u)
}

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

/** A sprite from the signals sheet anchored on tile ([x], [y])'s top corner at height [h], at [depth] in front of the tile's middle. */
private fun DrawScope.tileItem(items: ArrayList<FloatArray>, view: View, x: Int, y: Int, h: Float, depth: Float, cell: Int) {
    val vx = view.toViewX(x + 0.5f, y + 0.5f)
    val vy = view.toViewY(x + 0.5f, y + 0.5f)
    val sx = view.screenX(vx - 0.5f, vy - 0.5f, size.width)
    val sy = view.screenY(vx - 0.5f, vy - 0.5f, h, size.height) + view.halfHeight
    val margin = view.halfWidth * 3
    if (sx < -margin || sx > size.width + margin || sy < -margin || sy > size.height + margin * 2) return
    items += floatArrayOf(vx + vy + depth, sx, sy, cell.toFloat(), -1f)
}

/** The edge a step of ([dx], [dy]) goes out through. */
private fun edgeOf(dx: Int, dy: Int) = when {
    dx > 0 -> Track.XP
    dx < 0 -> Track.XM
    dy > 0 -> Track.YP
    else -> Track.YM
}

/** How far in front of its tile's middle a bridge deck is drawn, and how much further cars on it. */
private const val DECK_DEPTH = 0.45f
private const val ON_DECK = 0.9f

/** How far in front of its tile's middle an engine house is drawn, in view depth: past anything on the tile, short of the tile in front. */
private const val DEPOT_DEPTH = 0.95f

/** How much nearer the camera a car's front end is than its middle, per tile of half length, by heading in the view. */
private val NEAR = floatArrayOf(1f, 1.414f, 1f, 0f, 1f, 1.414f, 1f, 0f)

private fun DrawScope.add(items: ArrayList<FloatArray>, world: World, view: View, x: Float, y: Float, cell: Float, reach: Float = 0f, model: Int = -1, deck: Float = -1f) {
    val vx = view.toViewX(x, y)
    val vy = view.toViewY(x, y)
    val sx = view.screenX(vx, vy, size.width)
    val sy = view.screenY(vx, vy, if (deck >= 0f) deck else groundHeight(world, x, y), size.height)
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
