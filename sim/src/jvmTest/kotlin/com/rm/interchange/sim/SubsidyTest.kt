package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubsidyTest {
    private fun game() = World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 3, rivals = 2))

    private fun World.days(n: Int) = repeat(Calendar.TICKS_PER_DAY * n) { step() }

    @Test
    fun subsidiesAreOfferedAndRunOut() {
        val w = game()
        w.days(365 * 2)
        assertTrue(w.subsidies.isNotEmpty(), "nothing was offered")
        // An offer nobody took has run out after its year.
        val first = w.subsidies.first()
        if (first.company < 0) assertTrue(!first.open)
        assertTrue(w.subsidies.count { it.open && it.company < 0 } <= 4)
    }

    @Test
    fun theFirstToCarryItWinsItAndIsPaidDouble() {
        val w = game()
        w.days(200)
        val rival = w.rivals.first { r -> r.routes.any { !it.freight } }
        val route = rival.routes.first { !it.freight }
        // Only this one on offer, so one the rival already won for the same towns doesn't take its place.
        w.subsidies.clear()
        w.subsidies += Subsidy(Cargo.PASSENGERS, route.townA, route.townB, towns = true)
        val k = w.subsidies.size - 1
        val s = w.subsidies[k]
        w.days(120)
        assertEquals(rival.company, s.company, "the rival running the route didn't win it")
        assertTrue(s.open)
        // Double for the winner, plain for anyone else.
        val a = w.stations[route.a]!!
        val b = w.stations[route.b]!!
        assertEquals(200L, w.subsidised(rival.company, Cargo.PASSENGERS, a, b, 100))
        assertEquals(100L, w.subsidised(0, Cargo.PASSENGERS, a, b, 100))
        // Saved and loaded, it carries on the same.
        val c = Save.read(Save.write(w))
        w.days(30)
        c.days(30)
        assertEquals(w.checksum(), c.checksum())
        assertEquals(rival.company, c.subsidies[k].company)
    }

    @Test
    fun aCompanyIsWorthItsMoneyLessLoanAndItsVehicles() {
        val w = game()
        w.days(200)
        val rival = w.rivals.first()
        val c = w.companies[rival.company]
        val vehicles = w.roadVehicles.filter { it != null && it.owner == c.id }.sumOf { w.roadValue(it!!) }
        assertEquals(c.money - c.loan + vehicles + w.trains.filter { it != null && it.owner == c.id }.sumOf { w.value(it!!) }, w.companyValue(c))
    }
}
