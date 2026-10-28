package com.rm.interchange.map

import com.rm.interchange.sim.CurveKeys
import com.rm.interchange.sim.CurveTiles
import com.rm.interchange.sim.World

/**
 * The curved track sprites of a rail tile (docs/CURVES_PLAN.md) as [view] turns it, by [CurveKeys] over [CurveTiles].
 * Reads the world as it is, from a baking thread, like [ChunkBaker]; one per bake.
 */
internal class CurveArt(world: World, private val view: View) {
    private val tiles = CurveTiles { world }

    /**
     * The static sheet cells world tile ([x], [y]) draws, in order: every curve's bed, then ties, then running rails,
     * then switch gear. Null where [CurveTiles] keeps the tile on its old straight art.
     */
    fun cells(x: Int, y: Int): IntArray? {
        install()
        if (!tiles.curved(x, y)) return null
        val keys = tiles.keys(x, y, view.turn)
        if (keys.isEmpty()) return null
        return IntArray(keys.size) { CELLS[keys[it]] ?: return null }
    }

    companion object {
        /** Each "layer key" in [Atlas.CURVE_KEYS] to its cell. */
        private val CELLS: Map<String, Int> by lazy {
            HashMap<String, Int>().also { m -> Atlas.CURVE_KEYS.forEachIndexed { i, k -> if (Atlas.CURVE[i] >= 0) m[k] = Atlas.CURVE[i] } }
        }

        /** Tells [CurveKeys] which sprites the sheet has, for [CurveTiles] in the sim's cars as well as here. */
        fun install() {
            if (CurveKeys.known == null) CurveKeys.known = CELLS.keys
        }
    }
}
