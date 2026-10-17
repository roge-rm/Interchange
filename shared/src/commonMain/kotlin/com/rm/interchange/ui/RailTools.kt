package com.rm.interchange.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.rm.interchange.map.LandPainter
import com.rm.interchange.map.Pick
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.pieceKey
import com.rm.interchange.sim.CATCHMENT
import com.rm.interchange.sim.World
import com.rm.interchange.sim.bridgeCost
import kotlin.math.abs

/**
 * The signal a tap at [at] puts down: on the piece of the tile nearest the
 * finger, facing trains that come in at its end nearer the finger. Null if
 * there's no track, or with [signalled], none with a signal.
 */
fun signalAt(world: World, company: Int, at: Pick, signalled: Boolean = false): Command.Signal? {
    val pieces = if (world.inside(at.tileX, at.tileY)) world.trackAt(at.tileX, at.tileY) else 0
    if (pieces == 0) return null
    var best = 0
    var bestD = Float.MAX_VALUE
    for (p in Track.PIECES) {
        if (pieces and p == 0) continue
        if (signalled && world.signals[pieceKey(world.index(at.tileX, at.tileY), p)] == null) continue
        val d = distanceToPiece(p, at.fx, at.fy)
        if (d < bestD) { bestD = d; best = p }
    }
    if (best == 0) return null
    val e = Track.edges(best)
    val a = e and 3
    val b = e shr 2
    val da = sq(Track.edgeX4(a) / 4f - at.fx) + sq(Track.edgeY4(a) / 4f - at.fy)
    val db = sq(Track.edgeX4(b) / 4f - at.fx) + sq(Track.edgeY4(b) / 4f - at.fy)
    return Command.Signal(company, at.tileX, at.tileY, best, if (da <= db) a else b)
}

private fun sq(v: Float) = v * v

private fun distanceToPiece(piece: Int, x: Float, y: Float): Float {
    val e = Track.edges(piece)
    val ax = Track.edgeX4(e and 3) / 4f
    val ay = Track.edgeY4(e and 3) / 4f
    val bx = Track.edgeX4(e shr 2) / 4f
    val by = Track.edgeY4(e shr 2) / 4f
    val len = sq(bx - ax) + sq(by - ay)
    val t = (((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / len).coerceIn(0f, 1f)
    return sq(ax + (bx - ax) * t - x) + sq(ay + (by - ay) * t - y)
}

/** The depot a tap at [at] builds: its doors towards track next door that could run in, else the side nearest the finger. */
fun depotAt(world: World, company: Int, at: Pick): Command.Depot {
    val x = at.tileX
    val y = at.tileY
    for (edge in intArrayOf(Track.XM, Track.XP, Track.YM, Track.YP)) {
        val nx = x + Track.dx(edge)
        val ny = y + Track.dy(edge)
        if (!world.inside(nx, ny)) continue
        val back = Track.opposite(edge)
        if (Track.PIECES.any { world.trackAt(nx, ny) and it != 0 && Track.has(it, back) }) return Command.Depot(company, x, y, edge)
    }
    val u = at.fx - 0.5f
    val v = at.fy - 0.5f
    val edge = if (abs(u) > abs(v)) (if (u < 0) Track.XM else Track.XP) else (if (v < 0) Track.YM else Track.YP)
    return Command.Depot(company, x, y, edge)
}

/**
 * The platforms a drag from [start] to [end] lays: the rectangle between
 * them, running along its longer side, or along [alongX] when it's square.
 */
fun stationPlan(company: Int, start: Pick, end: Pick, alongX: Boolean): Command.Station {
    val dx = abs(end.tileX - start.tileX)
    val dy = abs(end.tileY - start.tileY)
    val axis = when {
        dx > dy -> Track.X
        dy > dx -> Track.Y
        alongX -> Track.X
        else -> Track.Y
    }
    return Command.Station(company, start.tileX, start.tileY, end.tileX, end.tileY, axis)
}

/**
 * The platforms [plan] would lay, shaded over the map, with their track, and
 * round them the tiles the station would gather passengers and mail from, its
 * buildings brighter.
 */
fun DrawScope.drawStationPlan(world: World, painter: LandPainter, plan: Command.Station) {
    val x0 = minOf(plan.x0, plan.x1)
    val x1 = maxOf(plan.x0, plan.x1)
    val y0 = minOf(plan.y0, plan.y1)
    val y1 = maxOf(plan.y0, plan.y1)
    for (y in y0 - CATCHMENT..y1 + CATCHMENT) for (x in x0 - CATCHMENT..x1 + CATCHMENT) {
        if (!world.inside(x, y) || (x in x0..x1 && y in y0..y1)) continue
        with(painter) { tile(x, y, if (world.house[world.index(x, y)].toInt() != 0) REACH_BUILT else REACH) }
    }
    for (y in minOf(plan.y0, plan.y1)..maxOf(plan.y0, plan.y1)) for (x in minOf(plan.x0, plan.x1)..maxOf(plan.x0, plan.x1)) {
        if (!world.inside(x, y)) continue
        val ok = !world.isWater(x, y)
        with(painter) { track(x, y, plan.axis, if (ok) PLAN_OK else PLAN_BAD, if (ok) Color(0xCCFFFFFF) else PLAN_BAD) }
    }
}

/** The bridge a drag from [start] to [end] builds: straight along whichever way it went further, of [style]. */
fun bridgePlan(company: Int, start: Pick, end: Pick, style: Int): Command.Bridge {
    val alongX = abs(end.tileX - start.tileX) >= abs(end.tileY - start.tileY)
    return if (alongX) Command.Bridge(company, start.tileX, start.tileY, end.tileX, start.tileY, style)
    else Command.Bridge(company, start.tileX, start.tileY, start.tileX, end.tileY, style)
}

/** The tiles [plan] would span, head to head, lit if it can be built and red if not. */
fun DrawScope.drawBridgePlan(world: World, painter: LandPainter, plan: Command.Bridge) {
    val ok = world.companies.getOrNull(plan.company)?.let { world.bridgeCost(it, plan) >= 0 } ?: false
    val dx = (plan.x1 - plan.x0).coerceIn(-1, 1)
    val dy = (plan.y1 - plan.y0).coerceIn(-1, 1)
    val n = maxOf(abs(plan.x1 - plan.x0), abs(plan.y1 - plan.y0))
    val axis = if (dx != 0) Track.X else Track.Y
    for (k in 0..n) {
        val x = plan.x0 + dx * k
        val y = plan.y0 + dy * k
        if (!world.inside(x, y)) continue
        with(painter) { track(x, y, axis, if (ok) PLAN_OK else PLAN_BAD, if (ok) Color(0xCCFFFFFF) else PLAN_BAD) }
    }
}

private val PLAN_OK = Color(0x99F0B429)
private val REACH = Color(0x2EF0B429)
private val REACH_BUILT = Color(0x70F0B429)
private val PLAN_BAD = Color(0x99E05A4E)
