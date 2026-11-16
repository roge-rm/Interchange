package com.rm.interchange.sim

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Services, the vehicles sharing orders: their measured times, timetable, lateness and holding. */
class TimetableTest {
    private val bus = Command.CopyVehicle.ROAD

    /** A straight road with stops at x 8, 38 and, with [third], 23, a garage at its west end, and [buses] buses in it. */
    private fun line(buses: Int = 2, third: Boolean = false): World {
        val w = World(NewGame(64, 64, 1, 1965, towns = 0, industries = 0, weather = false, collisions = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        w.apply(Command.RoadStop(0, 8, 20))
        w.apply(Command.RoadStop(0, 38, 20))
        if (third) w.apply(Command.RoadStop(0, 23, 20))
        val model = w.roster.engines.first { it.key == "base:new_look" }.id
        repeat(buses) { w.apply(Command.BuyRoadVehicle(0, 0, model)) }
        for (v in w.roadVehicles) v!!.seasonalTires = false
        return w
    }

    private fun World.order(id: Int, what: Int, vararg values: Int) = apply(Command.RoadOrder(0, id, what, values))
    private fun World.service(id: Int, what: Int, vararg values: Int, name: String = "") = apply(Command.ServiceOrder(0, bus, id, what, values, name))
    private fun World.serviceOf(id: Int) = roadVehicles[id]!!.let { services[serviceKey(bus, it.shared)] }

    /** Two buses sharing orders 0 and 1 (and 2 with [third]), the second set off [after] ticks behind the first. */
    private fun shared(after: Int = 600, third: Boolean = false): World {
        val w = line(third = third)
        w.order(0, Command.TrainOrder.ORDERS, *(if (third) intArrayOf(0, 1, 2) else intArrayOf(0, 1)))
        w.order(1, Command.TrainOrder.SHARE, 0)
        w.order(0, Command.TrainOrder.START_STOP)
        w.run(after)
        w.order(1, Command.TrainOrder.START_STOP)
        return w
    }

    private fun World.run(ticks: Int) = repeat(ticks) { step() }

    /** Steps until [done], at most [most] ticks. */
    private fun World.until(most: Int = 20_000, done: () -> Boolean) {
        repeat(most) {
            if (done()) return
            step()
        }
        throw AssertionError("never happened")
    }

    /** Runs [ticks], watching each bus's arrivals and departures, and gives the average travel and wait per order seen. */
    private fun watch(w: World, ticks: Int): Pair<DoubleArray, DoubleArray> {
        val n = w.roadVehicles[0]!!.orders.size
        val travel = Array(n) { ArrayList<Long>() }
        val wait = Array(n) { ArrayList<Long>() }
        val buses = w.roadVehicles.filterNotNull()
        val order = IntArray(buses.size) { buses[it].order }
        val waiting = BooleanArray(buses.size) { buses[it].waiting > 0 }
        val arrived = LongArray(buses.size) { -1 }
        val left = LongArray(buses.size) { -1 }
        val leftOrder = IntArray(buses.size) { -1 }
        repeat(ticks) {
            w.step()
            for ((k, v) in buses.withIndex()) {
                if (v.order != order[k]) {
                    if (arrived[k] >= 0) wait[order[k]] += w.tick - arrived[k]
                    left[k] = if (arrived[k] >= 0) w.tick else -1
                    leftOrder[k] = order[k]
                    arrived[k] = -1
                    order[k] = v.order
                }
                if (v.waiting > 0 && !waiting[k] && arrived[k] < 0) {
                    arrived[k] = w.tick
                    if (left[k] >= 0 && leftOrder[k] == (v.order - 1).mod(n)) travel[v.order] += w.tick - left[k]
                    left[k] = -1
                }
                waiting[k] = v.waiting > 0
            }
        }
        return DoubleArray(n) { travel[it].average() } to DoubleArray(n) { wait[it].average() }
    }

    private fun assertNear(expected: Double, actual: Int, within: Double, what: String) {
        assertTrue(abs(actual - expected) <= expected * within, "$what: watched $expected, measured $actual")
    }

    @Test
    fun aSharedBusLineMeasuresEachOrdersTravelAndWait() {
        val w = shared()
        w.run(3000)
        val (travel, wait) = watch(w, 20_000)
        val s = assertNotNull(w.serviceOf(0))
        assertEquals(2, s.orders.size)
        println("SERVICE travel ${travel.toList()} measured ${s.measuredTravel.toList()}, wait ${wait.toList()} measured ${s.measuredWait.toList()}")
        for (i in 0..1) {
            assertNear(travel[i], s.measuredTravel[i], 0.05, "travel $i")
            assertNear(wait[i], s.measuredWait[i], 0.05, "wait $i")
        }
        // The view has it, with both buses.
        val view = w.view(0).services.single()
        assertEquals(s.key, view.id)
        assertContentEquals(intArrayOf(0, 1), view.vehicleIds)
        assertEquals(s.key, w.view(0).roadVehicles[1].serviceId)
    }

    @Test
    fun autofillCopiesTheMeasuredTimesAndClearTakesThemOff() {
        val w = shared()
        w.run(12_000)
        val s = w.serviceOf(0)!!
        w.service(0, Command.ServiceOrder.AUTOFILL)
        for (i in 0..1) {
            assertEquals(roundUpTenth(s.measuredTravel[i]), s.planTravel[i])
            assertEquals(roundUpTenth(s.measuredWait[i]), s.planWait[i])
            assertTrue(s.planTravel[i] >= s.measuredTravel[i] && s.planTravel[i] - s.measuredTravel[i] < 7)
        }
        // A tenth of a day is 6.4 ticks: 100 ticks is 1.5625 days, planned as 1.6.
        assertEquals(103, roundUpTenth(100))
        assertEquals(64, roundUpTenth(64))
        w.service(1, Command.ServiceOrder.PLAN, 1, 500, -1)
        assertEquals(500, s.planTravel[1])
        w.service(1, Command.ServiceOrder.CLEAR)
        assertTrue(s.planTravel.all { it == 0 } && s.planWait.all { it == 0 })
        w.service(1, Command.ServiceOrder.NAME, name = "  Main Street shuttle and a long name past the cap  ")
        assertTrue(s.name.startsWith("Main Street shuttle"))
        assertTrue(s.name.length <= SERVICE_NAME_MOST)
    }

    @Test
    fun autofillPlansAtLeastTheOrdersWaitDays() {
        val w = shared()
        w.run(12_000)
        w.order(0, Command.TrainOrder.ORDERS, Order.withWait(0, 9), 1)
        val s = w.serviceOf(0)!!
        assertTrue(s.measuredWait[0] in 0 until 9 * Calendar.TICKS_PER_DAY)
        w.service(0, Command.ServiceOrder.AUTOFILL)
        assertEquals(9 * Calendar.TICKS_PER_DAY, s.planWait[0])
    }

    /** A bus timetabled from what was measured, stood at its stop with [late] ticks and its wait just up. */
    private fun standing(late: Int): Pair<World, RoadVehicle> {
        val w = shared()
        w.run(12_000)
        w.service(0, Command.ServiceOrder.AUTOFILL)
        w.service(0, Command.ServiceOrder.TIMETABLE, 1)
        val v = w.roadVehicles[0]!!
        w.until { v.waiting > 1 && v.orderReachedAt >= 0 }
        v.waiting = 1
        v.late = late
        return w to v
    }

    @Test
    fun anEarlyBusHoldsUntilItsOnTime() {
        val (w, v) = standing(-200)
        val s = w.serviceOf(0)!!
        val i = v.order
        val held = -(v.late + (w.tick + 1 - v.orderReachedAt) - s.planWait[i])
        assertTrue(held > 0)
        w.step()
        assertEquals(i, v.order)
        assertEquals(held.toInt(), v.waiting)
        w.until { v.order != i }
        // It leaves on time to the tick.
        assertEquals(0, v.late)
    }

    @Test
    fun aLateBusLeavesAtOnce() {
        val (w, v) = standing(1000)
        val s = w.serviceOf(0)!!
        val i = v.order
        val wait = w.tick + 1 - v.orderReachedAt
        w.step()
        assertEquals((i + 1) % 2, v.order)
        assertEquals(1000 + wait - s.planWait[i], v.late.toLong())
    }

    @Test
    fun latenessAddsUpOverASlowedRun() {
        // One bus, so no other holds it up and each run takes the same.
        val w = line(buses = 1)
        w.order(0, Command.TrainOrder.ORDERS, 0, 1)
        w.service(0, Command.ServiceOrder.START)
        w.order(0, Command.TrainOrder.START_STOP)
        w.run(12_000)
        w.service(0, Command.ServiceOrder.AUTOFILL)
        w.service(0, Command.ServiceOrder.TIMETABLE, 1)
        val v = w.roadVehicles[0]!!
        assertEquals(0, v.late)
        // Just away from a stop, it breaks down for two days on the way to the next: late by what the run took over the plan.
        w.until { v.orderLeftAt == w.tick }
        val before = v.late
        val left = w.tick
        val to = v.order
        v.brokenFor = 2 * Calendar.TICKS_PER_DAY
        w.until { v.orderReachedAt >= 0 && v.order == to }
        val s = w.serviceOf(0)!!
        val added = v.late - before
        assertEquals(w.tick - left - s.planTravel[to], added.toLong())
        assertTrue(added > 2 * Calendar.TICKS_PER_DAY - 20, "late by $added, plan ${s.planTravel[to]}")
        // And on over the wait there.
        val reached = w.tick
        w.until { v.order != to }
        assertEquals(added + (w.tick - reached) - s.planWait[to], v.late - before.toLong())
        // Turning the timetable off and on again starts every bus on time.
        w.service(0, Command.ServiceOrder.TIMETABLE, 0)
        w.service(0, Command.ServiceOrder.TIMETABLE, 1)
        assertEquals(0, v.late)
    }

    @Test
    fun addingAndRemovingAnOrderKeepsTheOtherOrdersTimes() {
        val w = shared(third = true)
        w.run(16_000)
        val s = w.serviceOf(0)!!
        assertTrue(s.measuredTravel.all { it > 0 } && s.measuredWait.all { it > 0 }, "${s.measuredTravel.toList()} ${s.measuredWait.toList()}")
        w.service(0, Command.ServiceOrder.AUTOFILL)
        val travel = s.measuredTravel.copyOf()
        val wait = s.measuredWait.copyOf()
        val plan = s.planTravel.copyOf()
        // Out with the third: the first now comes from the second, the second still from the first.
        w.order(0, Command.TrainOrder.ORDERS, 0, 1)
        assertContentEquals(intArrayOf(0, 1), s.orders)
        assertContentEquals(intArrayOf(-1, travel[1]), s.measuredTravel)
        assertContentEquals(intArrayOf(wait[0], wait[1]), s.measuredWait)
        assertContentEquals(intArrayOf(0, plan[1]), s.planTravel)
        // In again before the first, waiting there two days: the second keeps its times, the new one has none.
        w.order(1, Command.TrainOrder.ORDERS, Order.withWait(2, 2), 0, 1)
        assertContentEquals(intArrayOf(-1, -1, travel[1]), s.measuredTravel)
        assertContentEquals(intArrayOf(-1, wait[0], wait[1]), s.measuredWait)
        assertContentEquals(intArrayOf(0, 0, plan[1]), s.planTravel)
        assertContentEquals(intArrayOf(2, 0, 1), w.roadVehicles[0]!!.orders.map { Order.station(it) }.toIntArray())
        // A flag changed keeps everything.
        w.order(0, Command.TrainOrder.ORDERS, 2, 0 or Order.FULL_LOAD, 1)
        assertContentEquals(intArrayOf(-1, -1, travel[1]), s.measuredTravel)
        assertContentEquals(intArrayOf(-1, wait[0], wait[1]), s.measuredWait)
    }

    @Test
    fun aLoneBusIsAServiceOfOne() {
        val w = line(buses = 1)
        w.order(0, Command.TrainOrder.ORDERS, 0, 1)
        w.service(0, Command.ServiceOrder.START)
        val v = w.roadVehicles[0]!!
        assertEquals(v.id, v.shared)
        val s = assertNotNull(w.serviceOf(0))
        w.order(0, Command.TrainOrder.START_STOP)
        w.run(10_000)
        assertTrue(s.measuredTravel.all { it > 0 } && s.measuredWait.all { it > 0 })
        assertContentEquals(intArrayOf(0), w.view(0).services.single().vehicleIds)
        // Leaving it ends the service.
        w.order(0, Command.TrainOrder.UNSHARE)
        assertTrue(w.services.isEmpty())
    }

    @Test
    fun theServiceGoesWhenItsLastBusIsSold() {
        val w = line()
        w.order(0, Command.TrainOrder.ORDERS, 0, 1)
        w.order(1, Command.TrainOrder.SHARE, 0)
        w.service(1, Command.ServiceOrder.NAME, name = "Shuttle")
        val key = serviceKey(bus, 0)
        assertEquals("Shuttle", w.services[key]?.name)
        w.order(0, Command.TrainOrder.SELL)
        assertNotNull(w.services[key])
        w.order(1, Command.TrainOrder.SELL)
        assertNull(w.services[key])
        assertTrue(w.view(0).services.isEmpty())
    }

    @Test
    fun aServiceCommandGoesOverTheWire() {
        val c = Codec.decode(Codec.encode(Command.ServiceOrder(1, bus, 7, Command.ServiceOrder.PLAN, intArrayOf(2, 300, -1), "Shuttle"))) as Command.ServiceOrder
        assertEquals(listOf(1, bus, 7, Command.ServiceOrder.PLAN), listOf(c.company, c.kind, c.vehicle, c.what))
        assertContentEquals(intArrayOf(2, 300, -1), c.values)
        assertEquals("Shuttle", c.name)
    }

    @Test
    fun aSaveKeepsServicesAndStaysInStep() {
        val w = shared()
        w.run(12_000)
        w.service(0, Command.ServiceOrder.AUTOFILL)
        w.service(0, Command.ServiceOrder.TIMETABLE, 1)
        w.service(0, Command.ServiceOrder.NAME, name = "Shuttle")
        w.run(3_000)
        val older = Save.write(w, 60)
        val c = Save.read(Save.write(w))
        assertEquals(w.checksum(), c.checksum())
        val a = w.serviceOf(0)!!
        val b = c.serviceOf(0)!!
        assertEquals("Shuttle", b.name)
        assertTrue(b.timetabled)
        assertContentEquals(a.planWait, b.planWait)
        assertContentEquals(a.measuredTravel, b.measuredTravel)
        for (id in 0..1) assertEquals(w.roadVehicles[id]!!.late, c.roadVehicles[id]!!.late)
        repeat(20_000) { w.step(); c.step() }
        assertEquals(w.checksum(), c.checksum())
        // A save from before services loads with none, every bus on time.
        val old = Save.read(older)
        assertTrue(old.services.isEmpty())
        assertTrue(old.roadVehicles.all { it!!.late == 0 })
        old.run(5_000)
        assertNotNull(old.serviceOf(0))
    }
}
