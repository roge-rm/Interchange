package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rivals with a style: shipping lines and airlines, their ways, and how they save. */
class RivalStyleTest {
    private fun World.days(n: Int) = repeat(Calendar.TICKS_PER_DAY * n) { step() }

    /**
     * Flat land a step up with a lake from x 30 to 89, y 8 to 55, a town to the west, a coal mine on the west shore at
     * its busiest and a steel mill on the east, and one shipping rival. With [split] a strip of land cuts the lake in
     * two at x 60.
     */
    private fun lake(split: Boolean = false): World {
        val w = World(128, 64, 1, startYear = 1960)
        for (y in 0..64) for (x in 0..128) w.corners[w.corner(x, y)] = if (x in 30..90 && y in 8..56 && !(split && x in 59..62)) 0 else 1
        w.shoreChanged()
        w.editor = true
        w.apply(Command.PlaceTown(0, 12, 32, "Kenora", TownSize.MEDIUM))
        w.apply(Command.PlaceIndustry(0, "base:coal_mine", 25, 30, 0))
        w.apply(Command.PlaceIndustry(0, "base:steel_mill", 93, 30, 0))
        w.editor = false
        assertEquals(1, w.towns.size)
        assertEquals(2, w.industries.size)
        // A ship's load of coal at a smaller mine takes too long to fill to pay.
        w.industries[0].level = MAX_LEVEL
        w.startRivals(1, styled = false)
        w.rivals[0].style = RivalStyle.SHIPPING
        return w
    }

