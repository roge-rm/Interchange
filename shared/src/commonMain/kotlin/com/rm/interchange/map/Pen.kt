package com.rm.interchange.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Where the shapes over the map go, in screen pixels: the Compose canvas ([CanvasPen]) or the GPU map's live list.
 * A shape is a convex outline of up to four corners, x and y one after another.
 */
internal interface Pen {
    fun line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, cap: StrokeCap = StrokeCap.Butt)
    fun rect(color: Color, x: Float, y: Float, w: Float, h: Float)
    /** A rectangle's edge, [width] wide, centred on its outline. */
    fun rectEdge(color: Color, x: Float, y: Float, w: Float, h: Float, width: Float)
    fun circle(color: Color, radius: Float, x: Float, y: Float)
    /** The first [n] corners of [xy] filled, or their outline [edge] wide when edge is above 0. */
    fun shape(color: Color, xy: FloatArray, n: Int, edge: Float = 0f)
}

/** Draws on a Compose canvas, a call each as the canvas has them. */
internal class CanvasPen(private val scope: DrawScope) : Pen {
    override fun line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, cap: StrokeCap) =
        scope.drawLine(color, Offset(x1, y1), Offset(x2, y2), width, cap)

    override fun rect(color: Color, x: Float, y: Float, w: Float, h: Float) = scope.drawRect(color, Offset(x, y), Size(w, h))

    override fun rectEdge(color: Color, x: Float, y: Float, w: Float, h: Float, width: Float) =
        scope.drawRect(color, Offset(x, y), Size(w, h), style = Stroke(width))

    override fun circle(color: Color, radius: Float, x: Float, y: Float) = scope.drawCircle(color, radius, Offset(x, y))

    override fun shape(color: Color, xy: FloatArray, n: Int, edge: Float) {
        val p = Path()
        p.moveTo(xy[0], xy[1])
        for (k in 1 until n) p.lineTo(xy[2 * k], xy[2 * k + 1])
        p.close()
        if (edge > 0f) scope.drawPath(p, color, style = Stroke(edge)) else scope.drawPath(p, color)
    }
}
