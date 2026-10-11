package com.rm.interchange.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import com.rm.interchange.sim.World
import kotlin.math.floor

/**
 * A stand-in for the real renderer: each tile is a diamond raised to its
 * height, with the faces towards the camera showing the drop to the next tile.
 * The real one bakes the land into chunks and draws sprites over it.
 *
 * [frame] changes whenever the world does, so the canvas draws again.
 */
@Composable
fun MapView(world: World, view: View, frame: Long, onTap: (x: Int, y: Int) -> Unit, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .pointerInput(view) {
                detectTransformGestures { _, pan, zoom, _ ->
                    view.pan(pan.x, pan.y)
                    view.zoom(zoom)
                }
            }
            .pointerInput(view) {
                detectTapGestures { p -> pick(world, view, p, size.width / 2f, size.height / 2f)?.let { onTap(it.first, it.second) } }
            },
    ) {
        frame.hashCode()
        drawLand(world, view)
    }
}

private fun DrawScope.drawLand(world: World, view: View) {
    val hw = view.halfWidth
    val hh = view.halfHeight
    val ox = size.width / 2 - (view.focusViewX() - view.focusViewY()) * hw
    val oy = size.height / 2 - (view.focusViewX() + view.focusViewY()) * hh
    val top = Path()
    val face = Path()
    for (vy in 0 until view.viewHeight) for (vx in 0 until view.viewWidth) {
        val h = height(world, view, vx, vy)
        val cx = ox + (vx - vy) * hw
        val cy = oy + (vx + vy) * hh - h * view.step
        if (cx < -hw || cx > size.width + hw || cy < -hh * 2 || cy > size.height + hh * 2 + 16 * view.step) continue
        val water = h <= World.SEA
        // The faces down to the next tile on the left and on the right, where it's lower.
        val left = (h - height(world, view, vx, vy + 1)).coerceAtLeast(0) * view.step
        if (left > 0) {
            face.reset()
            face.moveTo(cx - hw, cy)
            face.lineTo(cx, cy + hh)
            face.lineTo(cx, cy + hh + left)
            face.lineTo(cx - hw, cy + left)
            face.close()
            drawPath(face, SIDE_LIGHT)
        }
        val right = (h - height(world, view, vx + 1, vy)).coerceAtLeast(0) * view.step
        if (right > 0) {
            face.reset()
            face.moveTo(cx, cy + hh)
            face.lineTo(cx + hw, cy)
            face.lineTo(cx + hw, cy + right)
            face.lineTo(cx, cy + hh + right)
            face.close()
            drawPath(face, SIDE_DARK)
        }
        top.reset()
        top.moveTo(cx, cy - hh)
        top.lineTo(cx + hw, cy)
        top.lineTo(cx, cy + hh)
        top.lineTo(cx - hw, cy)
        top.close()
        drawPath(top, if (water) WATER else GRASS[h.coerceIn(0, GRASS.size - 1)])
        if (hw >= 12) drawPath(top, OUTLINE, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
    }
}

/** The height of a view tile, with water flat at sea level and off the map counted as the bottom. */
private fun height(world: World, view: View, vx: Int, vy: Int): Int {
    if (vx !in 0 until view.viewWidth || vy !in 0 until view.viewHeight) return 0
    return world.heightAt(view.worldX(vx, vy), view.worldY(vx, vy)).coerceAtLeast(World.SEA)
}

/** The world tile under a point on screen, ignoring height for now. */
private fun pick(world: World, view: View, p: Offset, centreX: Float, centreY: Float): Pair<Int, Int>? {
    val a = (p.x - centreX) / view.halfWidth
    val b = (p.y - centreY) / view.halfHeight
    val vx = floor(view.focusViewX() + (a + b) / 2).toInt()
    val vy = floor(view.focusViewY() + (b - a) / 2).toInt()
    if (vx !in 0 until view.viewWidth || vy !in 0 until view.viewHeight) return null
    val x = view.worldX(vx, vy)
    val y = view.worldY(vx, vy)
    return if (world.inside(x, y)) x to y else null
}

private val WATER = Color(0xFF3E6E8E)
private val GRASS = Array(16) { h ->
    val t = h / 15f
    Color(0.36f + 0.22f * t, 0.52f + 0.08f * t, 0.27f + 0.06f * t)
}
private val SIDE_LIGHT = Color(0xFF7A6248)
private val SIDE_DARK = Color(0xFF5A4634)
private val OUTLINE = Color(0x22000000)
