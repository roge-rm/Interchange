package com.rm.interchange.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.rm.interchange.map.LandPainter
import com.rm.interchange.map.Pick
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.World
import com.rm.interchange.sim.planRail
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * The line of track a drag from [start] to [end] lays. Mostly along a row or a
 * column it's straight, otherwise a diagonal, on whichever of the two diagonal
 * lines through the first tile the finger started nearer. A tap lays the piece
 * nearest the finger.
 */
fun trackLine(company: Int, start: Pick, end: Pick, remove: Boolean): Command.Rail {
    val sx = start.tileX
    val sy = start.tileY
    val dx = end.tileX - sx
    val dy = end.tileY - sy
    val u = start.fx - 0.5f
    val v = start.fy - 0.5f
    if (dx == 0 && dy == 0) {
        val piece = when {
            // Out in a corner: the diagonal across it.
            abs(u) + abs(v) > 0.5f -> if (v < 0) (if (u < 0) Track.N else Track.E) else (if (u < 0) Track.W else Track.S)
            abs(v) < abs(u) -> Track.X
            else -> Track.Y
        }
        return Command.Rail(company, sx, sy, sx, sy, piece, remove)
    }
    if (abs(dy) * 3 <= abs(dx)) return Command.Rail(company, sx, sy, sx + dx, sy, Track.X, remove)
    if (abs(dx) * 3 <= abs(dy)) return Command.Rail(company, sx, sy, sx, sy + dy, Track.Y, remove)
    val m = max(abs(dx), abs(dy))
    val ex = sx + m * dx.sign
    val ey = sy + m * dy.sign
    // Two diagonals run through the tile each way; take the one nearer where the finger went down.
    val first = if (dx.sign == dy.sign) (if (u - v > 0) Track.E else Track.W) else (if (u + v < 0) Track.N else Track.S)
    return Command.Rail(company, sx, sy, ex, ey, first, remove)
}

/** The line [rail] would lay, drawn over the map: pale where it'll go, red where it can't. */
fun DrawScope.drawTrackPlan(world: World, painter: LandPainter, rail: Command.Rail) {
    val line = Track.line(world, rail.sx, rail.sy, rail.ex, rail.ey, rail.first) ?: return
    val ok = world.planRail(rail) ?: return
    for ((k, entry) in line.withIndex()) {
        val i = entry / 64
        with(painter) {
            track(i % world.width, i / world.width, entry % 64, if (ok[k]) PLAN_OK else PLAN_BAD, if (ok[k]) PLAN_RAIL else PLAN_BAD)
        }
    }
}

private val PLAN_OK = Color(0x99F0B429)
private val PLAN_RAIL = Color(0xCCFFFFFF)
private val PLAN_BAD = Color(0x99E05A4E)
