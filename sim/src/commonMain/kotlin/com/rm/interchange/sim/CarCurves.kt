package com.rm.interchange.sim

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/** Drawing caches kept per world, outside it, so they never touch game state. A world's entry goes when the world does. */
expect object DrawCache {
    fun carCurves(world: World): CarCurves
}

/*
 * Where train cars are drawn on the curved track (docs/CURVES_PLAN.md, Trains). Drawing only: it reads the steps and
 * the track, and nothing it works out goes back into the game.
 *
 * Each car sits on two bogie points along the drawn line, its middle plus and minus [BOGIES] of half its length,
 * measured along the train's steps. A point a share of the way along a step is that share of the way along the step's
 * drawn curve. The body is drawn halfway between the points and turned to the line between them. Tiles drawn with
 * the old straight art ([CurveTiles]: slopes, level crossings, depots, platforms, bridges and tunnels) keep the
 * straight line edge to edge, which is where their neighbours' curves end too.
 *
 * Used from the game thread only, one world at a time, inside [using] once a frame.
 */
class CarCurves(world: World) {
    /** The world while a view is being made ([using]), so the cache keeps no dropped world between views. */
    private var bound: World? = world
    private val world: World get() = bound ?: error("CarCurves used outside using()")
    private val width = world.width
    private val height = world.height

    private val tiles = CurveTiles { this.world }
    val curves = tiles.curves

    /** Each tile's curves, filled from [curves] as cars reach it; [OLD_ART] for the old straight lines. */
    private val lines = arrayOfNulls<List<TileCurve>>(width * height)
    private val watch = BlockWatch(world)
    private var known = CurveKeys.known

    /** By train id: its steps when last seen, how many, and the step before the first, for a rear bogie on a turnout's straight. */
    private var lasts = arrayOfNulls<IntArray>(0)
    private var lastSizes = IntArray(0)
    private var befores = IntArray(0)

    private val front = DoubleArray(4)
    private val rear = DoubleArray(4)

    /** Runs [block] with this bound to [world], brought up to date, and lets go of the world after. */
    fun <T> using(world: World, block: () -> T): T {
        bound = world
        try {
            refresh()
            return block()
        } finally {
            bound = null
        }
    }

    /** Drops the curves of tiles near any that changed since the last call. */
    fun refresh() {
        if (CurveKeys.known !== known) {
            known = CurveKeys.known
            lines.fill(null)
        }
        watch.forChanged(world) { x0, y0, x1, y1 ->
            tiles.invalidate(x0, y0, x1, y1)
            val r = CurveTiles.REACH
            for (y in maxOf(0, y0 - r)..minOf(height - 1, y1 + r)) for (x in maxOf(0, x0 - r)..minOf(width - 1, x1 + r)) lines[y * width + x] = null
        }
    }

    /**
     * Notes train [t]'s steps. When its first step has moved on, the step before the new first is found in the steps
     * last seen, however many dropped off in between, as long as the train hasn't run its whole length since.
     */
    fun noteFirst(t: Train) {
        if (t.id >= lastSizes.size) {
            val n = maxOf(t.id + 1, lastSizes.size * 2, 16)
            lasts = lasts.copyOf(n)
            lastSizes = lastSizes.copyOf(n)
            befores = befores.copyOf(n).also { for (i in befores.size until n) it[i] = -1 }
        }
        val steps = t.steps
        if (steps.isEmpty()) return
        val first = steps[0]
        val old = lasts[t.id]
        val n = lastSizes[t.id]
        if (old == null || n == 0 || old[0] != first) {
            var b = -1
            if (old != null) for (j in 1 until n) if (old[j] == first && leadsInto(old[j - 1], first)) {
                b = old[j - 1]
                break
            }
            befores[t.id] = b
        }
        var keep = old
        if (keep == null || keep.size < steps.size) keep = IntArray(maxOf(steps.size, 16, (keep?.size ?: 0) * 2))
        for (i in steps.indices) keep[i] = steps[i]
        lasts[t.id] = keep
        lastSizes[t.id] = steps.size
    }

    /** The step before train [t]'s first, or -1 if not known. */
    fun before(t: Train): Int {
        if (t.id >= lastSizes.size || t.steps.isEmpty() || lastSizes[t.id] == 0) return -1
        return if (lasts[t.id]!![0] == t.steps[0]) befores[t.id] else -1
    }

    /** The step train [t] takes after its nose's, if it matters for drawing (its nose is on a turnout's straight), else -1. */
    fun ahead(t: Train): Int {
        if (t.steps.isEmpty()) return -1
        val last = t.steps.last()
        if (!hasBranch(last)) return -1
        val r = t.route
        return if (r != null && t.routeAt < r.size) r[t.routeAt] else world.onlyWayOn(last)
    }

