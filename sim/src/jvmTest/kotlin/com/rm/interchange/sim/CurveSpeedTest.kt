package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurveSpeedTest {
    private val tight = CURVE_TIGHT_KMH * 4096 * 16 / 2700
    private val gentle = CURVE_GENTLE_KMH * 4096 * 16 / 2700

    /** Flat land, a depot at (2, 10) and a station at x = 3 to 5 on a line east along y = 10 to x = 100. */
    private fun line(): World {
        val w = World(160, 64, 1, startYear = 1990)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 100, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 3, 10, 5, 10, Track.X))
        return w
    }

    private fun run(w: World): Train {
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:f40" }.id, w.roster.engines.first { it.key == "base:coach_streamline" }.id, 2))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        return w.trains[0]!!
    }

    /** Runs [t] till it stands at the second station, calling [each] after every tick. */
    private fun untilThere(w: World, t: Train, each: () -> Unit) {
        var there = false
        repeat(20_000) {
            if (there) return@repeat
            w.step()
            each()
            if (t.waiting > 0 && w.stationTiles[stepTile(t.steps.last())] == 1) there = true
        }
        assertTrue(there, "never got to the second station")
    }

    @Test
    fun aTrainSlowsForATight90AndKeepsToItTillItsTailIsThrough() {
        val w = line()
        w.apply(Command.Rail(0, 101, 10, 101, 10, Track.W, remove = false))
        w.apply(Command.Rail(0, 101, 11, 101, 50, Track.Y, remove = false))
        w.apply(Command.Station(0, 101, 44, 101, 47, Track.Y))
        val t = run(w)
        val bend = w.index(101, 10)
        var fastest = 0
        var onBend = 0
        var held = false
        var last = 0
        var drop = 0
        var after = 0
        untilThere(w, t) {
            val on = t.steps.any { stepTile(it) == bend }
            if (on) onBend = maxOf(onBend, t.speed)
            else if (stepTile(t.steps.last()) / w.width == 10) fastest = maxOf(fastest, t.speed)
            else after = maxOf(after, t.speed)
            if (t.curveKmh == CURVE_TIGHT_KMH) held = true
            if (t.waiting == 0 && last > tight) drop = maxOf(drop, last - t.speed)
            last = t.speed
        }
        assertTrue(fastest > gentle, "only got to $fastest before the bend")
        assertTrue(onBend in 1..tight + BRAKE, "$onBend on the bend against $tight")
        assertTrue(held, "never shown held by the curve")
        assertTrue(drop <= BRAKE + 2, "dropped $drop in one tick")
        assertTrue(after > tight + BRAKE, "only got back to $after past the bend")
    }

    @Test
    fun aTrainTakesA45GentlyAndALongDiagonalAtFullSpeed() {
        val w = line()
        // South-east from (101, 10) to (120, 30), then east again.
        for (k in 0 until 20) {
            w.apply(Command.Rail(0, 101 + k, 10 + k, 101 + k, 10 + k, Track.W, remove = false))
            w.apply(Command.Rail(0, 101 + k, 11 + k, 101 + k, 11 + k, Track.E, remove = false))
        }
        w.apply(Command.Rail(0, 121, 30, 150, 30, Track.X, remove = false))
        w.apply(Command.Station(0, 140, 30, 143, 30, Track.X))
        val t = run(w)
        val bend = w.index(101, 10)
        val before = w.index(100, 10)
        var fastest = 0
        var onBend = 0
        var diagonal = 0
        var last = 0
        var drop = 0
        untilThere(w, t) {
            val nose = stepTile(t.steps.last())
            // The bend is the edge between the two: the train is on it while it has a step either side.
            if (t.steps.any { stepTile(it) == bend } && t.steps.any { stepTile(it) == before }) onBend = maxOf(onBend, t.speed)
            else if (nose / w.width == 10) fastest = maxOf(fastest, t.speed)
            else if (t.steps.all { stepPiece(it) != Track.X && stepPiece(it) != Track.Y }) diagonal = maxOf(diagonal, t.speed)
            if (t.waiting == 0 && last > tight) drop = maxOf(drop, last - t.speed)
            last = t.speed
        }
        assertTrue(fastest > gentle + BRAKE, "only got to $fastest before the bend")
        assertTrue(onBend in tight + BRAKE..gentle + BRAKE, "$onBend on the bend against $gentle")
        assertTrue(diagonal > gentle + BRAKE, "only got to $diagonal along the diagonal")
        assertTrue(drop <= BRAKE + 2, "dropped $drop in one tick")
    }

    @Test
    fun anSBendIsGentleAndAStraightIsFree() {
        // East, a 45 down a tile and back east: a crossover.
        val steps = intArrayOf(step(0, 0, Track.XM), step(1, 5, Track.XM), step(2, 3, Track.YM), step(3, 0, Track.XM), step(4, 0, Track.XM))
        val s = CurveScan().reset(0, BRAKE)
        for (st in steps) s.add(stepHeading(st), CurveScan.UNDER)
        s.finish()
        assertEquals(CURVE_GENTLE_KMH, s.kmh)
        assertEquals(gentle, s.limit)
        // East then a diagonal then south: a 90 within a tile.
        val turn = CurveScan().reset(0, BRAKE)
        for (st in intArrayOf(step(0, 0, Track.XM), step(1, 5, Track.XM), step(2, 1, Track.YM))) turn.add(stepHeading(st), CurveScan.UNDER)
        turn.finish()
        assertEquals(CURVE_TIGHT_KMH, turn.kmh)
        val straight = CurveScan().reset(0, BRAKE)
        for (k in 0 until 6) straight.add(stepHeading(step(k, 0, Track.XM)), CurveScan.UNDER)
        straight.finish()
        assertEquals(Int.MAX_VALUE, straight.limit)
    }
}
