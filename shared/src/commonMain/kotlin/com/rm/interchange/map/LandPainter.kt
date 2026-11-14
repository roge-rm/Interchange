package com.rm.interchange.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.rm.interchange.sim.TileCurve
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.World

/**
 * Places things on the screen at any zoom, for what moves and for plain track
 * shapes for previews while dragging. The map itself is recorded by
 * [TileRecorder] and drawn on the GPU.
 *
 * [halfWidth] is the zoom's; [x] and [y] place view points on the screen once
 * [originX] and [originY] are set.
 */
class LandPainter(private val world: World, private val view: View, private val halfWidth: Float) {
    private val halfHeight = halfWidth / 2
    private val step = halfWidth / 4
    private val path = Path()

    /** Where view point 0, 0 at height 0 lands. */
    var originX = 0f
    var originY = 0f

    fun x(vx: Float, vy: Float) = originX + (vx - vy) * halfWidth
    fun y(vx: Float, vy: Float, h: Float) = originY + (vx + vy) * halfHeight - h * step

    /** Where a world tile edge shows in this view. */
    fun viewEdge(edge: Int): Int = when (view.turn) {
        0 -> edge
        1 -> when (edge) { Track.XM -> Track.YM; Track.XP -> Track.YP; Track.YM -> Track.XP; else -> Track.XM }
        2 -> Track.opposite(edge)
        else -> when (edge) { Track.YM -> Track.XM; Track.YP -> Track.XP; Track.XM -> Track.YP; else -> Track.YM }
    }

    /** A world heading in eighths, as it shows in this view. */
    fun viewHeading(h: Int): Int = (h + 2 * view.turn) % 8

    /** Sixteenth-turn heading [h16] in the view, for sprites drawn with 16 headings. */
    fun viewHeading16(h16: Int): Int = (h16 + 4 * view.turn) % 16

    private val pose = DoubleArray(4)
    private var sides = FloatArray(0)

    /**
     * One track piece on world tile ([wx], [wy]), drawn as plain shapes: a bed and two rails. For previews. With a
     * [curve] it follows it, in short straight runs; without, it runs edge to edge.
     */
    fun DrawScope.track(wx: Int, wy: Int, piece: Int, bed: Color, rail: Color, curve: TileCurve? = null) {
        val e = Track.edges(piece)
        val n = if (curve == null) 1 else 1 + (curve.length * 8).toInt().coerceIn(2, 9)
        // Points along the line and the unit direction at each, as x, y, dx, dy.
        if (sides.size < (n + 1) * 4) sides = FloatArray((n + 1) * 4)
        for (k in 0..n) {
            if (curve == null) {
                val a = e and 3
                val b = e shr 2
                val ax = Track.edgeX4(a) / 4f
                val ay = Track.edgeY4(a) / 4f
                val bx = Track.edgeX4(b) / 4f
                val by = Track.edgeY4(b) / 4f
                val len = kotlin.math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
                sides[k * 4] = wx + if (k == 0) ax else bx
                sides[k * 4 + 1] = wy + if (k == 0) ay else by
                sides[k * 4 + 2] = (bx - ax) / len
                sides[k * 4 + 3] = (by - ay) / len
            } else {
                curve.poseInto(curve.from, curve.length * k / n, pose)
                sides[k * 4] = wx + pose[0].toFloat()
                sides[k * 4 + 1] = wy + pose[1].toFloat()
                sides[k * 4 + 2] = pose[2].toFloat()
                sides[k * 4 + 3] = pose[3].toFloat()
            }
        }
        // Across the track, a fifth of a tile each side for the bed.
        path.reset()
        for (k in 0..n) {
            val px = sides[k * 4] - sides[k * 4 + 3] * 0.2f
            val py = sides[k * 4 + 1] + sides[k * 4 + 2] * 0.2f
            if (k == 0) path.moveTo(screenX(px, py), screenY(px, py)) else path.lineTo(screenX(px, py), screenY(px, py))
        }
        for (k in n downTo 0) {
            val px = sides[k * 4] + sides[k * 4 + 3] * 0.2f
            val py = sides[k * 4 + 1] - sides[k * 4 + 2] * 0.2f
            path.lineTo(screenX(px, py), screenY(px, py))
        }
        path.close()
        drawPath(path, bed)
        for (side in floatArrayOf(-0.09f, 0.09f)) {
            for (k in 0 until n) {
                val ax = sides[k * 4] - sides[k * 4 + 3] * side
                val ay = sides[k * 4 + 1] + sides[k * 4 + 2] * side
                val bx = sides[k * 4 + 4] - sides[k * 4 + 7] * side
                val by = sides[k * 4 + 5] + sides[k * 4 + 6] * side
                drawLine(rail, Offset(screenX(ax, ay), screenY(ax, ay)), Offset(screenX(bx, by), screenY(bx, by)), strokeWidth = (halfWidth / 16f).coerceAtLeast(1f))
            }
        }
    }

    /** World tile ([wx], [wy]) filled with [fill], following the ground. For previews. */
    fun DrawScope.tile(wx: Int, wy: Int, fill: Color) {
        path.reset()
        val xs = floatArrayOf(0f, 1f, 1f, 0f)
        val ys = floatArrayOf(0f, 0f, 1f, 1f)
        for (i in 0 until 4) {
            val sx = screenX(wx + xs[i], wy + ys[i])
            val sy = screenY(wx + xs[i], wy + ys[i])
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        path.close()
        drawPath(path, fill)
    }

    // A world point, lifted to the ground's height there.
    private fun screenX(x: Float, y: Float) = x(view.toViewX(x, y), view.toViewY(x, y))
    private fun screenY(x: Float, y: Float) = y(view.toViewX(x, y), view.toViewY(x, y), groundHeight(world, x, y))

    companion object {
        /** Bits for tile ([wx], [wy]) and [salt] that are the same every time, for placing things on it. */
        fun dice(wx: Int, wy: Int, salt: Int): Int {
            var h = wx * 73856093 xor wy * 19349663 xor salt * 83492791
            h = (h xor (h ushr 13)) * 0x5bd1e995
            return h xor (h ushr 15)
        }
    }
}
