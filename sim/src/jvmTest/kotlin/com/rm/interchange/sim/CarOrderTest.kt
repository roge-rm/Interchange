package com.rm.interchange.sim

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarOrderTest {
    private val w = World(64, 64, 1).also { w ->
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = 1
        w.shoreChanged()
    }

    private fun lay(x: Int, y: Int, piece: Int) {
        val i = w.index(x, y)
        w.track[i] = (w.track[i].toInt() or piece).toByte()
    }

    private fun st(x: Int, y: Int, piece: Int, entry: Int) = step(w.index(x, y), Track.index(piece), entry)

    /** A 90 degree turn: straights along x into a quarter circle on (6, 5) and down y. */
    private fun turn(): List<Int> {
        for (x in 2..5) lay(x, 5, Track.X)
        lay(6, 5, Track.pieceOf(Track.XM, Track.YP))
        for (y in 6..12) lay(6, y, Track.Y)
        return (2..5).map { st(it, 5, Track.X, Track.XM) } + st(6, 5, Track.pieceOf(Track.XM, Track.YP), Track.XM) +
            (6..12).map { st(6, it, Track.Y, Track.YM) }
    }

    /** A world point in view terms for view turn [t], as the map's View has it on a 64 by 64 map. */
    private fun viewX(x: Float, y: Float, t: Int) = when (t) { 0 -> x; 1 -> 64 - y; 2 -> 64 - x; else -> y }
    private fun viewY(x: Float, y: Float, t: Int) = when (t) { 0 -> y; 1 -> x; 2 -> 64 - y; else -> 64 - x }

    /** A footprint's corners in view terms, going round. */
    private fun corners(x: Float, y: Float, a: Float, half: Float): List<Pair<Float, Float>> {
        val c = cos(a)
        val s = sin(a)
        val hw = CarOrder.HALF_WIDTH
        return listOf(1 to 1, 1 to -1, -1 to -1, -1 to 1).map { (i, j) -> (x + c * half * i - s * hw * j) to (y + s * half * i + c * hw * j) }
    }

    /** The nearest depth of a polygon on screen column [col], or null off it. */
    private fun nearOn(p: List<Pair<Float, Float>>, col: Float): Float? {
        var best: Float? = null
        for (k in p.indices) {
            val (ax, ay) = p[k]
            val (bx, by) = p[(k + 1) % p.size]
            val ca = ax - ay - col
            val cb = bx - by - col
            if (ca == cb || (ca > 0) == (cb > 0) && ca != 0f && cb != 0f) continue
            val t = ca / (ca - cb)
            val d = ax + ay + t * (bx + by - ax - ay)
            best = maxOf(best ?: d, d)
        }
        return best
    }

    /** How many of many columns show a in front of b, and b in front of a. */
    private fun shows(a: List<Pair<Float, Float>>, b: List<Pair<Float, Float>>): Pair<Int, Int> {
        val lo = maxOf(a.minOf { it.first - it.second }, b.minOf { it.first - it.second })
        val hi = minOf(a.maxOf { it.first - it.second }, b.maxOf { it.first - it.second })
        var wa = 0
        var wb = 0
        if (lo >= hi) return 0 to 0
        for (k in 0 until 200) {
            val col = lo + (hi - lo) * (k + 0.5f) / 200
            val na = nearOn(a, col) ?: continue
            val nb = nearOn(b, col) ?: continue
            if (na > nb) wa++ else if (nb > na) wb++
        }
        return wa to wb
    }

    @Test
    fun touchingCarsAtEverySixteenthNearerDrawsLast() {
        for (h in 0 until 16) {
            val a = h * PI.toFloat() / 8
            val half = 13 / 32f
            val ax = 10f
            val ay = 10f
            val bx = ax - cos(a) * 2 * half
            val by = ay - sin(a) * 2 * half
            val (wa, wb) = shows(corners(ax, ay, a, half), corners(bx, by, a, half))
            val ka = ax + ay + CarOrder.reach(half, a)
            val kb = bx + by + CarOrder.reach(half, a)
            if (wa > wb) assertTrue(ka > kb, "heading $h")
            if (wb > wa) assertTrue(kb > ka, "heading $h")
            assertEquals(if (wa > wb) 1 else if (wb > wa) -1 else 0, CarOrder.front(ax, ay, a, half, bx, by, a, half), "heading $h")
        }
    }

    @Test
    fun neighboursRoundATurnDrawInTheOrderTheyShow() {
        val steps = turn()
        val cc = CarCurves(w)
        // An engine, coaches and a short and a long car, in sixteenths.
        val lengths = intArrayOf(14, 13, 13, 4, 16, 9, 13)
        val total = steps.sumOf { stepLength(it) }
        val trainLength = lengths.sum() * FIX
        val out = FloatArray(3)
        val px = FloatArray(lengths.size)
        val py = FloatArray(lengths.size)
        val pa = FloatArray(lengths.size)
        val chain = CarOrder.Chain()
        var checked = 0
        var d = trainLength
        while (d <= total) {
            var left = d
            var n = 0
            while (left > stepLength(steps[n])) {
                left -= stepLength(steps[n])
                n++
            }
            val held = steps.subList(0, n + 1)
            var middle = 0
            for (c in lengths.indices) {
                middle += lengths[c] * FIX / 2
                cc.place(held, left, middle, lengths[c] * FIX / 2, -1, -1, out)
                middle += lengths[c] * FIX / 2
                px[c] = out[0]
                py[c] = out[1]
                pa[c] = out[2]
            }
            for (t in 0 until 4) {
                chain.clear()
                val vx = FloatArray(lengths.size)
                val vy = FloatArray(lengths.size)
                val va = FloatArray(lengths.size)
                for (c in lengths.indices) {
                    vx[c] = viewX(px[c], py[c], t)
                    vy[c] = viewY(px[c], py[c], t)
                    va[c] = (pa[c] + 2 * t) * PI.toFloat() / 4
                    chain.add(vx[c], vy[c], va[c], lengths[c] / 32f, vx[c] + vy[c] + CarOrder.reach(lengths[c] / 32f, va[c]))
                }
                chain.settle()
                for (c in 0 until lengths.size - 1) {
                    val (wa, wb) = shows(corners(vx[c], vy[c], va[c], lengths[c] / 32f), corners(vx[c + 1], vy[c + 1], va[c + 1], lengths[c + 1] / 32f))
                    // Only where one clearly shows more; an even split is a coin toss either way.
                    if (wa + wb < 10 || wa in wb * 2 / 3..wb * 3 / 2) continue
                    checked++
                    val first = chain.depth[c] > chain.depth[c + 1]
                    assertEquals(wa > wb, first, "cars $c and ${c + 1}, nose at $d, view turn $t: shows $wa to $wb")
                }
            }
            d += 32
        }
        assertTrue(checked > 100, "checked $checked")
    }

    @Test
    fun settlingMovesDepthsAsLittleAsItCan() {
        val chain = CarOrder.Chain()
        // A long car across the screen beside a short one in front of its end.
        chain.add(10f, 10f, 0f, 0.5f, 25f)
        chain.add(10f, 10.6f, (PI / 2).toFloat(), 0.2f, 20f)
        chain.settle()
        val d0 = chain.depth[0]
        val d1 = chain.depth[1]
        val f = CarOrder.front(10f, 10f, 0f, 0.5f, 10f, 10.6f, (PI / 2).toFloat(), 0.2f)
        if (f > 0) {
            assertEquals(25f, d0)
            assertEquals(20f, d1)
        } else {
            assertEquals(25f, d0)
            assertEquals(25f + CarOrder.NUDGE, d1)
        }
    }
}
