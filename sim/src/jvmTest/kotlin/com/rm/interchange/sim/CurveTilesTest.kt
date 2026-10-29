package com.rm.interchange.sim

import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurveTilesTest {
    private fun flat() = World(64, 64, 1).also { w ->
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = 1
        w.shoreChanged()
    }

    private fun lay(w: World, x: Int, y: Int, piece: Int) {
        val i = w.index(x, y)
        w.track[i] = (w.track[i].toInt() or piece).toByte()
    }

    private val d1 = Track.pieceOf(Track.XM, Track.YP)
    private val d2 = Track.pieceOf(Track.YM, Track.XP)

    /** A turnout eased back, a 90 degree turn and a crossover, round (10, 10). */
    private fun layout(w: World) {
        for (x in 1..14) lay(w, x, 5, Track.X)
        lay(w, 6, 5, d1)
        lay(w, 6, 6, d2)
        lay(w, 7, 6, d1)
        lay(w, 7, 7, d2)
        for (x in 2..5) lay(w, x, 12, Track.X)
        lay(w, 6, 12, d1)
        for (y in 13..16) lay(w, 6, y, Track.Y)
        for (x in 9..16) {
            lay(w, x, 20, Track.X)
            lay(w, x, 21, Track.X)
        }
        lay(w, 12, 20, d1)
        lay(w, 12, 21, Track.pieceOf(Track.YM, Track.XP))
    }

    @Test
    fun aDroppedWorldLetsGoOfItsCache() {
        fun make(): WeakReference<World> {
            val w = flat()
            layout(w)
            w.view()
            return WeakReference(w)
        }
        val ref = make()
        var tries = 0
        while (ref.get() != null && tries++ < 100) {
            System.gc()
            Thread.sleep(20)
        }
        assertNull(ref.get(), "the world is still held")
    }

    @Test
    fun aFoundationDrawsCurvedASlopeDoesNot() {
        val w = flat()
        // A diagonal on a slope sits on a foundation, level; a straight running up a slope lies on it.
        w.corners[w.corner(21, 10)] = 2
        lay(w, 20, 10, d1)
        w.corners[w.corner(31, 10)] = 2
        w.corners[w.corner(31, 11)] = 2
        lay(w, 30, 10, Track.X)
        assertTrue(w.hasFoundation(20, 10))
        val tiles = CurveTiles { w }
        assertTrue(tiles.curved(20, 10))
        assertFalse(tiles.curved(30, 10))
        assertFalse(tiles.curved(40, 10))
    }

    @Test
    fun checkingATileInAllTurnsLeavesItsKeysTheSame() {
        val w = flat()
        layout(w)
        val tiles = CurveTiles { w }
        try {
            CurveKeys.known = HashSet<String>().also { all -> for (t in 0..3) for (y in 0..30) for (x in 0..30) if (CurveTiles.levelRail(w, x, y)) all += tiles.keys(x, y, t) }
            val before = tiles.keys(12, 20, 1)
            assertTrue(tiles.curved(12, 20))
            assertEquals(before, tiles.keys(12, 20, 1))
        } finally {
            CurveKeys.known = null
        }
    }

    @Test
    fun aTileMissingASpriteInAnyTurnStaysStraight() {
        val w = flat()
        layout(w)
        val tiles = CurveTiles { w }
        val all = HashSet<String>()
        for (t in 0..3) for (y in 0..30) for (x in 0..30) if (CurveTiles.levelRail(w, x, y)) all += tiles.keys(x, y, t)
        val gone = tiles.keys(12, 20, 2).last()
        try {
            CurveKeys.known = all
            assertTrue(tiles.curved(12, 20))
            CurveKeys.known = all - gone
            assertFalse(tiles.curved(12, 20))
            // The cars follow: the piece runs edge to edge.
            val cc = CarCurves(w)
            val p = DoubleArray(4)
            val s = step(w.index(12, 20), Track.index(d1), Track.XM)
            cc.pointAt(listOf(s), stepLength(s) / 2, 0, -1, -1, p)
            assertEquals(12.25, p[0], 1e-3)
            assertEquals(20.75, p[1], 1e-3)
        } finally {
            CurveKeys.known = null
        }
    }

    @Test
    fun turnedKeysMatchTheWorldTurned() {
        val w = flat()
        layout(w)
        val tiles = CurveTiles { w }
        for (t in 1..3) {
            // The world as a view at quarter turn t sees it, laid out as a world of its own.
            val r = flat()
            for (y in 0 until 64) for (x in 0 until 64) {
                val m = w.trackAt(x, y)
                if (m == 0) continue
                var out = 0
                for (p in Track.PIECES) if (m and p != 0) {
                    val e = Track.edges(p)
                    out = out or Track.pieceOf(CurveTiles.viewEdge(t, e and 3), CurveTiles.viewEdge(t, e shr 2))
                }
                val (u, v) = turned(t, x, y)
                lay(r, u, v, out)
            }
            val rt = CurveTiles { r }
            for (y in 0..30) for (x in 0..30) {
                if (w.trackAt(x, y) == 0) continue
                val (u, v) = turned(t, x, y)
                assertEquals(rt.keys(u, v, 0), tiles.keys(x, y, t), "turn $t at $x, $y")
            }
        }
    }

    /** Where world tile ([x], [y]) is in a 64 by 64 view at quarter turn [t], as View has it. */
    private fun turned(t: Int, x: Int, y: Int) = when (t) {
        1 -> Pair(63 - y, x)
        2 -> Pair(63 - x, 63 - y)
        else -> Pair(y, 63 - x)
    }

    @Test
    fun theStepBehindIsKeptAcrossManyTicks() {
        val w = flat()
        for (x in 1..20) lay(w, x, 5, Track.X)
        val american = w.roster.engines.first { it.key == "base:american" }.id
        val coach = w.roster.engines.first { it.key == "base:coach_wood" }.id
        val t = Train(w.roster, 0, 0, american, coach, 1, 0)
        val cc = CarCurves(w)
        fun at(x: Int) = step(w.index(x, 5), Track.index(Track.X), Track.XM)
        t.steps.addAll((1..4).map { at(it) })
        cc.noteFirst(t)
        assertEquals(-1, cc.before(t))
        // Three steps drop off behind before the next view.
        t.steps.clear()
        t.steps.addAll((4..8).map { at(it) })
        cc.noteFirst(t)
        assertEquals(at(3), cc.before(t))
        t.steps.add(at(9))
        cc.noteFirst(t)
        assertEquals(at(3), cc.before(t))
        // Further than the train was long: not known.
        t.steps.clear()
        t.steps.addAll((15..18).map { at(it) })
        cc.noteFirst(t)
        assertEquals(-1, cc.before(t))
    }
}
