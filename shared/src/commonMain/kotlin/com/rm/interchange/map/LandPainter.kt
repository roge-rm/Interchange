package com.rm.interchange.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.rm.interchange.sim.TileCurve
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.World

/**
 * Draws on the screen at any zoom: vehicles and signals standing where they
 * are, and plain track shapes for previews while dragging. The map itself is
 * baked by [ChunkBaker].
 *
 * [sheet] is one level's signals sheet, [halfWidth] that level's; [x] and [y]
 * place view points on the screen once [originX] and [originY] are set.
 */
class LandPainter(private val world: World, private val view: View, private val halfWidth: Float, private val sheet: ImageBitmap? = null) {
    private val halfHeight = halfWidth / 2
    private val step = halfWidth / 4
    private val path = Path()
    // How many times smaller than level 0 the sheet is.
    private val shrink = (Atlas.HALF_WIDTH / halfWidth).toInt().coerceAtLeast(1)

    /** Where view point 0, 0 at height 0 lands. */
    var originX = 0f
    var originY = 0f

    fun x(vx: Float, vy: Float) = originX + (vx - vy) * halfWidth
    fun y(vx: Float, vy: Float, h: Float) = originY + (vx + vy) * halfHeight - h * step

    /**
     * Draws a signal's [cell], standing at screen point ([px], [py]), as many times bigger than this level as
     * [scale]. Things are drawn as if on a tile whose middle is where they stand.
     */
    fun DrawScope.spriteAt(cell: Int, px: Float, py: Float, scale: Float) {
        spriteAt(sheet ?: return, Atlas.SIGNAL_RECTS, cell, px, py, scale)
    }

    /**
     * Draws sprite [cell] of [image], whose rects are [rects], standing at screen point ([px], [py]). The rects are
     * level 0's, divided by [shrink] for this level; pass 1 for rects already in this level's pixels.
     */
    fun DrawScope.spriteAt(image: ImageBitmap, rects: IntArray, cell: Int, px: Float, py: Float, scale: Float, shadow: Boolean = false, wreck: Boolean = false, shrink: Int = this@LandPainter.shrink) {
        if (cell < 0) return
        val r = cell * 6
        val f = shrink
        val sx = rects[r] / f
        val sy = rects[r + 1] / f
        val w = rects[r + 2] / f
        val h = rects[r + 3] / f
        if (w == 0 || h == 0) return
        val ax = px
        val ay = py - halfHeight * scale
        val left = ax + rects[r + 4] / f * scale
        val top = ay + rects[r + 5] / f * scale
        val l = left.toInt()
        val t = top.toInt()
        drawImage(
            image, IntOffset(sx, sy), IntSize(w, h), IntOffset(l, t),
            IntSize((left + w * scale).toInt() - l, (top + h * scale).toInt() - t),
            alpha = if (shadow) SHADOW_ALPHA else 1f,
            colorFilter = if (shadow) SHADOW_TINT else if (wreck) WRECK_TINT else null,
            filterQuality = if (scale == 1f) FilterQuality.None else FilterQuality.Low,
        )
    }

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

/** An aircraft's shadow on the ground: its own shape, darkened. */
private const val SHADOW_ALPHA = 0.3f
private val SHADOW_TINT = ColorFilter.tint(Color.Black, BlendMode.SrcIn)
/** A wreck: its own drawing, darkened and browned as if burnt. */
private val WRECK_TINT = ColorFilter.tint(Color(0xFF4A4038), BlendMode.Modulate)
