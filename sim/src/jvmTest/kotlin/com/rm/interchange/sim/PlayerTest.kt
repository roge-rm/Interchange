package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Money against a scripted [Player] and the roster: what a company starts with, and how it fares in its first years. */
class PlayerTest {
    /**
     * A sensible first railway in [year]: 30 tiles of track, a station of five platforms at each end, a depot, and the
     * cheapest engine within a quarter of the fastest (steam or diesel) with its tender and three of the day's coaches.
     */
    private fun firstLine(r: Roster, year: Int): Long {
        val coach = r.available(year, false).filter { Cargo.PASSENGERS in it.cargoes && it.capacity > 0 }.maxBy { it.capacity * 16 / it.length }
        val engines = r.available(year, true).filter { it.traction != Traction.ELECTRIC }
        val fastest = engines.maxOf { it.speed }
        val engine = engines.filter { it.speed * 4 >= fastest * 3 }.minBy { it.cost + (r.getOrNull(it.tender)?.cost ?: 0) }
        val train = engine.cost + (r.getOrNull(engine.tender)?.cost ?: 0) + coach.cost * 3
        return train + Prices.TRACK * 30 + Prices.PLATFORM * 10 + Prices.DEPOT
    }

    @Test
    fun startMoneyBuysOneFirstLineAndALittleNotThree() {
        val r = Roster.BASE
        for (year in 1836..2060 step 4) {
            val line = firstLine(r, year)
            val start = Prices.startMoney(year)
            val w = World(NewGame(64, 64, 1, year, towns = 0, industries = 0))
            assertEquals(start, w.companies[0].money)
            // Within a few years of a dearer engine coming in, it can be a little short; the loan makes it up.
            assertTrue(start * 10 >= line * 8, "$year: start $start, first line $line")
            assertTrue(start + w.maxLoan() >= line * 3 / 2, "$year: with the loan ${start + w.maxLoan()}, first line $line")
            assertTrue(start < line * 2, "$year: start $start buys two lines of $line")
        }
    }

    @Test
    fun aNewCompanyIsWorthNothingTillItEarns() {
        val w = Player.game(1, 1950, 1)
        assertEquals(0, w.companyValue(w.companies[0]))
        assertEquals(w.companies[0].money, w.netWorth(w.companies[0]))
    }

    /** The scripted player is solvent and growing by its fifth year, and not sitting on a pile of cash from the first. */
    private fun solventAndGrowing(year: Int, dayLength: Int) {
        val p = Player.run(1, year, dayLength, 5)
        val c = p.company
        val y = p.years
        val note = y.joinToString("\n") { "${it.year}: money ${it.money}, loan ${it.loan}, value ${it.value}, profit ${it.profit}, routes ${it.routes}" }
        assertTrue(!c.gone && c.trouble == 0, "in trouble\n$note")
        assertTrue(p.vehicles() > 0, "nothing running\n$note")
        assertTrue(y.last().profit > 0, "losing in year 5\n$note")
        // Worth more, or earning more in a year it spent on new lines, as value counts track at nothing and vehicles at resale.
        val spending = y.last().built + y.last().bought > 0 && y.last().profit > y[1].profit
        assertTrue(y.last().value > y[1].value || spending, "not growing\n$note")
        // A year's work doesn't make it rich: at most a few times what it started with, at any day length.
        assertTrue(y.first().money < p.startMoney * 3, "rich after a year\n$note")
    }

    @Test
    fun the1950PlayerIsSolventAndGrowingAtDayLength1() = solventAndGrowing(1950, 1)

    @Test
    fun the1950PlayerIsSolventAndGrowingAtDayLength10() = solventAndGrowing(1950, 10)
}
