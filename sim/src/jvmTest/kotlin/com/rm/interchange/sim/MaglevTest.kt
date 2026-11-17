package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaglevTest {
    private fun id(w: World, key: String) = w.roster.engines.first { it.key == key }.id

    /** A flat 128 map from [year], with plenty of money. */
    private fun flat(year: Int = 2045, future: Boolean = true): World {
        val w = World(NewGame(128, 128, 1, year, towns = 0, industries = 0, weather = false, handling = false, eraCosts = false, future = future))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 100_000_000
        return w
    }

    /** A guideway line along y = 10 with stations at x 10 and 110, a depot behind the first and an Aurora with three coaches running. */
    private fun line(w: World = flat()): World {
        w.apply(Command.Depot(0, 2, 10, Track.XP, maglev = true))
        w.apply(Command.Rail(0, 3, 10, 120, 10, Track.X, remove = false, maglev = true))
        w.apply(Command.Station(0, 10, 10, 14, 10, Track.X, maglev = true))
        w.apply(Command.Station(0, 106, 10, 110, 10, Track.X, maglev = true))
        w.apply(Command.BuyTrain(0, 0, id(w, "base:aurora"), id(w, "base:aurora_coach"), 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        return w
    }

    @Test
    fun anOldSaveLoadsWithoutFutureAndKeepsInStep() {
        val g = NewGame(128, 128, 6, 2040, Region.MIXED, towns = 8, industries = 6, rivals = 2, rivalLevel = RivalLevel.HARD)
        val old = Save.read(Save.write(World(g), version = 72))
        assertFalse(old.settings.future)
        val off = Save.read(Save.write(World(g.copy(future = false))))
        assertEquals(off.checksum(), old.checksum())
        while (old.day < 365) { old.step(); off.step() }
        assertEquals(off.checksum(), old.checksum())
        // Off, no guideway and no maglev on sale.
        old.apply(Command.Rail(0, 3, 3, 10, 3, Track.X, remove = false, maglev = true))
        assertTrue(old.guideway.none { it })
        assertFalse(old.onSale(old.roster[id(old, "base:aurora")]))
    }

    @Test
    fun aMaglevLineIsBuiltRunAndLifted() {
        val w = line()
        assertTrue((3..120).all { w.guideway[w.index(it, 10)] && w.trackAt(it, 10) == Track.X })
        assertTrue(w.guideway[w.index(2, 10)] && w.guideway[w.index(10, 10)])
        assertEquals(1, w.trains.size)
        val t = w.trains[0]!!
        assertTrue(t.maglev)
        var fastest = 0
        var arrived = false
        repeat(20_000) {
            w.step()
            fastest = maxOf(fastest, t.speed)
            if (t.waiting > 0 && !t.inDepot && w.stationTiles[stepTile(t.steps.last())] == 1) arrived = true
        }
        assertTrue(arrived, "never got there")
        // Well past the Alto's 300 km/h.
        assertTrue(fastest * 2700 / 4096 / 16 > 400, "${fastest * 2700 / 4096 / 16} km/h")
        // Lifted by the guideway tool only, where no train is.
        w.apply(Command.Rail(0, 60, 10, 70, 10, Track.X, remove = true))
        assertEquals(Track.X, w.trackAt(65, 10))
        w.apply(Command.Rail(0, 60, 10, 70, 10, Track.X, remove = true, maglev = true))
        assertTrue((60..70).all { w.trackAt(it, 10) == 0 && !w.guideway[w.index(it, 10)] || w.trainOnTile(w.index(it, 10)) })
    }

    @Test
    fun railAndMaglevNeverJoin() {
        val w = flat()
        w.apply(Command.Rail(0, 3, 20, 20, 20, Track.X, remove = false, maglev = true))
        // Rail laid on into the guideway's end isn't laid where it would lead into it, nor over it.
        w.apply(Command.Rail(0, 21, 20, 30, 20, Track.X, remove = false))
        assertEquals(0, w.trackAt(21, 20))
        assertEquals(Track.X, w.trackAt(22, 20))
        w.apply(Command.Rail(0, 10, 15, 10, 25, Track.Y, remove = false))
        assertEquals(Track.X, w.trackAt(10, 20))
        assertTrue(w.guideway[w.index(10, 20)])
        // Nor guideway into rail.
        w.apply(Command.Rail(0, 40, 30, 50, 30, Track.X, remove = false))
        w.apply(Command.Rail(0, 30, 30, 39, 30, Track.X, remove = false, maglev = true))
        assertEquals(0, w.trackAt(39, 30))
        // From the guideway's end there's no way on.
        assertEquals(0, w.waysOn(step(w.index(20, 20), Track.index(Track.X), Track.XM)).size)
        // A maglev is bought only on guideway, a rail train only on rail.
        w.apply(Command.Depot(0, 2, 20, Track.XP, maglev = true))
        w.apply(Command.Depot(0, 51, 30, Track.XM))
        assertTrue(w.maglevDepot(w.depots[0]!!))
        assertFalse(w.maglevDepot(w.depots[1]!!))
        w.apply(Command.BuyTrain(0, 1, id(w, "base:aurora"), id(w, "base:aurora_coach"), 3))
        w.apply(Command.BuyTrain(0, 0, id(w, "base:charger"), id(w, "base:coach_streamline"), 3))
        w.apply(Command.BuyTrain(0, 0, id(w, "base:aurora"), id(w, "base:coach_streamline"), 3))
        assertEquals(0, w.trains.size)
        w.apply(Command.BuyTrain(0, 0, id(w, "base:aurora"), id(w, "base:aurora_coach"), 3))
        w.apply(Command.BuyTrain(0, 1, id(w, "base:charger"), id(w, "base:coach_streamline"), 3))
        assertEquals(2, w.trains.size)
        assertTrue(w.trains[0]!!.maglev)
        assertFalse(w.trains[1]!!.maglev)
        assertFalse(validTrainFormation(w.roster, listOf(TrainCar(id(w, "base:aurora")), TrainCar(id(w, "base:coach_streamline")))))
        // No wires on guideway.
        w.apply(Command.Wire(0, 3, 20, 20, 20, Track.X, remove = false))
        assertTrue((3..20).all { w.wires[w.index(it, 20)].toInt() == 0 })
    }

    @Test
    fun guidewayHasNoLevelCrossings() {
        val w = flat()
        w.apply(Command.Road(0, 5, 40, 30, 40, Surface.PAVED, remove = false))
        w.apply(Command.Rail(0, 10, 35, 10, 45, Track.Y, remove = false, maglev = true))
        assertEquals(0, w.trackAt(10, 40))
        assertEquals(Track.Y, w.trackAt(10, 39))
        w.apply(Command.Rail(0, 20, 30, 20, 50, Track.Y, remove = false, maglev = true))
        w.apply(Command.Road(0, 15, 45, 25, 45, Surface.PAVED, remove = false))
        assertEquals(0, w.road[w.index(20, 45)].toInt())
        // Rail crosses the same road on the level.
        w.apply(Command.Rail(0, 12, 35, 12, 45, Track.Y, remove = false))
        assertEquals(Track.Y, w.trackAt(12, 40))
        // Guideway goes over rail on a concrete bridge, never a trestle, and the rail runs on under it.
        w.apply(Command.Rail(0, 33, 55, 33, 70, Track.Y, remove = false))
        w.apply(Command.Rail(0, 20, 60, 30, 60, Track.X, remove = false, maglev = true))
        assertEquals(-1L, w.bridgeCost(w.companies[0], Command.Bridge(0, 30, 60, 36, 60, Spans.TRESTLE, maglev = true)))
        val rail = w.bridgeCost(w.companies[0], Command.Bridge(0, 31, 61, 37, 61, Spans.CONCRETE))
        val bridge = Command.Bridge(0, 30, 60, 36, 60, Spans.CONCRETE, maglev = true)
        assertTrue(w.bridgeCost(w.companies[0], bridge) > rail * 3 / 2, "${w.bridgeCost(w.companies[0], bridge)} against $rail")
        w.apply(bridge)
        assertEquals(1, w.spans.size)
        assertTrue(w.maglevSpan(w.spans[0]!!))
        assertTrue(w.guideway[w.index(36, 60)])
        assertEquals(Track.Y, w.trackAt(33, 60))
        assertFalse(w.guideway[w.index(33, 60)])
    }

    @Test
    fun guidewayCostsByYear() {
        val c = Command.Rail(0, 10, 10, 19, 10, Track.X, remove = false, maglev = true)
        // Not before its year, and not with future transport off.
        assertEquals(0, flat(2039).railCost(c).second)
        assertEquals(0, flat(2045, future = false).railCost(c).second)
        val w = flat(2045)
        assertEquals(10 * Maglev.TRACK to 10, w.railCost(c))
        assertEquals(10 * Prices.TRACK to 10, w.railCost(c.copy(maglev = false)))
        w.apply(c)
        w.apply(Command.Rail(0, 10, 12, 19, 12, Track.X, remove = false))
        // Kept up as three tiles of rail each.
        repeat(Calendar.TICKS_PER_DAY * 2) { w.step() }
        assertEquals(10 * Maglev.UPKEEP_TILES + 10, w.companies[0].trackTiles)
        // Trains don't wear it.
        w.wearBy(w.index(12, 10), 50_000_000L)
        w.wearBy(w.index(12, 12), 50_000_000L)
        assertEquals(255, w.condition[w.index(12, 10)].toInt() and 255)
        assertTrue(w.condition[w.index(12, 12)].toInt() and 255 < 255)
        // Maglev pays the electric price.
        assertEquals(EraCosts.ELECTRIC, EraCosts.fuelOf(w.roster[id(w, "base:aurora")]))
    }

    @Test
    fun maglevBendsAreFasterThanRails() {
        // A single 45 from a straight onto a diagonal.
        val steps = intArrayOf(step(0, Track.index(Track.X), Track.XM), step(1, Track.index(Track.X), Track.XM), step(2, Track.index(Track.W), Track.XM))
        assertEquals(CURVE_GENTLE_KMH, bendCaps(steps).max())
        assertEquals(Maglev.GENTLE_KMH, bendCaps(steps, maglev = true).max())
    }

    @Test
    fun save73KeepsStep() {
        val w = line()
        repeat(3_000) { w.step() }
        val again = Save.read(Save.write(w))
        assertTrue(again.settings.future)
        assertTrue((3..120).all { again.guideway[again.index(it, 10)] })
        assertEquals(w.checksum(), again.checksum())
        repeat(3_000) { w.step(); again.step() }
        assertEquals(w.checksum(), again.checksum())
        // Guideway is in the checksum.
        again.guideway[again.index(50, 10)] = false
        assertFalse(w.checksum() == again.checksum())
        assertTrue(again.maglevDepot(again.depots[0]!!))
    }
}
