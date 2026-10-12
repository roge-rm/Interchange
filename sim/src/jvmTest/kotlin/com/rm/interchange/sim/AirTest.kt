package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AirTest {
    /** Flat land a step up, a regional airport at (5, 5) and another at (40, 40), and a lake from x = 20 to 29, y = 50 to 59. */
    private fun airports(year: Int = 1965): World {
        val w = World(64, 64, 1, startYear = year)
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 20..30 && y in 50..60) 0 else 1
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        w.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
        w.apply(Command.Airport(0, 40, 40, Airports.REGIONAL, Track.Y))
        return w
    }

    private fun World.buy(key: String, airport: Int = 0, orders: IntArray = intArrayOf(0, 1)): Aircraft {
        val model = roster.engines.first { it.key == "base:$key" }.id
        apply(Command.BuyCraft(0, airports[airport]!!.depot, model))
        val id = aircraft.size - 1
        apply(Command.CraftOrder(0, true, id, Command.TrainOrder.ORDERS, orders))
        apply(Command.CraftOrder(0, true, id, Command.TrainOrder.START_STOP))
        return aircraft[id]!!
    }

    private fun calls(w: World, v: Aircraft, ticks: Int): List<Int> {
        val called = ArrayList<Int>()
        repeat(ticks) {
            val before = v.waiting
            w.step()
            if (v.state == Flight.GATE && v.waiting > before) called += w.airports[v.at]!!.station
        }
        return called
    }

    @Test
    fun airportsTakeFlatLandAndMakeStations() {
        val w = airports()
        assertEquals(2, w.airports.size)
        assertEquals(10, w.stations[0]!!.aero.size)
        assertEquals(Depots.HANGAR, w.depots[w.airports[0]!!.depot]!!.kind)
        // Not over another, and not before their time.
        w.apply(Command.Airport(0, 6, 5, Airports.STRIP, Track.X))
        assertEquals(2, w.airports.size)
        val old = World(64, 64, 1, startYear = 1925)
        old.companies[0].money = 10_000_000
        old.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
        assertEquals(0, old.airports.size)
    }

    @Test
    fun aDc3FliesBetweenItsAirports() {
        val w = airports()
        val v = w.buy("dc3")
        val called = calls(w, v, 20_000)
        assertTrue(called.size >= 4, "called at $called")
        // It loads at home first.
        assertEquals(listOf(0, 1, 0, 1), called.take(4))
    }

    @Test
    fun jetsNeedLongerRunways() {
        val w = airports()
        val model = w.roster.engines.first { it.key == "base:dc8" }.id
        w.apply(Command.BuyCraft(0, w.airports[0]!!.depot, model))
        assertEquals(0, w.aircraft.size)
    }

    @Test
    fun fogClosesAnAirportWithoutLandingAids() {
        val w = airports()
        val v = w.buy("dc3")
        val b = w.airports[1]!!
        w.airSkies[b.id] = 1
        assertEquals("fog", w.cantLand(v, b))
        w.apply(Command.AirportUpgrade(0, b.id, 0))
        assertTrue(b.ils)
        w.airSkies[b.id] = 1
        assertNull(w.cantLand(v, b))
    }

    @Test
    fun stormsGroundSmallAircraftFirst() {
        val w = airports()
        val dc3 = w.buy("dc3")
        w.apply(Command.Airport(0, 20, 20, Airports.STRIP, Track.X))
        val strip = w.airports.last()!!
        val beaver = w.buy("beaver", strip.id, intArrayOf(strip.station))
        w.weather.wind = 50
        assertNull(w.cantLand(dc3, w.airports[1]!!))
        assertEquals("storm", w.cantLand(beaver, strip))
    }

    @Test
    fun aSeaplaneBaseIsShutWhileItsLakeFreezes() {
        val w = airports()
        // On the lake's west shore, its water out to the east.
        w.apply(Command.Airport(0, 19, 55, Airports.SEAPLANE, 0))
        val base = w.airports.last()!!
        assertEquals(Track.XP, base.axis)
        val v = w.buy("beaver", base.id, intArrayOf(base.station))
        v.gear = Gear.FLOATS
        val water = w.index(20, 55)
        assertNull(w.cantLand(v, base))
        w.weather.ice[water] = 10
        assertEquals("ice", w.cantLand(v, base))
        v.gear = Gear.SKIS
        assertEquals("ice", w.cantLand(v, base))
        w.weather.ice[water] = 40
        assertNull(w.cantLand(v, base))
        // A DC-3 can't use it at all.
        val dc3 = w.roster.engines.first { it.key == "base:dc3" }
        assertTrue(!base.serves(dc3))
    }

    @Test
    fun aircraftSaveAndCarryOnTheSame() {
        val w = airports()
        w.buy("dc3")
        w.buy("jetranger", 1, intArrayOf(1, 0))
        repeat(700) { w.step() }
        val copy = Save.read(Save.write(w))
        assertEquals(w.checksum(), copy.checksum())
        repeat(3000) { w.step(); copy.step() }
        assertEquals(w.checksum(), copy.checksum())
    }
}
