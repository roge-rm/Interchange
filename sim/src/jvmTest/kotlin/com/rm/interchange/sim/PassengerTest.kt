package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PassengerTest {
    /** The train tests' line, with a row of clapboard houses beside each station, each row a town. */
    private fun line(): World {
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        val house = w.roster.houses.first { it.key == "base:clapboard_house" }
        for ((n, xs) in listOf(10..13, 36..39).withIndex()) {
            val t = Town(n, "Town $n", xs.first, 12, 3)
            w.towns += t
            for (x in xs) {
                val i = w.index(x, 12)
                w.house[i] = packHouse(house.id, Track.YM).toShort()
                w.townOf[i] = n.toShort()
                t.houses++
                t.population += house.residents
            }
        }
        w.catchmentDirty = true
        return w
    }

    private fun World.run(ticks: Int) = repeat(ticks) { step() }

    @Test
    fun stationsGatherPassengersFromTheBuildingsInReach() {
        val w = line()
        w.run(Calendar.TICKS_PER_DAY * 30)
        val a = w.stations[0]!!
        val b = w.stations[1]!!
        assertTrue(a.waiting[Cargo.PASSENGERS] > 0 && b.waiting[Cargo.PASSENGERS] > 0, "${a.waiting.toList()} ${b.waiting.toList()}")
        assertTrue(a.waiting[Cargo.PASSENGERS] > a.waiting[Cargo.MAIL])
    }

    @Test
    fun aTrainCarriesPassengersAndIsPaid() {
        val w = line()
        w.run(Calendar.TICKS_PER_DAY * 20)
        w.apply(Command.BuyTrain(0, 0, 2, 18, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        assertEquals(120, t.capacity(Cargo.PASSENGERS))
        val before = w.companies[0].money
        var carried = false
        repeat(40_000) {
            w.step()
            if (t.load[Cargo.PASSENGERS] > 0) carried = true
        }
        assertTrue(carried)
        assertTrue(t.profitThisYear + t.profitLastYear > 0 || w.companies[0].money > before, "profit ${t.profitThisYear}")
        assertTrue(w.towns.all { it.servedLastMonth > 0 || it.servedThisMonth > 0 })
    }

    @Test
    fun stationsAreNamedAfterTheirTown() {
        val w = World(64, 64, 1)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.towns += Town(0, "Elmbrook", 20, 20, 3)
        w.towns += Town(1, "Saint-Lin", 50, 50, 3, french = true)
        w.apply(Command.Station(0, 18, 18, 21, 18, Track.X))
        w.apply(Command.Station(0, 30, 22, 33, 22, Track.X))
        w.apply(Command.Station(0, 48, 50, 51, 50, Track.X))
        w.apply(Command.Station(0, 50, 54, 50, 57, Track.Y))
        assertEquals(listOf("Elmbrook", "Elmbrook East", "Saint-Lin", "Saint-Lin-Sud"), w.stations.map { it!!.name })
    }

    @Test
    fun slowerTripsPayLess() {
        val a = Station(0, 0, "", 0, 0)
        val b = Station(1, 0, "", 40, 0)
        assertEquals(fare(Cargo.PASSENGERS, 100, a, b, 5), fare(Cargo.PASSENGERS, 100, a, b, 25))
        assertTrue(fare(Cargo.PASSENGERS, 100, a, b, 60) < fare(Cargo.PASSENGERS, 100, a, b, 25))
        assertTrue(fare(Cargo.MAIL, 100, a, b, 5) > fare(Cargo.PASSENGERS, 100, a, b, 5))
    }

    @Test
    fun aLoadedTrainCarriesOnTheSameAfterSaving() {
        val w = line()
        w.apply(Command.BuyTrain(0, 0, 2, 18, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        w.run(9000)
        val c = Save.read(Save.write(w))
        assertEquals(w.trains[0]!!.load.toList(), c.trains[0]!!.load.toList())
        repeat(10_000) { w.step(); c.step() }
        assertEquals(w.checksum(), c.checksum())
    }
}
