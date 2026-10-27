package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals

/** The track tool's line joins the track it meets with junctions. */
class JunctionTest {
    private val join = Command.Rail.JOIN

    /** Flat land with a line of company 0's along y = 10 from x 5 to 15, and a second company. */
    private fun world(): World {
        val w = World(64, 64, 1)
        for (k in w.corners.indices) w.corners[k] = 3
        w.companies += Company(1, "Other", 10_000_000)
        w.companies[0].money = 1_000_000
        w.apply(Command.Rail(0, 5, 10, 15, 10, Track.X, remove = false))
        return w
    }

    /** Applies [c] and checks it cost what [railCost] said. */
    private fun World.laid(c: Command.Rail) {
        val (cost, _) = railCost(c)
        val before = companies[c.company].money
        apply(c)
        assertEquals(cost, before - companies[c.company].money)
    }

    @Test
    fun aLineEndingOnALineJoinsItWithACurve() {
        val w = world()
        w.laid(Command.Rail(0, 10, 4, 10, 10, Track.Y, false, join or Command.Rail.END_X_LOW))
        assertEquals(Track.X or Track.N, w.trackAt(10, 10))
        w.laid(Command.Rail(0, 7, 4, 7, 10, Track.Y, false, join))
        assertEquals(Track.X or Track.E, w.trackAt(7, 10))
        // Starting on it the same.
        w.laid(Command.Rail(0, 12, 10, 12, 16, Track.Y, false, join or Command.Rail.START_X_LOW))
        assertEquals(Track.X or Track.W, w.trackAt(12, 10))
        // The planned junction is the one built.
        val c = Command.Rail(0, 14, 4, 14, 10, Track.Y, false, join)
        val plan = w.planJoins(c).single()
        w.apply(c)
        assertEquals(plan.to, w.trackAt(14, 10))
    }

    @Test
    fun aLineEndingNextToALineJoinsIt() {
        val w = world()
        w.laid(Command.Rail(0, 10, 4, 10, 9, Track.Y, false, join or Command.Rail.END_X_LOW))
        assertEquals(Track.Y, w.trackAt(10, 9))
        assertEquals(Track.X or Track.N, w.trackAt(10, 10))
        w.laid(Command.Rail(0, 8, 16, 8, 11, Track.Y, false, join))
        assertEquals(Track.X or Track.S, w.trackAt(8, 10))
    }

    @Test
    fun aStraightLineAcrossALineCrossesIt() {
        val w = world()
        w.laid(Command.Rail(0, 10, 4, 10, 16, Track.Y, false, join or Command.Rail.END_X_LOW or Command.Rail.START_X_LOW))
        assertEquals(Track.X or Track.Y, w.trackAt(10, 10))
        w.laid(Command.Rail(0, 12, 4, 12, 16, Track.Y, false))
        assertEquals(Track.X or Track.Y, w.trackAt(12, 10))
        // An end on the line with track going on past it crosses too.
        w.apply(Command.Rail(0, 8, 11, 8, 14, Track.Y, remove = false))
        w.laid(Command.Rail(0, 8, 4, 8, 10, Track.Y, false, join))
        assertEquals(Track.X or Track.Y, w.trackAt(8, 10))
    }

    @Test
    fun aJunctionIsMadeBesideATrain() {
        val w = world()
        w.tileTrains[w.index(10, 10)] = 1
        w.tileTrains[w.index(8, 10)] = 1
        w.laid(Command.Rail(0, 10, 4, 10, 10, Track.Y, false, join or Command.Rail.END_X_LOW))
        assertEquals(Track.X or Track.N, w.trackAt(10, 10))
        w.laid(Command.Rail(0, 8, 16, 8, 11, Track.Y, false, join))
        assertEquals(Track.X or Track.S, w.trackAt(8, 10))
        // Lifting takes nothing from under a train.
        w.laid(Command.Rail(0, 10, 4, 10, 9, Track.Y, true, join))
        assertEquals(Track.X or Track.N, w.trackAt(10, 10))
    }

    @Test
    fun aCurveTurnsToTheFingersSideOrWhereTheDragHeads() {
        val sx = Command.Rail.START_X_LOW
        val sy = Command.Rail.START_Y_LOW
        val ex = Command.Rail.END_X_LOW
        val ey = Command.Rail.END_Y_LOW
        // Off the middle, the side the finger's on.
        assertEquals(sx or ey, joinSides(-0.3f, 0.3f, 0.3f, -0.3f, 0.5f, 0.5f))
        // At the middle, the side the drag heads, either way.
        assertEquals(sx or sy or ex or ey, joinSides(0f, 0f, 0f, 0f, -0.2f, -6f))
        assertEquals(0, joinSides(0f, 0f, 0f, 0f, 0.2f, 6f))
        assertEquals(sx or ex, joinSides(0.05f, -0.05f, 0.05f, -0.05f, -0.2f, 6f))
        // With no drift at all, the finger's side.
        assertEquals(sy or ey, joinSides(0.05f, -0.05f, 0.05f, -0.05f, 0f, 0f))
    }

    @Test
    fun lineMeetingAtACornerStillMakesACurve() {
        val w = world()
        w.laid(Command.Rail(0, 15, 10, 15, 16, Track.Y, false, join or Command.Rail.START_X_LOW))
        assertEquals(Track.W, w.trackAt(15, 10))
    }

