package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkRoutingTest {
    /** Stations A, B and C along one line, ids 0 to 2, each with a row of houses as a town, and a depot at the west end. */
    private fun line(): World {
        val w = World(NewGame(96, 64, 1, 1900, towns = 0, industries = 0, weather = false, collisions = false))
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

    /** Buys a train of [wagon]s carrying [cargo] and gives it [orders]; it stays in its depot unless [start]. */
    private fun World.train(wagon: String, cargo: Int, vararg orders: Int, start: Boolean = false): Train {
        apply(Command.BuyTrain(0, 0, key("base:american"), key(wagon), 3, cargo))
        val id = trains.indexOfLast { it != null }
        apply(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, orders))
        if (start) apply(Command.TrainOrder(0, id, Command.TrainOrder.START_STOP))
        return trains[id]!!
    }

    private fun World.run(ticks: Int) = repeat(ticks) { step() }

    private fun World.goingTo(s: Int, c: Int, dest: Int) = stations[s]!!.cargo[c].filter { it.dest == dest }.sumOf { it.count }

    @Test
    fun aCoalTrainMakesNoWayForPassengers() {
        val w = line()
        val coal = w.train("base:hopper", Cargo.COAL, 0, 1)
        assertTrue(coal.capacity(Cargo.COAL) > 0)
        assertEquals(-1, w.nextHop(0, 1, Cargo.PASSENGERS))
        assertEquals(1, w.nextHop(0, 1, Cargo.COAL))
        w.run(Calendar.TICKS_PER_DAY * 10)
        assertEquals(0, w.goingTo(0, Cargo.PASSENGERS, 1))
        assertTrue(w.stations[0]!!.waiting(Cargo.PASSENGERS) > 0)
        // A passenger train on the same pair does.
        w.train("base:coach_wood", -1, 0, 1)
        assertEquals(1, w.nextHop(0, 1, Cargo.PASSENGERS))
        w.run(Calendar.TICKS_PER_DAY * 10)
        assertTrue(w.goingTo(0, Cargo.PASSENGERS, 1) > 0)
    }

    @Test
    fun aStationRunThroughIsntAStop() {
        val w = line()
        val t = w.train("base:coach_wood", -1, 0, Order.VIA or 1, 2)
        assertEquals(Order.VIA or 1, t.orders[1])
        assertEquals(2, w.nextHop(0, 2))
        assertEquals(-1, w.nextHop(0, 1))
        assertEquals(0, w.nextHop(2, 0))
    }

    @Test
    fun nothingGoesFromAStopThatDoesntLoad() {
        val w = line()
        val t = w.train("base:coach_wood", -1, Order.NO_LOAD or 0, 1, start = true)
        assertEquals(-1, w.nextHop(0, 1))
        assertEquals(0, w.nextHop(1, 0))
        var fromA = 0
        repeat(30_000) {
            w.step()
            fromA += t.cargo[Cargo.PASSENGERS].filter { it.origin == 0 }.sumOf { it.count }
            assertEquals(0, w.goingTo(0, Cargo.PASSENGERS, 1))
        }
        assertEquals(0, fromA)
        assertTrue(w.linkTimes.containsKey(linkKey(1, 0)), "the train never ran from B to A")
    }

    @Test
    fun mailGoesByAMailRoute() {
        val w = line()
        w.train("base:coach_wood", -1, 0, 2)
        w.train("base:baggage_wood", -1, 0, 1)
        w.train("base:baggage_wood", -1, 1, 2)
        assertEquals(2, w.nextHop(0, 2, Cargo.PASSENGERS))
        assertEquals(1, w.nextHop(0, 2, Cargo.MAIL))
        assertEquals(-1, w.nextHop(0, 1, Cargo.PASSENGERS))
    }

    @Test
    fun runsAreTimed() {
        val w = line()
        val t = w.train("base:coach_wood", -1, 0, 2, start = true)
        w.run(40_000)
        val there = w.linkTimes[linkKey(0, 2)]
        val back = w.linkTimes[linkKey(2, 0)]
        assertTrue(there != null && back != null)
        // Not quicker than the distance at top speed, and not hugely slower on a straight line.
        val guess = w.stationDistance(0, 2) * TILE_TICKS_KMH / t.topSpeed
        println("LINKS estimate $guess there $there back $back")
        assertTrue(there >= guess && there < guess * 3, "$there against $guess")
        assertTrue(back >= guess && back < guess * 3, "$back against $guess")
    }

    @Test
    fun theWayFollowsMeasuredTimesWeekByWeek() {
        val w = line()
        w.train("base:coach_wood", -1, 0, 2)
        w.train("base:coach_wood", -1, 0, 1)
        w.train("base:coach_wood", -1, 1, 2)
        // By the estimates, straight there saves a change.
        assertEquals(2, w.nextHop(0, 2))
        // Timed slow straight there and quick by B: the way changes at the week's rework, not before.
        repeat(20) {
            w.timeLink(0, 2, 5000)
            w.timeLink(0, 1, 300)
            w.timeLink(1, 2, 300)
        }
        w.linksDirty = true
        assertEquals(2, w.nextHop(0, 2))
        w.linksWeek()
        assertEquals(1, w.nextHop(0, 2))
        assertEquals(2, w.nextHop(1, 2))
        // A station taken away takes its times with it.
        w.dropLinkTimes(1)
        assertFalse(w.linkTimes.keys.any { (it shr 16).toInt() == 1 || (it and 0xffff).toInt() == 1 })
        assertEquals(1, w.linkTimes.size)
    }

    @Test
    fun linkTimesCarryOnTheSameAfterSaving() {
        val w = line()
        w.train("base:coach_wood", -1, 0, 1, 2, 1, start = true)
        w.run(Calendar.TICKS_PER_DAY * 40)
        assertTrue(w.linkTimes.isNotEmpty() && w.linkCosts.isNotEmpty())
        val older = Save.write(w, 58)
        val c = Save.read(Save.write(w))
        assertEquals(w.linkTimes, c.linkTimes)
        assertEquals(w.linkCosts, c.linkCosts)
        assertEquals(w.checksum(), c.checksum())
        repeat(10_000) { w.step(); c.step() }
        assertEquals(w.checksum(), c.checksum())
        assertTrue(w.linkTimes.size >= 3)
        // A save from before has no times, and goes by estimates.
        val old = Save.read(older)
        assertTrue(old.linkTimes.isEmpty() && old.linkCosts.isEmpty())
        assertEquals(1, old.nextHop(0, 1))
        old.run(Calendar.TICKS_PER_DAY * 60)
        assertTrue(old.linkTimes.isNotEmpty())
    }
}
