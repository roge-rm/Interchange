package com.rm.interchange.map

import com.rm.interchange.sim.BlockWatch
import com.rm.interchange.sim.CurveKeys
import com.rm.interchange.sim.CurveTiles
import com.rm.interchange.sim.TileCurve
import com.rm.interchange.sim.World

/*
 * The lines the map draws track along, for the things drawn over it live (wires, signals, reserved track): the same
 * curves TileRecorder draws, from TrackCurves, kept per world for the draw thread. A tile [CurveTiles] keeps on the old
 * straight art has no list: its pieces run edge to edge.
 */
internal class DrawLines(private val world: World) {
    val tiles = CurveTiles { world }
    val curves = tiles.curves
    private val lines = arrayOfNulls<List<TileCurve>>(world.width * world.height)
    private val watch = BlockWatch(world)
    private var known = CurveKeys.known

    /** Drops the lines of tiles near any that changed since the last call. */
    fun refresh() {
        if (CurveKeys.known !== known) {
            known = CurveKeys.known
            lines.fill(null)
        }
        watch.forChanged(world) { x0, y0, x1, y1 ->
            tiles.invalidate(x0, y0, x1, y1)
            val r = CurveTiles.REACH
            for (y in maxOf(0, y0 - r)..minOf(world.height - 1, y1 + r)) for (x in maxOf(0, x0 - r)..minOf(world.width - 1, x1 + r)) lines[y * world.width + x] = null
        }
    }

    /** The curves drawn on tile ([x], [y]); empty where the tile keeps the old straight art. */
    fun at(x: Int, y: Int): List<TileCurve> {
        val tile = world.index(x, y)
        lines[tile]?.let { return it }
        return (if (tiles.curved(x, y)) curves.curvesAt(x, y) else NONE).also { lines[tile] = it }
    }

    /** The curve [piece] is drawn along on tile ([x], [y]) (the through line on a switch), or null for edge to edge. */
    fun lineOf(x: Int, y: Int, piece: Int): TileCurve? {
        for (c in at(x, y)) if (c.sim == piece && c.via == 0) return c
        return null
    }

    companion object {
        private val NONE: List<TileCurve> = ArrayList(0)

        private var last: DrawLines? = null

        /** The lines of [world], brought up to date. */
        fun of(world: World): DrawLines {
            CurveArt.install()
            val l = last?.takeIf { it.world === world } ?: DrawLines(world).also { last = it }
            l.refresh()
            return l
        }

        /** Lets go of the lines of [world] when it is dropped. */
        fun drop(world: World) {
            if (last?.world === world) last = null
        }
    }
}