    /** Flat land a step up with two cities far apart at either end, and one airline. */
    private fun cities(): World {
        val w = World(192, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 1
        w.shoreChanged()
        w.editor = true
        w.apply(Command.PlaceTown(0, 25, 32, "Kenora", TownSize.CITY))
        w.apply(Command.PlaceTown(0, 165, 32, "Dryden", TownSize.CITY))
        w.editor = false
        assertEquals(2, w.towns.size)
        w.startRivals(1, styled = false)
        w.rivals[0].style = RivalStyle.AIRLINE
        return w
    }

    private fun World.rivalCompany() = companies[rivals[0].company]

    @Test
    fun aGamesRivalsGetMoreThanOneStyleAndNoShipsOnADryMap() {
        for (seed in 1L..4L) {
            val w = World(NewGame(128, 128, seed, 1960, Region.MIXED, sea = 0, towns = 6, industries = 4, rivals = 4))
            assertTrue(!w.waterForShips(), "water enough on a dry map, seed $seed")
            val styles = w.rivals.map { it.style }
            assertEquals(RivalStyle.GENERAL, styles[0])
            assertTrue(styles.toSet().size > 1, "all $styles, seed $seed")
            assertTrue(RivalStyle.SHIPPING !in styles, "a shipping line on a dry map, seed $seed")
        }
        // Before any airport's on sale, no airline.
        val old = World(NewGame(128, 128, 1, 1880, Region.MIXED, sea = 0, towns = 6, industries = 4, rivals = 4))
        assertTrue(old.rivals.none { it.style == RivalStyle.AIRLINE })
    }

    @Test
    fun namesSuitTheStyle() {
        val w = World(NewGame(128, 128, 2, 1960, Region.MIXED, sea = 0, towns = 6, industries = 4, rivals = 4))
        for (r in w.rivals) if (r.style == RivalStyle.AIRLINE) {
            val name = w.companies[r.company].name
            assertTrue(listOf("Air", "Airways", "Aviation").any { it in name }, name)
        }
    }

    @Test
    fun aShippingLineCarriesCoalAcrossTheLake() {
        val w = lake()
        val c = w.rivalCompany()
        w.days(365 * 3)
        val route = w.rivals[0].routes.firstOrNull { it.ship }
        assertNotNull(route, "no shipping line")
        assertEquals(2, w.docks.values.toSet().count { w.stations[it]?.owner == c.id })
        assertTrue(w.depots.any { it != null && it.owner == c.id && it.kind == Depots.SHIPYARD }, "no shipyard")
        val ship = w.ships.firstOrNull { it != null && it.owner == c.id }
        assertNotNull(ship, "no ship")
        assertTrue(w.industries[1].made.sum() > 0 || c.lastYear[Books.INCOME] + c.thisYear[Books.INCOME] > 0, "nothing delivered")
        assertTrue(c.lastYear[Books.INCOME] + c.thisYear[Books.INCOME] > 0, "no income")
    }

    @Test
    fun anAirlineJoinsTwoCitiesAndCarriesPeople() {
        val w = cities()
        val c = w.rivalCompany()
        w.days(365 * 2)
        assertTrue(w.rivals[0].routes.any { it.air }, "no air route")
        assertEquals(2, w.airports.count { it != null && it.owner == c.id })
        val planes = w.aircraft.filter { it != null && it.owner == c.id }
        assertTrue(planes.isNotEmpty(), "no aircraft")
        assertTrue(c.lastYear[Books.INCOME] + c.thisYear[Books.INCOME] > 0, "no income")
    }

    @Test
    fun theLookForWaterIsSpreadOverTicks() {
        val w = lake()
        w.shipTickStates = 50
        val r = w.rivals[0]
        var ticks = 0
        while (r.shipJob == null && ticks < Calendar.TICKS_PER_DAY * 60) { w.step(); ticks++ }
        assertNotNull(r.shipJob, "no job")
        var goes = 0
        while (r.shipJob != null) {
            val job = r.shipJob!!
            w.step()
            assertTrue(job.spent <= 50, "${job.spent} tiles in a tick")
            goes++
        }
        assertTrue(goes > 10, "done in $goes ticks")
        assertTrue(r.routes.any { it.ship }, "nothing built")
    }

    @Test
    fun aLineWhoseWaterDoesntJoinIsntTriedAgainAtOnce() {
        val w = lake(split = true)
        val r = w.rivals[0]
        w.days(90)
        val key = shipKey(0, 1, Cargo.COAL)
        val first = r.failed[key]
        assertNotNull(first, "not given up")
        assertTrue(r.routes.none { it.ship })
        w.days(365)
        assertEquals(first, r.failed[key], "tried again")
        assertNull(r.shipJob)
    }

    @Test
    fun aLosingShippingLineIsSoldAndTakenDown() {
        val w = lake()
        val c = w.rivalCompany()
        val r = w.rivals[0]
        w.days(365 * 3)
        assertTrue(r.routes.any { it.ship }, "no shipping line")
        // Every ship loses money and has for two years.
        var days = 0
        while (r.routes.any { it.ship } && days < 365 * 2) {
            for (s in w.ships) if (s != null && s.owner == c.id) { s.profitLastYear = -1_000_000; s.profitThisYear = -1_000_000 }
            w.days(1)
            days++
        }
        assertTrue(r.routes.none { it.ship }, "still running")
        assertTrue(w.ships.none { it != null && it.owner == c.id }, "ships left")
        assertTrue(w.docks.values.none { w.stations[it]?.owner == c.id }, "docks left")
        assertTrue(w.depots.none { it != null && it.owner == c.id && it.kind == Depots.SHIPYARD }, "shipyard left")
        assertNotNull(r.failed[shipKey(0, 1, Cargo.COAL)])
    }

    @Test
    fun aSaveMidLookCarriesOnTheSame() {
        val w = lake()
        w.shipTickStates = 50
        val r = w.rivals[0]
        while (r.shipJob?.queue == null || r.shipJob!!.head < 100) w.step()
        val bytes = Save.write(w)
        val copy = Save.read(bytes)
        copy.shipTickStates = 50
        assertEquals(w.checksum(), copy.checksum())
        assertTrue(Save.write(copy).contentEquals(bytes), "saved again differently")
        assertEquals(RivalStyle.SHIPPING, copy.rivals[0].style)
        repeat(Calendar.TICKS_PER_DAY * 120) {
            w.step()
            copy.step()
            assertEquals(w.checksum(), copy.checksum(), "out of step at tick ${w.tick}")
        }
        assertTrue(copy.rivals[0].routes.any { it.ship })
        assertEquals(RivalRoute.SHIP, copy.rivals[0].routes.first { it.ship }.craft)
    }

    @Test
    fun stylesAndWaysAreSaved() {
        val w = World(NewGame(128, 128, 3, 1960, Region.MIXED, towns = 6, industries = 4, rivals = 4))
        val copy = Save.read(Save.write(w))
        for ((a, b) in w.rivals.zip(copy.rivals)) {
            assertEquals(a.style, b.style)
            assertEquals(a.borrow, b.borrow)
            assertEquals(a.far, b.far)
            assertEquals(a.freight, b.freight)
        }
        assertEquals(w.checksum(), copy.checksum())
    }

    @Test
    fun aVersion65SaveLoadsWithGeneralRivalsAndTheSameChecksum() {
        // Without the settings a version 65 save loads without.
        val w = World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 3, rivals = 3, stockpiles = false, contracts = false, handling = false, eraCosts = false, energy = false, future = false))
        // As a game from before styles had them.
        for (r in w.rivals) { r.style = RivalStyle.GENERAL; r.borrow = 2; r.far = 1; r.freight = false }
        w.days(200)
        // And from before service scores, which version 68 added.
        for (s in w.stations) if (s != null) s.service = -1
        val old = Save.read(Save.write(w, version = 65))
        assertTrue(old.rivals.all { it.style == RivalStyle.GENERAL && !it.styled })
        assertEquals(w.checksum(), old.checksum())
        repeat(60) {
            w.days(1)
            old.days(1)
            assertEquals(w.checksum(), old.checksum(), "out of step on day $it")
        }
    }

    @Test
    fun twoWorldsKeepInStep() {
        for (make in listOf({ lake() }, { cities() })) {
            val a = make()
            val b = make()
            repeat(365) {
                a.days(1)
                b.days(1)
                assertEquals(a.checksum(), b.checksum(), "out of step on day $it")
            }
        }
    }
}
