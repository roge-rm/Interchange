package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BankruptcyTest {
    private fun game(rivals: Int = 1, level: Int = RivalLevel.NORMAL) =
        World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 0, rivals = rivals, rivalLevel = level))

    private fun World.days(n: Int) = repeat(Calendar.TICKS_PER_DAY * n) { step() }

    /** A rival with a route, sunk deep in debt. */
    private fun World.sinking(): Company {
        days(200)
        val r = rivals.first { it.routes.isNotEmpty() }
        val c = companies[r.company]
        c.loan = maxLoan()
        c.money = -maxLoan() * 3
        return c
    }

    @Test
    fun aRivalInTroubleIsWarnedThenOfferedForSale() {
        val w = game()
        val c = w.sinking()
        w.days(31 * TROUBLE_WARN + 5)
        assertTrue(c.trouble >= TROUBLE_WARN)
        assertTrue(!w.forSale(c))
        w.days(31 * (TROUBLE_FOR_SALE - TROUBLE_WARN))
        assertTrue(w.forSale(c), "not for sale after ${c.trouble} months")
        assertEquals(0L, w.salePrice(c))
    }

    @Test
    fun troubleGoesByMonthsOfWorkAtAnyDayLength() {
        val w = World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1, dayLength = 10))
        val c = w.sinking()
        // As many work days as at day length 1, a tenth of the calendar.
        w.days(WORK_MONTH * TROUBLE_WARN + 40)
        assertTrue(c.trouble >= TROUBLE_WARN && !w.forSale(c), "${c.trouble} months")
        w.days(WORK_MONTH * (TROUBLE_FOR_SALE - TROUBLE_WARN))
        assertTrue(w.forSale(c), "not for sale after ${c.trouble} months")
    }

    @Test
    fun buyingOneTakesOnAllItHasAndOwes() {
        val w = game()
        val c = w.sinking()
        w.days(31 * TROUBLE_FOR_SALE + 5)
        assertTrue(w.forSale(c))
        val stations = w.stations.filter { it != null && it.owner == c.id }.map { it!!.id }
        val vehicles = w.roadVehicles.filter { it != null && it.owner == c.id }.map { it!!.id }
        assertTrue(stations.isNotEmpty() && vehicles.isNotEmpty())
        val money = w.companies[0].money
        val owed = c.money - c.loan
        w.apply(Command.BuyCompany(0, c.id))
        assertTrue(c.gone)
        assertTrue(w.rivals.none { it.company == c.id })
        for (s in stations) assertEquals(0, w.stations[s]!!.owner)
        for (v in vehicles) assertEquals(0, w.roadVehicles[v]!!.owner)
        assertEquals(money + owed, w.companies[0].money)
        // It does nothing more, and the world goes on the same saved or not.
        val copy = Save.read(Save.write(w))
        w.days(60)
        copy.days(60)
        assertEquals(w.checksum(), copy.checksum())
        assertTrue(copy.companies[c.id].gone)
    }

    @Test
    fun oneNobodyBuysGoesUnder() {
        val w = game()
        val c = w.sinking()
        val roads = (0 until w.width * w.height).filter { w.roadOwner[it].toInt() == c.id }
        w.days(31 * TROUBLE_BANKRUPT + 5)
        assertTrue(c.gone, "still going after ${c.trouble} months")
        assertTrue(w.stations.none { it != null && it.owner == c.id })
        assertTrue(w.roadVehicles.none { it != null && it.owner == c.id })
        assertTrue(w.depots.none { it != null && it.owner == c.id })
        assertTrue((0 until w.width * w.height).none { w.owner[it].toInt() == c.id || w.roadOwner[it].toInt() == c.id })
        // Its roads are still there, for everyone, all but its garage's bit.
        assertTrue(roads.isNotEmpty())
        assertTrue(roads.count { w.road[it].toInt() != 0 } >= roads.size - 2)
    }

    @Test
    fun aRivalBuysOneUpForSale() {
        val w = game(rivals = 2)
        val c = w.sinking()
        val buyer = w.rivals.first { it.company != c.id }
        val routes = w.rivals.first { it.company == c.id }.routes.size + buyer.routes.size
        w.days(31 * TROUBLE_FOR_SALE + 40)
        assertTrue(c.gone)
        assertEquals(1, w.rivals.size)
        assertTrue(buyer.routes.size >= routes)
        assertTrue(w.stations.none { it != null && it.owner == c.id })
    }

    @Test
    fun thePlayerGoesUnderAfterAYearAndStartsAgain() {
        val w = game()
        val c = w.companies[0]
        c.loan = w.maxLoan()
        c.money = -w.maxLoan() * 3
        w.days(31 * TROUBLE_FOR_SALE + 5)
        // Not for sale on normal, only warned.
        assertTrue(!w.forSale(c))
        assertTrue(w.chronicle.any { it.kind == News.COMPANY_TROUBLE && it.about == 0 })
        w.days(31 * (TROUBLE_PLAYER - TROUBLE_FOR_SALE))
        assertTrue(!c.gone)
        assertEquals(0, c.trouble)
        assertEquals(0L, c.loan)
        assertTrue(c.money > 0)
        assertTrue(w.chronicle.any { it.kind == News.COMPANY_RESTARTED })
    }

    @Test
    fun aHardRivalBuysThePlayerUpForSale() {
        val w = game(level = RivalLevel.HARD)
        w.days(200)
        val c = w.companies[0]
        c.loan = w.maxLoan()
        c.money = -w.maxLoan() * 3
        w.days(31 * TROUBLE_FOR_SALE + 40)
        val buyer = w.rivals.first().company
        assertTrue(w.chronicle.any { it.kind == News.COMPANY_BOUGHT && it.about == (0 or (buyer shl 8)) }, "nobody bought the player")
        // The player carries on with a new company.
        assertTrue(!c.gone)
        assertEquals(0L, c.loan)
        assertTrue(c.money > 0)
    }

    @Test
    fun aPlayerCanBuyARivalOutrightAtAPremium() {
        val w = game()
        w.days(200)
        val c = w.companies[w.rivals.first { it.routes.isNotEmpty() }.company]
        assertTrue(!w.forSale(c) && w.canTakeOver(c))
        assertTrue(!w.canTakeOver(w.companies[0]))
        val price = w.takeoverPrice(c)
        assertEquals(w.salePrice(c) * 3 / 2, price)
        val me = w.companies[0]
        // Not enough money, nothing happens.
        me.money = price - 1
        w.apply(Command.BuyCompany(0, c.id))
        assertTrue(!c.gone)
        me.money = price + 1000
        val owed = c.money - c.loan
        w.apply(Command.BuyCompany(0, c.id))
        assertTrue(c.gone)
        assertEquals(1000 + owed, me.money)
        // Rivals can't take over a player that isn't for sale.
        val other = World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        other.days(200)
        val rival = other.rivals.first().company
        other.companies[rival].money = Long.MAX_VALUE / 8
        other.apply(Command.BuyCompany(rival, 0))
        assertTrue(!other.companies[0].gone)
    }
}
