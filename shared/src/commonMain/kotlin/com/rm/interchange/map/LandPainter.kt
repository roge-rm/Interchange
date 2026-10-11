package com.rm.interchange.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.rm.interchange.sim.CORNER_E
import com.rm.interchange.sim.CORNER_N
import com.rm.interchange.sim.CORNER_S
import com.rm.interchange.sim.CORNER_W
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.World
import com.rm.interchange.sim.hasFoundation
import com.rm.interchange.sim.surfaceHeight

/**
 * Draws tiles of land and track from the atlas made by tools/gen_tiles.py,
 * one level's sheet, at that level's size. The sprites are drawn as the
 * camera sees them, so the painter turns each tile's corners and track into
 * view coordinates and picks the sprite for that shape.
 *
 * Positions are worked out from view coordinates by [x] and [y], set for
 * whatever is being drawn on: a chunk being baked, or the screen. On the
 * screen, with no [sheet], only [track] is used, drawn as plain shapes at any
 * size, for previews.
 */
class LandPainter(private val world: World, private val view: View, private val halfWidth: Float, private val sheet: ImageBitmap? = null) {
    private val halfHeight = halfWidth / 2
    private val step = halfWidth / 4
    private val path = Path()

    // A cell's size and anchor at this level.
    private val scale = halfWidth / (Atlas.CELL_W / 2)
    private val cellW = (Atlas.CELL_W * scale).toInt()
    private val cellH = (Atlas.CELL_H * scale).toInt()
    private val anchorY = (Atlas.CELL_H_ANCHOR * scale).toInt()

    /** Where view point 0, 0 at height 0 lands. */
    var originX = 0f
    var originY = 0f

    fun x(vx: Float, vy: Float) = originX + (vx - vy) * halfWidth
    fun y(vx: Float, vy: Float, h: Float) = originY + (vx + vy) * halfHeight - h * step

    private fun cornerHeight(vx: Int, vy: Int) = world.heightAt(view.cornerX(vx, vy), view.cornerY(vx, vy))

    /** Which world corner of tile ([wx], [wy]) view corner ([cvx], [cvy]) is. */
    private fun worldCorner(wx: Int, wy: Int, cvx: Int, cvy: Int): Int {
        val dx = view.cornerX(cvx, cvy) - wx
        val dy = view.cornerY(cvx, cvy) - wy
        return if (dy == 0) (if (dx == 0) CORNER_N else CORNER_E) else (if (dx == 0) CORNER_W else CORNER_S)
    }

    /** Draws atlas [cell] with its anchor on view tile ([vx], [vy])'s top corner at height [h]. */
    private fun DrawScope.sprite(cell: Int, vx: Int, vy: Int, h: Int) {
        val image = sheet ?: return
        if (cell < 0) return
        val sx = (cell % Atlas.COLUMNS) * cellW
        val sy = (cell / Atlas.COLUMNS) * cellH
        val dx = x(vx.toFloat(), vy.toFloat()) - halfWidth
        val dy = y(vx.toFloat(), vy.toFloat(), h.toFloat()) - anchorY
        drawImage(image, IntOffset(sx, sy), IntSize(cellW, cellH), IntOffset(dx.toInt(), dy.toInt()), IntSize(cellW, cellH))
    }

