package com.rm.interchange.map

import com.rm.interchange.sim.BlockWatch
import com.rm.interchange.sim.CurveKeys
import com.rm.interchange.sim.CurveTiles
import com.rm.interchange.sim.World

/**
 * The curved track sprites of a rail tile (docs/CURVES_PLAN.md) at each turn of the view, by [CurveKeys] over
 * [CurveTiles]. Each tile's cells are kept between recordings and dropped when track near them changes ([refresh]).
 *
 * Reads the world as it is, from a recording thread, like [TileRecorder]. Not thread safe: one recording uses it at
 * a time.
 */
internal class CurveArt(private val world: World) {
    private val tiles = CurveTiles(keepTurns = true) { world }
    private val watch = BlockWatch(world)
    private var known = CurveKeys.known

    /** Each tile's cells at each turn, by tile index * 4 + turn; [STRAIGHT] where the tile keeps its old straight art. */
    private val cells = HashMap<Int, IntArray>()
    /** Each tile's [CurveTiles.curved], the same at every turn. */
    private val curved = HashMap<Int, Boolean>()

    /** Drops the cells of tiles near any that changed since the last call. Call before each recording. */
    fun refresh() {
        install()
        if (CurveKeys.known !== known) {
            known = CurveKeys.known
            cells.clear()
            curved.clear()
        }
        watch.forChanged(world) { x0, y0, x1, y1 ->
            tiles.invalidate(x0, y0, x1, y1)
            val r = CurveTiles.REACH
            for (y in maxOf(0, y0 - r)..minOf(world.height - 1, y1 + r)) for (x in maxOf(0, x0 - r)..minOf(world.width - 1, x1 + r)) {
                val i = world.index(x, y)
                curved.remove(i)
                for (t in 0..3) cells.remove(i * 4 + t)
            }
        }
    }

    /**
     * The static sheet cells world tile ([x], [y]) draws with the view at quarter turn [turn], in order: every curve's
     * bed, then ties, then running rails, then switch gear. Null where [CurveTiles] keeps the tile on its old straight art.
     */
    fun cells(x: Int, y: Int, turn: Int): IntArray? {
        val i = world.index(x, y)
        val got = cells[i * 4 + turn] ?: work(x, y, i, turn).also { cells[i * 4 + turn] = it ?: STRAIGHT }
        return if (got === STRAIGHT) null else got
    }

    private fun work(x: Int, y: Int, i: Int, turn: Int): IntArray? {
        if (!curved.getOrPut(i) { tiles.curved(x, y) }) return null
        val keys = tiles.keys(x, y, turn)
        if (keys.isEmpty()) return null
        return IntArray(keys.size) { CELLS[keys[it]] ?: return null }
    }

    companion object {
        private val STRAIGHT = IntArray(0)

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
