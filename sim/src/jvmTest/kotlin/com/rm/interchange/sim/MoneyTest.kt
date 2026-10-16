package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoneyTest {
    private fun World.run(days: Int) = repeat(Calendar.TICKS_PER_DAY * days) { step() }

    @Test
    fun aLoanGoesUpToItsLimitAndBackAndChargesInterest() {
        val w = World(64, 64, 1)
        val c = w.companies[0]
        val start = c.money
        val limit = w.maxLoan()
        repeat(40) { w.apply(Command.Loan(0, borrow = true)) }
        assertEquals(limit, c.loan)
        assertEquals(start + limit, c.money)
        w.run(62)
        // Two months' interest at 4% a year.
        assertEquals(-2 * limit * Prices.INTEREST / 10_000 / 12, c.thisYear[Books.INTEREST])
        repeat(40) { w.apply(Command.Loan(0, borrow = false)) }
        assertEquals(0, c.loan)
    }

    @Test
    fun everyDollarIsBooked() {
        val w = World(64, 64, 1)
        for (i in w.corners.indices) w.corners[i] = 3
        val c = w.companies[0]
        val start = c.money
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        // A Dorchester, the only engine in 1850.
        w.apply(Command.BuyTrain(0, 0, 0, 18, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        w.run(40)
        assertEquals(c.money - start, c.thisYear.sum())
        assertTrue(c.thisYear[Books.CONSTRUCTION] < 0 && c.thisYear[Books.NEW_TRAINS] < 0 && c.thisYear[Books.RUNNING] < 0)
        assertEquals(1, c.history.size)
    }

    @Test
    fun inflationRaisesCostsAndFaresYearByYear() {
        val w = World(NewGame(64, 64, 1, 1900, towns = 0, industries = 0, inflation = true))
        val track = w.price(Prices.TRACK)
        val limit = w.maxLoan()
        while (Calendar.year(w.startYear, w.day) < 1910) w.step()
        assertTrue(w.price(Prices.TRACK) > track * 12 / 10, "${w.price(Prices.TRACK)}")
        assertTrue(w.pay(1000) in 1150..1200, "${w.pay(1000)}")
        assertTrue(w.maxLoan() > limit)
        val off = World(NewGame(64, 64, 1, 1900, towns = 0, industries = 0, inflation = false))
        while (Calendar.year(off.startYear, off.day) < 1905) off.step()
        assertEquals(Prices.TRACK, off.price(Prices.TRACK))
    }

    @Test
    fun theBooksSurviveSaving() {
        val w = World(64, 64, 1)
        w.apply(Command.Loan(0, borrow = true))
        w.run(100)
        val c = Save.read(Save.write(w))
        assertEquals(w.companies[0].loan, c.companies[0].loan)
        assertEquals(w.companies[0].thisYear.toList(), c.companies[0].thisYear.toList())
        assertEquals(w.companies[0].history.size, c.companies[0].history.size)
        assertEquals(Command.Loan(0, true), Codec.decode(Codec.encode(Command.Loan(0, true))))
    }

    @Test
    fun whatACompanyWasSetUpWithIsSavedAndOldSavesHadTheBanks250k() {
        val w = World(NewGame(64, 64, 1, 1900, towns = 0, industries = 0))
        assertEquals(Prices.startMoney(1900), w.companies[0].founded)
        w.companies[0].money -= 1_000
        assertEquals(Prices.startMoney(1900), Save.read(Save.write(w)).companies[0].founded)
        assertEquals(Prices.START_MONEY, Save.read(Save.write(w, version = 31)).companies[0].founded)
    }

    @Test
    fun theLoanLimitAndTownActionsRiseWithTheEra() {
        val early = World(NewGame(64, 64, 1, 1840, towns = 0, industries = 0, inflation = false))
        val late = World(NewGame(64, 64, 1, 2000, towns = 0, industries = 0, inflation = false))
        assertTrue(early.maxLoan() * 5 < late.maxLoan(), "${early.maxLoan()} against ${late.maxLoan()}")
        assertTrue(early.townActionCost(TownActions.STATUE) * 5 < late.townActionCost(TownActions.STATUE))
        // Priced as before in 1950.
        val mid = World(NewGame(64, 64, 1, 1950, towns = 0, industries = 0, inflation = false))
        assertEquals(TownActions.COST[TownActions.STATUE], mid.townActionCost(TownActions.STATUE))
    }

    @Test
    fun anOldSaveOwingMoreThanTheEraLendsIsNotInTrouble() {
        // A 1900 game saved when everyone could borrow $300,000.
        val w = World(NewGame(64, 64, 1, 1900, towns = 0, industries = 0))
        val c = w.companies[0]
        c.loan = 300_000
        c.money = 10_000
        val back = Save.read(Save.write(w, version = 31))
        assertTrue(back.maxLoan() < 300_000)
        repeat(Calendar.TICKS_PER_DAY * 200) { back.step() }
        val b = back.companies[0]
        assertTrue(!back.inTrouble(b) && b.trouble == 0 && !b.gone, "trouble ${b.trouble}")
        assertEquals(300_000, b.loan)
        // It can pay back, but not borrow more.
        back.apply(Command.Loan(0, borrow = true))
        assertEquals(300_000, b.loan)
    }
}
