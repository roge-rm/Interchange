package com.rm.interchange.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.World

/**
 * Draws tiles of land and track, in plain shapes until the generated art
 * comes. The light comes from the top left of the screen in every view.
 *
 * Positions are worked out from view coordinates by [x] and [y], set for
 * whatever is being drawn on: a chunk being baked, or the screen.
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

    private fun cornerHeight(vx: Int, vy: Int) = world.heightAt(view.cornerX(vx, vy), view.cornerY(vx, vy))

    /** Draws view tile ([vx], [vy]): the ground, track on it and, at the map's front edges, the earth under it. */
    fun DrawScope.tile(vx: Int, vy: Int) {
        val t = cornerHeight(vx, vy)
        val r = cornerHeight(vx + 1, vy)
        val b = cornerHeight(vx + 1, vy + 1)
        val l = cornerHeight(vx, vy + 1)
        val wx = view.worldX(vx, vy)
        val wy = view.worldY(vx, vy)
        val water = t == 0 && r == 0 && b == 0 && l == 0

        val fx = vx.toFloat()
        val fy = vy.toFloat()
        val txp = x(fx, fy); val typ = y(fx, fy, t.toFloat())
        val rxp = x(fx + 1, fy); val ryp = y(fx + 1, fy, r.toFloat())
        val bxp = x(fx + 1, fy + 1); val byp = y(fx + 1, fy + 1, b.toFloat())
        val lxp = x(fx, fy + 1); val lyp = y(fx, fy + 1, l.toFloat())

        // The earth under the front edges of the map, down past sea level.
        if (vx == view.viewWidth - 1) {
            quad(rxp, ryp, bxp, byp, bxp, y(fx + 1, fy + 1, -2f), rxp, y(fx + 1, fy, -2f))
            drawPath(path, EARTH_DARK)
        }
        if (vy == view.viewHeight - 1) {
            quad(lxp, lyp, bxp, byp, bxp, y(fx + 1, fy + 1, -2f), lxp, y(fx, fy + 1, -2f))
            drawPath(path, EARTH_LIGHT)
        }

        quad(txp, typ, rxp, ryp, bxp, byp, lxp, lyp)
        val colour = if (water) WATER else {
            // Brighter facing the top left of the screen, darker facing away.
            val alongX = (r + b - t - l) / 2f
            val alongY = (l + b - t - r) / 2f
            val shade = (1f - 0.16f * alongX - 0.06f * alongY).coerceIn(0.6f, 1.25f)
            val base = if (minOf(t, r, b, l) == 0) SAND else grass(maxOf(t, r, b, l))
            Color((base.red * shade).coerceAtMost(1f), (base.green * shade).coerceAtMost(1f), (base.blue * shade).coerceAtMost(1f))
        }
        drawPath(path, colour)
        if (halfWidth >= 12f) drawPath(path, GRID, style = Stroke(1f))

        val pieces = world.trackAt(wx, wy)
        if (world.hasFoundation(wx, wy)) {
            // Built up level with the highest corner: a flat top and the stone faces towards the camera.
            val top = maxOf(t, r, b, l).toFloat()
            val ty = y(fx, fy, top); val ry = y(fx + 1, fy, top); val by2 = y(fx + 1, fy + 1, top); val ly = y(fx, fy + 1, top)
            quad(rxp, ry, bxp, by2, bxp, byp, rxp, ryp)
            drawPath(path, STONE_DARK)
            quad(lxp, ly, bxp, by2, bxp, byp, lxp, lyp)
            drawPath(path, STONE_LIGHT)
            quad(txp, ty, rxp, ry, bxp, by2, lxp, ly)
            drawPath(path, grass(top.toInt()))
            if (halfWidth >= 12f) drawPath(path, GRID, style = Stroke(1f))
        }
        if (pieces != 0) for (p in Track.PIECES) if (pieces and p != 0) track(wx, wy, p, TRACK_BED, RAIL)
    }

    /** One track piece on world tile ([wx], [wy]), drawn as a bed with sleepers and two rails. */
    fun DrawScope.track(wx: Int, wy: Int, piece: Int, bed: Color, rail: Color) {
        val e = Track.edges(piece)
        val a = e and 3
        val b = e shr 2
        val ax = wx + Track.edgeX4(a) / 4f
        val ay = wy + Track.edgeY4(a) / 4f
        val bx = wx + Track.edgeX4(b) / 4f
        val by = wy + Track.edgeY4(b) / 4f
        val len = kotlin.math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
        // Across the track, a fifth of a tile each side for the bed.
        val nx = -(by - ay) / len
        val ny = (bx - ax) / len
        strip(ax, ay, bx, by, nx, ny, 0.2f, bed)
        if (halfWidth >= 6f) {
            val sleepers = (len * 6).toInt().coerceAtLeast(2)
            for (i in 0 until sleepers) {
                val t0 = (i + 0.3f) / sleepers
                val t1 = (i + 0.6f) / sleepers
                strip(ax + (bx - ax) * t0, ay + (by - ay) * t0, ax + (bx - ax) * t1, ay + (by - ay) * t1, nx, ny, 0.16f, SLEEPER)
            }
            line(ax + nx * 0.09f, ay + ny * 0.09f, bx + nx * 0.09f, by + ny * 0.09f, rail)
            line(ax - nx * 0.09f, ay - ny * 0.09f, bx - nx * 0.09f, by - ny * 0.09f, rail)
        }
    }

    /** A strip from world point a to b, [half] tiles each side of the line along ([nx], [ny]). */
    private fun DrawScope.strip(ax: Float, ay: Float, bx: Float, by: Float, nx: Float, ny: Float, half: Float, colour: Color) {
        val p = floatArrayOf(
            ax + nx * half, ay + ny * half, bx + nx * half, by + ny * half,
            bx - nx * half, by - ny * half, ax - nx * half, ay - ny * half,
        )
        path.reset()
        for (i in 0 until 4) {
            val sx = screenOf(p[i * 2], p[i * 2 + 1])
            val sy = screenYOf(p[i * 2], p[i * 2 + 1])
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        path.close()
        drawPath(path, colour)
    }

    private fun DrawScope.line(ax: Float, ay: Float, bx: Float, by: Float, colour: Color) {
        drawLine(
            colour,
            androidx.compose.ui.geometry.Offset(screenOf(ax, ay), screenYOf(ax, ay)),
            androidx.compose.ui.geometry.Offset(screenOf(bx, by), screenYOf(bx, by)),
            strokeWidth = (halfWidth / 16f).coerceAtLeast(1f),
        )
    }

    // A world point, lifted to the ground's height there, a little above it so track sits on top.
    private fun screenOf(x: Float, y: Float) = x(view.toViewX(x, y), view.toViewY(x, y))
    private fun screenYOf(x: Float, y: Float) = y(view.toViewX(x, y), view.toViewY(x, y), groundHeight(world, x, y))

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset()
        path.moveTo(x0, y0)
        path.lineTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.close()
    }

    companion object {
        val WATER = Color(0xFF3A6FB0)
        val SAND = Color(0xFFC9B47E)
        val EARTH_LIGHT = Color(0xFF7A6248)
        val EARTH_DARK = Color(0xFF5A4634)
        val GRID = Color(0x1A000000)
        val STONE_LIGHT = Color(0xFFA39A8A)
        val STONE_DARK = Color(0xFF7F7768)
        val TRACK_BED = Color(0xFF8C8478)
        val SLEEPER = Color(0xFF5B4632)
        val RAIL = Color(0xFFD9D4CC)
        private val GRASS = Array(World.MAX_HEIGHT + 1) { h ->
            // Greener low down, drier and paler up high.
            val t = h / World.MAX_HEIGHT.toFloat()
            Color(0.35f + 0.25f * t, 0.6f - 0.05f * t, 0.24f + 0.1f * t)
        }

        fun grass(h: Int) = GRASS[h.coerceIn(0, World.MAX_HEIGHT)]
    }
}
