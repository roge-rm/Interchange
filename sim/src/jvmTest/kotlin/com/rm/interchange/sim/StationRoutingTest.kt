package com.rm.interchange.sim

import com.rm.interchange.sim.Track.E
import com.rm.interchange.sim.Track.N
import com.rm.interchange.sim.Track.S
import com.rm.interchange.sim.Track.W
import com.rm.interchange.sim.Track.X
import com.rm.interchange.sim.Track.XM
import com.rm.interchange.sim.Track.XP
import com.rm.interchange.sim.Track.Y
import com.rm.interchange.sim.Track.YM
import com.rm.interchange.sim.Track.YP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Trains choosing their platform, bay or a way round at a path signal or a block signal that lets them in. */
class StationRoutingTest {
    private fun world(): World {
        val w = World(NewGame(64, 48, 1, 1900, towns = 0, industries = 0, weather = false, collisions = true))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 1_000_000_000
        return w
    }

    private fun World.rail(sx: Int, sy: Int, ex: Int, ey: Int, p: Int) = apply(Command.Rail(0, sx, sy, ex, ey, p, remove = false))
    private fun World.piece(x: Int, y: Int, p: Int) = rail(x, y, x, y, p)
    private fun World.path(x: Int, y: Int, p: Int, facing: Int, oneWay: Boolean = false) =
        repeat(if (oneWay) 2 else 1) { apply(Command.Signal(0, x, y, p, facing, Signals.PATH)) }

    private fun World.block(x: Int, y: Int, p: Int, facing: Int, kind: Int) = apply(Command.Signal(0, x, y, p, facing, kind))

