package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkStatsTest {
    /** Stations A, B and C along one line, ids 0 to 2, each with a row of houses unless not, and a depot at the west end. */
    private fun line(houses: Boolean = true): World {
        val w = World(NewGame(96, 64, 1, 1900, towns = 0, industries = 0, weather = false, collisions = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 90, 10, Track.X, remove = false))
        val house = w.roster.houses.first { it.key == "base:clapboard_house" }
        for ((n, xs) in listOf(10..13, 46..49, 82..85).withIndex()) {
            if (houses) {
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
            w.apply(Command.Station(0, xs.first, 10, xs.last, 10, Track.X))
        }
        w.catchmentDirty = true
        return w
    }

    private fun World.key(k: String) = roster.engines.first { it.key == k }.id

    private fun World.train(wagon: String, cargo: Int, vararg orders: Int, start: Boolean = false): Train {
        apply(Command.BuyTrain(0, 0, key("base:american"), key(wagon), 3, cargo))
        val id = trains.indexOfLast { it != null }
        apply(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, orders))
        if (start) apply(Command.TrainOrder(0, id, Command.TrainOrder.START_STOP))
        return trains[id]!!
    }

    private fun World.monthStarted(before: Int) = day != before && Calendar.dayOfMonth(startYear, day) == 1

    @Test
    fun aLinesDeparturesCapacityAndLoadLastMonthMatchWhatRan() {
        val w = line()
        val t = w.train("base:coach_wood", -1, 0, 1, start = true)
        // Watched as it leaves: departures and load by link, for the month so far, checked against last month's at each month's start.
        val seen = HashMap<Long, IntArray>()
        var rolled = 0
        var total = 0
        while (rolled < 6) {
            val was = t.departed
            val before = w.day
            val from = t.leftFrom
            w.step()
            if (t.departed != was && from >= 0) {
                val a = seen.getOrPut(linkKey(from, 1 - from)) { IntArray(2) }
                a[0]++
                a[1] += t.load(Cargo.PASSENGERS)
            }
            if (!w.monthStarted(before)) continue
            rolled++
            for (key in listOf(linkKey(0, 1), linkKey(1, 0))) {
                val m = w.linkStats[key]?.last ?: LinkMonth()
                val a = seen[key] ?: IntArray(2)
                assertEquals(a[0], m.departures)
                assertEquals(a[1], m.load[Cargo.PASSENGERS])
                assertEquals(a[0] * t.capacity(Cargo.PASSENGERS), m.capacity[Cargo.PASSENGERS])
                assertEquals(0, m.capacity[Cargo.COAL])
                total += m.departures
            }
            seen.clear()
        }
        assertTrue(total >= 4, "$total")
        println("LINK departures over 6 months $total, last month 0-1 ${w.linkStats[linkKey(0, 1)]?.last?.departures} 1-0 ${w.linkStats[linkKey(1, 0)]?.last?.departures}")
        // The view has them for the player, with the journey time measured.
        val links = w.view(0, withNetwork = true).links
        assertEquals(listOf(linkKey(0, 1), linkKey(1, 0)), links.map { linkKey(it.from, it.to) })
        val l = links[0]
        assertEquals(1 shl OverviewVehicle.TRAIN, l.modes)
        assertTrue(l.measured)
        assertEquals(w.linkTimes[linkKey(0, 1)], l.ticks)
        assertEquals(w.linkStats.getValue(linkKey(0, 1)).last.departures, l.departures)
        assertContentEquals(intArrayOf(OverviewVehicle.TRAIN), l.vehicleModes)
        assertContentEquals(intArrayOf(t.id), l.vehicleIds)
        assertTrue(w.view(0).links.isEmpty())
    }

    @Test
    fun aTrainHeldAtARedSignalCountsItsTicksOnThatLink() {
        val w = line()
        w.apply(Command.Signal(0, 30, 10, Track.X, Track.XM))
        val ahead = w.train("base:coach_wood", -1, 0, 1, start = true)
        // The first train out past the signal, then stopped there, so the block ahead stays red.
        var n = 0
        while (!(ahead.leftFrom == 0 && stepTile(ahead.steps.last()) % w.width in 34..40)) {
            w.step()
            assertTrue(++n < 100_000, "never got past the signal")
        }
        w.apply(Command.TrainOrder(0, ahead.id, Command.TrainOrder.START_STOP))
        val t = w.train("base:coach_wood", -1, 0, 1, start = true)
        var held = 0
        n = 0
        while (held < 2000) {
            w.step()
            if (t.held && t.leftAt >= 0) held++
            assertTrue(++n < 100_000, "never held")
        }
        assertEquals(held, t.heldTicks)
        // Calling at B now ends the run: on a single line the train ahead can't be got out of the way, so it's called by hand.
        val runsBefore = w.linkStats[linkKey(0, 1)]?.month?.runs ?: 0
        val lostBefore = w.linkStats[linkKey(0, 1)]?.month?.lost ?: 0L
        w.callAt(t, w.stations[1]!!, t.orders[1])
        val m = w.linkStats.getValue(linkKey(0, 1)).month
        assertEquals(runsBefore + 1, m.runs)
        assertEquals(held.toLong(), m.lost - lostBefore)
        assertEquals(0, t.heldTicks)
        println("HELD $held lost ${m.lost} runs ${m.runs}")
    }

    @Test
    fun aBusLinesWaitAverage() {
        val w = World(NewGame(64, 64, 1, 1965, towns = 0, industries = 0, weather = false, collisions = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        w.apply(Command.RoadStop(0, 8, 20))
        w.apply(Command.RoadStop(0, 38, 20))
        val house = w.roster.houses.first { it.key == "base:clapboard_house" }
        for ((n, xs) in listOf(7..10, 36..39).withIndex()) {
            val t = Town(n, "Town $n", xs.first, 22, 3)
            w.towns += t
            for (x in xs) {
                val i = w.index(x, 22)
                w.house[i] = packHouse(house.id, Track.YM).toShort()
                w.townOf[i] = n.toShort()
                t.houses++
                t.population += house.residents
            }
        }
        w.catchmentDirty = true
        // Loading by hand: two lots that waited 100 and 300 ticks average 250 over the four.
        val s = w.stations[0]!!
        w.apply(Command.BuyRoadVehicle(0, 0, w.key("base:new_look")))
        val v = w.roadVehicles[0]!!
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        s.cargo[Cargo.PASSENGERS] += Packet(-1, 0, 1, w.tick - 100)
        s.cargo[Cargo.PASSENGERS] += Packet(-1, 0, 3, w.tick - 300)
        w.loadAt(v, s)
        assertEquals(1 * 100L + 3 * 300L, s.waitedTicks[Cargo.PASSENGERS])
        assertEquals(4, s.boarded[Cargo.PASSENGERS])
        w.networkStatsMonth()
        assertEquals(250, w.view(0).stations[0].waited[Cargo.PASSENGERS])
        assertEquals(4, w.view(0).stations[0].boarded[Cargo.PASSENGERS])
        assertEquals(-1, w.view(0).stations[0].waited[Cargo.MAIL])
        // Then run: what boards waits, but no longer than the bus takes to come round.
        v.cargo[Cargo.PASSENGERS].clear()
        v.seasonalTires = false
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.START_STOP))
        var rolled = 0
        while (rolled < 3) {
            val before = w.day
            w.step()
            if (w.monthStarted(before)) rolled++
        }
        val view = w.view(0).stations[0]
        assertTrue(view.boarded[Cargo.PASSENGERS] > 0)
        assertTrue(view.waited[Cargo.PASSENGERS] in 1 until Calendar.TICKS_PER_DAY * GIVE_UP_DAYS, "${view.waited[Cargo.PASSENGERS]}")
        assertEquals(0, view.gaveUp[Cargo.PASSENGERS])
        println("BUS waited ${view.waited[Cargo.PASSENGERS]} boarded ${view.boarded[Cargo.PASSENGERS]}")
    }

    @Test
    fun whatGaveUpIsCounted() {
        val w = line()
        w.run(Calendar.TICKS_PER_DAY * (GIVE_UP_DAYS + 5))
        assertTrue(w.stations[0]!!.gaveUp[Cargo.PASSENGERS] + w.stations[0]!!.gaveUpLastMonth[Cargo.PASSENGERS] > 0)
    }

    private fun World.run(ticks: Int) = repeat(ticks) { step() }

    @Test
    fun eachReasonItCantGetThere() {
        val w = line()
        w.train("base:coach_wood", -1, 0, 1)
        assertEquals(WhyNot.OK, w.whyNot(0, 1, Cargo.PASSENGERS))
        assertEquals(WhyNot.NO_SERVICE, w.whyNot(0, 2, Cargo.PASSENGERS))
        // A coal train only from B to C.
        w.train("base:hopper", Cargo.COAL, 1, 2)
        assertEquals(WhyNot.NOT_CARRIED, w.whyNot(1, 2, Cargo.PASSENGERS))
        assertEquals(WhyNot.NOT_CARRIED, w.whyNot(0, 2, Cargo.PASSENGERS))
        // Run through at B, and not loading at B: passengers can't go from B, nor to it.
        val bare = line()
        bare.train("base:coach_wood", -1, 0, Order.VIA or 1, 2)
        assertEquals(WhyNot.OK, bare.whyNot(0, 2, Cargo.PASSENGERS))
        assertEquals(WhyNot.ORDERS, bare.whyNot(0, 1, Cargo.PASSENGERS))
        val noLoad = line()
        noLoad.train("base:coach_wood", -1, Order.NO_LOAD or 0, 1)
        assertEquals(WhyNot.ORDERS, noLoad.whyNot(0, 1, Cargo.PASSENGERS))
        assertEquals(WhyNot.OK, noLoad.whyNot(1, 0, Cargo.PASSENGERS))
        // No one lives at B.
        val empty = line(houses = false)
        empty.train("base:coach_wood", -1, 0, 1)
        assertEquals(WhyNot.NOT_ACCEPTED, empty.whyNot(0, 1, Cargo.PASSENGERS))
        // The station view gives it for the top destinations when asked.
        w.run(Calendar.TICKS_PER_DAY * 3)
        val going = w.view(0, withNetwork = true).stations[0].going
        assertTrue(going.isNotEmpty())
        assertTrue(going.take(GOING_WHY).all { it.dest < 0 || it.why == WhyNot.OK })
        assertTrue(w.view(0).stations[0].going.all { it.why == -1 })
    }

    @Test
    fun statsSurviveASave() {
        val w = line()
        w.train("base:coach_wood", -1, 0, 1, 2, 1, start = true)
        w.run(Calendar.TICKS_PER_DAY * 45)
        assertTrue(w.linkStats.values.any { !it.last.empty })
        val older = Save.write(w, 59)
        val c = Save.read(Save.write(w))
        assertEquals(w.linkStats.keys, c.linkStats.keys)
        for ((k, l) in w.linkStats) {
            val o = c.linkStats.getValue(k)
            for ((a, b) in listOf(l.month to o.month, l.last to o.last)) {
                assertEquals(a.departures, b.departures)
                assertContentEquals(a.capacity, b.capacity)
                assertContentEquals(a.load, b.load)
                assertEquals(a.lost, b.lost)
                assertEquals(a.runs, b.runs)
            }
        }
        for (id in 0..2) {
            val a = w.stations[id]!!
            val b = c.stations[id]!!
            assertContentEquals(a.waitedTicks, b.waitedTicks)
            assertContentEquals(a.boarded, b.boarded)
            assertContentEquals(a.waitedLastMonth, b.waitedLastMonth)
            assertContentEquals(a.boardedLastMonth, b.boardedLastMonth)
            assertContentEquals(a.gaveUpLastMonth, b.gaveUpLastMonth)
        }
        assertEquals(w.trains[0]!!.heldTicks, c.trains[0]!!.heldTicks)
        // Both run on the same, stats and all.
        repeat(20_000) { w.step(); c.step() }
        assertEquals(w.checksum(), c.checksum())
        assertEquals(w.linkStats.keys, c.linkStats.keys)
        for ((k, l) in w.linkStats) assertEquals(l.month.departures, c.linkStats.getValue(k).month.departures)
        // A save from before has none.
        val old = Save.read(older)
        assertTrue(old.linkStats.isEmpty())
        assertTrue(old.stations[0]!!.boardedLastMonth.all { it == 0 })
    }

    @Test
    fun twoWorldsKeepInStepWithStats() {
        val a = line()
        val b = line()
        for (w in listOf(a, b)) {
            w.apply(Command.Signal(0, 30, 10, Track.X, Track.XM))
            w.train("base:coach_wood", -1, 0, 1, 2, 1, start = true)
            w.train("base:coach_wood", -1, 2, 1, 0, 1, start = true)
            w.train("base:hopper", Cargo.COAL, 0, 2, start = true)
        }
        repeat(Calendar.TICKS_PER_DAY * 70) { a.step(); b.step() }
        assertEquals(a.checksum(), b.checksum())
        val va = a.view(0, withNetwork = true).links
        val vb = b.view(0, withNetwork = true).links
        assertEquals(va.map { linkKey(it.from, it.to) }, vb.map { linkKey(it.from, it.to) })
        for ((x, y) in va.zip(vb)) {
            assertEquals(x.departures, y.departures)
            assertEquals(x.lostPerRun, y.lostPerRun)
            assertContentEquals(x.load, y.load)
        }
        assertTrue(va.any { it.departures > 0 })
    }
}
