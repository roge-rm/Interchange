package com.rm.interchange.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Signals
import com.rm.interchange.sim.Spans
import com.rm.interchange.sim.edgeHeight
import com.rm.interchange.sim.burning
import com.rm.interchange.sim.keyLevel
import com.rm.interchange.sim.keyPiece
import com.rm.interchange.sim.keyTile
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
        val heads = if ((moving.now.weather?.year ?: 0) >= Atlas.SIGNAL_LIGHTS_FROM) Atlas.SIGNAL_LIGHT else Atlas.SIGNAL
        add(items, world, view, p.first, p.second, (if (s.red) heads[1] else heads[0]).toFloat())
        // A plate at its foot for the kinds that aren't plain block signals, just in front of it.
        if (s.kind != Signals.TWO_WAY && s.kind != Signals.ONE_WAY) add(items, world, view, p.first, p.second, s.kind.toFloat(), 0.01f, MARK)
    }
    for (tile in moving.now.waypoints) add(items, world, view, tile % world.width + 0.5f, tile / world.width + 0.5f, 0f, 0f, WAYPOINT)
    for (tile in moving.now.buoys) add(items, world, view, tile % world.width + 0.5f, tile / world.width + 0.5f, 0f, 0f, BUOY)
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
    // Level crossings with a train near: a post with two red lamps on opposite corners, close enough in to make out.
    if (view.halfWidth >= 8f) for (tile in moving.now.crossings) {
        val x = tile % world.width
        val y = tile / world.width
        add(items, world, view, x + 0.18f, y + 0.18f, 0f, 0f, CROSSING)
        add(items, world, view, x + 0.82f, y + 0.82f, 1f, 0f, CROSSING)
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
    // Snow sheds over their track like engine houses, and snow fences, a run of slats parallel to the track nearby.
    for (tile in moving.now.sheds) {
        val x = tile % world.width
        val y = tile / world.width
        val along = if (painter.viewEdge(if (world.track[tile].toInt() == Track.X) Track.XP else Track.YP) <= Track.XP) 0 else 1
        val base = minOf(minOf(world.heightAt(x, y), world.heightAt(x + 1, y)), minOf(world.heightAt(x + 1, y + 1), world.heightAt(x, y + 1)))
        tileItem(items, view, x, y, base.toFloat(), DEPOT_DEPTH, Atlas.SHED[along])
    }
    if (view.halfWidth >= 8f) for (tile in moving.now.fences) {
        val x = tile % world.width
        val y = tile / world.width
        val alongX = fenceAlongX(world, x, y)
        val (ax, ay, bx, by) = if (alongX) listOf(x + 0.1f, y + 0.5f, x + 0.9f, y + 0.5f) else listOf(x + 0.5f, y + 0.1f, x + 0.5f, y + 0.9f)
        val h = groundHeight(world, x + 0.5f, y + 0.5f)
        fun sx(px: Float, py: Float) = view.screenX(view.toViewX(px, py), view.toViewY(px, py), size.width)
        fun sy(px: Float, py: Float, ph: Float) = view.screenY(view.toViewX(px, py), view.toViewY(px, py), ph, size.height)
        items += floatArrayOf(view.toViewX(x + 0.5f, y + 0.5f) + view.toViewY(x + 0.5f, y + 0.5f), sx(ax, ay), sy(ax, ay, h), 0f, FENCE.toFloat(), sx(bx, by), sy(bx, by, h), view.step)
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
        items += floatArrayOf(vx + vy + DEPOT_DEPTH, sx, sy, Atlas.DEPOT_OVER[ChunkBaker.depotEra(Calendar.year(world.startYear, world.day)) * 4 + painter.viewEdge(d.edge)].toFloat(), -1f)
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
        // Up in the air, an aircraft's drawings with no shadow are 8 on, one for each heading; its shadow goes on the ground.
        val slope = v.slope(i)
        if (v.flying(i)) {
            add(items, world, view, x, y, (8 + heading).toFloat(), FLYING_DEPTH, v.sprite(i), v.deck(i))
            add(items, world, view, x + SUN_X * v.deck(i), y + SUN_Y * v.deck(i), (8 + heading).toFloat(), -0.4f, v.sprite(i), shadow = true)
            continue
        }
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
        add(items, world, view, x, y, cell.toFloat(), reach, v.sprite(i), deck, wreck = v.wrecked(i))
    }
    // Vehicles' drawings at the levels either side unpacked ahead, so they're there when the view zooms.
    for (i in 0 until v.count) {
        val model = v.sprite(i)
        if (level > 0) sheets.vehicle(model, level - 1)
        if (level + 1 < Renderer.LEVELS.size) sheets.vehicle(model, level + 1)
    }
    val lit = if (view.halfWidth >= LIT_FROM) litPieces(world, view, moving, items) else emptyList()
    items.sortBy { it[0] }
    val cover = Cover(world, view, level, sheets.get(level) ?: return)
    // Reserved track on the ground goes under everything else, cut away behind buildings; edges first so the lines join.
    if (lit.isNotEmpty()) {
        val pad = view.halfWidth
        // Pieces with the same buildings in front of them are cut together; each is cut only by its own.
        val groups = HashMap<Long, Pair<Path, ArrayList<FloatArray>>>()
        val bare = ArrayList<FloatArray>()
        val set = LongArray(1)
        for (l in lit) {
            set[0] = 0
            val h = with(cover) { covering(l[0], l[1], Rect(minOf(l[2], l[4]) - pad, minOf(l[3], l[5]) - pad, maxOf(l[2], l[4]) + pad, maxOf(l[3], l[5]) + pad), set) }
            if (h == null) bare += l else groups.getOrPut(set[0]) { h to ArrayList() }.second += l
        }
        for (edge in booleanArrayOf(true, false)) {
            for (l in bare) drawLit(l[2], l[3], l[4], l[5], view.halfWidth, edge, StrokeCap.Round)
            for ((path, pieces) in groups.values) clipPath(path, ClipOp.Difference) {
                for (l in pieces) drawLit(l[2], l[3], l[4], l[5], view.halfWidth, edge, StrokeCap.Round)
            }
        }
    }
    with(painter) {
        for (it in items) {
            val model = it[4].toInt()
            if (model == WIRE) {
                drawWire(it, view.halfWidth)
            } else if (model == LIT) {
                drawLit(it[1], it[2], it[5], it[6], view.halfWidth, true, StrokeCap.Butt)
                drawLit(it[1], it[2], it[5], it[6], view.halfWidth, false, StrokeCap.Butt)
            } else if (model == FENCE) {
                drawFence(it)
            } else if (model == FIRE) {
                drawFire(it[3].toInt(), it[1], it[2], view.halfWidth)
            } else if (model == BOARD) {
                drawBoard(it[3].toInt(), it[1], it[2], view.halfWidth)
            } else if (model == MARK || model == WAYPOINT) {
                drawMark(model, it[3].toInt(), it[1], it[2], view.halfWidth)
            } else if (model == BUOY) {
                drawBuoy(it[1], it[2], view.halfWidth)
            } else if (model == CROSSING) {
                drawCrossingLights(it[1], it[2], view.halfWidth)
            } else if (model < 0) {
                spriteAt(it[3].toInt(), it[1], it[2], scale)
            } else {
                // Its own sheet at this level, unpacked the first time it's on screen; it shows a frame or two later.
                val sheet = sheets.vehicle(model, level) ?: continue
                val rects = sheets.vehicleRects(model) ?: continue
                // Behind a building in front of it, cut away where the building stands.
                val hidden = if (it[6] >= 0f) with(cover) { covering(it[6], it[7], Rect(it[1] - view.halfWidth * 1.5f, it[2] - view.halfWidth * 2f, it[1] + view.halfWidth * 1.5f, it[2] + view.halfWidth)) } else null
                if (hidden != null) clipPath(hidden, ClipOp.Difference) { spriteAt(sheet, rects, it[3].toInt(), it[1], it[2], scale, shadow = it[5] == 1f, wreck = it[5] == 2f) }
                else spriteAt(sheet, rects, it[3].toInt(), it[1], it[2], scale, shadow = it[5] == 1f, wreck = it[5] == 2f)
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
private const val FENCE = -7
private const val BUOY = -8
private const val LIT = -9
private const val CROSSING = -10
/** Aircraft in the air go over everything; their shadows fall a little off from under them, by height, as the sun stands. */
private const val FLYING_DEPTH = 1000f
private const val SUN_X = 0.06f
private const val SUN_Y = 0.1f

/** Reserved track is drawn from this zoom in. */
private const val LIT_FROM = 8f

/** The reserved pieces as world numbers, nine to a piece: tile x and y, the two ends' x, y and height, and 1 on a bridge. */
private class LitCache(val world: World, val reserved: Int, val touches: Int, val geometry: FloatArray)

private var litCache: LitCache? = null

/** The geometry of the pieces in [keys], worked out again only when they or the map change. */
private fun litGeometry(world: World, keys: IntArray): FloatArray {
    val reserved = keys.contentHashCode()
    val touches = world.touches
    litCache?.let { if (it.world === world && it.reserved == reserved && it.touches == touches) return it.geometry }
    val out = FloatArray(keys.size * 9)
    var n = 0
    for (k in keys) {
        val tile = keyTile(k)
        val x = tile % world.width
        val y = tile / world.width
        // On a bridge, at its deck's height; the sim leaves out those in tunnels.
        val deck = if (keyLevel(k) == 1) (world.deck[tile]?.let { world.spans.getOrNull(it) }?.takeIf { !it.tunnel } ?: continue) else null
        val e = Track.edges(keyPiece(k))
        val a = e and 3
        val b = e shr 2
        val pieces = world.track[tile].toInt()
        out[n] = x.toFloat()
        out[n + 1] = y.toFloat()
        out[n + 2] = x + Track.edgeX4(a) / 4f
        out[n + 3] = y + Track.edgeY4(a) / 4f
        out[n + 4] = (deck?.height ?: world.edgeHeight(x, y, pieces, a)) + RAIL_TOP
        out[n + 5] = x + Track.edgeX4(b) / 4f
        out[n + 6] = y + Track.edgeY4(b) / 4f
        out[n + 7] = (deck?.height ?: world.edgeHeight(x, y, pieces, b)) + RAIL_TOP
        out[n + 8] = if (deck != null) 1f else 0f
        n += 9
    }
    return out.copyOf(n).also { litCache = LitCache(world, reserved, touches, it) }
}

/**
 * The reserved pieces on screen, each from one end to the other along its middle at the height of the rails. Those on
 * the ground come back as view x, view y and the ends on screen; those on a bridge go into [items], just over its deck.
 */
private fun DrawScope.litPieces(world: World, view: View, moving: Moving, items: ArrayList<FloatArray>): List<FloatArray> {
    val keys = moving.now.reserved
    if (keys.isEmpty()) return emptyList()
    val g = litGeometry(world, keys)
    val out = ArrayList<FloatArray>()
    val margin = view.halfWidth * 2
    val w = size.width
    val h = size.height
    var n = 0
    while (n < g.size) {
        val ax = g[n + 2]
        val ay = g[n + 3]
        val bx = g[n + 5]
        val by = g[n + 6]
        val x1 = view.screenX(view.toViewX(ax, ay), view.toViewY(ax, ay), w)
        val y1 = view.screenY(view.toViewX(ax, ay), view.toViewY(ax, ay), g[n + 4], h)
        val x2 = view.screenX(view.toViewX(bx, by), view.toViewY(bx, by), w)
        val y2 = view.screenY(view.toViewX(bx, by), view.toViewY(bx, by), g[n + 7], h)
        if (maxOf(x1, x2) >= -margin && minOf(x1, x2) <= w + margin && maxOf(y1, y2) >= -margin && minOf(y1, y2) <= h + margin) {
            val vx = view.toViewX(g[n] + 0.5f, g[n + 1] + 0.5f)
            val vy = view.toViewY(g[n] + 0.5f, g[n + 1] + 0.5f)
            if (g[n + 8] > 0f) items += floatArrayOf(vx + vy + DECK_DEPTH + 0.05f, x1, y1, 0f, LIT.toFloat(), x2, y2)
            else out += floatArrayOf(vx, vy, x1, y1, x2, y2)
        }
        n += 9
    }
    return out
}

/** A lit line along a reserved piece from ([x1], [y1]) to ([x2], [y2]) on screen: its dark [edge], or the light over it. */
private fun DrawScope.drawLit(x1: Float, y1: Float, x2: Float, y2: Float, hw: Float, edge: Boolean, cap: StrokeCap) {
    val w = maxOf(1.2f, hw * 0.06f)
    if (edge) drawLine(LIT_EDGE, Offset(x1, y1), Offset(x2, y2), w * 2.2f, cap)
    else drawLine(LIT_LIGHT, Offset(x1, y1), Offset(x2, y2), w, cap)
}

/** Reserved track's light, warm white to read on grass and in the dark, and its edge, to read on snow. */
private val LIT_LIGHT = Color(0xfffff4c0)
private val LIT_EDGE = Color(0xa0302010)
/** How far above the bed the rails' tops are, in steps. */
private const val RAIL_TOP = 0.15f

/** A buoy: a red can on the water, with a white band. */
private fun DrawScope.drawBuoy(x: Float, y: Float, hw: Float) {
    val u = hw / 16f
    drawRect(Color(0xffc8302a), Offset(x - 1.6f * u, y - 5f * u), Size(3.2f * u, 5f * u))
    drawRect(Color(0xfff4f4f0), Offset(x - 1.6f * u, y - 3.4f * u), Size(3.2f * u, 1f * u))
    drawRect(Color(0xff2a2a2a), Offset(x - 1.6f * u, y - 5f * u), Size(3.2f * u, 5f * u), style = Stroke(0.4f * u))
}

/** A level crossing's post with two red lamps flashing in turn, about once a second. */
private fun DrawScope.drawCrossingLights(x: Float, y: Float, hw: Float) {
    val u = hw / 32f
    val left = (flicker.elapsedNow().inWholeMilliseconds / 500) % 2 == 0L
    drawLine(Color(0xff3a3a3a), Offset(x, y), Offset(x, y - 12f * u), 1.2f * u)
    drawLine(Color(0xff2a2a2a), Offset(x - 3.5f * u, y - 12f * u), Offset(x + 3.5f * u, y - 12f * u), 1.6f * u)
    for (k in 0 until 2) {
        val on = (k == 0) == left
        val c = Offset(x + (if (k == 0) -3f else 3f) * u, y - 12f * u)
        if (on) drawCircle(Color(0x60ff3020), 3.2f * u, c)
        drawCircle(if (on) Color(0xffff3a28) else Color(0xff5a1810), 1.5f * u, c)
    }
}

/** Whether a snow fence on ([x], [y]) runs along x: the way the nearest track does, or across the wind if there's none near. */
private fun fenceAlongX(world: World, x: Int, y: Int): Boolean {
    for (r in 1..4) for (dy in -r..r) for (dx in -r..r) {
        if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r || !world.inside(x + dx, y + dy)) continue
        val t = world.track[world.index(x + dx, y + dy)].toInt()
        if (t == Track.X) return true
        if (t == Track.Y) return false
    }
    return false
}

/** A fence item: two rails between its ends, slats along them, a step high. */
private fun DrawScope.drawFence(it: FloatArray) {
    val step = it[7]
    val wood = Color(0xff6a5038)
    val n = 9
    for (k in 0..n) {
        val t = k / n.toFloat()
        val x = it[1] + (it[5] - it[1]) * t
        val y = it[2] + (it[6] - it[2]) * t
        drawLine(wood, Offset(x, y), Offset(x, y - step * 1.1f), step / 6f)
    }
    for (f in floatArrayOf(0.35f, 0.85f)) drawLine(Color(0xff4a3828), Offset(it[1], it[2] - step * f), Offset(it[5], it[6] - step * f), step / 10f)
}

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
/** How high the contact wire hangs over the rails, in steps: on top of a raised pantograph (2.26-2.27). */
private const val WIRE_HEIGHT = 2.28f

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

private fun DrawScope.add(items: ArrayList<FloatArray>, world: World, view: View, x: Float, y: Float, cell: Float, reach: Float = 0f, model: Int = -1, deck: Float = -1f, shadow: Boolean = false, wreck: Boolean = false) {
    val vx = view.toViewX(x, y)
    val vy = view.toViewY(x, y)
    val sx = view.screenX(vx, vy, size.width)
    val sy = view.screenY(vx, vy, if (deck >= 0f) deck else groundHeight(world, x, y), size.height)
    val margin = view.halfWidth * 2
    if (sx < -margin || sx > size.width + margin || sy < -margin || sy > size.height + margin * 2) return
    // Where it stands in the view, for cutting away what's behind a building; up off the ground, nothing's in front.
    val low = !shadow && deck < 0f
    items += floatArrayOf(vx + vy + reach, sx, sy, cell, model.toFloat(), if (shadow) 1f else if (wreck) 2f else 0f, if (low) vx else -1f, if (low) vy else -1f)
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

/** The car key of the vehicle under screen point ([px], [py]): a train's car (its id times 256 and up), or a road vehicle, ship or aircraft (below zero, as [com.rm.interchange.sim.VehicleView.key] makes them); [NO_VEHICLE] for none. */
fun vehicleAt(world: World, view: View, moving: Moving?, px: Float, py: Float, w: Float, h: Float): Int {
    val v = moving?.now?.vehicles ?: return NO_VEHICLE
    var best = NO_VEHICLE
    var bestD = view.halfWidth * view.halfWidth * 0.5f
    for (i in 0 until v.count) {
        val vx = view.toViewX(v.x(i), v.y(i))
        val vy = view.toViewY(v.x(i), v.y(i))
        val sx = view.screenX(vx, vy, w)
        // Aimed at the car's middle, a little above the ground.
        val sy = view.screenY(vx, vy, (if (v.deck(i) >= 0f) v.deck(i) else groundHeight(world, v.x(i), v.y(i))) + 1f, h)
        val d = (sx - px) * (sx - px) + (sy - py) * (sy - py)
        if (d < bestD) {
            bestD = d
            best = v.key(i)
        }
    }
    return best
}

const val NO_VEHICLE = Int.MIN_VALUE
