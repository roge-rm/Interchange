package com.rm.interchange.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/**
 * Where the shapes over the map go, in screen pixels: the GPU map's live list.
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