    private fun World.train(depot: Int, vararg orders: Int, start: Boolean = true): Train {
        apply(Command.BuyTrain(0, depot, roster.engines.first { it.key == "base:american" }.id, roster.engines.first { it.key == "base:coach_wood" }.id, 2))
        val id = trains.indexOfLast { it != null }
        apply(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, orders))
        if (start) apply(Command.TrainOrder(0, id, Command.TrainOrder.START_STOP))
        return trains[id]!!
    }

    private fun World.start(t: Train) = apply(Command.TrainOrder(0, t.id, Command.TrainOrder.START_STOP))

    private fun World.row(t: Train) = stepTile(t.steps.last()) / width
    private fun World.col(t: Train) = stepTile(t.steps.last()) % width
    private fun World.atStation(t: Train) = !t.inDepot && t.waiting > 0 && stationTiles.containsKey(stepTile(t.steps.last()))

    private fun World.runUntil(days: Int, what: String, done: () -> Boolean) {
        var ticks = 0
        while (!done()) {
            step()
            assertTrue(++ticks < days * Calendar.TICKS_PER_DAY, "$what within $days days")
        }
    }

    /**
     * A line east along y = 10 from a depot at x = 2, a path signal at x = 15, splitting at x = 20 into y = 10 and 11,
     * into a station at x = 24 to 29 on both rows. A second depot at x = 31 opens west onto y = 10.
     */
    private fun twoPlatforms(): World {
        val w = world()
        w.apply(Command.Depot(0, 2, 10, XP))
        w.rail(3, 10, 30, 10, X)
        w.piece(20, 10, W)
        w.piece(20, 11, E)
        w.rail(21, 11, 30, 11, X)
        w.apply(Command.Depot(0, 31, 10, XM))
        w.apply(Command.Station(0, 24, 10, 29, 11, X))
        w.path(15, 10, X, XM)
        return w
    }

    @Test
    fun aTrainPlannedToOnePlatformTakesTheOtherWhenATrainStopsOnItFirst() {
        val w = twoPlatforms()
        // Both set off on one tick, b planning first while a is still in its depot.
        val b = w.train(0, Order.withWait(0, 20), start = false)
        val a = w.train(1, Order.withWait(0, 60), start = false)
        w.start(b)
        w.start(a)
        w.step()
        assertTrue(!b.inDepot && !a.inDepot, "both out")
        assertEquals(10, stepTile(b.route!!.last()) / w.width, "b planned for row 10 while it was free")
        w.runUntil(20, "a at its platform") { w.atStation(a) }
        assertTrue(w.col(b) < 15, "b still short of the signal")
        // It chooses as it comes up to the signal, so never stands there.
        var stood = 0
        w.runUntil(20, "b at a platform") {
            if (b.speed == 0 && w.col(b) == 14) stood++
            w.atStation(b)
        }
        assertEquals(0, stood, "ticks b stood at the signal")
        assertEquals(11, w.row(b))
        assertEquals(10, w.row(a))
    }

    @Test
    fun aTrainStandingOnlyAtTheFarEndTakesThePlatform() {
        val w = twoPlatforms()
        val a = w.train(0, Order.withWait(0, 60))
        w.runUntil(20, "a at its platform") { w.atStation(a) }
        assertEquals(10, w.row(a))
        assertTrue(a.onTiles.none { it == w.index(24, 10) }, "a stands past the entry tile")
        val b = w.train(0, Order.withWait(0, 20))
        w.runUntil(5, "b set off") { !b.inDepot && b.route != null }
        assertEquals(11, stepTile(b.route!!.last()) / w.width, "b planned for the free platform")
        w.runUntil(20, "b at a platform") { w.atStation(b) }
        assertEquals(11, w.row(b))
    }

    @Test
    fun withEveryPlatformFullATrainWaitsAtTheSignalThenTakesTheFirstToFree() {
        // As twoPlatforms, with row 11 joining row 10 again at x = 31 and a second station on to the east.
        val w = world()
        w.apply(Command.Depot(0, 2, 10, XP))
        w.rail(3, 10, 50, 10, X)
        w.piece(20, 10, W)
        w.piece(20, 11, E)
        w.rail(21, 11, 30, 11, X)
        w.piece(31, 11, N)
        w.piece(31, 10, S)
        w.apply(Command.Station(0, 24, 10, 29, 11, X))
        w.apply(Command.Station(0, 44, 10, 47, 10, X))
        w.path(15, 10, X, XM)
        val a1 = w.train(0, Order.withWait(0, 100))
        w.runUntil(20, "a1 at its platform") { w.atStation(a1) }
        val a2 = w.train(0, Order.withWait(0, 12), Order.withWait(1, 100))
        w.runUntil(20, "a2 at its platform") { w.atStation(a2) }
        assertEquals(setOf(10, 11), setOf(w.row(a1), w.row(a2)))
        val b = w.train(0, Order.withWait(0, 20))
        // It comes up to the signal and stands there, holding nothing past it.
        var stood = 0
        w.runUntil(30, "b at a platform") {
            if (!b.inDepot && b.held && b.speed == 0 && w.col(b) == 14) stood++
            w.atStation(b)
        }
        assertTrue(stood > Calendar.TICKS_PER_DAY, "b stood at the signal, $stood ticks")
        assertEquals(11, w.row(b), "b on the platform a2 left")
        assertEquals(10, w.row(a1))
    }

    /** The diagonals of a ladder fanning out from row [rows].first eastbound, one row further each tile, from [a]. */
    private fun World.entryLadder(a: Int, rows: IntRange) {
        val top = rows.first
        piece(a, top, W)
        for (y in top + 1..rows.last) {
            piece(a + y - top - 1, y, E)
            if (y != rows.last) piece(a + y - top, y, W)
        }
    }

    /** The mirror of [entryLadder]: rows merging eastbound into row [rows].first, ending at [b]. */
    private fun World.exitLadder(b: Int, rows: IntRange) {
        val top = rows.first
        piece(b, top, S)
        for (y in top + 1..rows.last) {
            piece(b - (y - top - 1), y, N)
            if (y != rows.last) piece(b - (y - top), y, S)
        }
    }

    @Test
    fun withTheFirstBayTakenTheSecondIsUsed() {
        // Three bays on rows 10 to 12 between x = 12 and 26, each with a path signal at x = 21, then one platform.
        val w = world()
        w.apply(Command.Depot(0, 2, 10, XP))
        w.rail(3, 10, 55, 10, X)
        w.entryLadder(12, 10..12)
        w.exitLadder(26, 10..12)
        for (y in 11..12) w.rail(12 + y - 10, y, 26 - (y - 10), y, X)
        w.apply(Command.Station(0, 34, 10, 38, 10, X))
        w.path(8, 10, X, XM, oneWay = true)
        for (y in 10..12) w.path(21, y, X, XM, oneWay = true)
        val a = w.train(0, Order.withWait(0, 100))
        w.runUntil(20, "a at the platform") { w.atStation(a) }
        val c = w.train(0, Order.withWait(0, 20))
        w.runUntil(20, "c stood in a bay") { !c.inDepot && c.held && c.speed == 0 && w.col(c) == 20 }
        assertEquals(10, w.row(c))
        val b = w.train(0, Order.withWait(0, 20))
        w.runUntil(20, "b stood in a bay") { !b.inDepot && b.held && b.speed == 0 && w.col(b) == 20 }
        assertEquals(11, w.row(b))
    }

    @Test
    fun aLoopRoundIsTakenWhenEveryPlatformIsFull() {
        // A line east on y = 10 past a path signal at x = 8 to a station at x = 14 to 17; at x = 10 a loop runs down to
        // y = 14, east to x = 24 and back up onto y = 10, coming into the station from the east. A one-way path signal
        // on the loop at (10, 12).
        val w = world()
        w.apply(Command.Depot(0, 2, 10, XP))
        w.rail(3, 10, 23, 10, X)
        w.piece(10, 10, W)
        w.rail(10, 11, 10, 13, Y)
        w.piece(10, 14, E)
        w.rail(11, 14, 23, 14, X)
        w.piece(24, 14, N)
        w.rail(24, 11, 24, 13, Y)
        w.piece(24, 10, W)
        w.apply(Command.Station(0, 14, 10, 17, 10, X))
        w.path(8, 10, X, XM, oneWay = true)
        w.path(10, 12, Y, YM, oneWay = true)
        val a = w.train(0, Order.withWait(0, 100))
        w.runUntil(20, "a at the platform") { w.atStation(a) }
        val b = w.train(0, Order.withWait(0, 20))
        w.runUntil(20, "b onto the loop") { !b.inDepot && w.col(b) == 10 && w.row(b) >= 11 }
    }

    /**
     * A line east along y = 10 from a depot at x = 2, splitting at x = 20 into rows 10 to [rows].last, into a station at
     * x = 24 to 29, with a [kind] signal at x = 15 and [platform] signals at x = 23 on each row.
     */
    private fun blockThroat(rows: IntRange, kind: Int, platform: Int): World {
        val w = world()
        w.apply(Command.Depot(0, 2, 10, XP))
        w.rail(3, 10, 30, 10, X)
        w.entryLadder(20, rows)
        for (y in rows.first + 1..rows.last) w.rail(20 + y - rows.first, y, 30, y, X)
        w.apply(Command.Station(0, 24, rows.first, 29, rows.last, X))
        w.block(15, 10, X, XM, kind)
        for (y in rows) w.block(23, y, X, XM, platform)
        return w
    }

    @Test
    fun anEntrySignalSendsTheTrainToThePlatformWhoseExitIsGreen() {
        val w = blockThroat(10..11, Signals.ENTRY, Signals.EXIT)
        val a = w.train(0, Order.withWait(0, 100))
        w.runUntil(20, "a past the entry signal") { !a.inDepot && w.col(a) >= 17 }
        // Planned while a is still short of the platforms, so both look free.
        val b = w.train(0, Order.withWait(0, 20))
        w.runUntil(5, "b set off") { !b.inDepot && b.route != null }
        assertEquals(10, stepTile(b.route!!.last()) / w.width, "b planned for row 10")
        w.runUntil(30, "b at a platform") { w.atStation(b) }
        assertEquals(10, w.row(a))
        assertEquals(11, w.row(b))
    }

    @Test
    fun aComboSignalCrossesToTheOtherGroupWhenItsOwnIsFull() {
        // Group one on rows 10 and 11, group two on rows 13 and 14 off a diagonal at x = 12, each behind a combo signal
        // at x = 16 and exits at x = 23, all behind an entry signal at x = 8.
        val w = world()
        w.apply(Command.Depot(0, 2, 10, XP))
        w.rail(3, 10, 30, 10, X)
        w.piece(12, 10, W); w.piece(12, 11, E); w.piece(13, 11, W); w.piece(13, 12, E); w.piece(14, 12, W); w.piece(14, 13, E)
        w.rail(15, 13, 30, 13, X)
        w.entryLadder(20, 10..11)
        w.entryLadder(20, 13..14)
        w.rail(21, 11, 30, 11, X)
        w.rail(21, 14, 30, 14, X)
        w.apply(Command.Station(0, 24, 10, 29, 14, X))
        w.block(8, 10, X, XM, Signals.ENTRY)
        w.block(16, 10, X, XM, Signals.COMBO)
        w.block(16, 13, X, XM, Signals.COMBO)
        for (y in intArrayOf(10, 11, 13, 14)) w.block(23, y, X, XM, Signals.EXIT)
        val a1 = w.train(0, Order.withWait(0, 100))
        w.runUntil(20, "a1 at its platform") { w.atStation(a1) }
        val a2 = w.train(0, Order.withWait(0, 100))
        w.runUntil(20, "a2 set off") { !a2.inDepot && w.col(a2) >= 12 }
        val b = w.train(0, Order.withWait(0, 20))
        w.runUntil(5, "b set off") { !b.inDepot && b.route != null }
        assertTrue(stepTile(b.route!!.last()) / w.width <= 11, "b planned for group one")
        w.runUntil(30, "b at a platform") { w.atStation(b) }
        assertEquals(setOf(10, 11), setOf(w.row(a1), w.row(a2)))
        assertTrue(w.row(b) >= 13, "b in group two, row ${w.row(b)}")
    }

    @Test
    fun aBlockSignalThroatUsesMoreThanOnePlatform() {
        val w = blockThroat(10..12, Signals.TWO_WAY, Signals.TWO_WAY)
        // All set off on one tick, so all plan for the same platform.
        val ts = (0 until 3).map { w.train(0, Order.withWait(0, 100), start = false) }
        for (t in ts) w.start(t)
        // The row each first calls at.
        val rows = HashMap<Int, Int>()
        w.runUntil(60, "all called") {
            for (t in ts) if (t.id !in rows && w.atStation(t)) rows[t.id] = w.row(t)
            rows.size == ts.size
        }
        assertEquals(3, rows.values.toSet().size, "rows $rows")
    }

    /** The ladder of StationLayoutRun: six platforms off one line, with a ring back round past a depot and a waypoint. */
    private fun ladder(): World {
        val w = world()
        // The ring: top row y = 3 run west from x = 56 to 4, columns down to y = 10, and the line east on y = 10.
        w.rail(5, 3, 55, 3, X)
        w.piece(4, 3, S); w.piece(56, 3, W); w.piece(56, 10, N)
        w.rail(56, 4, 56, 9, Y)
        w.rail(4, 4, 4, 9, Y)
        w.piece(4, 10, E)
        w.apply(Command.Depot(0, 52, 1, YP))
        w.piece(52, 2, Y); w.piece(52, 3, N)
        w.apply(Command.Station(0, 42, 3, 46, 3, X))
        w.path(52, 2, Y, YM)
        for (x in intArrayOf(49, 40, 6, 11, 16, 21, 26, 31)) w.path(x, 3, X, XP, oneWay = true)
        w.path(56, 6, Y, YP, oneWay = true)
        w.path(4, 6, Y, YM, oneWay = true)
        w.apply(Command.Waypoint(0, 36, 3))
        w.rail(5, 10, 55, 10, X)
        w.entryLadder(12, 10..15)
        w.exitLadder(32, 10..15)
        for (y in 11..15) w.rail(12 + y - 10, y, 32 - (y - 10), y, X)
        w.apply(Command.Station(0, 20, 10, 24, 15, X))
        w.path(8, 10, X, XM, oneWay = true)
        for (y in 10..15) w.path(25, y, X, XM, oneWay = true)
        w.path(40, 10, X, XM, oneWay = true)
        for (x in 44..52 step 4) w.path(x, 10, X, XM, oneWay = true)
        val a = w.stationTiles.getValue(w.index(20, 10))
        val wp = w.stationTiles.getValue(w.index(36, 3))
        repeat(8) { w.train(0, Order.withWait(a, 16), wp) }
        return w
    }

    @Test
    fun twoWorldsOnTheLadderStayTheSame() {
        val one = ladder()
        val two = ladder()
        assertTrue(one.trains.count { it != null } == 8)
        repeat(20 * Calendar.TICKS_PER_DAY) { k ->
            one.step()
            two.step()
            if (k % Calendar.TICKS_PER_DAY == 0) assertEquals(one.checksum(), two.checksum(), "day ${k / Calendar.TICKS_PER_DAY}")
        }
        assertEquals(one.checksum(), two.checksum())
        assertTrue(one.trains.filterNotNull().any { !it.inDepot }, "trains running")
    }
}