    /**
     * Places a car whose middle is [middle] behind the nose of a train on [steps] with its nose [nose] along the
     * last, the car [half] long each side, all in step units. [ahead] is the step after the last and [before] the
     * one before the first, or -1. Writes x, y in tiles and the angle in eighths, 0 along x on round towards y,
     * facing the nose, into [out].
     */
    fun place(steps: List<Int>, nose: Int, middle: Int, half: Int, ahead: Int, before: Int, out: FloatArray) {
        val off = half * BOGIES_TENTHS / 10
        pointAt(steps, nose, middle - off, ahead, before, front)
        pointAt(steps, nose, middle + off, ahead, before, rear)
        val dx = front[0] - rear[0]
        val dy = front[1] - rear[1]
        out[0] = ((front[0] + rear[0]) / 2).toFloat()
        out[1] = ((front[1] + rear[1]) / 2).toFloat()
        val a = if (hypot(dx, dy) > 1e-6) atan2(dy, dx) else atan2(front[3], front[2])
        out[2] = (((a / (PI / 4)) % 8 + 8) % 8).toFloat()
    }

    /** The point [d] behind the nose, into [out] as x, y, dx, dy. Past the first step it runs on along its line. */
    internal fun pointAt(steps: List<Int>, nose: Int, d: Int, ahead: Int, before: Int, out: DoubleArray) {
        var left = d
        var i = steps.size - 1
        var along = nose
        while (left > along && i > 0) {
            left -= along
            i--
            along = stepLength(steps[i])
        }
        along -= left
        val s = steps[i]
        val tile = stepTile(s)
        val tx = tile % width
        val ty = tile / width
        val entry = stepEntry(s)
        val share = along.toDouble() / stepLength(s)
        val curve = lineOf(steps, i, ahead, before)
        if (curve == null) {
            val exit = stepExit(s)
            val ax = Track.edgeX4(entry) / 4.0
            val ay = Track.edgeY4(entry) / 4.0
            val bx = Track.edgeX4(exit) / 4.0
            val by = Track.edgeY4(exit) / 4.0
            val n = hypot(bx - ax, by - ay)
            out[0] = tx + ax + (bx - ax) * share
            out[1] = ty + ay + (by - ay) * share
            out[2] = (bx - ax) / n
            out[3] = (by - ay) / n
        } else {
            curve.poseInto(entry, share * curve.length, out)
            out[0] += tx
            out[1] += ty
        }
    }

    /** The curves drawn on [tile], or [OLD_ART]. */
    private fun linesAt(tile: Int): List<TileCurve> {
        lines[tile]?.let { return it }
        val x = tile % width
        val y = tile / width
        return (if (tiles.curved(x, y)) curves.curvesAt(x, y) else OLD_ART).also { lines[tile] = it }
    }

    /** Whether step [s] is a straight beside an eased turnout, with a branch line on it. */
    private fun hasBranch(s: Int): Boolean {
        if (stepLevel(s) != 0) return false
        val piece = stepPiece(s)
        for (c in linesAt(stepTile(s))) if (c.sim == piece && c.via != 0) return true
        return false
    }

    /**
     * The curve step [i] runs along, or null for a straight edge to edge. On a straight before an eased turnout it's
     * the branch's line when the step on past the switch end is the branch, else the through line.
     */
    private fun lineOf(steps: List<Int>, i: Int, ahead: Int, before: Int): TileCurve? {
        val s = steps[i]
        if (stepLevel(s) != 0) return null
        val list = linesAt(stepTile(s))
        if (list === OLD_ART) return null
        val piece = stepPiece(s)
        var through: TileCurve? = null
        var branch: TileCurve? = null
        for (c in list) if (c.sim == piece) {
            if (c.via == 0) through = c else if (branch == null) branch = c
        }
        if (branch == null) return through
        val toward = branch.to
        val next = when (toward) {
            stepExit(s) -> if (i + 1 < steps.size) steps[i + 1] else ahead
            stepEntry(s) -> if (i > 0) steps[i - 1] else before
            else -> -1
        }
        if (next >= 0 && stepLevel(next) == 0 && stepTile(next) == stepTile(s) + Track.dx(toward) + Track.dy(toward) * width) {
            val via = stepPiece(next)
            for (c in list) if (c.sim == piece && c.via == via) return c
        }
        return through ?: branch
    }

    /** Whether step [a] leads straight into step [b]: [a]'s tile is next to [b]'s at [b]'s entry edge. */
    private fun leadsInto(a: Int, b: Int): Boolean {
        val e = stepEntry(b)
        return stepLevel(a) == stepLevel(b) && stepExit(a) == Track.opposite(e) &&
            stepTile(a) == stepTile(b) + Track.dx(e) + Track.dy(e) * width
    }

    companion object {
        /** Bogie points sit this many tenths of half the car's length either side of its middle. */
        const val BOGIES_TENTHS = 7
        private val OLD_ART: List<TileCurve> = ArrayList(0)
    }
}
