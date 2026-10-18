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
    Save, Folder, Plus, Minus, Gear, Home, Package, Layers, Grid, Heights, Renew, Back, Waypoint, Wires, Shed, Fence, OneWay, Dome,
    TrackAuto, PieceX, PieceY, PieceN, PieceE, PieceS, PieceW,
    Chat, People, Flag, Tick, Up, Down,
    Thermometer, Wind, Snow, Sun, Calendar, Alert,
    RoadDirt, RoadGravel, RoadHighway,
    AirStrip, Seaplane, Heliport, AirRegional, AirCity, AirInternational,
    BridgeTrestle, BridgeTruss, BridgeConcrete,
    SignalPath, SignalEntry, SignalExit, SignalCombo, Spacing,
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
            Glyph.Dome -> { arc(12f, 17f, 9f, 180f, 180f); line(3f, 17f, 3f, 20f); line(21f, 17f, 21f, 20f); line(2f, 20f, 22f, 20f); shape(false, 10f, 20f, 10f, 14f, 14f, 14f, 14f, 20f) }
            Glyph.OneWay -> { line(4f, 12f, 18f, 12f); open(13f, 6f, 19f, 12f, 13f, 18f) }
            Glyph.Shed -> { shape(false, 3f, 6f, 21f, 9f, 21f, 11f, 3f, 8f); line(4f, 8f, 4f, 21f); line(20f, 11f, 20f, 21f); line(7f, 18f, 17f, 18f, 1.5f); line(2f, 21f, 22f, 21f) }
            Glyph.Fence -> { line(3f, 10f, 21f, 10f, 1.5f); line(3f, 16f, 21f, 16f, 1.5f); for (x in intArrayOf(5, 9, 13, 17)) line(x.toFloat(), 6f, x.toFloat(), 20f) }
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
            Glyph.Minus -> line(5f, 12f, 19f, 12f, 2.5f)
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
            // A speech bubble with a tail at the bottom left, and two lines of words.
            Glyph.Chat -> { shape(false, 4f, 5f, 20f, 5f, 20f, 16f, 11f, 16f, 6f, 20f, 7f, 16f, 4f, 16f); line(8f, 9f, 16f, 9f, 1.5f); line(8f, 12.5f, 13f, 12.5f, 1.5f) }
            // Two people, one a little behind the other.
            Glyph.People -> { circle(9f, 8f, 3f); arc(9f, 20f, 6f, 180f, 180f); circle(16f, 7f, 2.5f); arc(16f, 18f, 5f, 200f, 120f) }
            // A flag on a pole, its free edge cut in.
            Glyph.Flag -> { line(6f, 3f, 6f, 21f); shape(false, 6f, 4f, 19f, 4f, 16f, 8.5f, 19f, 13f, 6f, 13f) }
            Glyph.Tick -> open(5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Glyph.Up -> { line(12f, 19f, 12f, 5f, 2.5f); open(6f, 11f, 12f, 5f, 18f, 11f) }
            Glyph.Down -> { line(12f, 5f, 12f, 19f, 2.5f); open(6f, 13f, 12f, 19f, 18f, 13f) }
            // A thermometer: a filled bulb and tube, with marks beside.
            Glyph.Thermometer -> { circle(11f, 17.5f, 3.5f, true); line(11f, 4.5f, 11f, 15f, 4f); line(15.5f, 6f, 18f, 6f, 1.5f); line(15.5f, 9.5f, 18f, 9.5f, 1.5f); line(15.5f, 13f, 18f, 13f, 1.5f) }
            // Three gusts, the top and bottom ones curling at their ends.
            Glyph.Wind -> { line(3f, 9f, 15f, 9f); arc(15f, 6.5f, 2.5f, 90f, -270f); line(3f, 13f, 19f, 13f); line(3f, 17f, 13f, 17f); arc(13f, 19.5f, 2.5f, -90f, 270f) }
            // A snowflake, three strokes through the middle.
            Glyph.Snow -> { line(12f, 3f, 12f, 21f); line(4.2f, 7.5f, 19.8f, 16.5f); line(4.2f, 16.5f, 19.8f, 7.5f) }
            Glyph.Sun -> {
                circle(12f, 12f, 4f)
                for (k in 0 until 8) {
                    val a = k * kotlin.math.PI.toFloat() / 4
                    line(12f + 7f * kotlin.math.cos(a), 12f + 7f * kotlin.math.sin(a), 12f + 9.5f * kotlin.math.cos(a), 12f + 9.5f * kotlin.math.sin(a))
                }
            }
            // A calendar page with its two rings.
            Glyph.Calendar -> { shape(false, 4f, 6f, 20f, 6f, 20f, 20f, 4f, 20f); line(4f, 10.5f, 20f, 10.5f, 1.5f); line(8f, 3.5f, 8f, 7.5f); line(16f, 3.5f, 16f, 7.5f) }
            // A warning triangle with a mark in it.
            Glyph.Alert -> { shape(false, 12f, 3.5f, 21f, 19.5f, 3f, 19.5f); line(12f, 9f, 12f, 13.5f); circle(12f, 16.5f, 1.1f, true) }
            // A tile seen from above as the map shows it, and the piece across it, between the middles of two edges.
            Glyph.PieceX -> { tile(); line(7.5f, 8f, 16.5f, 16f, 3f) }
            Glyph.PieceY -> { tile(); line(16.5f, 8f, 7.5f, 16f, 3f) }
            Glyph.PieceN -> { tile(); line(16.5f, 8f, 7.5f, 8f, 3f) }
            Glyph.PieceE -> { tile(); line(16.5f, 8f, 16.5f, 16f, 3f) }
            Glyph.PieceS -> { tile(); line(16.5f, 16f, 7.5f, 16f, 3f) }
            Glyph.PieceW -> { tile(); line(7.5f, 8f, 7.5f, 16f, 3f) }
            Glyph.TrackAuto -> { tile(); open(7.5f, 8f, 11f, 11f, 11f, 13f, 7.5f, 16f); line(12f, 12f, 16.5f, 16f, 3f) }
            // Road surfaces: dirt has two wheel ruts, gravel is speckled, a highway is wide with two lanes each way.
            Glyph.RoadDirt -> { line(8f, 3f, 4f, 21f); line(16f, 3f, 20f, 21f); line(10.5f, 3f, 9f, 21f, 1.2f); line(13.5f, 3f, 15f, 21f, 1.2f) }
            Glyph.RoadGravel -> {
                line(8f, 3f, 4f, 21f); line(16f, 3f, 20f, 21f)
                for ((x, y) in listOf(10f to 6f, 13.5f to 8f, 11f to 11f, 14.5f to 13f, 9.5f to 15.5f, 12.5f to 17f, 15.5f to 19f, 9f to 19.5f)) circle(x, y, 0.9f, true)
            }
            Glyph.RoadHighway -> {
                line(6f, 3f, 2f, 21f); line(18f, 3f, 22f, 21f); line(12f, 3f, 12f, 21f, 1.2f)
                for (y in listOf(4f, 10f, 16f)) { line(9f - (y - 3f) / 9f, y, 9f - y / 9f, y + 3f, 1.5f); line(15f + (y - 3f) / 9f, y, 15f + y / 9f, y + 3f, 1.5f) }
            }
            // Airports from above: a strip of gravel; a plane on the water; a pad with an H; then a runway with more
            // beside it as the airport gets bigger, a terminal, a tower, a second runway.
            Glyph.AirStrip -> { shape(false, 2f, 10f, 22f, 10f, 22f, 14f, 2f, 14f); for (x in listOf(7f, 12f, 17f)) circle(x, 12f, 0.9f, true) }
            Glyph.Seaplane -> { small(Glyph.Air, 4f, 0f, 0.66f); wave(20f) }
            Glyph.Heliport -> { circle(12f, 12f, 9f); line(8.5f, 7.5f, 8.5f, 16.5f); line(15.5f, 7.5f, 15.5f, 16.5f); line(8.5f, 12f, 15.5f, 12f) }
            Glyph.AirRegional -> { runway(16f); shape(false, 7f, 6f, 17f, 6f, 17f, 11f, 7f, 11f) }
            Glyph.AirCity -> { runway(16f); shape(false, 3f, 7f, 13f, 7f, 13f, 11f, 3f, 11f); line(18f, 11f, 18f, 5f); shape(false, 16f, 3f, 20f, 3f, 20f, 5f, 16f, 5f) }
            Glyph.AirInternational -> { runway(2f, 4f); runway(18f, 4f); shape(false, 4f, 10f, 13f, 10f, 13f, 14f, 4f, 14f); line(18f, 14f, 18f, 11f); shape(false, 16.5f, 9.5f, 19.5f, 9.5f, 19.5f, 11f, 16.5f, 11f) }
            // Bridges: a timber trestle of braced posts, a steel truss, a thick concrete deck on piers.
            Glyph.BridgeTrestle -> {
                line(2f, 8f, 22f, 8f)
                for (x in listOf(4f, 12f, 20f)) line(x, 8f, x, 21f)
                line(4f, 9f, 12f, 20f, 1.2f); line(12f, 9f, 4f, 20f, 1.2f); line(12f, 9f, 20f, 20f, 1.2f); line(20f, 9f, 12f, 20f, 1.2f)
            }
            Glyph.BridgeTruss -> {
                line(2f, 17f, 22f, 17f); line(6f, 7f, 18f, 7f); line(2f, 17f, 6f, 7f); line(22f, 17f, 18f, 7f)
                open(6f, 7f, 10f, 17f, 14f, 7f, 18f, 17f); line(10f, 17f, 10f, 7f, 1.2f); line(14f, 17f, 14f, 7f, 1.2f)
                line(2f, 17f, 2f, 21f); line(22f, 17f, 22f, 21f)
            }
            Glyph.BridgeConcrete -> {
                line(2f, 8f, 22f, 8f, 3.5f)
                shape(true, 5.5f, 9f, 8.5f, 9f, 9f, 21f, 5f, 21f); shape(true, 15.5f, 9f, 18.5f, 9f, 19f, 21f, 15f, 21f)
            }
            // Signal kinds: a path signal's head points up; entry, exit and combo signals have an arrow in, out or both.
            Glyph.SignalPath -> { line(12f, 13f, 12f, 22f); shape(false, 12f, 2f, 16f, 6f, 16f, 13f, 8f, 13f, 8f, 6f); circle(12f, 9f, 1.5f, true) }
            Glyph.SignalEntry -> { signalHead(); arrow(3f) }
            Glyph.SignalExit -> { signalHead(); arrow(15f) }
            Glyph.SignalCombo -> { signalHead(); arrow(3f); arrow(15f) }
            // Two signals with the gap between them marked.
            Glyph.Spacing -> {
                line(4f, 4f, 4f, 20f); shape(true, 2.5f, 4f, 5.5f, 4f, 5.5f, 9f, 2.5f, 9f)
                line(20f, 4f, 20f, 20f); shape(true, 18.5f, 4f, 21.5f, 4f, 21.5f, 9f, 18.5f, 9f)
                line(7.5f, 15f, 16.5f, 15f, 1.5f); open(10f, 12.5f, 7.5f, 15f, 10f, 17.5f); open(14f, 12.5f, 16.5f, 15f, 14f, 17.5f)
            }
        }
    }

    /** [g] drawn [s] times its size with its corner at ([x], [y]). */
    private fun small(g: Glyph, x: Float, y: Float, s: Float) = GlyphPen(scope, u * s, dx + x * u, dy + y * u, tint).draw(g)

    /** A runway across from [y], [h] deep, with marks down its middle. */
    private fun runway(y: Float, h: Float = 5f) {
        shape(false, 2f, y, 22f, y, 22f, y + h, 2f, y + h)
        if (h >= 5f) for (x in listOf(6f, 11f, 16f)) line(x, y + h / 2, x + 2f, y + h / 2, 1.2f)
    }

    /** A signal's head on its post, narrow enough for an arrow either side. */
    private fun signalHead() { line(12f, 12f, 12f, 22f); shape(false, 9.5f, 2f, 14.5f, 2f, 14.5f, 12f, 9.5f, 12f); circle(12f, 5f, 1.2f, true); circle(12f, 9f, 1.2f, true) }

    /** An arrow pointing right from [x], beside a signal's post. */
    private fun arrow(x: Float) { line(x, 17f, x + 6f, 17f, 1.5f); open(x + 4f, 15f, x + 6.5f, 17f, x + 4f, 19f) }

    private fun tile() = shape(false, 12f, 3.5f, 21f, 12f, 12f, 20.5f, 3f, 12f)

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
