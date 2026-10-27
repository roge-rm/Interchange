package com.rm.interchange.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.interchange.map.View
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.rm.interchange.map.LandPainter
import com.rm.interchange.map.Pick
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.World
import com.rm.interchange.sim.joinSides
import com.rm.interchange.sim.planJoins
import com.rm.interchange.sim.planRail
import com.rm.interchange.sim.planSignals
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
    val m = max(abs(dx), abs(dy))
    val (ex, ey) = when {
        abs(dy) * 3 <= abs(dx) -> sx + dx to sy
        abs(dx) * 3 <= abs(dy) -> sx to sy + dy
        else -> sx + m * dx.sign to sy + m * dy.sign
    }
    // Where the line meets other track it joins it, a curve at either end turning to the side of that end's tile the
    // finger is on; the line's end tile may not be the finger's, so it goes by the middle of that tile. A lifted
    // line takes the junction curves it leaves as stubs with it.
    val join = Command.Rail.JOIN or if (remove) 0 else
        joinSides(u, v, end.x - ex - 0.5f, end.y - ey - 0.5f, end.x - start.x, end.y - start.y)
    if (ey == sy) return Command.Rail(company, sx, sy, ex, sy, Track.X, remove, join)
    if (ex == sx) return Command.Rail(company, sx, sy, sx, ey, Track.Y, remove, join)
    // Two diagonals run through the tile each way; take the one nearer where the finger went down.
    val first = if (dx.sign == dy.sign) (if (u - v > 0) Track.E else Track.W) else (if (u + v < 0) Track.N else Track.S)
    return Command.Rail(company, sx, sy, ex, ey, first, remove, join)
}

/** The line [rail] would lay, drawn over the map: pale where it'll go, red where it can't. */
fun DrawScope.drawTrackPlan(world: World, painter: LandPainter, rail: Command.Rail) {
    val line = Track.line(world, rail.sx, rail.sy, rail.ex, rail.ey, rail.first) ?: return
    val ok = world.planRail(rail) ?: return
    val joins = world.planJoins(rail)
    for ((k, entry) in line.withIndex()) {
        val i = entry / 64
        // A straight a junction curve takes the place of isn't drawn.
        if (!rail.remove && joins.any { it.tile == i && it.to and (entry % 64) == 0 }) continue
        with(painter) {
            track(i % world.width, i / world.width, entry % 64, if (ok[k]) PLAN_OK else PLAN_BAD, if (ok[k]) PLAN_RAIL else PLAN_BAD)
        }
    }
    // Pieces a junction adds, or when lifting the stubs it takes up.
    for (j in joins) for (p in Track.PIECES) {
        if (if (rail.remove) j.from and p == 0 || j.to and p != 0 else j.to and p == 0 || j.from and p != 0) continue
        with(painter) { track(j.tile % world.width, j.tile / world.width, p, PLAN_OK, PLAN_RAIL) }
    }
}

/** The run of signals a drag from [start] to [end] places, along the line the track tool would lay. */
fun signalRun(company: Int, start: Pick, end: Pick, kind: Int, spacing: Int, remove: Boolean): Command.SignalRun =
    trackLine(company, start, end, false).let { Command.SignalRun(company, it.sx, it.sy, it.ex, it.ey, it.first, kind, spacing, remove) }

/** Where [run] would put signals, lit on the track; the rest of its line pale, or all of it red to remove. */
fun DrawScope.drawSignalPlan(world: World, painter: LandPainter, run: Command.SignalRun) {
    val line = Track.line(world, run.sx, run.sy, run.ex, run.ey, run.first) ?: return
    val spots = world.planSignals(run).map { world.index(it.x, it.y) }.toSet()
    for (entry in line) {
        val i = entry / 64
        val on = run.remove || i in spots
        with(painter) {
            track(i % world.width, i / world.width, entry % 64, if (run.remove) PLAN_BAD else if (on) PLAN_OK else PLAN_DIM, if (on) PLAN_RAIL else PLAN_DIM)
        }
    }
}

