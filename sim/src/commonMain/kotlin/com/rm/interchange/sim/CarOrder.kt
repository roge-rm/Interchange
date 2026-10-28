package com.rm.interchange.sim

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draw order for cars on the map, drawing only. All in view terms: depth is vx + vy, bigger is nearer the camera,
 * and a screen column is vx - vy. A car's footprint is a box [HALF_WIDTH] either side of the line through its middle
 * at its angle in the view (radians, 0 along vx on round towards vy).
 *
 * Each car sorts by its nearest footprint corner. On a tight curve two cars of a train overlap a little at the outside
 * of the coupling, and a longer car can reach nearer than its neighbour while being behind it where they cover the
 * same columns. [Chain] then puts each pair of neighbours in the order they show in those columns.
 */
object CarOrder {
    /** Half a car body's width, in tiles. */
    const val HALF_WIDTH = 0.12f

    /** Columns compared between two neighbours. */
    private const val COLUMNS = 16

    /** How far a car put in front of its neighbour is put past it, in depth. */
    const val NUDGE = 0.001f

    /** How much nearer the camera a car's nearest footprint corner is than its middle, for [half] its length. */
    fun reach(half: Float, angle: Float): Float {
        val c = cos(angle)
        val s = sin(angle)
        return half * abs(c + s) + HALF_WIDTH * abs(c - s)
    }

    /**
     * Which of two cars shows in front where they cover the same screen columns: 1 for a, -1 for b, 0 if they share
     * none. Goes by which near edge of their footprints is nearer in more of the shared columns.
     */
    fun front(ax: Float, ay: Float, aAngle: Float, aHalf: Float, bx: Float, by: Float, bAngle: Float, bHalf: Float): Int {
        val ac = cos(aAngle)
        val asn = sin(aAngle)
        val bc = cos(bAngle)
        val bsn = sin(bAngle)
        // Each footprint's half extents along the column axis.
        val aw = aHalf * abs(ac - asn) + HALF_WIDTH * abs(ac + asn)
        val bw = bHalf * abs(bc - bsn) + HALF_WIDTH * abs(bc + bsn)
        val lo = maxOf(ax - ay - aw, bx - by - bw)
        val hi = minOf(ax - ay + aw, bx - by + bw)
        if (lo >= hi) return 0
        var wins = 0
        for (k in 0 until COLUMNS) {
            val col = lo + (hi - lo) * (k + 0.5f) / COLUMNS
            val gap = near(ax, ay, ac, asn, aHalf, col) - near(bx, by, bc, bsn, bHalf, col)
            if (gap > 0f) wins++ else if (gap < 0f) wins--
        }
        return wins.coerceIn(-1, 1)
    }

    /** The nearest depth of the footprint at middle ([x], [y]) facing ([c], [s]) on column [col], or a large negative off it. */
    internal fun near(x: Float, y: Float, c: Float, s: Float, half: Float, col: Float): Float {
        // In (column, depth) terms the footprint is middle + i * u + j * p for i, j in -1..1.
        val mc = x - y
        val md = x + y
        val uc = (c - s) * half
        val ud = (c + s) * half
        val pc = (-s - c) * HALF_WIDTH
        val pd = (c - s) * HALF_WIDTH
        var best = -1e9f
        // Its four sides: i = -1 and 1 with j running, j = -1 and 1 with i running.
        for (sign in -1..1 step 2) {
            if (abs(pc) > 1e-6f) {
                val j = (col - mc - sign * uc) / pc
                if (j >= -1f && j <= 1f) best = maxOf(best, md + sign * ud + j * pd)
            }
            if (abs(uc) > 1e-6f) {
                val i = (col - mc - sign * pc) / uc
                if (i >= -1f && i <= 1f) best = maxOf(best, md + i * ud + sign * pd)
            }
        }
        return best
    }

    /**
     * The cars of one train in order, front to back, each with its sort depth. [settle] moves depths so each pair of
     * neighbours sorts the way [front] says; depths only go up, by as little as they can. Kept and reused, so a frame
     * allocates nothing once it has grown.
     */
    class Chain {
        var size = 0
            private set
        private var x = FloatArray(16)
        private var y = FloatArray(16)
        private var angle = FloatArray(16)
        private var half = FloatArray(16)
        /** Sort depths, read back after [settle]. */
        var depth = FloatArray(16)
            private set
        private var ahead = IntArray(16)

        fun clear() {
            size = 0
        }

        fun add(vx: Float, vy: Float, viewAngle: Float, halfLength: Float, sortDepth: Float) {
            if (size == x.size) {
                val n = size * 2
                x = x.copyOf(n)
                y = y.copyOf(n)
                angle = angle.copyOf(n)
                half = half.copyOf(n)
                depth = depth.copyOf(n)
                ahead = ahead.copyOf(n)
            }
            x[size] = vx
            y[size] = vy
            angle[size] = viewAngle
            half[size] = halfLength
            depth[size] = sortDepth
            size++
        }

        fun settle() {
            if (size < 2) return
            // ahead[k] is 1 when car k must draw after car k + 1, -1 when before, 0 when it doesn't matter.
            for (k in 0 until size - 1) ahead[k] = front(x[k], y[k], angle[k], half[k], x[k + 1], y[k + 1], angle[k + 1], half[k + 1])
            // A train is a line of pairs, so this settles within a few sweeps; the cap is only a guard.
            for (sweep in 0..size) {
                var moved = false
                for (k in 0 until size - 1) moved = fix(k) or moved
                for (k in size - 2 downTo 0) moved = fix(k) or moved
                if (!moved) return
            }
        }

        private fun fix(k: Int): Boolean {
            val a = ahead[k]
            if (a > 0 && depth[k] <= depth[k + 1]) {
                depth[k] = depth[k + 1] + NUDGE
                return true
            }
            if (a < 0 && depth[k + 1] <= depth[k]) {
                depth[k + 1] = depth[k] + NUDGE
                return true
            }
            return false
        }
    }
}
