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
        assertTrue(a.waiting(Cargo.PASSENGERS) > 0 && b.waiting(Cargo.PASSENGERS) > 0)
        assertTrue(a.waiting(Cargo.PASSENGERS) > a.waiting(Cargo.MAIL))
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
        var carried = false
        var paid = 0L
        // Each town has had some of its people picked up at some point.
        val picked = BooleanArray(w.towns.size)
        repeat(40_000) {
            w.step()
            if (t.load(Cargo.PASSENGERS) > 0) carried = true
            for (town in w.towns) if (town.servedThisMonth > 0) picked[town.id] = true
            paid += w.payments.filter { it.tick == w.tick }.sumOf { it.amount }
        }
        assertTrue(carried)
        assertTrue(paid > 0, "no fares paid")
        assertTrue(picked.all { it })
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
        assertEquals(fare(Cargo.PASSENGERS, 100, a, b, 14), fare(Cargo.PASSENGERS, 100, a, b, 25))
        // Faster than about three tiles a day pays more, as an aircraft goes.
        assertTrue(fare(Cargo.PASSENGERS, 100, a, b, 5) > fare(Cargo.PASSENGERS, 100, a, b, 14))
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
        assertEquals(w.trains[0]!!.load(Cargo.PASSENGERS), c.trains[0]!!.load(Cargo.PASSENGERS))
        repeat(10_000) { w.step(); c.step() }
        assertEquals(w.checksum(), c.checksum())
    }

    /**
     * Three towns along one line, stations A, B and C, with one train between A and B and another between B and
     * C: passengers from A for C ride the first train, change at B and ride the second.
     */
    @Test
    fun passengersChangeTrainsToGetWhereTheyreGoing() {
        // Collisions off: the two trains share station B with no signals, waiting for each other there.
        val w = World(NewGame(96, 64, 1, 1900, towns = 0, industries = 0, weather = false, collisions = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        // A depot at each end, one for each train.
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Depot(0, 91, 10, Track.XM))
        w.apply(Command.Rail(0, 3, 10, 90, 10, Track.X, remove = false))
        val house = w.roster.houses.first { it.key == "base:clapboard_house" }
        for ((n, xs) in listOf(10..13, 46..49, 82..85).withIndex()) {
            val t = Town(n, "Town $n", xs.first, 12, 3)
            w.towns += t
            for (x in xs) {
                val i = w.index(x, 12)
                w.house[i] = packHouse(house.id, Track.YM).toShort()
                w.townOf[i] = n.toShort()
                t.houses++
                t.population += house.residents
            }
            w.apply(Command.Station(0, xs.first, 10, xs.last, 10, Track.X))
        }
        w.catchmentDirty = true
        for ((k, orders) in listOf(intArrayOf(0, 1), intArrayOf(1, 2)).withIndex()) {
            w.apply(Command.BuyTrain(0, k, 2, 18, 3))
            w.apply(Command.TrainOrder(0, k, Command.TrainOrder.ORDERS, orders))
            w.apply(Command.TrainOrder(0, k, Command.TrainOrder.START_STOP))
        }
        assertEquals(1, w.nextHop(0, 2))
        assertEquals(2, w.nextHop(1, 2))
        assertEquals(-1, w.nextHop(2, 0).let { if (it == 1) -1 else it })
        // Some of A's passengers are bound for C.
        var forC = false
        repeat(Calendar.TICKS_PER_DAY * 10) {
            w.step()
            if (w.stations[0]!!.cargo[Cargo.PASSENGERS].any { it.dest == 2 }) forC = true
        }
        assertTrue(forC)
        // Watch for them arriving at C: a payment there for passengers who started at A.
        var throughToC = false
        var paidAtC = 0L
        repeat(60_000) {
            val onSecond = w.trains[1]!!.cargo[Cargo.PASSENGERS].any { it.origin == 0 && it.dest == 2 }
            w.step()
            if (onSecond) throughToC = true
            paidAtC += w.payments.filter { it.tick == w.tick && w.stationTiles[it.tile] == 2 }.sumOf { it.amount }
        }
        assertTrue(throughToC, "nobody from A rode the second train")
        assertTrue(paidAtC > 0, "nothing paid at C")
        // The first train only goes as far as B, but its legs towards C are credited to it.
        assertTrue(w.trains[0]!!.profitThisYear + w.trains[0]!!.profitLastYear > -10_000)
    }

    @Test
    fun withNoLinksPassengersGoAnywhere() {
        val w = line()
        w.run(Calendar.TICKS_PER_DAY * 5)
        assertTrue(w.stations[0]!!.cargo[Cargo.PASSENGERS].all { it.dest == -1 })
    }

    @Test
    fun theCalendarKnowsItsWeeksAndHolidays() {
        // 1 January 1925 was a Thursday, and 1 July 1925 a Wednesday.
        assertEquals(3, Calendar.weekday(1925, 0))
        assertEquals(2, Calendar.weekday(1925, 181))
        assertTrue(Calendar.holiday(1925, 181))
        assertEquals(0, Calendar.mail(1925, 181))
        assertEquals(250, Calendar.mail(1925, 340))
        assertTrue(Calendar.travel(1925, 0) > Calendar.travel(1925, 3))
        // Thanksgiving 1925 was Monday 9 November, with Armistice Day; in 1960, a leap year, it was Monday 10 October.
        assertTrue(Calendar.holiday(1925, 312))
        assertTrue(!Calendar.holiday(1925, 284))
        assertTrue(Calendar.holiday(1960, 283))
        // 29 February 2024 is there, and was a Thursday; 1 March 1900 follows 28 February, 1900 not being a leap year.
        assertEquals(1, Calendar.month(2024, 59))
        assertEquals(29, Calendar.dayOfMonth(2024, 59))
        assertEquals(3, Calendar.weekday(2024, 59))
        assertEquals(2, Calendar.month(1900, 59))
        assertEquals(2025, Calendar.year(2024, 366))
        assertTrue(Calendar.newYear(2024, 366))
        // No Dominion Day before 1879, and no Labour Day before 1894.
        assertTrue(!Calendar.holiday(1850, 181))
        assertTrue(Calendar.holiday(1850, 0))
    }

    @Test
    fun ratingsFallWithoutServiceAndRiseWithIt() {
        val w = line()
        w.run(Calendar.TICKS_PER_DAY * 60)
        val idle = w.stations[0]!!.rating[Cargo.PASSENGERS]
        assertTrue(idle < START_RATING, "rating $idle")
        w.apply(Command.BuyTrain(0, 0, 2, 18, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        w.run(Calendar.TICKS_PER_DAY * 120)
        assertTrue(w.stations[0]!!.rating[Cargo.PASSENGERS] > idle)
    }

    @Test
    fun aTownThatThinksLittleOfACompanyWontHaveItsStations() {
        val w = line()
        w.towns[0].approval[0] = APPROVAL_REFUSES
        w.apply(Command.Station(0, 15, 10, 16, 10, Track.X))
        assertEquals(2, w.stations.size)
        assertEquals(0, approvalLevel(-1000))
        assertEquals(7, approvalLevel(1000))
    }

    @Test
    fun fellingTreesAloneNeverStopsAStation() {
        val w = line()
        val t = w.towns[0]
        repeat(200) { w.fellTrees(w.companies[0], w.index(t.x, t.y + 2).also { i -> w.trees[i] = Trees.pack(Trees.MAPLE, 3).toByte() }) }
        assertTrue(t.approval[0] > APPROVAL_REFUSES)
    }
}
