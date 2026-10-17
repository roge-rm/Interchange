package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollisionTest {
    private fun world(collisions: Boolean, width: Int = 64): World {
        val w = World(NewGame(width, 64, 1, 1900, towns = 0, industries = 0, weather = false, collisions = collisions))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        return w
    }

    /** Buys a train in depot [depot] going to station [station], and sets it going unless [go] is false. */
    private fun buy(w: World, depot: Int, station: Int, go: Boolean = true): Train {
        w.apply(Command.BuyTrain(0, depot, w.roster.engines.first { it.key == "base:american" }.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 2))
        val id = w.trains.size - 1
        w.apply(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, intArrayOf(Order.withWait(station, 20))))
        if (go) w.apply(Command.TrainOrder(0, id, Command.TrainOrder.START_STOP))
        return w.trains[id]!!
    }

    private fun Train.x(w: World) = stepTile(steps.last()) % w.width
    private fun World.crashes() = chronicle.count { it.kind == News.CRASH }

    /** A plain line between depots at x = 2 and x = 40, a station near each end. */
    private fun line(collisions: Boolean): World {
        val w = world(collisions)
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Depot(0, 40, 10, Track.XM))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 5, 10, 7, 10, Track.X))
        w.apply(Command.Station(0, 35, 10, 37, 10, Track.X))
        return w
    }

    @Test
    fun headOnOnAnUnsignalledLineBothAreWrecked() {
        val w = line(collisions = true)
        val a = buy(w, 0, 1)
        val b = buy(w, 1, 0)
        var ticks = 0
        while (a.wreckedFor == 0 && ticks++ < 5_000) w.step()
        assertTrue(a.wreckedFor > 0 && b.wreckedFor > 0, "no crash")
        // One news line for the crash, naming both.
        assertEquals(1, w.crashes())
        assertEquals(listOf(a.id, b.id), w.crashes.list.single().vehicles.map { it.id }.sorted())
        assertTrue(a.speed == 0 && !a.running && b.speed == 0)
        // Its orders are gone, so passengers aren't routed by it.
        assertTrue(a.orders.isEmpty() && b.orders.isEmpty())
        assertTrue(w.carriers().none { it === a || it === b })
        // A wreck takes no orders and can't be sold.
        w.apply(Command.TrainOrder(0, a.id, Command.TrainOrder.START_STOP))
        assertTrue(!a.running)
        assertEquals(0L, w.value(a))
    }

    @Test
    fun headOnWithCollisionsOffTheyWait() {
        val w = line(collisions = false)
        val a = buy(w, 0, 1)
        val b = buy(w, 1, 0)
        repeat(6_000) { w.step() }
        assertEquals(0, w.crashes())
        assertTrue(a.wreckedFor == 0 && b.wreckedFor == 0)
        assertTrue(!a.inDepot && !b.inDepot && a.x(w) < b.x(w))
    }

    /** A train stopped on the line is hit from behind by the next one out of the depot, with no signal between. */
    @Test
    fun aTrainRunsIntoTheBackOfAStoppedOne() {
        val w = line(collisions = true)
        val a = buy(w, 0, 1)
        var ticks = 0
        while (a.inDepot || a.x(w) < 20) { w.step(); assertTrue(ticks++ < 5_000) }
        w.apply(Command.TrainOrder(0, a.id, Command.TrainOrder.START_STOP))
        val b = buy(w, 0, 1)
        ticks = 0
        while (b.wreckedFor == 0 && ticks++ < 5_000) w.step()
        assertTrue(a.wreckedFor > 0 && b.wreckedFor > 0, "no crash")
    }

    /** As above, but a block signal at x = 12: the second train waits there. */
    @Test
    fun aSignalKeepsTheNextTrainBack() {
        val w = line(collisions = true)
        w.apply(Command.Signal(0, 12, 10, Track.X, Track.XP, Signals.TWO_WAY))
        val a = buy(w, 0, 1)
        var ticks = 0
        while (a.inDepot || a.x(w) < 20) { w.step(); assertTrue(ticks++ < 5_000) }
        w.apply(Command.TrainOrder(0, a.id, Command.TrainOrder.START_STOP))
        val b = buy(w, 0, 1)
        repeat(4_000) { w.step() }
        assertEquals(0, w.crashes())
        assertTrue(!b.inDepot && b.x(w) in 9..12 && b.speed == 0, "train 1 at ${b.x(w)}")
    }

    /**
     * A long line with block signals every 8 tiles: two trains run one behind the other to a terminus at the far end.
     * The second comes into each block past a signal, so it stops behind the first there, at the station, and
     * nothing crashes.
     */
    @Test
    fun trainsInSignalledBlocksDontCrash() {
        val w = world(collisions = true, width = 128)
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 100, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 96, 10, 100, 10, Track.X))
        for (x in 10..90 step 8) w.apply(Command.Signal(0, x, 10, Track.X, Track.XP, Signals.TWO_WAY))
        val a = buy(w, 0, 0)
        var ticks = 0
        while (a.inDepot || a.x(w) < 12) { w.step(); assertTrue(ticks++ < 5_000) }
        val b = buy(w, 0, 0)
        var behind = false
        repeat(8_000) {
            w.step()
            if (a.waiting > 0 && !b.inDepot && b.speed == 0 && b.x(w) in 80..95) behind = true
        }
        assertEquals(0, w.crashes())
        assertTrue(behind, "train 1 never waited behind train 0 at the station")
    }

    /** A wreck stands for its days, then it's gone and a train can run through again. */
    @Test
    fun aWreckIsClearedAndTheLineIsFreeAgain() {
        val w = line(collisions = true)
        val a = buy(w, 0, 1)
        val b = buy(w, 1, 0)
        var ticks = 0
        while (a.wreckedFor == 0 && ticks++ < 5_000) w.step()
        assertTrue(a.wreckedFor > 0)
        repeat(WRECK_TICKS - 2) { w.step() }
        assertTrue(w.trains[a.id] != null && w.trains[b.id] != null, "cleared too soon")
        repeat(4) { w.step() }
        assertNull(w.trains[a.id])
        assertNull(w.trains[b.id])
        assertTrue(w.reserved.isEmpty())
        assertTrue((3..39).none { w.trainOnTile(w.index(it, 10)) })
        val c = buy(w, 0, 1)
        ticks = 0
        while (!(c.waiting > 0 && c.x(w) in 35..37) && ticks++ < 5_000) w.step()
        assertTrue(c.waiting > 0 && c.x(w) in 35..37, "the new train never got through")
        assertEquals(1, w.crashes())
    }

    /** A crash is saved with the wrecks, and a copy goes on the same. */
    @Test
    fun wrecksAndCrashesAreSaved() {
        val w = line(collisions = true)
        buy(w, 0, 1)
        buy(w, 1, 0)
        var ticks = 0
        while (w.crashes() == 0 && ticks++ < 5_000) w.step()
        val copy = Save.read(Save.write(w))
        assertTrue(copy.settings.collisions)
        assertEquals(w.crashes.list.size, copy.crashes.list.size)
        assertEquals(w.checksum(), copy.checksum())
        repeat(WRECK_TICKS) { w.step(); copy.step() }
        assertEquals(w.checksum(), copy.checksum())
        assertTrue(copy.trains.all { it == null })
    }

    /** A rival railway with a passing loop and two trains, left to run and grow for three years. */
    @Test
    fun rivalRailwaysDontCrash() {
        val w = World(NewGame(160, 160, 5, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        assertTrue(w.settings.collisions)
        val r = w.rivals[0]
        val c = w.companies[r.company]
        c.money = 10_000_000
        val serves = { s: Station, _: Boolean -> (w.stationPeople[s.id] ?: 0) > 0 }
        val towns = w.towns.sortedByDescending { it.population }
        var route: RivalRoute? = null
        loop@ for (t1 in towns) for (t2 in towns) {
            if (t1.id == t2.id) continue
            if (w.tryRail(c, railSite(t1, t2.x, t2.y), railSite(t2, t1.x, t1.y), Cargo.PASSENGERS, 100, t1.id, t2.id, serves, payback = 0) { r.routes += it } == Tried.BUILT) {
                route = r.routes.last()
                break@loop
            }
        }
        assertTrue(route != null && w.addLoop(c, route) && w.addTrain(c, route), "no railway with a loop to test with")
        repeat(Calendar.TICKS_PER_DAY * 365 * 3) { w.step() }
        assertEquals(0, w.crashes(), "crashes: ${w.crashes.list.map { "trains ${it.vehicles.map { v -> v.id }} at ${it.tile % w.width}, ${it.tile / w.width}" }}")
        assertTrue(w.trains.count { it != null && it.owner == r.company } >= 2, "the railway's trains are gone")
    }
}