private val PLAN_DIM = Color(0x33F0B429)
private val PLAN_OK = Color(0x99F0B429)
private val PLAN_RAIL = Color(0xCCFFFFFF)
private val PLAN_BAD = Color(0x99E05A4E)

/** The pieces the track tool can lay one at a time, in the view's own terms, and their drawings. */
val VIEW_PIECES = intArrayOf(Track.X, Track.Y, Track.N, Track.E, Track.S, Track.W)
val PIECE_GLYPHS = listOf(Glyph.PieceX, Glyph.PieceY, Glyph.PieceN, Glyph.PieceE, Glyph.PieceS, Glyph.PieceW)

/** The piece in the world that shows on screen as [viewPiece], with the view turned as [painter] has it. */
fun worldPiece(painter: LandPainter, viewPiece: Int): Int {
    val e = Track.edges(viewPiece)
    val want = setOf(e and 3, e shr 2)
    return Track.PIECES.first { p -> Track.edges(p).let { setOf(painter.viewEdge(it and 3), painter.viewEdge(it shr 2)) } == want }
}

/**
 * Track of just [piece] from [start] towards [end]: a tap lays the one piece; a drag lays a straight piece along its
 * own row or column, or a diagonal piece and its partner in turn along their diagonal, as far as the finger went.
 */
fun pieceLine(world: World, company: Int, start: Pick, end: Pick, piece: Int, remove: Boolean): Command.Rail {
    val sx = start.tileX
    val sy = start.tileY
    val dx = end.tileX - sx
    val dy = end.tileY - sy
    if (dx == 0 && dy == 0) return Command.Rail(company, sx, sy, sx, sy, piece, remove)
    return when (piece) {
        Track.X -> Command.Rail(company, sx, sy, sx + dx, sy, piece, remove)
        Track.Y -> Command.Rail(company, sx, sy, sx, sy + dy, piece, remove)
        else -> {
            // N and S zigzag along x = -y, E and W along x = y.
            val across = piece == Track.N || piece == Track.S
            val k = if (across) (dx - dy) / 2 else (dx + dy) / 2
            val ex = sx + k
            val ey = if (across) sy - k else sy + k
            val partner = when (piece) { Track.N -> Track.S; Track.S -> Track.N; Track.E -> Track.W; else -> Track.E }
            val first = if (Track.line(world, sx, sy, ex, ey, piece) != null) piece else partner
            Command.Rail(company, sx, sy, ex, ey, first, remove)
        }
    }
}

/**
 * What a drag would take, [text], on a dark label just above and right of the tile under the finger ([at]), kept on
 * the screen; red when there isn't the money for it ([short]).
 */
fun DrawScope.drawDragLabel(world: World, view: View, measurer: TextMeasurer, text: String, short: Boolean, at: Pick) {
    val style = TextStyle(color = if (short) LABEL_SHORT else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val layout = measurer.measure(text, style)
    val x = at.tileX + 1f
    val y = at.tileY.toFloat()
    val vx = view.toViewX(x, y)
    val vy = view.toViewY(x, y)
    val cornerX = view.screenX(vx, vy, size.width)
    val cornerY = view.screenY(vx, vy, world.heightAt(at.tileX + 1, at.tileY).toFloat(), size.height)
    val pad = 6.dp.toPx()
    val box = Size(layout.size.width + 2 * pad, layout.size.height + pad)
    val topLeft = Offset((cornerX + pad).coerceIn(0f, size.width - box.width), (cornerY - box.height - pad).coerceIn(0f, size.height - box.height))
    drawRoundRect(LABEL, topLeft, box, CornerRadius(pad))
    drawText(layout, topLeft = Offset(topLeft.x + pad, topLeft.y + pad / 2))
}

private val LABEL = Color(0xD91C1F24)
private val LABEL_SHORT = Color(0xFFFF8A80)
