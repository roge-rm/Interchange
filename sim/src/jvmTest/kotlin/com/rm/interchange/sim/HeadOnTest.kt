package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadOnTest {
    private fun world(): World {
        // Collisions off: trains wait for each other.
        val w = World(NewGame(64, 64, 1, 1900, towns = 0, industries = 0, weather = false, collisions = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        return w
    }

    /** Buys a train in depot [depot] going to station [station], and sets it going. */
    private fun buy(w: World, depot: Int, station: Int): Train {
        w.apply(Command.BuyTrain(0, depot, w.roster.engines.first { it.key == "base:american" }.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 2))
        val id = w.trains.size - 1
        w.apply(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, intArrayOf(Order.withWait(station, 20))))
        w.apply(Command.TrainOrder(0, id, Command.TrainOrder.START_STOP))
        return w.trains[id]!!
    }

    private fun Train.x(w: World) = stepTile(steps.last()) % w.width
    private fun Train.y(w: World) = stepTile(steps.last()) / w.width
    private fun Train.atStation(w: World, id: Int) = !inDepot && waiting > 0 && w.stationTiles[stepTile(steps.last())] == id

    private fun ring(): World {
        val w = world()
        w.apply(Command.Rail(0, 11, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Rail(0, 11, 20, 39, 20, Track.X, remove = false))
        w.apply(Command.Rail(0, 10, 11, 10, 19, Track.Y, remove = false))
        w.apply(Command.Rail(0, 40, 11, 40, 19, Track.Y, remove = false))
        w.apply(Command.Rail(0, 10, 10, 10, 10, Track.S, remove = false))
        w.apply(Command.Rail(0, 40, 10, 40, 10, Track.W, remove = false))
        w.apply(Command.Rail(0, 40, 20, 40, 20, Track.N, remove = false))
        w.apply(Command.Rail(0, 10, 20, 10, 20, Track.E, remove = false))
        w.apply(Command.Rail(0, 15, 10, 15, 10, Track.E, remove = false))
        w.apply(Command.Rail(0, 35, 20, 35, 20, Track.S, remove = false))
        w.apply(Command.Depot(0, 15, 9, Track.YP))
        w.apply(Command.Depot(0, 35, 21, Track.YM))
        w.apply(Command.Station(0, 20, 10, 23, 10, Track.X))
        w.apply(Command.Station(0, 20, 20, 23, 20, Track.X))
        assertEquals(2, w.stations.size)
        return w
    }

    /**
     * A ring from (10, 10) to (40, 20), station 0 on the top at x = 20 to 23 and station 1 on the bottom. Train 0
     * comes out at (15, 10) heading east for the bottom, train 1 at (35, 20) heading east for the top, so they meet
     * head-on on the east side. Train 1 turns and goes round the west side; train 0 waits, then carries on.
     */
    @Test
    fun theHigherIdTurnsAndGoesTheOtherWayRound() {
        val w = ring()
        val a = buy(w, 0, 1)
        val b = buy(w, 1, 0)
        var bWest = false
        var aWest = false
        var aThere = false
        var bThere = false
        var ticks = 0
        while (!(aThere && bThere) && ticks++ < 20_000) {
            w.step()
            if (!b.inDepot && b.x(w) == 10) bWest = true
            if (!a.inDepot && a.x(w) == 10) aWest = true
            if (a.atStation(w, 1)) aThere = true
            if (b.atStation(w, 0)) bThere = true
        }
        assertTrue(aThere, "train 0 never got to the bottom station")
        assertTrue(bThere, "train 1 never got to the top station")
        assertTrue(bWest, "train 1 didn't go round the west side")
        assertTrue(!aWest, "train 0 went round the west side")
        assertTrue(w.chronicle.none { it.kind == News.TRAIN_STUCK })
    }

    /** A plain line between depots at x = 2 and x = 40: trains coming at each other can't get round, and each says so once. */
    @Test
    fun withNoWayRoundTheNewsIsToldOnceForThePair() {
        val w = world()
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Depot(0, 40, 10, Track.XM))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 5, 10, 7, 10, Track.X))
        w.apply(Command.Station(0, 35, 10, 37, 10, Track.X))
        val a = buy(w, 0, 1)
        val b = buy(w, 1, 0)
        repeat(8_000) { w.step() }
        val stuck = w.chronicle.filter { it.kind == News.TRAIN_STUCK }
        // One line for the pair: train 1's, as train 0 only tries once train 1 is stuck.
        assertEquals(1, stuck.size)
        assertEquals(b.id, stuck[0].about)
        assertTrue(b.stuck && a.stuck)
        // Neither turned: each still faces the way it set out.
        assertEquals(Track.XP, stepExit(a.steps.last()))
        assertEquals(Track.XM, stepExit(b.steps.last()))
        assertTrue(a.x(w) < b.x(w))
    }

    /** A train held behind one going the same way, stopped on the line, waits there and never turns. */
    @Test
    fun aTrainBehindOneGoingTheSameWayJustWaits() {
        val w = world()
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 35, 10, 37, 10, Track.X))
        val a = buy(w, 0, 0)
        var ticks = 0
        while (a.inDepot || a.x(w) < 20) { w.step(); assertTrue(ticks++ < 5_000) }
        w.apply(Command.TrainOrder(0, a.id, Command.TrainOrder.START_STOP))
        val b = buy(w, 0, 0)
        repeat(6_000) { w.step() }
        assertTrue(!b.inDepot && b.x(w) in 10 until a.x(w), "train 1 at ${b.x(w)}, train 0 at ${a.x(w)}")
        assertEquals(Track.XP, stepExit(b.steps.last()))
        assertEquals(0, b.headOn)
        assertTrue(w.chronicle.none { it.kind == News.TRAIN_STUCK })
        assertEquals(10, b.y(w))
    }

    /** On the ring, train 1 is stopped by the player on the east side: train 0, the lower id, goes round it the other way. */
    @Test
    fun aTrainStoppedByThePlayerIsGoneRoundWhateverTheIds() {
        val w = ring()
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:american" }.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 2))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(Order.withWait(1, 20))))
        val a = w.trains[0]!!
        val b = buy(w, 1, 0)
        var ticks = 0
        while (b.inDepot || b.x(w) != 40 || b.y(w) > 14) { w.step(); assertTrue(ticks++ < 5_000) }
        w.apply(Command.TrainOrder(0, b.id, Command.TrainOrder.START_STOP))
        w.apply(Command.TrainOrder(0, a.id, Command.TrainOrder.START_STOP))
        var aWest = false
        var aThere = false
        ticks = 0
        while (!aThere && ticks++ < 20_000) {
            w.step()
            if (!a.inDepot && a.x(w) == 10) aWest = true
            if (a.atStation(w, 1)) aThere = true
        }
        assertTrue(aThere, "train 0 never got to the bottom station")
        assertTrue(aWest, "train 0 didn't go round the west side")
        assertTrue(!b.running && b.x(w) == 40)
    }

    /**
     * A line between depots at x = 2 and x = 45 with a block between two-way signals at x = 12 and x = 32, and a
     * branch in it at x = 22 to a platform at y = 11. Train 0 loads there for days; train 1 for the platform waits at
     * x = 12 behind it, facing train 2, which comes from the east for the west station and waits at x = 32. Train 1
     * is held by train 0, not train 2, so train 2 waits and doesn't turn.
     */
    @Test
    fun aTrainFacingOneHeldBySomethingElseDoesntTurn() {
        val w = world()
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Depot(0, 45, 10, Track.XM))
        w.apply(Command.Rail(0, 3, 10, 44, 10, Track.X, remove = false))
        w.apply(Command.Rail(0, 22, 10, 22, 10, Track.W, remove = false))
        w.apply(Command.Rail(0, 22, 11, 22, 11, Track.E, remove = false))
        w.apply(Command.Rail(0, 23, 11, 27, 11, Track.X, remove = false))
        w.apply(Command.Station(0, 24, 11, 27, 11, Track.X))
        w.apply(Command.Station(0, 5, 10, 7, 10, Track.X))
        w.apply(Command.Signal(0, 12, 10, Track.X, Track.XP, Signals.TWO_WAY))
        w.apply(Command.Signal(0, 32, 10, Track.X, Track.XM, Signals.TWO_WAY))
        val engine = w.roster.engines.first { it.key == "base:american" }.id
        val coach = w.roster.engines.first { it.key == "base:coach_wood" }.id
        w.apply(Command.BuyTrain(0, 0, engine, coach, 2))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(Order.withWait(0, 200))))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val l = w.trains[0]!!
        var ticks = 0
        while (!l.atStation(w, 0)) { w.step(); assertTrue(ticks++ < 5_000, "train 0 never got to the platform") }
        val u = buy(w, 0, 0)
        val t = buy(w, 1, 1)
        repeat(3_000) { w.step() }
        assertTrue(l.waiting > 0, "train 0 left too soon")
        assertTrue(!u.inDepot && u.x(w) in 9..12 && u.speed == 0, "train 1 at ${u.x(w)}")
        assertTrue(!t.inDepot && t.x(w) in 32..34 && t.speed == 0, "train 2 at ${t.x(w)}")
        assertEquals(Track.XM, stepExit(t.steps.last()))
        assertEquals(0, t.headOn)
        assertTrue(w.chronicle.none { it.kind == News.TRAIN_STUCK })
    }

    /**
     * A line between depots at x = 2 and x = 45, stations at x = 5 to 7 and 38 to 40, and a passing loop at y = 11
     * from x = 24 to 34. Train 1 runs west past the loop before train 0 sets out east, so they meet west of it. Train 1
     * turns, backs into the loop, waits there while train 0 goes by on the main line, then carries on west.
     */
    /** A line between depots at x = 2 and x = 45, stations at x = 5 to 7 and 38 to 40, and a loop at y = 11 from x = 24 to 34. */
    private fun loopLine(): World {
        val w = world()
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Depot(0, 45, 10, Track.XM))
        w.apply(Command.Rail(0, 3, 10, 44, 10, Track.X, remove = false))
        w.apply(Command.Rail(0, 24, 10, 24, 10, Track.W, remove = false))
        w.apply(Command.Rail(0, 24, 11, 24, 11, Track.E, remove = false))
        w.apply(Command.Rail(0, 25, 11, 33, 11, Track.X, remove = false))
        w.apply(Command.Rail(0, 34, 11, 34, 11, Track.N, remove = false))
        w.apply(Command.Rail(0, 34, 10, 34, 10, Track.S, remove = false))
        w.apply(Command.Station(0, 5, 10, 7, 10, Track.X))
        w.apply(Command.Station(0, 38, 10, 40, 10, Track.X))
        return w
    }

    @Test
    fun theTurnedTrainBacksIntoALoopAndLetsTheOtherBy() {
        val w = loopLine()
        val engine = w.roster.engines.first { it.key == "base:american" }.id
        val coach = w.roster.engines.first { it.key == "base:coach_wood" }.id
        w.apply(Command.BuyTrain(0, 0, engine, coach, 2))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(Order.withWait(1, 20))))
        val a = w.trains[0]!!
        val b = buy(w, 1, 0)
        var ticks = 0
        while (b.inDepot || b.x(w) > 18) { w.step(); assertTrue(ticks++ < 5_000, "train 1 never got west of the loop") }
        w.apply(Command.TrainOrder(0, a.id, Command.TrainOrder.START_STOP))
        var inLoop = false
        var passed = false
        var aThere = false
        var bThere = false
        ticks = 0
        while (!(aThere && bThere) && ticks++ < 20_000) {
            w.step()
            if (b.y(w) == 11 && b.aside == a.id && !inLoop) {
                inLoop = true
                // Standing aside is saved.
                assertEquals(a.id, Save.read(Save.write(w)).trains[b.id]!!.aside)
            }
            if (inLoop && !a.inDepot && a.x(w) > 34) passed = true
            if (a.atStation(w, 1)) aThere = true
            if (b.atStation(w, 0)) bThere = true
        }
        assertTrue(inLoop, "train 1 didn't back into the loop")
        assertTrue(passed, "train 0 didn't go by")
        assertTrue(aThere, "train 0 never got to the east station")
        assertTrue(bThere, "train 1 never got to the west station")
        assertEquals(-1, b.aside)
        assertTrue(w.chronicle.none { it.kind == News.TRAIN_STUCK })
    }

    /**
     * On the loop line with a platform in the loop, train 1 stands stopped in it. Trains 0 and 2 meet west of the loop:
     * train 2 doesn't back into the taken loop, and nobody is left standing aside. Once train 1 has gone off to its
     * depot, train 2 backs into the loop and both get where they're going.
     */
    @Test
    fun aTrainDoesntBackIntoALoopWithATrainInIt() {
        val w = loopLine()
        w.apply(Command.Station(0, 25, 11, 30, 11, Track.X))
        val engine = w.roster.engines.first { it.key == "base:american" }.id
        val coach = w.roster.engines.first { it.key == "base:coach_wood" }.id
        w.apply(Command.BuyTrain(0, 0, engine, coach, 2))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(Order.withWait(1, 20))))
        val a = w.trains[0]!!
        val c = buy(w, 1, 2)
        var ticks = 0
        while (!c.atStation(w, 2)) { w.step(); assertTrue(ticks++ < 5_000, "train 1 never got to the loop") }
        w.apply(Command.TrainOrder(0, c.id, Command.TrainOrder.START_STOP))
        val b = buy(w, 1, 0)
        ticks = 0
        while (b.inDepot || b.x(w) > 18) { w.step(); assertTrue(ticks++ < 5_000, "train 2 never got west of the loop") }
        w.apply(Command.TrainOrder(0, a.id, Command.TrainOrder.START_STOP))
        repeat((ASIDE_WAIT + 4 * HEAD_ON_WAIT).toInt()) {
            w.step()
            assertTrue(b.aside < 0 && a.aside < 0, "a train went to stand aside in the taken loop")
        }
        assertTrue(c.y(w) == 11 && !c.running)
        w.apply(Command.TrainOrder(0, c.id, Command.TrainOrder.TO_DEPOT))
        w.apply(Command.TrainOrder(0, c.id, Command.TrainOrder.START_STOP))
        var inLoop = false
        var aThere = false
        var bThere = false
        var asideFor = 0
        ticks = 0
        while (!(aThere && bThere) && ticks++ < 20_000) {
            w.step()
            if (b.y(w) == 11 && b.aside == a.id) inLoop = true
            if (b.aside >= 0) asideFor++ else asideFor = 0
            assertTrue(asideFor <= ASIDE_WAIT, "train 2 stood aside too long")
            if (a.atStation(w, 1)) aThere = true
            if (b.atStation(w, 0)) bThere = true
        }
        assertTrue(c.inDepot, "train 1 never got to its depot")
        assertTrue(inLoop, "train 2 didn't back into the loop once it was free")
        assertTrue(aThere, "train 0 never got to the east station")
        assertTrue(bThere, "train 2 never got to the west station")
    }
}
