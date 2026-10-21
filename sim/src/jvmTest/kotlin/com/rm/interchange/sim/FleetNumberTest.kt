package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FleetNumberTest {
    /** Two companies, each with a depot, on flat land. */
    private fun world(): World {
        val w = World(NewGame(64, 64, 1, 1900, towns = 0, industries = 0, inflation = false, weather = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.companies += Company(1, "North", 10_000_000)
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Depot(1, 2, 20, Track.XP))
        assertEquals(2, w.depots.count { it != null })
        return w
    }

    private fun buy(w: World, company: Int, depot: Int): Train {
        val engine = w.roster.engines.first { it.key == "base:american" }.id
        val wagon = w.roster.engines.first { it.key == "base:coach_wood" }.id
        val before = w.trains.size
        w.apply(Command.BuyTrain(company, depot, engine, wagon, 2))
        assertEquals(before + 1, w.trains.size)
        return w.trains[before]!!
    }

    @Test
    fun eachCompanyNumbersItsOwnTrains() {
        val w = world()
        val a = buy(w, 0, 0)
        val b = buy(w, 1, 1)
        val c = buy(w, 1, 1)
        val d = buy(w, 0, 0)
        assertEquals(0, a.id)
        assertEquals(1, b.id)
        assertEquals(2, c.id)
        assertEquals(3, d.id)
        assertEquals(1, a.number)
        assertEquals(1, b.number)
        assertEquals(2, c.number)
        assertEquals(2, d.number)
    }

    @Test
    fun aSoldNumberIsFreeForTheNextOfThatCompany() {
        val w = world()
        val first = buy(w, 0, 0)
        buy(w, 1, 1)
        buy(w, 0, 0)
        w.apply(Command.TrainOrder(0, first.id, Command.TrainOrder.SELL))
        assertNull(w.trains[first.id])
        val again = buy(w, 0, 0)
        assertEquals(1, again.number)
        assertEquals(3, again.id)
        assertEquals(2, w.trains[2]!!.number)
    }

    @Test
    fun aRefusedBuyTakesNoNumber() {
        val w = world()
        w.companies[0].money = 0
        val before = w.trains.size
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:american" }.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 2))
        assertEquals(before, w.trains.size)
        w.companies[0].money = 10_000_000
        assertEquals(1, buy(w, 0, 0).number)
    }

    @Test
    fun aSavedGameKeepsTheNumbers() {
        val w = world()
        buy(w, 0, 0)
        buy(w, 1, 1)
        buy(w, 0, 0)
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.SELL))
        val again = buy(w, 0, 0)
        assertEquals(1, again.number)
        val back = Save.read(Save.write(w))
        assertNull(back.trains[0])
        assertEquals(1, back.trains[1]!!.number)
        assertEquals(2, back.trains[2]!!.number)
        assertEquals(1, back.trains[3]!!.number)
        // Company 1's train, company 0's second, and the one that took the freed number.
        assertEquals(1, back.trains[1]!!.owner)
        assertEquals(0, back.trains[2]!!.owner)
        assertEquals(0, back.trains[3]!!.owner)
    }

    @Test
    fun anOlderSaveNumbersEachCompanyInOrder() {
        val w = world()
        buy(w, 0, 0)
        buy(w, 1, 1)
        buy(w, 1, 1)
        buy(w, 0, 0)
        val old = Save.read(Save.write(w, version = 40))
        assertEquals(w.checksum(), old.checksum())
        assertEquals(1, old.trains[0]!!.number)
        assertEquals(1, old.trains[1]!!.number)
        assertEquals(2, old.trains[2]!!.number)
        assertEquals(2, old.trains[3]!!.number)
        assertEquals(0, old.trains[0]!!.owner)
        assertEquals(1, old.trains[1]!!.owner)
        assertEquals(0, old.trains[3]!!.owner)
    }
}
