package com.rm.interchange.map

import com.rm.interchange.sim.CORNER_E
import com.rm.interchange.sim.CORNER_N
import com.rm.interchange.sim.CORNER_S
import com.rm.interchange.sim.CORNER_W
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.Trees
import com.rm.interchange.sim.World
import com.rm.interchange.sim.hasFoundation
import com.rm.interchange.sim.surfaceHeight
import kotlin.math.sqrt

/**
 * Bakes a chunk of the map into pixels, tile by tile from the back: the
 * ground painted from its kind's texture with the slope's light, ragged where
 * one kind meets another and foamy at the shore, then foundations, track,
 * buildings, trees and the odd boulder or shrub blitted from the static sheet.
 *
 * A tile owns the pixels whose middles fall in it, so neighbours share their
 * edges exactly and no seam shows. Textures are laid in each tile's view
 * coordinates and repeat every [Atlas.PERIOD] tiles, so neighbours join up.
 *
 * Reads the world as it is, from a baking thread; see [Renderer].
 */
internal class ChunkBaker(
    private val world: World,
    private val view: View,
    private val level: Int,
    private val sheets: LevelSheets,
    private val width: Int,
    private val height: Int,
) {
    private val hw = Renderer.LEVELS[level]
    private val hh = hw / 2
    private val step = hw / 4
    private val shrink = 1 shl level
    private val texels = Atlas.TEXELS shr level
    private val size = texels * Atlas.PERIOD
    private val pixels = IntArray(width * height)

    /** Where view point 0, 0 at height 0 lands. */
    var originX = 0f
    var originY = 0f

    fun x(vx: Float, vy: Float) = originX + (vx - vy) * hw
    fun y(vx: Float, vy: Float, h: Float) = originY + (vx + vy) * hh - h * step

    fun finish(): IntArray = pixels

    private fun cornerHeight(vx: Int, vy: Int) = world.heightAt(view.cornerX(vx, vy), view.cornerY(vx, vy))

    /** The ground kind of view tile ([vx], [vy]) as drawn: water, beach sand, or the land's own. -1 off the map. */
    private fun kindOf(vx: Int, vy: Int): Int {
        if (vx < 0 || vy < 0 || vx >= view.viewWidth || vy >= view.viewHeight) return -1
        val t = cornerHeight(vx, vy)
        val r = cornerHeight(vx + 1, vy)
        val b = cornerHeight(vx + 1, vy + 1)
        val l = cornerHeight(vx, vy + 1)
        val low = minOf(minOf(t, r), minOf(b, l))
        if (low > 0) return world.ground[world.index(view.worldX(vx, vy), view.worldY(vx, vy))].toInt()
        return if (t == 0 && r == 0 && b == 0 && l == 0) Atlas.WATER else Atlas.SAND
    }

    /**
     * The first pass, for every tile from the back: the ground, the earth under the map's edges, foundations and
     * track. Shadows come next, over all of it, then what stands up ([objects]).
     */
    fun ground(vx: Int, vy: Int) {
        val t = cornerHeight(vx, vy)
        val r = cornerHeight(vx + 1, vy)
        val b = cornerHeight(vx + 1, vy + 1)
        val l = cornerHeight(vx, vy + 1)
        val wx = view.worldX(vx, vy)
        val wy = view.worldY(vx, vy)
        val kind = kindOf(vx, vy)
        val edges = intArrayOf(kindOf(vx, vy - 1), kindOf(vx + 1, vy), kindOf(vx, vy + 1), kindOf(vx - 1, vy))
        ground(vx, vy, t, r, b, l, kind, edges)
        earthFaces(vx, vy, r, b, l)

        val tile = world.index(wx, wy)
        val pieces = world.trackAt(wx, wy)
        if (pieces == 0) return
        val base = minOf(minOf(t, r), minOf(b, l))
        world.depotTiles[tile]?.let {
            sprite(Atlas.TRACK[viewPieceIndex(Track.index(pieces)) * 16], vx, vy, base)
            return
        }
        val st = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx, vy))
        val sr = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx + 1, vy))
        val sb = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx + 1, vy + 1))
        val sl = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx, vy + 1))
        val sBase = minOf(minOf(st, sr), minOf(sb, sl))
        val sShape = shapeOf(st - sBase, sr - sBase, sb - sBase, sl - sBase)
        if (world.hasFoundation(wx, wy)) {
            val shape = shapeOf(t - base, r - base, b - base, l - base)
            sprite(Atlas.WALLS[shape * 16 + shapeOf(st - base, sr - base, sb - base, sl - base)], vx, vy, base)
            ground(vx, vy, st, sr, sb, sl, kind, intArrayOf(-1, -1, -1, -1))
        }
        // Platforms bring their own track, drawn with them.
        if (world.stationTiles.containsKey(tile)) return
        for ((i, p) in Track.PIECES.withIndex()) {
            if (pieces and p == 0) continue
            sprite(Atlas.TRACK[viewPieceIndex(i) * 16 + sShape], vx, vy, sBase)
        }
    }

    /** The second pass: the shadows of what stands on a tile, laid over the ground of every tile before anything stands up. */
    fun shadows(vx: Int, vy: Int) = standing(vx, vy, shadow = true)

    /** The third pass, from the back: trees, boulders and shrubs, platforms and engine houses. */
    fun objects(vx: Int, vy: Int) = standing(vx, vy, shadow = false)

    private fun standing(vx: Int, vy: Int, shadow: Boolean) {
        val wx = view.worldX(vx, vy)
        val wy = view.worldY(vx, vy)
        val tile = world.index(wx, wy)
        val pieces = world.trackAt(wx, wy)
        val kind = kindOf(vx, vy)
        if (pieces == 0) {
            if (kind != Atlas.WATER && kind != Atlas.SAND) {
                trees(wx, wy, shadow)
                decor(wx, wy, kind, shadow)
            }
            return
        }
        val base = minOf(minOf(cornerHeight(vx, vy), cornerHeight(vx + 1, vy)), minOf(cornerHeight(vx + 1, vy + 1), cornerHeight(vx, vy + 1)))
        world.depotTiles[tile]?.let { id ->
            val d = world.depots.getOrNull(id) ?: return@let
            val e = viewEdge(d.edge)
            sprite(if (shadow) Atlas.DEPOT_SHADOW[e] else Atlas.DEPOT[e], vx, vy, base)
            return
        }
        if (world.stationTiles.containsKey(tile)) {
            val st = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx, vy))
            val a = viewPieceIndex(Track.index(pieces))
            sprite(if (shadow) Atlas.STATION_SHADOW[a] else Atlas.STATION[a], vx, vy, st)
        }
    }

    // ---- ground --------------------------------------------------------------------------

    /**
     * Paints a tile's top with corner heights [t], [r], [b], [l] (view order) in [kind]'s texture, letting the
     * kinds of its neighbours across each edge ([edges], -1 for none) spread in where they rank higher.
     */
    private fun ground(vx: Int, vy: Int, t: Int, r: Int, b: Int, l: Int, kind: Int, edges: IntArray) {
        // Split along whichever diagonal has its ends level, as the slopes are drawn. Each half is a plane
        // h = h0 + gu * u + gv * v over the tile's own u and v.
        val alongRL = r == l
        val p1 = if (alongRL) floatArrayOf(t.toFloat(), (r - t).toFloat(), (l - t).toFloat()) else floatArrayOf(t.toFloat(), (r - t).toFloat(), (b - r).toFloat())
        val p2 = if (alongRL) floatArrayOf((r + l - b).toFloat(), (b - l).toFloat(), (b - r).toFloat()) else floatArrayOf(t.toFloat(), (b - l).toFloat(), (l - t).toFloat())
        val f1 = light(p1[1], p1[2])
        val f2 = light(p2[1], p2[2])
        val fx = vx.toFloat()
        val fy = vy.toFloat()
        val left = x(fx, fy + 1).toInt() - 1
        val right = x(fx + 1, fy).toInt() + 1
        val top = minOf(minOf(y(fx, fy, t.toFloat()), y(fx + 1, fy, r.toFloat())), minOf(y(fx, fy + 1, l.toFloat()), y(fx + 1, fy + 1, b.toFloat()))).toInt() - 1
        val bottom = maxOf(maxOf(y(fx, fy, t.toFloat()), y(fx + 1, fy, r.toFloat())), maxOf(y(fx, fy + 1, l.toFloat()), y(fx + 1, fy + 1, b.toFloat()))).toInt() + 1
        val x0 = maxOf(0, left)
        val x1 = minOf(width - 1, right)
        val y0 = maxOf(0, top)
        val y1 = minOf(height - 1, bottom)
        if (x0 > x1 || y0 > y1) return
        val rankHere = rank(kind)
        val txBase = (vx % Atlas.PERIOD) * texels
        val tyBase = (vy % Atlas.PERIOD) * texels
        val kindBase = kind * size * size
        val ragBase = Atlas.RAG * size * size
        val baseY = originY + (vx + vy) * hh
        val baseX = originX + (vx - vy) * hw
        for (py in y0..y1) {
            val yRel = py + 0.5f - baseY
            for (px in x0..x1) {
                val d = (px + 0.5f - baseX) / hw
                // Try the first half's plane, then the second's.
                var u: Float
                var v: Float
                var f: Int
                var s = (yRel + step * (p1[0] + (p1[1] - p1[2]) / 2 * d)) / (hh - step * (p1[1] + p1[2]) / 2)
                u = (s + d) / 2
                v = (s - d) / 2
                val inFirst = if (alongRL) u + v <= 1f else u >= v
                if (inFirst) {
                    f = f1
                } else {
                    s = (yRel + step * (p2[0] + (p2[1] - p2[2]) / 2 * d)) / (hh - step * (p2[1] + p2[2]) / 2)
                    u = (s + d) / 2
                    v = (s - d) / 2
                    f = f2
                    if (if (alongRL) u + v < 1f else u > v) continue
                }
                if (u < 0f || v < 0f || u >= 1f || v >= 1f) continue
                val tx = txBase + (u * texels).toInt()
                val ty = tyBase + (v * texels).toInt()
                val ti = ty * size + tx
                var texel = sheets.ground[kindBase + ti]
                val rag = (sheets.ground[ragBase + ti] and 255) / 255f
                // Higher ranked ground next door spreads in across the edge with a ragged line; water gets foam at the shore.
                for (e in 0 until 4) {
                    val other = edges[e]
                    if (other < 0 || other == kind || rank(other) <= rankHere) continue
                    val dist = when (e) { 0 -> v; 1 -> 1f - u; 2 -> 1f - v; else -> u }
                    val reach = 0.08f + rag * 0.26f
                    if (dist < reach) {
                        texel = sheets.ground[other * size * size + ti]
                    } else if (kind == Atlas.WATER && dist < reach + 0.07f) {
                        texel = FOAM
                    }
                }
                pixels[py * width + px] = lit(texel, f)
            }
        }
    }

    /** How lit a plane with these slopes is, in 256ths: a little over a quarter of what a real slope would show, as the old sprites were. */
    private fun light(gu: Float, gv: Float): Int {
        // The normal of h = gu * u + gv * v, with a step half a tile high.
        val nx = -gu * 0.5f
        val ny = -gv * 0.5f
        val nz = 1f
        val n = sqrt(nx * nx + ny * ny + nz * nz)
        val d = (nx * LX + ny * LY + nz * LZ) / (n * LL)
        val flat = LZ / LL
        return ((1f + 0.28f * (d / flat - 1f)).coerceIn(0.84f, 1.12f) * 256).toInt()
    }

    private fun lit(texel: Int, f: Int): Int {
        val r = minOf(255, ((texel shr 16) and 255) * f shr 8)
        val g = minOf(255, ((texel shr 8) and 255) * f shr 8)
        val b = minOf(255, (texel and 255) * f shr 8)
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** The earth under the map's front edges, down past sea level. */
    private fun earthFaces(vx: Int, vy: Int, r: Int, b: Int, l: Int) {
        val fx = vx.toFloat()
        val fy = vy.toFloat()
        if (vx == view.viewWidth - 1) {
            face(x(fx + 1, fy), y(fx + 1, fy, r.toFloat()), y(fx + 1, fy, -2f), x(fx + 1, fy + 1), y(fx + 1, fy + 1, b.toFloat()), y(fx + 1, fy + 1, -2f), EARTH_DARK)
        }
        if (vy == view.viewHeight - 1) {
            face(x(fx, fy + 1), y(fx, fy + 1, l.toFloat()), y(fx, fy + 1, -2f), x(fx + 1, fy + 1), y(fx + 1, fy + 1, b.toFloat()), y(fx + 1, fy + 1, -2f), EARTH_LIGHT)
        }
    }

    /** An upright face between screen columns [ax] and [bx], each end running from its top y down to its bottom y. */
    private fun face(ax: Float, aTop: Float, aBottom: Float, bx: Float, bTop: Float, bBottom: Float, colour: Int) {
        for (px in maxOf(0, minOf(ax, bx).toInt())..minOf(width - 1, maxOf(ax, bx).toInt())) {
            val t = ((px + 0.5f - ax) / (bx - ax)).coerceIn(0f, 1f)
            val top = aTop + (bTop - aTop) * t
            val bottom = aBottom + (bBottom - aBottom) * t
            for (py in maxOf(0, top.toInt())..minOf(height - 1, bottom.toInt())) {
                if (py + 0.5f >= top) pixels[py * width + px] = colour
            }
        }
    }

    // ---- sprites -------------------------------------------------------------------------

    /** Blits static sprite [cell] with its anchor at ([ax], [ay]), blending by its alpha. */
    private fun blit(cell: Int, ax: Float, ay: Float) {
        if (cell < 0) return
        val r = cell * 6
        val sx = Atlas.STATIC_RECTS[r] / shrink
        val sy = Atlas.STATIC_RECTS[r + 1] / shrink
        val w = Atlas.STATIC_RECTS[r + 2] / shrink
        val h = Atlas.STATIC_RECTS[r + 3] / shrink
        val left = (ax + Atlas.STATIC_RECTS[r + 4] / shrink).toInt()
        val top = (ay + Atlas.STATIC_RECTS[r + 5] / shrink).toInt()
        val src = sheets.static
        val sw = sheets.staticWidth
        for (j in 0 until h) {
            val py = top + j
            if (py < 0 || py >= height) continue
            val srcRow = (sy + j) * sw + sx
            val dstRow = py * width
            for (i in 0 until w) {
                val px = left + i
                if (px < 0 || px >= width) continue
                val c = src[srcRow + i]
                val a = c ushr 24
                if (a == 0) continue
                if (a == 255) {
                    pixels[dstRow + px] = c
                    continue
                }
                val d = pixels[dstRow + px]
                val da = d ushr 24
                val inv = 255 - a
                val rr = (((c shr 16) and 255) * a + ((d shr 16) and 255) * inv) / 255
                val gg = (((c shr 8) and 255) * a + ((d shr 8) and 255) * inv) / 255
                val bb = ((c and 255) * a + (d and 255) * inv) / 255
                val oa = a + da * inv / 255
                pixels[dstRow + px] = (oa shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
    }

    /** A static sprite anchored on view tile ([vx], [vy])'s top corner at height [h]. */
    private fun sprite(cell: Int, vx: Int, vy: Int, h: Int) = blit(cell, x(vx.toFloat(), vy.toFloat()), y(vx.toFloat(), vy.toFloat(), h.toFloat()))

    /** A sprite standing at world point ([tx], [ty]), drawn as if on a tile whose middle is there. */
    private fun standing(cell: Int, tx: Float, ty: Float) {
        val vx = view.toViewX(tx, ty)
        val vy = view.toViewY(tx, ty)
        blit(cell, x(vx - 0.5f, vy - 0.5f), y(vx - 0.5f, vy - 0.5f, groundHeight(world, tx, ty)))
    }

    /** The trees on a tile, or their shadows, each where the tile's own dice put it, the furthest first. */
    private fun trees(wx: Int, wy: Int, shadow: Boolean) {
        val packed = world.trees[world.index(wx, wy)].toInt()
        val n = Trees.count(packed)
        if (n == 0) return
        val kind = Trees.kind(packed)
        val spots = ArrayList<FloatArray>(n)
        for (k in 0 until n) {
            val d = LandPainter.dice(wx, wy, k)
            // Spread over the middle of the tile so they don't crowd onto the track next door.
            val tx = wx + 0.22f + (d and 255) / 255f * 0.56f
            val ty = wy + 0.22f + ((d shr 8) and 255) / 255f * 0.56f
            spots += floatArrayOf(view.toViewX(tx, ty) + view.toViewY(tx, ty), tx, ty, ((d shr 16) and 255).toFloat())
        }
        spots.sortBy { it[0] }
        val cells = if (shadow) Atlas.TREE_SHADOW else Atlas.TREE
        for (sp in spots) standing(cells[kind * Atlas.TREE_LOOKS + sp[3].toInt() % Atlas.TREE_LOOKS], sp[1], sp[2])
    }

    /** Now and then a boulder, a shrub or a clump of tall grass, as suits the ground, or its shadow. Only for looks. */
    private fun decor(wx: Int, wy: Int, kind: Int, shadow: Boolean) {
        if (Trees.count(world.trees[world.index(wx, wy)].toInt()) > 0) return
        val d = LandPainter.dice(wx, wy, 11)
        val roll = d and 255
        val what = when (kind) {
            Atlas.ROCK -> if (roll < 80) 0 else -1
            Atlas.TUNDRA -> if (roll < 25) 0 else if (roll < 90) 1 else -1
            Atlas.MEADOW -> if (roll < 12) 1 else if (roll < 50) 2 else -1
            Atlas.PRAIRIE -> if (roll < 70) 2 else -1
            Atlas.FOREST -> if (roll < 60) 1 else -1
            else -> -1
        }
        if (what < 0) return
        val tx = wx + 0.25f + ((d shr 8) and 255) / 255f * 0.5f
        val ty = wy + 0.25f + ((d shr 16) and 255) / 255f * 0.5f
        val cells = if (shadow) Atlas.DECOR_SHADOW else Atlas.DECOR
        standing(cells[what * Atlas.DECOR_LOOKS + (d ushr 24) % Atlas.DECOR_LOOKS], tx, ty)
    }

    // ---- view helpers ---------------------------------------------------------------------

    private fun worldCorner(wx: Int, wy: Int, cvx: Int, cvy: Int): Int {
        val dx = view.cornerX(cvx, cvy) - wx
        val dy = view.cornerY(cvx, cvy) - wy
        return if (dy == 0) (if (dx == 0) CORNER_N else CORNER_E) else (if (dx == 0) CORNER_W else CORNER_S)
    }

    private fun viewPieceIndex(index: Int): Int {
        val e = Track.edges(Track.PIECES[index])
        return Track.PIECES.indexOf(Track.pieceOf(viewEdge(e and 3), viewEdge(e shr 2)))
    }

    private fun viewEdge(edge: Int): Int = when (view.turn) {
        0 -> edge
        1 -> when (edge) { Track.XM -> Track.YM; Track.XP -> Track.YP; Track.YM -> Track.XP; else -> Track.XM }
        2 -> Track.opposite(edge)
        else -> when (edge) { Track.YM -> Track.XM; Track.YP -> Track.XP; Track.XM -> Track.YP; else -> Track.YM }
    }

    companion object {
        // Light from the top left of the screen and above, in (u, v, up).
        private const val LX = -1f
        private const val LY = -0.35f
        private const val LZ = 1.6f
        private val LL = sqrt(LX * LX + LY * LY + LZ * LZ)

        private const val FOAM = 0xffe8f0f4.toInt()
        private const val EARTH_LIGHT = 0xff7a6248.toInt()
        private const val EARTH_DARK = 0xff5a4634.toInt()

        fun shapeOf(t: Int, r: Int, b: Int, l: Int) = t or (r shl 1) or (b shl 2) or (l shl 3)

        /** Which ground wins where two meet, spreading in across the edge: water lowest, snow highest. */
        fun rank(kind: Int) = when (kind) {
            Atlas.WATER -> 0
            Atlas.SAND -> 1
            Atlas.PRAIRIE -> 2
            Atlas.MEADOW -> 3
            Atlas.TUNDRA -> 4
            Atlas.FOREST -> 5
            Atlas.ROCK -> 6
            else -> 7
        }
    }
}
