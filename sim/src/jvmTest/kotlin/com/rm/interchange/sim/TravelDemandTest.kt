package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TravelDemandTest {
    /** Stations A, B and C along one line, ids 0 to 2, each with a row of houses as a town, and a depot at the west end. */
    private fun line(): World {
        val w = World(NewGame(96, 64, 1, 1900, towns = 0, industries = 0, weather = false, collisions = false, energy = false, future = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
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
        return w
    }

    private fun World.key(k: String) = roster.engines.first { it.key == k }.id

    private fun World.train(vararg orders: Int, start: Boolean = false): Train {
        apply(Command.BuyTrain(0, 0, key("base:american"), key("base:coach_wood"), 3, -1))
        val id = trains.indexOfLast { it != null }
        apply(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, orders))
        if (start) apply(Command.TrainOrder(0, id, Command.TrainOrder.START_STOP))
        return trains[id]!!
    }

    private fun World.run(ticks: Int) = repeat(ticks) { step() }

    /** Last month's departures on the link from [a] to [b]. */
    private fun World.departures(a: Int, b: Int, n: Int) {
        linkStats.getOrPut(linkKey(a, b)) { LinkStats() }.last.departures = n
    }

    @Test
    fun aFrequentSlowWayWinsOverAFastRareOne() {
        val w = line()
        w.train(0, 2)
        w.train(0, 1)
        w.train(1, 2)
        // Straight there is quicker on the move, by B slower with a change.
        repeat(30) {
            w.timeLink(0, 2, 1000)
            w.timeLink(0, 1, 600)
            w.timeLink(1, 2, 600)
        }
        w.linksWeek()
        assertEquals(2, w.nextHop(0, 2))
        // One train a month straight there, one a day by B: by B wins.
        w.departures(0, 2, 1)
        w.departures(0, 1, 30)
        w.departures(1, 2, 30)
        w.linksWeek()
        val rare = w.linkCosts.getValue(linkKey(0, 2))
        val often = w.linkCosts.getValue(linkKey(0, 1))
        println("TRAVEL rare link cost $rare, frequent $often")
        assertEquals(1000 + WAIT_MOST_DAYS * Calendar.TICKS_PER_DAY / 2, rare)
        assertEquals(600 + Calendar.TICKS_PER_DAY / 2, often)
        assertEquals(1, w.nextHop(0, 2))
        // The way from A to C costs both links, a change each and the waits.
        val toC = w.routeCosts[Cargo.PASSENGERS][0]!!.getValue(2)
        assertEquals(often + CHANGE_TICKS + w.linkCosts.getValue(linkKey(1, 2)) + CHANGE_TICKS, toC)
    }

    @Test
    fun aLinkWithLateTrainsCostsMore() {
        val w = line()
        val t = w.train(0, 1)
        repeat(30) { w.timeLink(0, 1, 800); w.timeLink(1, 0, 800) }
        w.linksWeek()
        assertEquals(800, w.linkCosts[linkKey(0, 1)])
        t.late = 300
        // Held 400 ticks over two runs.
        w.linkStats.getOrPut(linkKey(0, 1)) { LinkStats() }.last.apply { runs = 2; lost = 400 }
        w.linksWeek()
        assertEquals(800 + 300 + 200, w.linkCosts[linkKey(0, 1)])
        assertEquals(800 + 300, w.linkCosts[linkKey(1, 0)])
        // Early counts for nothing.
        t.late = -500
        w.linkStats.clear()
        w.linksWeek()
        assertEquals(800, w.linkCosts[linkKey(0, 1)])
        val view = w.view(withNetwork = true).links.first { it.from == 0 && it.to == 1 }
        assertEquals(0, view.wait)
        assertEquals(0, view.reliability)
    }

    @Test
    fun goodServiceMakesMorePassengers() {
        // The score follows the way: quick and frequent scores higher.
        val quick = line()
        quick.train(0, 2)
        repeat(30) { quick.timeLink(0, 2, 1200); quick.timeLink(2, 0, 1200) }
        quick.departures(0, 2, 60)
        quick.linksWeek()
        quick.serviceMonth()
        val slow = line()
        slow.train(0, 2)
        repeat(30) { slow.timeLink(0, 2, 6000); slow.timeLink(2, 0, 6000) }
        slow.departures(0, 2, 1)
        slow.linksWeek()
        slow.serviceMonth()
        val good = quick.stations[0]!!.service
        val poor = slow.stations[0]!!.service
        println("TRAVEL service quick $good slow $poor")
        assertTrue(good > 50 && poor < 50, "$good $poor")
        assertEquals(-1, quick.stations[1]!!.service)
        assertTrue(serviceFactor(quick.stations[0]!!, Cargo.PASSENGERS) > 100)
        assertTrue(serviceFactor(slow.stations[0]!!, Cargo.PASSENGERS) < 100)
        // The same station with good service makes more than with poor, the trains standing in the depot.
        val made = IntArray(2)
        for ((k, score) in listOf(100, 0).withIndex()) {
            val w = line()
            w.train(0, 2)
            // Held at the score over the months, which would work it out again.
            repeat(Calendar.TICKS_PER_DAY * 80) {
                w.stations[0]!!.service = score
                w.step()
            }
            made[k] = w.stations[0]!!.waiting(Cargo.PASSENGERS)
        }
        println("TRAVEL made with good service ${made[0]}, poor ${made[1]}")
        assertTrue(made[0] > made[1] * 2, made.toList().toString())
    }

    @Test
    fun aQuietStationKeepsItsPassengersNearTheMiddle() {
        // Station A making about one passenger a day or less, held at a score, the train in the depot. A rounded step
        // of its own would cut each lone passenger at 45 and never add one at 55.
        fun made(score: Int, days: Int): Int {
            val w = line()
            w.train(0, 2)
            var n = 0
            repeat(days) {
                w.stations[0]!!.service = score
                w.passengersDay()
                n += w.stations[0]!!.waiting(Cargo.PASSENGERS)
                w.stations[0]!!.cargo[Cargo.PASSENGERS].clear()
            }
            return n
        }
        val month = made(45, 30)
        val monthMid = made(50, 30)
        val mid = made(50, 120)
        val high = made(55, 120)
        println("TRAVEL quiet station made at 45 $month and 50 $monthMid in a month, at 50 $mid and 55 $high in 120 days")
        assertTrue(month > 0 && month >= monthMid * 3 / 4, "$month $monthMid")
        assertTrue(high > mid, "$mid $high")
    }

    @Test
    fun demandShowsAnUnservedPairAndDropsWhenCarried() {
        val w = line()
        w.towns[0].population = 5000
        w.towns[2].population = 4000
        w.towns[1].population = 50
        w.demandMonth()
        val top = w.demand.first()
        assertEquals(0, top.townA)
        assertEquals(2, top.townB)
        assertFalse(top.served)
        assertEquals(0, top.carried)
        val dist = 72
        assertEquals((5000L * 4000 / ((dist + GRAVITY_NEAR) * GRAVITY)).toInt(), top.potential)
        // A line between them serves the pair; carried, it drops off.
        w.train(0, 2)
        w.demandMonth()
        assertTrue(w.demand.first { it.townA == 0 && it.townB == 2 }.served)
        w.tripsMonth[linkKey(2, 0)] = top.potential
        w.networkStatsMonth()
        assertEquals(top.potential, w.tripsLast[linkKey(2, 0)])
        assertTrue(w.tripsMonth.isEmpty())
        w.demandMonth()
        assertNull(w.demand.firstOrNull { it.townA == 0 && it.townB == 2 })
        assertTrue(w.view().demand.isEmpty())
        assertTrue(w.view(withNetwork = true).demand.isNotEmpty())
    }

    @Test
    fun statsSurviveASaveAndKeepStep() {
        val w = line()
        w.towns[0].population = 5000
        w.towns[2].population = 4000
        w.train(0, 1, 2, 1, start = true)
        w.train(2, 1, 0, 1, start = true)
        w.run(Calendar.TICKS_PER_DAY * 70)
        assertTrue(w.tripsLast.isNotEmpty() || w.tripsMonth.isNotEmpty(), "nothing delivered")
        assertTrue(w.stations[0]!!.service >= 0)
        assertTrue(w.demand.isNotEmpty())
        val c = Save.read(Save.write(w))
        assertEquals(w.tripsMonth, c.tripsMonth)
        assertEquals(w.tripsLast, c.tripsLast)
        assertEquals(w.demand.map { listOf(it.townA, it.townB, it.potential, it.carried, if (it.served) 1 else 0) }, c.demand.map { listOf(it.townA, it.townB, it.potential, it.carried, if (it.served) 1 else 0) })
        for (s in w.stations) if (s != null) assertEquals(s.service, c.stations[s.id]!!.service)
        assertEquals(w.checksum(), c.checksum())
        repeat(Calendar.TICKS_PER_DAY * 40) { w.step(); c.step() }
        assertEquals(w.checksum(), c.checksum())
    }

    @Test
    fun save67LoadsAndPlaysOn() {
        val w = line()
        w.train(0, 1, 2, 1, start = true)
        w.run(Calendar.TICKS_PER_DAY * 40)
        val old = Save.read(Save.write(w, 67))
        assertTrue(old.stations.filterNotNull().all { it.service == -1 })
        assertTrue(old.tripsMonth.isEmpty() && old.tripsLast.isEmpty() && old.demand.isEmpty())
        old.run(Calendar.TICKS_PER_DAY * 35)
        assertTrue(old.stations[0]!!.service >= 0)
        assertNotNull(old.view(withNetwork = true))
    }

    @Test
    fun twoWorldsKeepStep() {
        val a = line()
        val b = line()
        for (w in listOf(a, b)) {
            w.train(0, 1, 2, 1, start = true)
            w.train(0, 2, start = true)
        }
        repeat(3) {
            repeat(Calendar.TICKS_PER_DAY * 30) { a.step(); b.step() }
            assertEquals(a.checksum(), b.checksum())
        }
        assertTrue(a.linkCosts.isNotEmpty())
    }

    @Test
    fun theMonthsDemandIsCheap() {
        // 100 towns over a big map, two stations each linked to the next town's: the month's demand and service work.
        val w = World(NewGame(512, 512, 1, 1900, towns = 0, industries = 0, weather = false, collisions = false))
        for (i in 0 until 100) w.towns += Town(i, "T$i", 20 + (i % 10) * 48, 20 + (i / 10) * 48, 3).also { it.population = 500 + i * 37 }
        repeat(20) { w.demandMonth() }
        val t0 = System.nanoTime()
        repeat(100) { w.demandMonth() }
        val us = (System.nanoTime() - t0) / 100 / 1000
        println("TRAVEL demandMonth with 100 towns $us us, ${w.demand.size} pairs")
        assertEquals(DEMAND_TOP, w.demand.size)
        val l = line()
        l.train(0, 1, 2, 1, start = true)
        l.run(Calendar.TICKS_PER_DAY * 40)
        val t1 = System.nanoTime()
        repeat(1000) { l.linkCharges(); l.serviceMonth() }
        println("TRAVEL linkCharges and serviceMonth ${(System.nanoTime() - t1) / 1000 / 1000} us")
    }
}
