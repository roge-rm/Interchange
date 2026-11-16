package com.rm.interchange.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.rm.interchange.map.LandPainter
import com.rm.interchange.map.Pick
import com.rm.interchange.map.View
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.Warning
import com.rm.interchange.sim.World
import com.rm.interchange.sim.WorldView

/** The run of priority track or road a drag from [start] to [end] marks, or with [on] false clears. */
fun priorityRun(company: Int, start: Pick, end: Pick, road: Boolean, on: Boolean): Command.Priority =
    if (road) roadLine(company, start, end, 0, false).let { Command.Priority(company, it.sx, it.sy, it.ex, it.ey, 0, true, on) }
    else trackLine(company, start, end, false).let { Command.Priority(company, it.sx, it.sy, it.ex, it.ey, it.first, false, on) }

/** What [run] would mark, lit on the track as signals are, or red to clear it. */
fun DrawScope.drawPriorityPlan(world: World, painter: LandPainter, run: Command.Priority) {
    val fill = if (run.on) PRIORITY_NEW else PRIORITY_CLEAR
    if (run.road) {
        val dx = (run.ex - run.sx).coerceIn(-1, 1)
        val dy = (run.ey - run.sy).coerceIn(-1, 1)
        val n = maxOf(kotlin.math.abs(run.ex - run.sx), kotlin.math.abs(run.ey - run.sy))
        for (k in 0..n) {
            val x = run.sx + dx * k
            val y = run.sy + dy * k
            if (world.inside(x, y)) with(painter) { tile(x, y, fill) }
        }
        return
    }
    val line = Track.line(world, run.sx, run.sy, run.ex, run.ey, run.first) ?: return
    for (entry in line) {
        val i = entry / 64
        with(painter) { track(i % world.width, i / world.width, entry % 64, fill, PRIORITY_RAIL) }
    }
}

/**
 * [plan] with the priority tiles and the snow risk drawn under it, as asked: priority track and road in the
 * company's gold, the risk in amber where the next days' snow would slow, and red where it would stop.
 */
fun drawLit(
    world: World, view: View, wv: WorldView?, risk: WorldView?, showPriority: Boolean, showRisk: Boolean,
    plan: (DrawScope.(LandPainter) -> Unit)?,
): (DrawScope.(LandPainter) -> Unit)? {
    if (!(showPriority && wv != null) && !(showRisk && risk != null)) return plan
    return { painter ->
        if (showRisk && risk != null) for (k in risk.riskTiles.indices) {
            val i = risk.riskTiles[k]
            if (onScreen(world, view, i)) with(painter) { tile(i % world.width, i / world.width, if (risk.riskLevels[k].toInt() >= 2) RISK_STOPS else RISK_SLOWS) }
        }
        if (showPriority && wv != null) {
            for (i in wv.priorityRoad) if (onScreen(world, view, i)) with(painter) { tile(i % world.width, i / world.width, PRIORITY_ROAD) }
            for (i in wv.priorityTrack) if (onScreen(world, view, i)) with(painter) { tile(i % world.width, i / world.width, PRIORITY_TRACK) }
        }
        plan?.invoke(this, painter)
    }
}

/** Whether tile [i] is within the screen drawn on, with a tile's margin. */
private fun DrawScope.onScreen(world: World, view: View, i: Int): Boolean {
    val x = i % world.width + 0.5f
    val y = i / world.width + 0.5f
    val vx = view.toViewX(x, y)
    val vy = view.toViewY(x, y)
    val sx = view.screenX(vx, vy, size.width)
    val sy = view.screenY(vx, vy, world.heightAt(i % world.width, i / world.width).toFloat(), size.height)
    val margin = view.halfWidth * 2f
    return sx > -margin && sx < size.width + margin && sy > -margin && sy < size.height + margin
}

/** Where a weather warning's town is, or null if it names none. */
fun warnPlace(world: World, about: Int): Pair<Float, Float>? =
    world.towns.getOrNull(Warning.town(about))?.let { it.x + 0.5f to it.y + 0.5f }

private val PRIORITY_NEW = Color(0x99F0B429)
private val PRIORITY_CLEAR = Color(0x99E05A4E)
private val PRIORITY_RAIL = Color(0xCCFFFFFF)
private val PRIORITY_TRACK = Color(0x88F0B429)
private val PRIORITY_ROAD = Color(0x66F0B429)
private val RISK_SLOWS = Color(0x88E0A030)
private val RISK_STOPS = Color(0x99D8483C)
