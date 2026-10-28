package com.rm.interchange.sim

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrackCurvesTest {
    private class Scene(val masks: MutableMap<Pair<Int, Int>, Int>, val flags: MutableMap<Pair<Int, Int>, Int>)

    /** One entry of curves_reference.txt, written by tools/blender/export_curves.py from curves.py. */
    private class Ref(val x: Int, val y: Int, val sim: Int, val via: Int, val from: Int, val to: Int, val ctrl: DoubleArray, val length: Double)

    private val scenes = LinkedHashMap<String, Scene>()
    private val refs = LinkedHashMap<String, ArrayList<Ref>>()

    private fun piece(c: String) = when (c) { "X" -> Track.X; "Y" -> Track.Y; "N" -> Track.N; "E" -> Track.E; "S" -> Track.S; "W" -> Track.W; else -> 0 }

    init {
        var name = ""
        val text = TrackCurvesTest::class.java.getResourceAsStream("/curves_reference.txt")!!.bufferedReader().readText()
        for (line in text.lines()) {
            val w = line.split(" ")
            when (w[0]) {
                "#" -> {
                    name = w[1]
                    scenes[name] = Scene(HashMap(), HashMap())
                    refs[name] = ArrayList()
                }
                "T" -> {
                    scenes.getValue(name).masks[w[1].toInt() to w[2].toInt()] = w[3].toInt()
                    scenes.getValue(name).flags[w[1].toInt() to w[2].toInt()] = w[4].toInt()
                }
                "C" -> refs.getValue(name) += Ref(
                    w[1].toInt(), w[2].toInt(), piece(w[3]), piece(w[4]), w[5].toInt(), w[6].toInt(),
                    DoubleArray(8) { w[7 + it].toDouble() }, w[15].toDouble(),
                )
            }
        }
    }

    private fun curvesOf(s: Scene) = TrackCurves({ x, y -> s.masks[x to y] ?: 0 }, { x, y -> s.flags[x to y] ?: 0 })

    private fun copy(s: Scene) = Scene(HashMap(s.masks), HashMap(s.flags))

    private fun scene(name: String) = scenes.getValue(name)

    private fun TrackCurves.all(s: Scene) = s.masks.filterValues { it != 0 }.keys.sortedWith(compareBy({ it.first }, { it.second }))
        .flatMap { (x, y) -> curvesAt(x, y).map { Triple(x, y, it) } }

    private fun mid(edge: Int) = doubleArrayOf(Track.edgeX4(edge) / 4.0, Track.edgeY4(edge) / 4.0)

    /** A curve's pose at [edge] in world terms, with the direction away from the edge into the curve. */
    private fun worldPose(x: Int, y: Int, c: TileCurve, edge: Int): CurvePose {
        val p = c.fromEdge(edge, 0.0)
        return CurvePose(p.x + x, p.y + y, p.dx, p.dy)
    }

    @Test
    fun matchesCurvesPy() {
        var curves = 0
        for ((name, list) in refs) {
            val tc = curvesOf(scene(name))
            val byTile = list.groupBy { it.x to it.y }
            for ((tile, expected) in byTile) {
                val got = tc.curvesAt(tile.first, tile.second)
                assertEquals(expected.size, got.size, "$name tile $tile curve count")
                for ((e, g) in expected.zip(got)) {
                    val at = "$name tile $tile piece ${e.sim}"
                    assertEquals(e.sim, g.sim, at)
                    assertEquals(e.via, g.via, at)
                    assertEquals(e.from, g.from, at)
                    assertEquals(e.to, g.to, at)
                    for (i in 0 until 8) assertEquals(e.ctrl[i], g.ctrl[i], 2e-9, "$at ctrl $i")
                    assertEquals(e.length, g.length, 2e-9, "$at length")
                    curves++
                }
            }
        }
        assertTrue(curves > 500, "$curves curves compared")
    }

    @Test
    fun endsAreOnEdgeMiddlesExceptAcrossEasedSpans() {
        var off = 0
        for ((name, s) in scenes) {
            val tc = curvesOf(s)
            for ((x, y, c) in tc.all(s)) {
                for (edge in intArrayOf(c.from, c.to)) {
                    val p = c.fromEdge(edge, 0.0)
                    val m = mid(edge)
                    val d = hypot(p.x - m[0], p.y - m[1])
                    if (d < 1e-9) continue
                    // Off centre only on an eased span, and by less than a third of a tile.
                    assertTrue(c.kind != TrackCurves.PLAIN, "$name ($x, $y) plain curve off its edge middle")
                    assertTrue(d < 0.3, "$name ($x, $y) off by $d")
                    off++
                }
            }
        }
        assertTrue(off > 0)
    }

    @Test
    fun neighboursAgreeAcrossEverySharedEdge() {
        var pairs = 0
        for ((name, s) in scenes) {
            val tc = curvesOf(s)
            fun meets(x: Int, y: Int, c: TileCurve, edge: Int): Boolean {
                val nx = x + Track.dx(edge)
                val ny = y + Track.dy(edge)
                val a = worldPose(x, y, c, edge)
                return tc.curvesAt(nx, ny).any { n ->
                    (n.from == Track.opposite(edge) || n.to == Track.opposite(edge)) && worldPose(nx, ny, n, Track.opposite(edge)).let { b ->
                        hypot(a.x - b.x, a.y - b.y) < 1e-6 && a.dx * b.dx + a.dy * b.dy < -1 + 1e-6
                    }
                }
            }
            for ((x, y, c) in tc.all(s)) {
                for (edge in intArrayOf(c.from, c.to)) {
                    val there = tc.curvesAt(x + Track.dx(edge), y + Track.dy(edge))
                        .any { it.from == Track.opposite(edge) || it.to == Track.opposite(edge) }
                    if (!there) continue
                    // A branch of a switch leaves at its own angle; the route it branches from has to meet the other side.
                    val ok = tc.curvesAt(x, y).any { o -> (o.from == edge || o.to == edge) && meets(x, y, o, edge) }
                    assertTrue(ok, "$name ($x, $y) piece ${c.sim} edge $edge has no partner")
                    if (meets(x, y, c, edge)) pairs++
                }
            }
        }
        assertTrue(pairs > 500, "$pairs edges compared")
    }

    @Test
    fun straightsStayStraightAndATurnoutsThroughRouteIsDead() {
        for ((name, s) in scenes) {
            val tc = curvesOf(s)
            for ((x, y, c) in tc.all(s)) {
                if (c.sim != Track.X && c.sim != Track.Y || c.via != 0) continue
                val axis = if (c.sim == Track.X) 1 else 0
                for (k in 0 until 4) assertEquals(0.5, c.ctrl[k * 2 + axis], 1e-12, "$name ($x, $y) straight bends")
                assertEquals(1.0, c.length, 1e-9)
            }
        }
        val s = scene("turnout")
        val tc = curvesOf(s)
        val through = tc.curveOf(1, 0, Track.X)!!
        assertEquals(1.0, through.length, 1e-9)
        assertEquals(TrackCurves.PLAIN, through.kind)
        // The branch leaves the points at the far edge of the tile before the switch.
        assertNotNull(tc.branchAt(0, 0, Track.X, Track.W))
        assertEquals(TrackCurves.BACK, tc.curveOf(1, 0, Track.W)!!.kind)
    }

    @Test
    fun noBackShapeSwingsPastFortyFiveDegrees() {
        var checked = 0
        for ((name, s) in scenes) {
            val tc = curvesOf(s)
            for ((x, y, c) in tc.all(s)) {
                if (c.kind != TrackCurves.BACK) continue
                // Angle off the straight's axis: x for X, y for Y.
                val straightTile = c.via != 0
                val straightPiece = if (straightTile) c.sim else if (c.from == Track.XM || c.from == Track.XP) Track.X else Track.Y
                for (i in 0..20) {
                    val p = c.atT(i / 20.0)
                    val along = if (straightPiece == Track.X) p.dx else p.dy
                    val across = if (straightPiece == Track.X) p.dy else p.dx
                    assertTrue(abs(across) <= abs(along) + 1e-6, "$name ($x, $y) swings past 45 degrees at ${i / 20.0}")
                }
                checked++
            }
        }
        assertTrue(checked > 4)
    }

    @Test
    fun cornersTurnNinetyDegreesWithoutOvershoot() {
        val s = scene("corner")
        val tc = curvesOf(s)
        val corners = tc.all(s).filter { it.third.kind == TrackCurves.CORNER }
        assertTrue(corners.isNotEmpty())
        for ((x, y, c) in corners) {
            val a = worldPose(x, y, c, c.from)
            for (i in 0..20) {
                val p = c.atT(i / 20.0)
                val turned = acos((p.dx * a.dx + p.dy * a.dy).coerceIn(-1.0, 1.0))
                assertTrue(turned <= Math.PI / 2 + 1e-6)
            }
        }
    }

    private fun kindsAt(s: Scene, x: Int, y: Int, piece: Int) = curvesOf(s).curvesAt(x, y).first { it.sim == piece && it.via == 0 }

    @Test
    fun fallsBackToTheHookWhenTheTilesAreNotClear() {
        val base = scene("turnout")
        val eased = kindsAt(base, 1, 0, Track.W)
        assertEquals(TrackCurves.BACK, eased.kind)
        assertEquals("EE", eased.looks)

        fun hook(s: Scene) {
            val tc = curvesOf(s)
            val w = tc.curveOf(1, 0, Track.W)!!
            assertEquals(TrackCurves.PLAIN, w.kind)
            assertEquals("TD", w.looks)
            assertNull(tc.branchAt(0, 0, Track.X, Track.W))
        }
        // A signal on the straight tile.
        copy(base).also { it.flags[0 to 0] = TrackCurves.SIGNAL; hook(it) }
        // A slope on the switch tile.
        copy(base).also { it.flags[1 to 0] = TrackCurves.SLOPE; hook(it) }
        // A level crossing, a platform, a depot and a bridge end each keep it off too.
        for (f in intArrayOf(TrackCurves.CROSSING, TrackCurves.PLATFORM, TrackCurves.DEPOT, TrackCurves.BRIDGE, TrackCurves.TUNNEL)) {
            copy(base).also { it.flags[0 to 0] = f; hook(it) }
        }
        // A parallel diagonal beside the straight on the tile before the switch.
        copy(base).also { it.masks[0 to 0] = Track.X or Track.N; hook(it) }
        // Another piece in the switch tile that doesn't leave by the switch's edge.
        copy(base).also { it.masks[1 to 0] = Track.X or Track.W or Track.S; hook(it) }
        // A second turnout right behind it.
        val ladder = scene("ladder")
        val tc = curvesOf(ladder)
        assertEquals(TrackCurves.PLAIN, tc.curveOf(1, 0, Track.W)!!.kind)
        assertEquals("TD", tc.curveOf(1, 0, Track.W)!!.looks)
    }

    @Test
    fun aPlainDiagonalEasesIntoItsStraight() {
        val s = scene("ease")
        val tc = curvesOf(s)
        val into = tc.curvesAt(2, 2)
        assertEquals(1, into.size)
        assertEquals(TrackCurves.BACK, into[0].kind)
        assertEquals(Track.X, into[0].sim)
        assertTrue(into[0].via != 0)
    }

    @Test
    fun distanceAlongACurve() {
        val tc = curvesOf(scene("turn"))
        val quarter = tc.curveOf(2, 0, Track.W)!!
        assertEquals("TT", quarter.looks)
        // A quarter circle of half a tile radius.
        assertEquals(Math.PI / 4, quarter.length, 0.002)
        val a = quarter.at(0.0)
        assertEquals(0.0, a.x, 1e-9)
        assertEquals(0.5, a.y, 1e-9)
        assertEquals(1.0, a.dx, 1e-9)
        val z = quarter.at(99.0)
        assertEquals(0.5, z.x, 1e-9)
        assertEquals(1.0, z.y, 1e-9)
        assertEquals(1.0, z.dy, 1e-9)
        val back = quarter.fromEdge(quarter.to, 0.0)
        assertEquals(-1.0, back.dy, 1e-9)
        val m = quarter.at(quarter.length / 2)
        assertEquals(hypot(m.dx, m.dy), 1.0, 1e-9)
        assertEquals(m.dx, m.dy, 0.01)
        assertEquals(2, m.heading16)
        val straight = tc.curveOf(0, 0, Track.X)!!
        assertEquals(0.25, straight.at(0.25).x, 1e-9)
    }

    @Test
    fun headingsGoRoundInSixteenths() {
        assertEquals(0, TrackCurves.heading16(1.0, 0.0))
        assertEquals(2, TrackCurves.heading16(1.0, 1.0))
        assertEquals(4, TrackCurves.heading16(0.0, 1.0))
        assertEquals(8, TrackCurves.heading16(-1.0, 0.0))
        assertEquals(12, TrackCurves.heading16(0.0, -1.0))
        assertEquals(14, TrackCurves.heading16(1.0, -1.0))
        assertEquals(15, TrackCurves.heading16(1.0, -0.4))
    }

    @Test
    fun cacheDropsTilesWithinTwoOfAChange() {
        val s = copy(scene("turnout"))
        val tc = curvesOf(s)
        val near = tc.curvesAt(1, 0)
        val far = tc.curvesAt(3, 0)
        assertSame(near, tc.curvesAt(1, 0))
        s.flags[0 to 0] = TrackCurves.SIGNAL
        // Stale until told.
        assertSame(near, tc.curvesAt(1, 0))
        tc.invalidate(0, 0)
        assertNotSame(near, tc.curvesAt(1, 0))
        assertEquals("TD", tc.curveOf(1, 0, Track.W)!!.looks)
        // Three tiles away isn't read by (0, 0), so it stays.
        assertSame(far, tc.curvesAt(3, 0))
    }

    @Test
    fun readsFlagsFromAWorld() {
        val w = World(64, 64, 1)
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = 1
        w.shoreChanged()
        w.track[w.index(5, 5)] = Track.X.toByte()
        w.track[w.index(6, 5)] = (Track.X or Track.W).toByte()
        w.track[w.index(6, 6)] = Track.E.toByte()
        val tc = TrackCurves.of(w)
        assertEquals(TrackCurves.BACK, tc.curveOf(6, 5, Track.W)!!.kind)
        assertNotNull(tc.branchAt(5, 5, Track.X, Track.W))
        w.corners[w.corner(5, 5)] = 2
        assertTrue(TrackCurves.flagsOf(w, 5, 5) and TrackCurves.SLOPE != 0)
        tc.invalidateAll()
        assertEquals(TrackCurves.PLAIN, tc.curveOf(6, 5, Track.W)!!.kind)
    }
}
