package com.rm.interchange.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.rm.interchange.map.LandPainter
import com.rm.interchange.map.Pick
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.World
import com.rm.interchange.sim.roadCost
import com.rm.interchange.sim.roadEdges
import kotlin.math.abs

/** The road a drag from [start] to [end] lays: straight along whichever way it went further. */
fun roadLine(company: Int, start: Pick, end: Pick, surface: Int, remove: Boolean, tram: Boolean = false): Command.Road {
    val alongX = abs(end.tileX - start.tileX) >= abs(end.tileY - start.tileY)
    return if (alongX) Command.Road(company, start.tileX, start.tileY, end.tileX, start.tileY, surface, remove, tram)
    else Command.Road(company, start.tileX, start.tileY, start.tileX, end.tileY, surface, remove, tram)
}

/** The tiles [plan] covers, lit if any of it can be built and red if not. */
fun DrawScope.drawRoadPlan(world: World, painter: LandPainter, plan: Command.Road) {
    val ok = world.roadCost(world.companies[plan.company], plan) >= 0
    val dx = (plan.ex - plan.sx).coerceIn(-1, 1)
    val dy = (plan.ey - plan.sy).coerceIn(-1, 1)
    val n = maxOf(abs(plan.ex - plan.sx), abs(plan.ey - plan.sy))
    val piece = if (dy == 0) Track.X else Track.Y
    for (k in 0..n) {
        val x = plan.sx + dx * k
        val y = plan.sy + dy * k
        if (!world.inside(x, y)) continue
        with(painter) { track(x, y, piece, if (ok) ROAD_OK else ROAD_BAD, if (ok) Color(0x66FFFFFF) else ROAD_BAD) }
    }
}

/** The garage a tap at [at] builds: its door onto a road next door, else towards the side nearest the finger. */
fun garageAt(world: World, company: Int, at: Pick): Command.Garage {
    for (edge in intArrayOf(Track.XM, Track.XP, Track.YM, Track.YP)) {
        val nx = at.tileX + Track.dx(edge)
        val ny = at.tileY + Track.dy(edge)
        if (world.inside(nx, ny) && world.roadEdges(world.index(nx, ny)) != 0) return Command.Garage(company, at.tileX, at.tileY, edge)
    }
    val u = at.fx - 0.5f
    val v = at.fy - 0.5f
    val edge = if (abs(u) > abs(v)) (if (u < 0) Track.XM else Track.XP) else (if (v < 0) Track.YM else Track.YP)
    return Command.Garage(company, at.tileX, at.tileY, edge)
}

private val ROAD_OK = Color(0x99F0B429)
private val ROAD_BAD = Color(0x99E05A4E)

/** The tiles a canal [plan] covers, lit on flat land it can be dug on and red elsewhere. */
fun DrawScope.drawCanalPlan(world: World, painter: LandPainter, plan: Command.Canal) {
    val dx = (plan.ex - plan.sx).coerceIn(-1, 1)
    val dy = (plan.ey - plan.sy).coerceIn(-1, 1)
    val n = maxOf(abs(plan.ex - plan.sx), abs(plan.ey - plan.sy))
    val piece = if (dy == 0) Track.X else Track.Y
    for (k in 0..n) {
        val x = plan.sx + dx * k
        val y = plan.sy + dy * k
        if (!world.inside(x, y)) continue
        val ok = if (plan.remove) world.canals.contains(world.index(x, y)) else world.isFlat(x, y) && !world.isWater(x, y)
        with(painter) { track(x, y, piece, if (ok) CANAL_OK else ROAD_BAD, if (ok) Color(0x66FFFFFF) else ROAD_BAD) }
    }
}

private val CANAL_OK = Color(0x993A7AB0)
