package com.rm.interchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** The little drawings on buttons and tabs. */
enum class Glyph {
    Inspect, Rail, Road, Water, Air, Land,
    Signal, Station, Depot, Bridge, Tunnel,
    RoadStop, Canal, Dock, Lock, Airport,
    Raise, Lower, Level, Tree, Clear,
    Pause, Play, Speed, TurnLeft, TurnRight, Remove, Lift, Menu,
    Save, Folder, Plus, Gear, Home, Package, Layers, Grid, Heights, Renew, Back, Waypoint, Wires,
}

/** [glyph] drawn in [tint] on a 24 unit square scaled to fit. */
@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val dx = (size.width - 24 * u) / 2
        val dy = (size.height - 24 * u) / 2
        GlyphPen(this, u, dx, dy, tint).draw(glyph)
    }
}

private class GlyphPen(val scope: DrawScope, val u: Float, val dx: Float, val dy: Float, val tint: Color) {
    private val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun p(x: Float, y: Float) = Offset(dx + x * u, dy + y * u)

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, w: Float = 2f) =
        scope.drawLine(tint, p(x0, y0), p(x1, y1), strokeWidth = w * u, cap = StrokeCap.Round)

    fun shape(fill: Boolean, vararg pts: Float) {
        val path = Path()
        path.moveTo(dx + pts[0] * u, dy + pts[1] * u)
        var i = 2
        while (i < pts.size) {
            path.lineTo(dx + pts[i] * u, dy + pts[i + 1] * u)
            i += 2
        }
        path.close()
        if (fill) scope.drawPath(path, tint) else scope.drawPath(path, tint, style = stroke)
    }

    fun open(vararg pts: Float) {
        val path = Path()
        path.moveTo(dx + pts[0] * u, dy + pts[1] * u)
        var i = 2
        while (i < pts.size) {
            path.lineTo(dx + pts[i] * u, dy + pts[i + 1] * u)
            i += 2
        }
        scope.drawPath(path, tint, style = stroke)
    }

    fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) =
        if (fill) scope.drawCircle(tint, r * u, p(x, y)) else scope.drawCircle(tint, r * u, p(x, y), style = stroke)

    fun arc(x: Float, y: Float, r: Float, start: Float, sweep: Float) =
        scope.drawArc(tint, start, sweep, false, p(x - r, y - r), Size(2 * r * u, 2 * r * u), style = stroke)

    fun draw(g: Glyph) {
        when (g) {
            Glyph.Inspect -> { circle(10f, 10f, 6f); line(14.5f, 14.5f, 20f, 20f, 2.5f) }
            Glyph.Rail -> {
                line(8f, 3f, 5f, 21f); line(16f, 3f, 19f, 21f)
                for (y in listOf(6f, 11f, 16f)) line(6f, y, 18f, y, 1.5f)
            }
            Glyph.Road -> { line(8f, 3f, 4f, 21f); line(16f, 3f, 20f, 21f); line(12f, 4f, 12f, 8f); line(12f, 11f, 12f, 14f); line(12f, 17f, 12f, 21f) }
            Glyph.Water -> { wave(8f); wave(13f); wave(18f) }
            Glyph.Air -> shape(true, 12f, 2f, 13.5f, 9f, 21f, 13f, 21f, 15f, 13.5f, 13f, 13f, 19f, 16f, 21f, 16f, 22f, 12f, 21f, 8f, 22f, 8f, 21f, 11f, 19f, 10.5f, 13f, 3f, 15f, 3f, 13f, 10.5f, 9f)
            Glyph.Land -> { shape(true, 2f, 20f, 9f, 8f, 13f, 14f, 16f, 10f, 22f, 20f) }
            Glyph.Signal -> { line(12f, 12f, 12f, 22f); shape(false, 9f, 2f, 15f, 2f, 15f, 12f, 9f, 12f); circle(12f, 5f, 1.5f, true); circle(12f, 9f, 1.5f, true) }
            Glyph.Wires -> { line(5f, 22f, 5f, 3f); line(5f, 5f, 19f, 5f, 1.5f); line(2f, 9f, 22f, 9f, 1.5f); line(12f, 5f, 12f, 9f, 1.2f); line(19f, 5f, 19f, 9f, 1.2f); line(2f, 20f, 22f, 20f) }
            Glyph.Waypoint -> { line(12f, 11f, 12f, 22f); shape(false, 12f, 2f, 17f, 6.5f, 12f, 11f, 7f, 6.5f) }
            Glyph.Station -> { shape(false, 3f, 9f, 12f, 4f, 21f, 9f); line(5f, 9f, 5f, 18f); line(19f, 9f, 19f, 18f); line(2f, 18f, 22f, 18f); line(2f, 21f, 22f, 21f, 1.5f) }
            Glyph.Depot -> { shape(false, 3f, 10f, 12f, 4f, 21f, 10f, 21f, 21f, 3f, 21f); shape(false, 8f, 21f, 8f, 13f, 16f, 13f, 16f, 21f) }
            Glyph.Bridge -> { line(2f, 9f, 22f, 9f); arc(12f, 19f, 8f, 180f, 180f); line(4f, 9f, 4f, 21f); line(20f, 9f, 20f, 21f) }
            Glyph.Tunnel -> { shape(false, 3f, 21f, 3f, 11f, 6f, 6f, 12f, 4f, 18f, 6f, 21f, 11f, 21f, 21f); arc(12f, 15f, 4.5f, 180f, 180f); line(7.5f, 15f, 7.5f, 21f); line(16.5f, 15f, 16.5f, 21f) }
            Glyph.RoadStop -> { line(12f, 10f, 12f, 22f); shape(false, 6f, 2f, 18f, 2f, 18f, 10f, 6f, 10f) }
            Glyph.Canal -> { line(4f, 4f, 4f, 20f); line(20f, 4f, 20f, 20f); wave(9f, 6f, 18f); wave(14f, 6f, 18f) }
            Glyph.Dock -> { line(3f, 12f, 21f, 12f); line(6f, 12f, 6f, 20f); line(12f, 12f, 12f, 20f); line(18f, 12f, 18f, 20f); line(14f, 12f, 14f, 3f); line(14f, 3f, 20f, 6f) }
            Glyph.Lock -> { shape(false, 4f, 4f, 20f, 4f, 20f, 20f, 4f, 20f); line(4f, 12f, 20f, 12f); line(12f, 4f, 12f, 20f, 1.5f) }
            Glyph.Airport -> { draw(Glyph.Air) }
            Glyph.Raise -> { shape(true, 2f, 21f, 8f, 13f, 14f, 21f); line(17f, 20f, 17f, 5f); open(13f, 9f, 17f, 4f, 21f, 9f) }
            Glyph.Lower -> { shape(true, 2f, 21f, 8f, 13f, 14f, 21f); line(17f, 4f, 17f, 19f); open(13f, 15f, 17f, 20f, 21f, 15f) }
            Glyph.Level -> { line(3f, 16f, 21f, 16f, 2.5f); open(6f, 10f, 9f, 7f, 12f, 10f); open(15f, 7f, 18f, 10f) }
            Glyph.Tree -> { circle(12f, 9f, 6f, true); line(12f, 14f, 12f, 22f, 2.5f) }
            Glyph.Clear -> { line(6f, 6f, 18f, 18f, 2.5f); line(18f, 6f, 6f, 18f, 2.5f) }
            Glyph.Pause -> { line(9f, 6f, 9f, 18f, 3f); line(15f, 6f, 15f, 18f, 3f) }
            Glyph.Play -> shape(true, 8f, 5f, 19f, 12f, 8f, 19f)
            Glyph.Speed -> { shape(true, 4f, 6f, 11f, 12f, 4f, 18f); shape(true, 12f, 6f, 19f, 12f, 12f, 18f) }
            Glyph.TurnLeft -> { arc(12f, 13f, 7f, 200f, 250f); open(3f, 7f, 5f, 11f, 9f, 9f) }
            Glyph.TurnRight -> { arc(12f, 13f, 7f, 90f, 250f); open(21f, 7f, 19f, 11f, 15f, 9f) }
            Glyph.Remove -> { line(7f, 7f, 17f, 17f); line(17f, 7f, 7f, 17f) }
            Glyph.Lift -> { draw(Glyph.Rail); line(3f, 21f, 21f, 3f, 2.5f) }
            Glyph.Menu -> { line(5f, 7f, 19f, 7f, 2.5f); line(5f, 12f, 19f, 12f, 2.5f); line(5f, 17f, 19f, 17f, 2.5f) }
            Glyph.Save -> { shape(false, 4f, 4f, 17f, 4f, 20f, 7f, 20f, 20f, 4f, 20f); shape(false, 8f, 4f, 8f, 9f, 15f, 9f, 15f, 4f); shape(false, 7f, 13f, 17f, 13f, 17f, 20f, 7f, 20f) }
            Glyph.Folder -> { shape(false, 3f, 6f, 9f, 6f, 11f, 8f, 21f, 8f, 21f, 19f, 3f, 19f); line(3f, 11f, 21f, 11f, 1.5f) }
            Glyph.Plus -> { line(12f, 5f, 12f, 19f, 2.5f); line(5f, 12f, 19f, 12f, 2.5f) }
            Glyph.Gear -> {
                circle(12f, 12f, 3f)
                for (k in 0 until 8) {
                    val a = k * kotlin.math.PI.toFloat() / 4
                    line(12f + 6f * kotlin.math.cos(a), 12f + 6f * kotlin.math.sin(a), 12f + 9f * kotlin.math.cos(a), 12f + 9f * kotlin.math.sin(a), 2.5f)
                }
                circle(12f, 12f, 6f)
            }
            Glyph.Home -> { open(3f, 12f, 12f, 4f, 21f, 12f); open(6f, 10f, 6f, 20f, 18f, 20f, 18f, 10f); shape(false, 10f, 20f, 10f, 14f, 14f, 14f, 14f, 20f) }
            Glyph.Package -> { shape(false, 12f, 3f, 21f, 7.5f, 21f, 16.5f, 12f, 21f, 3f, 16.5f, 3f, 7.5f); open(3f, 7.5f, 12f, 12f, 21f, 7.5f); line(12f, 12f, 12f, 21f); line(7.5f, 5.25f, 16.5f, 9.75f, 1.5f) }
            Glyph.Layers -> { shape(false, 12f, 4f, 21f, 9f, 12f, 14f, 3f, 9f); open(3f, 13f, 12f, 18f, 21f, 13f); open(3f, 17f, 12f, 22f, 21f, 17f) }
            Glyph.Grid -> { shape(false, 12f, 3f, 21f, 12f, 12f, 21f, 3f, 12f); line(7.5f, 7.5f, 16.5f, 16.5f, 1.5f); line(16.5f, 7.5f, 7.5f, 16.5f, 1.5f) }
            Glyph.Heights -> { open(2f, 19f, 8f, 9f, 12f, 14f, 15f, 10f, 22f, 19f); line(4.5f, 15f, 19.5f, 15f, 1.5f); line(6.5f, 12f, 17.5f, 12f, 1.5f) }
            Glyph.Renew -> { arc(12f, 12f, 7f, 30f, 280f); open(17f, 3f, 19.5f, 8.5f, 14f, 9.5f) }
            Glyph.Back -> { line(5f, 12f, 19f, 12f, 2.5f); open(11f, 6f, 5f, 12f, 11f, 18f) }
        }
    }

    private fun wave(y: Float, x0: Float = 3f, x1: Float = 21f) {
        val path = Path()
        path.moveTo(dx + x0 * u, dy + y * u)
        var x = x0
        var up = true
        while (x < x1) {
            val nx = minOf(x + 4.5f, x1)
            path.quadraticTo(dx + (x + nx) / 2 * u, dy + (if (up) y - 2.5f else y + 2.5f) * u, dx + nx * u, dy + y * u)
            x = nx
            up = !up
        }
        scope.drawPath(path, tint, style = stroke)
    }
}
