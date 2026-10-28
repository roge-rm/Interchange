package com.rm.interchange.sim

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CarCurvesTest {
    private val w = World(64, 64, 1).also { w ->
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = 1
        w.shoreChanged()
    }

    private fun lay(x: Int, y: Int, piece: Int) {
        val i = w.index(x, y)
        w.track[i] = (w.track[i].toInt() or piece).toByte()
    }

    private fun st(x: Int, y: Int, piece: Int, entry: Int) = step(w.index(x, y), Track.index(piece), entry)

    private fun total(steps: List<Int>) = steps.sumOf { stepLength(it) }

    /** Half the length of an 8 sixteenths car, in step units. */
    private val half = 8 * FIX / 2

    /** A 90 degree turn: straights along x into a quarter circle on (6, 5) and down y. */
    private fun turn(): List<Int> {
        for (x in 2..5) lay(x, 5, Track.X)
        lay(6, 5, Track.pieceOf(Track.XM, Track.YP))
        for (y in 6..9) lay(6, y, Track.Y)
        return (2..5).map { st(it, 5, Track.X, Track.XM) } + st(6, 5, Track.pieceOf(Track.XM, Track.YP), Track.XM) +
            (6..9).map { st(6, it, Track.Y, Track.YM) }
    }

    /** An eased turnout: straight along y = 5, the switch on (6, 5) and the branch down a diagonal line. */
    private fun turnout(branch: Boolean): List<Int> {
        for (x in 1..10) lay(x, 5, Track.X)
        val d1 = Track.pieceOf(Track.XM, Track.YP)
        val d2 = Track.pieceOf(Track.YM, Track.XP)
        lay(6, 5, d1)
        lay(6, 6, d2)
        lay(7, 6, d1)
        lay(7, 7, d2)
        lay(8, 7, d1)
        val lead = (1..5).map { st(it, 5, Track.X, Track.XM) }
        return if (branch) lead + listOf(st(6, 5, d1, Track.XM), st(6, 6, d2, Track.YM), st(7, 6, d1, Track.XM), st(7, 7, d2, Track.YM))
        else lead + (6..10).map { st(it, 5, Track.X, Track.XM) }
    }

    @Test
    fun aStraightLineIsWhereItWas() {
        val steps = (2..9).map { st(it, 5, Track.X, Track.XM) }
        for (x in 2..9) lay(x, 5, Track.X)
        val cc = CarCurves(w)
        val out = FloatArray(3)
        for (middle in listOf(half, 3000, 5000, 9000)) {
            val nose = 1000
            cc.place(steps, nose, middle, half, -1, -1, out)
            // Old placement: the middle lerped along its step.
            val fromStart = total(steps) - stepLength(steps.last()) + nose - middle
            assertEquals(2 + fromStart / 4096.0, out[0].toDouble(), 1e-4)
            assertEquals(5.5, out[1].toDouble(), 1e-6)
            assertEquals(0.0, out[2].toDouble(), 1e-6)
        }
    }

    @Test
    fun bogiesSitOnTheCurveAndTheBodySwingsIn() {
        val steps = turn()
        val cc = CarCurves(w)
        // The car's middle halfway round the quarter circle, both bogies on it.
        val diag = 4
        val p = DoubleArray(4)
        val off = half * CarCurves.BOGIES_TENTHS / 10
        val out = FloatArray(3)
        val fullNose = stepLength(steps[diag + 1]) / 2
        val train = steps.subList(0, diag + 2)
        val middle = fullNose + stepLength(steps[diag]) / 2
        for (d in listOf(middle - off, middle + off)) {
            cc.pointAt(train, fullNose, d, -1, -1, p)
            assertEquals(0.5, hypot(p[0] - 6, p[1] - 6), 1e-3)
        }
        cc.place(train, fullNose, middle, half, -1, -1, out)
        // Halfway round: facing 45 degrees, its middle inside the arc.
        assertEquals(1.0, out[2].toDouble(), 1e-3)
        val r = hypot(out[0] - 6.0, out[1] - 6.0)
        assertTrue(r < 0.49, "middle at $r from the centre")
        assertEquals(out[0] - 6.0, -(out[1] - 6.0), 1e-3)
    }

    /** Runs a car along [steps] in small moves and checks it never jumps or turns suddenly. */
    private fun assertSmooth(cc: CarCurves, steps: List<Int>, ahead: (List<Int>) -> Int = { -1 }) {
        val out = FloatArray(3)
        var px = Float.NaN
        var py = 0f
        var pa = 0f
        val move = 32
        var d = 2 * half
        while (d <= total(steps)) {
            // The steps the nose has reached, as a train holds them.
            var left = d
            var n = 0
            while (left > stepLength(steps[n])) {
                left -= stepLength(steps[n])
                n++
            }
            val held = steps.subList(0, n + 1)
            cc.place(held, left, half, half, if (n + 1 < steps.size) ahead(steps.subList(0, n + 2)) else -1, -1, out)
            if (!px.isNaN()) {
                val jump = hypot(out[0] - px, out[1] - py)
                assertTrue(jump < 0.02, "jumped $jump at $d")
                var turn = abs(out[2] - pa)
                if (turn > 4) turn = 8 - turn
                assertTrue(turn < 0.08, "turned $turn eighths at $d")
            }
            px = out[0]
            py = out[1]
            pa = out[2]
            d += move
        }
    }

    @Test
    fun smoothRoundATurn() {
        val steps = turn()
        assertSmooth(CarCurves(w), steps)
    }

    @Test
    fun aTurnoutsBranchTakesItsLineOnTheTileBefore() {
        val steps = turnout(true)
        val cc = CarCurves(w)
        assertNotNull(cc.curves.branchAt(5, 5, Track.X, Track.pieceOf(Track.XM, Track.YP)))
        val p = DoubleArray(4)
        // Near the far end of (5, 5): on the branch's line when the next step is the branch, off y = 5.5.
        cc.pointAt(steps.subList(0, 6), stepLength(steps[5]), stepLength(steps[5]) + 512, -1, -1, p)
        assertTrue(p[1] > 5.55, "branch at ${p[1]}")
        // The same point with the nose still on (5, 5): known from the step ahead.
        cc.pointAt(steps.subList(0, 5), 4096, 512, steps[5], -1, p)
        assertTrue(p[1] > 5.55, "branch ahead at ${p[1]}")
        // The through route stays on y = 5.5.
        val through = turnout(false)
        cc.pointAt(through.subList(0, 6), 4096, 4096 + 512, -1, -1, p)
        assertEquals(5.5, p[1], 1e-9)
        cc.pointAt(through.subList(0, 5), 4096, 512, through[5], -1, p)
        assertEquals(5.5, p[1], 1e-9)
    }

    @Test
    fun aTrainOffTheBranchKeepsItsLineAfterTheStepDrops() {
        turnout(true)
        val cc = CarCurves(w)
        val d1 = Track.pieceOf(Track.XM, Track.YP)
        // Going west, off the diagonal onto (5, 5): the diagonal step is gone, known as the one before.
        val steps = listOf(st(5, 5, Track.X, Track.XP), st(4, 5, Track.X, Track.XP))
        val p = DoubleArray(4)
        cc.pointAt(steps, 2048, 2048 + 3584, -1, st(6, 5, d1, Track.YP), p)
        assertTrue(p[1] > 5.55, "branch at ${p[1]}")
    }

    @Test
    fun smoothThroughAnEasedTurnout() {
        val cc = CarCurves(w)
        val branch = turnout(true)
        assertSmooth(cc, branch) { it.last() }
        assertSmooth(cc, turnout(false)) { it.last() }
    }

    @Test
    fun smoothIntoAnEasedDiagonal() {
        // A plain diagonal line eased into its straight, no turnout.
        for (x in 1..5) lay(x, 5, Track.X)
        val d1 = Track.pieceOf(Track.XM, Track.YP)
        val d2 = Track.pieceOf(Track.YM, Track.XP)
        lay(6, 5, d1)
        lay(6, 6, d2)
        lay(7, 6, d1)
        lay(7, 7, d2)
        val cc = CarCurves(w)
        val c = cc.curves.curvesAt(5, 5)
        assertEquals(1, c.size)
        assertEquals(TrackCurves.BACK, c[0].kind)
        val steps = (1..5).map { st(it, 5, Track.X, Track.XM) } + listOf(st(6, 5, d1, Track.XM), st(6, 6, d2, Track.YM), st(7, 6, d1, Track.XM), st(7, 7, d2, Track.YM))
        assertSmooth(cc, steps)
    }

    @Test
    fun oldArtTilesKeepTheirStraightLine() {
        val steps = turn()
        // A level crossing beside the turn: its straight stays edge to edge, and the turn still joins it smoothly.
        w.road[w.index(5, 5)] = 1
        val cc = CarCurves(w)
        assertSmooth(cc, steps)
    }
}