    /** Draws view tile ([vx], [vy]): the ground, any foundation and track on it, and at the map's front edges the earth under it. */
    fun DrawScope.tile(vx: Int, vy: Int) {
        // The corners in view order: top, right, bottom, left.
        val t = cornerHeight(vx, vy)
        val r = cornerHeight(vx + 1, vy)
        val b = cornerHeight(vx + 1, vy + 1)
        val l = cornerHeight(vx, vy + 1)
        val wx = view.worldX(vx, vy)
        val wy = view.worldY(vx, vy)
        val fx = vx.toFloat()
        val fy = vy.toFloat()

        // The earth under the front edges of the map, down past sea level.
        if (vx == view.viewWidth - 1) {
            quad(x(fx + 1, fy), y(fx + 1, fy, r.toFloat()), x(fx + 1, fy + 1), y(fx + 1, fy + 1, b.toFloat()), x(fx + 1, fy + 1), y(fx + 1, fy + 1, -2f), x(fx + 1, fy), y(fx + 1, fy, -2f))
            drawPath(path, EARTH_DARK)
        }
        if (vy == view.viewHeight - 1) {
            quad(x(fx, fy + 1), y(fx, fy + 1, l.toFloat()), x(fx + 1, fy + 1), y(fx + 1, fy + 1, b.toFloat()), x(fx + 1, fy + 1), y(fx + 1, fy + 1, -2f), x(fx, fy + 1), y(fx, fy + 1, -2f))
            drawPath(path, EARTH_LIGHT)
        }

        val base = minOf(minOf(t, r), minOf(b, l))
        val shape = shapeOf(t - base, r - base, b - base, l - base)
        val water = base == 0 && shape == 0
        val kind = when {
            water -> Atlas.WATER
            base == 0 -> Atlas.SAND
            else -> Atlas.GRASS0 + variant(wx, wy)
        }
        sprite(Atlas.GROUND[kind * 16 + shape], vx, vy, base)

        val pieces = world.trackAt(wx, wy)
        if (pieces == 0) return
        // What the track lies on: the land, or a foundation's top.
        val st = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx, vy))
        val sr = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx + 1, vy))
        val sb = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx + 1, vy + 1))
        val sl = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx, vy + 1))
        val sBase = minOf(minOf(st, sr), minOf(sb, sl))
        val sShape = shapeOf(st - sBase, sr - sBase, sb - sBase, sl - sBase)
        if (world.hasFoundation(wx, wy)) {
            sprite(Atlas.WALLS[shape * 16 + shapeOf(st - base, sr - base, sb - base, sl - base)], vx, vy, base)
            sprite(Atlas.GROUND[(Atlas.GRASS0 + variant(wx, wy)) * 16 + sShape], vx, vy, sBase)
        }
        for ((i, p) in Track.PIECES.withIndex()) {
            if (pieces and p == 0) continue
            sprite(Atlas.TRACK[viewPieceIndex(i) * 16 + sShape], vx, vy, sBase)
        }
    }

    /** The index of world piece [index] as it shows in this view. */
    private fun viewPieceIndex(index: Int): Int {
        val e = Track.edges(Track.PIECES[index])
        val v = Track.pieceOf(viewEdge(e and 3), viewEdge(e shr 2))
        return Track.PIECES.indexOf(v)
    }

    /** Where a world tile edge shows in this view. */
    private fun viewEdge(edge: Int): Int = when (view.turn) {
        0 -> edge
        1 -> when (edge) { Track.XM -> Track.YM; Track.XP -> Track.YP; Track.YM -> Track.XP; else -> Track.XM }
        2 -> Track.opposite(edge)
        else -> when (edge) { Track.YM -> Track.XM; Track.YP -> Track.XP; Track.XM -> Track.YP; else -> Track.YM }
    }

    /** One track piece on world tile ([wx], [wy]), drawn as plain shapes: a bed and two rails. For previews. */
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
        val p = floatArrayOf(
            ax + nx * 0.2f, ay + ny * 0.2f, bx + nx * 0.2f, by + ny * 0.2f,
            bx - nx * 0.2f, by - ny * 0.2f, ax - nx * 0.2f, ay - ny * 0.2f,
        )
        path.reset()
        for (i in 0 until 4) {
            val sx = screenX(p[i * 2], p[i * 2 + 1])
            val sy = screenY(p[i * 2], p[i * 2 + 1])
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        path.close()
        drawPath(path, bed)
        for (side in floatArrayOf(-0.09f, 0.09f)) {
            drawLine(
                rail,
                Offset(screenX(ax + nx * side, ay + ny * side), screenY(ax + nx * side, ay + ny * side)),
                Offset(screenX(bx + nx * side, by + ny * side), screenY(bx + nx * side, by + ny * side)),
                strokeWidth = (halfWidth / 16f).coerceAtLeast(1f),
            )
        }
    }

    // A world point, lifted to the ground's height there.
    private fun screenX(x: Float, y: Float) = x(view.toViewX(x, y), view.toViewY(x, y))
    private fun screenY(x: Float, y: Float) = y(view.toViewX(x, y), view.toViewY(x, y), groundHeight(world, x, y))

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset()
        path.moveTo(x0, y0)
        path.lineTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.close()
    }

    companion object {
        val EARTH_LIGHT = Color(0xFF7A6248)
        val EARTH_DARK = Color(0xFF5A4634)

        /** Which view corners stand a step up, as Atlas shapes. */
        fun shapeOf(t: Int, r: Int, b: Int, l: Int) = t or (r shl 1) or (b shl 2) or (l shl 3)

        /** Which of the grass sprites a tile gets, the same in every view. */
        fun variant(wx: Int, wy: Int): Int {
            var h = wx * 73856093 xor wy * 19349663
            h = h xor (h ushr 13)
            return (h * 0x5bd1e995 ushr 29) and 3
        }
    }
}
