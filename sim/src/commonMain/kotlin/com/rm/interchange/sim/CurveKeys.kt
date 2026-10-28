package com.rm.interchange.sim

import kotlin.concurrent.Volatile
import kotlin.math.abs

/**
 * The names of the curved track sprites a tile draws, as tools/blender/curve_art.py gives them, which says what each
 * holds. Drawing only, like [TrackCurves].
 */
object CurveKeys {
    private val EDGE_NAMES = arrayOf("XM", "XP", "YM", "YP")

    /** The "layer key"s the static sheet has a sprite for, set by the map from its atlas; null takes every key as there. */
    @Volatile var known: Set<String>? = null

    /**
     * "layer key" for everything tile ([x], [y]) draws, in order: every curve's bed, then ties, then running rails,
     * then switch gear. [curves] and [maskAt] must read the same track. Empty for a tile with no track.
     */
    fun at(curves: TrackCurves, maskAt: (Int, Int) -> Int, x: Int, y: Int): List<String> {
        val here = curves.curvesAt(x, y)
        if (here.isEmpty()) return emptyList()
        val pieces = maskAt(x, y)
        val beds = ArrayList<String>()
        val ties = ArrayList<String>()
        val gear = ArrayList<String>()
        val covered = here.filter { it.via == 0 && it.kind != TrackCurves.PLAIN }.map { it.sim }
        for (c in here) {
            val bk = bedKey(c)
            beds += bk
            when (c.kind) {
                TrackCurves.PLAIN -> {
                    var tk = bk
                    if (!straight(c.sim)) {
                        val ends = StringBuilder()
                        for (edge in intArrayOf(c.from, c.to)) {
                            val others = others(pieces, c.sim, edge)
                            val on = others.isNotEmpty() && others.none { it in covered }
                            ends.append(if (on && others.any { straight(it) }) 't' else '-')
                            if (on) gear += "g${letter(c.sim)}${EDGE_NAMES[edge]}${c.looks}${ctx(here, pieces, c.sim, edge)}"
                        }
                        if ('t' in ends) tk = "${bk}_$ends"
                    }
                    ties += tk
                }
                TrackCurves.BACK -> {
                    val e = splitEdge(c)
                    val ctx = if (c.via != 0) {
                        // The straight tile's part: the switch tile is across its split edge.
                        val sx = x + Track.dx(e)
                        val sy = y + Track.dy(e)
                        ctx(curves.curvesAt(sx, sy), maskAt(sx, sy), c.via, Track.opposite(e))
                    } else ctx(here, pieces, c.sim, e)
                    ties += bk + if (ctx.startsWith("s")) "t" else ""
                    if (ctx.isNotEmpty()) gear += "g$bk$ctx"
                }
                else -> ties += bk
            }
        }
        return beds.map { "bed $it" } + ties.map { "ties $it" } + beds.map { "running $it" } + gear.map { "gear $it" }
    }

    private fun straight(piece: Int) = piece == Track.X || piece == Track.Y

    private fun others(pieces: Int, piece: Int, edge: Int) = Track.PIECES.filter { pieces and it != 0 && it != piece && Track.has(it, edge) }

    /** The other routes leaving by [edge] beside [piece]: s for a straight, q and the diagonal's looks, or E if eased. */
    private fun ctx(curves: List<TileCurve>, pieces: Int, piece: Int, edge: Int): String {
        var out = ""
        for (x in others(pieces, piece, edge)) {
            if (straight(x)) out = "s$out"
            else {
                val q = curves.firstOrNull { it.sim == x && it.via == 0 } ?: return "?"
                out += "q" + if (q.kind != TrackCurves.PLAIN) "E" else q.looks
            }
        }
        return out
    }

    private fun bedKey(c: TileCurve): String {
        val label = letter(c.sim) + if (c.via != 0) "to${letter(c.via)}" else ""
        return when (c.kind) {
            TrackCurves.PLAIN -> "p$label${c.looks}"
            TrackCurves.BACK -> "b${label}_${EDGE_NAMES[splitEdge(c)]}"
            else -> "c${label}_${EDGE_NAMES[splitEdge(c)]}"
        }
    }

    private fun letter(piece: Int) = when (piece) { Track.X -> "X"; Track.Y -> "Y"; Track.N -> "N"; Track.E -> "E"; Track.S -> "S"; else -> "W" }

    /** The end of an eased part off its edge's middle, where it crosses into the span's other tile. */
    private fun splitEdge(c: TileCurve): Int {
        val atFrom = abs(c.ctrl[0] - Track.edgeX4(c.from) / 4.0) + abs(c.ctrl[1] - Track.edgeY4(c.from) / 4.0)
        return if (atFrom > 1e-6) c.from else c.to
    }
}
