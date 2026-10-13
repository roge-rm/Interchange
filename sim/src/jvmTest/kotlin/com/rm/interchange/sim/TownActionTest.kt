package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TownActionTest {
    private fun game() = World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))

    private fun World.days(n: Int) = repeat(Calendar.TICKS_PER_DAY * n) { step() }

    @Test
    fun aStatueGoesUpOnceAndStays() {
        val w = game()
        val t = w.towns.maxBy { it.population }
        val money = w.companies[0].money
        val approval = t.approval[0]
        w.apply(Command.TownAction(0, t.id, TownActions.STATUE))
        val tile = t.statue[0]
        assertTrue(tile >= 0, "no statue")
        assertTrue(w.roster.houses[houseOf(w.house[tile].toInt())].monument)
        assertEquals(money - w.townActionCost(TownActions.STATUE), w.companies[0].money)
        assertEquals(approval + STATUE_APPROVAL, t.approval[0])
        assertEquals(-1L, w.demolishCost(0, tile))
        // One each.
        w.apply(Command.TownAction(0, t.id, TownActions.STATUE))
        assertEquals(money - w.townActionCost(TownActions.STATUE), w.companies[0].money)
        // The town builds round it and leaves it be.
        w.days(700)
        assertTrue(w.roster.houses[houseOf(w.house[tile].toInt())].monument, "the statue was pulled down")
    }

    @Test
    fun roadWorksSlowTheStreetsThenLeaveThemNew() {
        val w = game()
        val t = w.towns.maxBy { it.population }
        val street = (0 until w.width * w.height).first { w.townOf[it].toInt() == t.id && w.road[it].toInt() != 0 && w.track[it].toInt() == 0 }
        w.condition[street] = 40
        w.apply(Command.TownAction(0, t.id, TownActions.ROAD_WORKS))
        assertTrue(w.underRoadWorks(street))
        w.days(TownActions.ROAD_WORKS_DAYS + 1)
        assertTrue(!w.underRoadWorks(street))
        assertEquals(255, w.condition[street].toInt() and 255)
    }

    @Test
    fun advertisingLiftsRatingsAndExclusiveRightsShutOthersOut() {
        val w = game()
        w.days(200)
        val rival = w.rivals.first { it.routes.isNotEmpty() }
        val s = w.stations[rival.routes.first().a]!!
        val t = w.towns.minBy { chebyshev(it.x, it.y, s.signX, s.signY) }
        s.rating.fill(100)
        w.apply(Command.TownAction(rival.company, t.id, TownActions.AD_LARGE))
        assertEquals(100 + TownActions.AD_LIFT[TownActions.AD_LARGE], s.rating[Cargo.PASSENGERS])
        w.apply(Command.TownAction(0, t.id, TownActions.EXCLUSIVE))
        assertEquals(0, t.exclusive)
        assertTrue(s.id in w.shutOut())
        // Nobody else can buy them while they run, and they run out after a year.
        w.apply(Command.TownAction(rival.company, t.id, TownActions.EXCLUSIVE))
        assertEquals(0, t.exclusive)
        w.days(TownActions.EXCLUSIVE_DAYS + 1)
        assertEquals(-1, t.exclusive)
        assertTrue(s.id !in w.shutOut())
    }

    @Test
    fun whatTownsWerePaidForIsSaved() {
        val w = game()
        val t = w.towns.maxBy { it.population }
        w.apply(Command.TownAction(0, t.id, TownActions.STATUE))
        w.apply(Command.TownAction(0, t.id, TownActions.FUND))
        w.apply(Command.TownAction(0, t.id, TownActions.EXCLUSIVE))
        val c = Save.read(Save.write(w))
        w.days(60)
        c.days(60)
        assertEquals(w.checksum(), c.checksum())
        val ct = c.towns[t.id]
        assertEquals(t.statue[0], ct.statue[0])
        assertEquals(t.funded, ct.funded)
        assertEquals(0, ct.exclusive)
    }
}