    @Test
    fun anEndTurnedIntoACornerAddsNothingPastIt() {
        val w = world()
        w.apply(Command.Rail(0, 5, 20, 10, 20, Track.X, remove = false))
        w.apply(Command.Rail(0, 5, 21, 15, 21, Track.X, remove = false))
        w.laid(Command.Rail(0, 10, 14, 10, 20, Track.Y, false, join))
        assertEquals(Track.N, w.trackAt(10, 20))
        assertEquals(Track.X, w.trackAt(10, 21))
    }

    @Test
    fun liftingALineTakesItsJunctionCurves() {
        val w = world()
        w.apply(Command.Rail(0, 10, 4, 10, 16, Track.Y, false, join))
        w.apply(Command.Rail(0, 7, 4, 7, 10, Track.Y, false, join))
        w.apply(Command.Rail(0, 12, 4, 12, 9, Track.Y, false, join))
        w.apply(Command.Rail(0, 14, 15, 14, 10, Track.Y, false, join))
        assertEquals(Track.X or Track.S, w.trackAt(14, 10))
        w.laid(Command.Rail(0, 10, 4, 10, 16, Track.Y, true, join))
        assertEquals(Track.X, w.trackAt(10, 10))
        w.laid(Command.Rail(0, 7, 4, 7, 10, Track.Y, true, join))
        assertEquals(Track.X, w.trackAt(7, 10))
        w.laid(Command.Rail(0, 12, 4, 12, 9, Track.Y, true, join))
        assertEquals(Track.X, w.trackAt(12, 10))
        // Lifting only part of a branch leaves its junction joined to what's left.
        w.laid(Command.Rail(0, 14, 15, 14, 13, Track.Y, true, join))
        assertEquals(Track.X or Track.S, w.trackAt(14, 10))
    }

    @Test
    fun whatALineCostsIsWhatItTakes() {
        val w = world()
        w.companies[0].money = 1_000_000_000
        for (y in intArrayOf(14, 18, 22, 26)) w.apply(Command.Rail(0, 2, y, 40, y, Track.X, remove = false))
        for (x in intArrayOf(20, 25, 30)) w.apply(Command.Rail(0, x, 12, x, 40, Track.Y, remove = false))
        val r = kotlin.random.Random(7)
        repeat(300) {
            val sx = r.nextInt(2, 40)
            val sy = r.nextInt(12, 40)
            val n = r.nextInt(1, 10) * (if (r.nextBoolean()) 1 else -1)
            val bits = join or (r.nextInt(16) shl 1)
            val remove = r.nextInt(4) == 0
            val c = when (r.nextInt(3)) {
                0 -> Command.Rail(0, sx, sy, (sx + n).coerceIn(0, 63), sy, Track.X, remove, bits)
                1 -> Command.Rail(0, sx, sy, sx, (sy + n).coerceIn(0, 63), Track.Y, remove, bits)
                else -> Command.Rail(0, sx, sy, sx + n, sy + n, if (n > 0) Track.E else Track.W, remove, bits)
            }
            if (w.railCost(c).first >= 0) w.laid(c)
        }
    }

    @Test
    fun rivalTrackIsLeftAlone() {
        val w = world()
        w.apply(Command.Rail(1, 5, 20, 15, 20, Track.X, remove = false))
        w.laid(Command.Rail(0, 10, 14, 10, 20, Track.Y, false, join))
        assertEquals(Track.X, w.trackAt(10, 20))
        w.laid(Command.Rail(0, 8, 14, 8, 19, Track.Y, false, join))
        assertEquals(Track.X, w.trackAt(8, 20))
        w.laid(Command.Rail(0, 12, 14, 12, 26, Track.Y, false, join))
        assertEquals(Track.X, w.trackAt(12, 20))
        assertEquals(1, w.owner[w.index(12, 20)].toInt())
    }

    @Test
    fun platformsAndSignalsAreLeftAlone() {
        val w = world()
        w.apply(Command.Station(0, 9, 10, 11, 10, Track.X))
        w.laid(Command.Rail(0, 10, 4, 10, 9, Track.Y, false, join))
        assertEquals(Track.X, w.trackAt(10, 10))
        w.laid(Command.Rail(0, 9, 4, 9, 10, Track.Y, false, join))
        assertEquals(Track.X, w.trackAt(9, 10))
        w.apply(Command.Signal(0, 6, 10, Track.X, Track.XP))
        w.laid(Command.Rail(0, 6, 4, 6, 9, Track.Y, false, join))
        assertEquals(Track.X, w.trackAt(6, 10))
        assertEquals(true, w.signals.containsKey(pieceKey(w.index(6, 10), Track.X)))
    }

    @Test
    fun aJoiningLineSurvivesTheCodec() {
        val c = Command.Rail(0, 1, 2, 1, 9, Track.Y, false, join or Command.Rail.END_Y_LOW)
        val w = ByteWriter()
        Codec.write(w, c)
        assertEquals(c, Codec.read(ByteReader(w.toByteArray())))
        val plain = Command.Rail(0, 1, 2, 1, 9, Track.Y, false)
        val v = ByteWriter()
        Codec.write(v, plain)
        assertEquals(plain, Codec.read(ByteReader(v.toByteArray())))
    }
}
