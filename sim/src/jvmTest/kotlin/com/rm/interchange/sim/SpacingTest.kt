package com.rm.interchange.sim

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Even spacing, conditional orders, company values and timed connections. */
class SpacingTest {
    private val bus = Command.CopyVehicle.ROAD
    private val rail = Command.CopyVehicle.TRAIN
    private val day = Calendar.TICKS_PER_DAY

    /** A straight road with stops at x 8 and 38 (stations 0 and 1) and, with [third], 23 (station 2), a garage at its west end, and [buses] buses in it. */
    private fun line(buses: Int, third: Boolean = false): World {
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
    private fun World.service(id: Int, what: Int, vararg values: Int, kind: Int = bus) = apply(Command.ServiceOrder(0, kind, id, what, values))
    private fun World.serviceOf(id: Int) = roadVehicles[id]!!.let { services[serviceKey(bus, it.shared)] }
    private fun World.run(ticks: Int) = repeat(ticks) { step() }

    /** Steps until [done], at most [most] ticks. */
    private fun World.until(most: Int = 20_000, done: () -> Boolean) {
        repeat(most) {
            if (done()) return
            step()
        }
        throw AssertionError("never happened")
    }

    /**
     * Three buses sharing stops 0 and 1, set off 40 ticks apart, [spaced] or not, kept from breaking down or going in
     * for a service. The world, and the gaps between their departures from stop 1 over a few rounds once settled.
     */
    private fun bunched(spaced: Boolean): Pair<World, List<Long>> {
        val w = line(3)
        w.order(0, Command.TrainOrder.ORDERS, 0, 1)
        w.order(1, Command.TrainOrder.SHARE, 0)
        w.order(2, Command.TrainOrder.SHARE, 0)
        w.service(0, Command.ServiceOrder.SPACED, if (spaced) 1 else 0)
        for (id in 0..2) {
            w.order(id, Command.TrainOrder.START_STOP)
            w.run(40)
        }
        fun step() {
            w.step()
            for (v in w.roadVehicles) { v!!.reliability = 10_000; v.serviced = w.workDay }
        }
        repeat(30_000) { step() }
        val left = ArrayList<Long>()
        val departed = LongArray(3) { w.roadVehicles[it]!!.departed }
        repeat(15_000) {
            step()
            for (id in 0..2) {
                val v = w.roadVehicles[id]!!
                if (v.departed != departed[id]) {
                    departed[id] = v.departed
                    // Gone on to order 0, so it left stop 1.
                    if (v.order == 0) left += v.departed
                }
            }
        }
        return w to left.zipWithNext { a, b -> b - a }
    }

    @Test
    fun bunchedBusesSpreadOutToTheInterval() {
        val (w, gaps) = bunched(true)
        val s = assertNotNull(w.serviceOf(0))
        assertTrue(s.spaced)
        val interval = w.interval(s)
        assertTrue(interval > 0)
        println("SPACING interval $interval, round trip ${w.roundTrip(s)}, gaps $gaps")
        assertTrue(gaps.size >= 8)
        for (g in gaps.takeLast(8)) assertTrue(abs(g - interval) <= day, "gaps $gaps, interval $interval")
        // The view has it.
        val view = w.view(0).services.single()
        assertTrue(view.spaced)
        assertEquals(interval, view.interval)
        assertEquals(2, view.lastLeft.size)
        // Not spaced, they stay bunched.
        val (_, loose) = bunched(false)
        println("SPACING off, gaps $loose")
        assertTrue(loose.any { it < interval / 2 })
    }

    /** A bus with orders 0 (stop 0), a conditional order [cond], 2 (stop 1) and 3 (stop 0 again), at order 0. */
    private fun condBus(cond: Int): Pair<World, RoadVehicle> {
        val w = line(1)
        w.order(0, Command.TrainOrder.ORDERS, 0, cond, 1, 0 or Order.FULL_LOAD)
        val v = w.roadVehicles[0]!!
        assertEquals(4, v.orders.size)
        assertEquals(cond, v.orders[1])
        assertEquals(0, v.order)
        return w to v
    }

    /** Where [v] goes on to from order 0. */
    private fun World.next(v: RoadVehicle): Int {
        v.order = 0
        stepOrder(v)
        return v.order
    }

    @Test
    fun aLoadConditionJumpsWhenFullAndNotWhenEmpty() {
        val (w, v) = condBus(Order.cond(Order.IF_LOAD, Order.AT_LEAST, 80, 3))
        assertEquals(2, w.next(v))
        val room = v.capacity(Cargo.PASSENGERS)
        assertTrue(room > 0)
        v.cargo[Cargo.PASSENGERS] += Packet(1, 0, room, w.tick)
        assertEquals(3, w.next(v))
        v.cargo[Cargo.PASSENGERS].single().count = room * 79 / 100
        assertEquals(2, w.next(v))
    }

    @Test
    fun ageReliabilityAndServiceConditions() {
        val (w, v) = condBus(Order.cond(Order.IF_AGE, Order.AT_LEAST, 5, 3))
        w.tick = 365L * 10 * day
        v.built = w.workDay - 365 * 4
        assertEquals(2, w.next(v))
        v.built = w.workDay - 365 * 5
        assertEquals(3, w.next(v))

        v.orders[1] = Order.cond(Order.IF_RELIABILITY, Order.LESS, 60, 3)
        v.reliability = 8500
        assertEquals(2, w.next(v))
        v.reliability = 5999
        assertEquals(3, w.next(v))

        v.orders[1] = Order.cond(Order.IF_SINCE_SERVICE, Order.MORE, 180, 3)
        v.serviced = w.workDay - 180
        assertEquals(2, w.next(v))
        v.serviced = w.workDay - 181
        assertEquals(3, w.next(v))

        v.orders[1] = Order.cond(Order.IF_ALWAYS, Order.LESS, 0, 3)
        assertEquals(3, w.next(v))
        // Each comparison.
        for ((cmp, want) in listOf(Order.LESS to booleanArrayOf(true, false, false), Order.AT_MOST to booleanArrayOf(true, true, false),
            Order.EQUAL to booleanArrayOf(false, true, false), Order.NOT_EQUAL to booleanArrayOf(true, false, true),
            Order.AT_LEAST to booleanArrayOf(false, true, true), Order.MORE to booleanArrayOf(false, false, true))) {
            for ((k, x) in listOf(4, 5, 6).withIndex()) assertEquals(want[k], Order.compare(x, cmp, 5), "$x by $cmp")
        }
    }

    @Test
    fun aCompanyValueSendsABusDownAnotherBranch() {
        val (w, v) = condBus(Order.cond(Order.IF_VALUE + 2, Order.EQUAL, 7, 3))
        assertEquals(2, w.next(v))
        w.apply(Command.CompanyValue(0, 2, 7, "Winter"))
        assertEquals(7, w.companies[0].values[2])
        assertEquals("Winter", w.companies[0].valueNames[2])
        assertEquals(3, w.next(v))
        // Out of range values and indices are left alone, a null name keeps the name, a long one is cut.
        w.apply(Command.CompanyValue(0, 2, 4096))
        w.apply(Command.CompanyValue(0, 8, 1, "x"))
        w.apply(Command.CompanyValue(0, -1, 1, "x"))
        assertEquals(7, w.companies[0].values[2])
        assertEquals("Winter", w.companies[0].valueNames[2])
        w.apply(Command.CompanyValue(0, 2, 3, "  A very long name for a value, past the cap  "))
        assertEquals(COMPANY_VALUE_NAME_MOST, w.companies[0].valueNames[2].length)
        assertEquals(2, w.next(v))
        val view = w.view(0)
        assertEquals(3, view.values[2])
        assertEquals(COMPANY_VALUES, view.valueNames.size)
        // Over the wire, with and without a name.
        val c = Codec.decode(Codec.encode(Command.CompanyValue(1, 5, 4095, "Peak"))) as Command.CompanyValue
        assertEquals(Command.CompanyValue(1, 5, 4095, "Peak"), c)
        assertEquals(Command.CompanyValue(1, 5, -1, null), Codec.decode(Codec.encode(Command.CompanyValue(1, 5, -1, null))))
        // Another company can't set this one's.
        w.apply(Command.CompanyValue(1, 2, 9))
        assertEquals(3, w.companies[0].values[2])
    }

    @Test
    fun aLoopOfConditionsDoesntHang() {
        val w = line(1)
        val always = { to: Int -> Order.cond(Order.IF_ALWAYS, Order.EQUAL, 0, to) }
        w.order(0, Command.TrainOrder.ORDERS, always(3), 0, always(0), always(2))
        val v = w.roadVehicles[0]!!
        assertEquals(4, v.orders.size)
        // From the start it goes round the loop, then on to the first stop.
        assertEquals(1, v.order)
        v.order = 1
        w.stepOrder(v)
        assertEquals(1, v.order)
        w.order(0, Command.TrainOrder.START_STOP)
        w.run(5_000)
        assertEquals(1, v.order)
        // Orders of only conditions aren't kept, nor one jumping past the end, nor a test there isn't.
        w.order(0, Command.TrainOrder.ORDERS, always(0), always(1))
        assertTrue(v.orders.isEmpty())
        w.order(0, Command.TrainOrder.ORDERS, 0, always(5), 1, Order.COND or (7 shl 23), Order.COND or (Order.IF_LOAD shl 23) or (6 shl 20))
        assertContentEquals(intArrayOf(0, 1), v.orders.toIntArray())
    }

    @Test
    fun conditionalOrdersFollowWhenOrdersAreLeftOut() {
        val w = line(1)
        // Station 5 isn't there: it's left out and the jump to order 3 moves to 2.
        w.order(0, Command.TrainOrder.ORDERS, 0, Order.cond(Order.IF_ALWAYS, Order.EQUAL, 0, 3), 5, 1)
        val v = w.roadVehicles[0]!!
        assertContentEquals(intArrayOf(0, Order.cond(Order.IF_ALWAYS, Order.EQUAL, 0, 2), 1), v.orders.toIntArray())
        // A station taken away: the jump to it goes to the order after it.
        w.order(0, Command.TrainOrder.ORDERS, Order.cond(Order.IF_ALWAYS, Order.EQUAL, 0, 2), 0, 1)
        w.dropStation(1)
        assertContentEquals(intArrayOf(Order.cond(Order.IF_ALWAYS, Order.EQUAL, 0, 0), 0), v.orders.toIntArray())
        // Not a depot or a station anywhere.
        val o = Order.cond(Order.IF_VALUE + 7, Order.MORE, 4095, 255)
        assertTrue(Order.isCond(o))
        assertTrue(!Order.isDepot(o))
        assertEquals(-1, Order.station(o))
        assertEquals(-1, Order.depot(o))
        assertTrue(!w.callsAt(o))
        assertEquals(Order.IF_VALUE + 7, Order.condWhat(o))
        assertEquals(Order.MORE, Order.condCompare(o))
        assertEquals(4095, Order.condValue(o))
        assertEquals(255, Order.condTarget(o))
    }

    /**
     * Rail along y 10 with station 0 at x 10..13 and station 1 at x 40..43, a road along y 11 with a stop at x 12
     * joining station 0 and one at x 28, station 2. A train and a bus, each a service, stopped.
     */
    private fun interchange(): World {
        val w = World(NewGame(64, 64, 1, 1965, towns = 0, industries = 0, weather = false, collisions = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 50, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 40, 10, 43, 10, Track.X))
        w.apply(Command.Road(0, 8, 11, 30, 11, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 8, 11, 8, 13, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 8, 14, Track.YM))
        w.apply(Command.RoadStop(0, 12, 11))
        w.apply(Command.RoadStop(0, 28, 11))
        assertEquals(3, w.stations.count { it != null })
        assertEquals(0, w.roadStops[w.index(12, 11)])
        val engine = w.roster.engines.first { it.key == "base:gp9" }.id
        val coach = w.roster.engines.first { it.key == "base:coach_steel" }.id
        w.apply(Command.BuyTrain(0, 0, engine, coach, 2, Cargo.PASSENGERS))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        val model = w.roster.engines.first { it.key == "base:new_look" }.id
        w.apply(Command.BuyRoadVehicle(0, 1, model))
        w.roadVehicles[0]!!.seasonalTires = false
        w.order(0, Command.TrainOrder.ORDERS, 0, 2)
        w.service(0, Command.ServiceOrder.START)
        w.service(0, Command.ServiceOrder.START, kind = rail)
        return w
    }

    private val World.trainKey get() = serviceKey(rail, trains[0]!!.shared)

    @Test
    fun aBusWaitsForTheTrainAndGoesWhenItArrives() {
        val w = interchange()
        w.service(0, Command.ServiceOrder.CONNECT, 0, w.trainKey, CONNECTION_WAIT_MOST)
        val s = w.serviceOf(0)!!
        assertEquals(1, s.connections.size)
        val b = w.roadVehicles[0]!!
        w.order(0, Command.TrainOrder.START_STOP)
        w.until { b.order == 0 && b.orderReachedAt >= 0 && b.waiting > 0 }
        val reached = b.orderReachedAt
        w.run(2 * day)
        // Still there, waiting for the train.
        assertEquals(0, b.order)
        assertTrue(b.orderReachedAt == reached)
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        w.until { t.order == 0 && t.orderReachedAt >= 0 }
        val came = w.tick
        w.until { b.order != 0 }
        println("CONNECTION bus reached $reached, train came $came, bus left ${w.tick}")
        assertTrue(w.tick - came <= FULL_LOAD_RETRY + 4, "left ${w.tick - came} after the train came")
        assertTrue(w.tick - reached < CONNECTION_WAIT_MOST)
        assertEquals(1, s.connections[0].made)
        assertEquals(0, s.connections[0].missed)
        // Counts roll over with the month.
        w.servicesMonth()
        val view = w.view(0).services.first { it.id == s.key }.connections.single()
        assertEquals(0, view.order)
        assertEquals(w.trainKey, view.other)
        assertEquals(CONNECTION_WAIT_MOST, view.most)
        assertEquals(1, view.made)
        assertEquals(0, view.missed)
        assertEquals(0, s.connections[0].made)
    }

    @Test
    fun aBusGoesAfterTheMostWaitCountingAMiss() {
        val w = interchange()
        w.service(0, Command.ServiceOrder.CONNECT, 0, w.trainKey, 3 * day)
        val s = w.serviceOf(0)!!
        val b = w.roadVehicles[0]!!
        w.order(0, Command.TrainOrder.START_STOP)
        w.until { b.order == 0 && b.orderReachedAt >= 0 && b.waiting > 0 }
        val reached = b.orderReachedAt
        w.until { b.order != 0 }
        val stood = w.tick - reached
        assertTrue(stood >= 3 * day && stood <= 3 * day + FULL_LOAD_RETRY, "stood $stood")
        assertEquals(0, s.connections[0].made)
        assertEquals(1, s.connections[0].missed)
        // Disconnected, it waits no more.
        w.service(0, Command.ServiceOrder.DISCONNECT, 0, w.trainKey)
        assertTrue(s.connections.isEmpty())
    }

    @Test
    fun connectCommandsAreChecked() {
        val w = interchange()
        val s = w.serviceOf(0)!!
        // Not to itself, not at a stop not there, not too long a wait, not to a service that isn't there.
        w.service(0, Command.ServiceOrder.CONNECT, 0, s.key, day)
        w.service(0, Command.ServiceOrder.CONNECT, 5, w.trainKey, day)
        w.service(0, Command.ServiceOrder.CONNECT, 0, w.trainKey, CONNECTION_WAIT_MOST + 1)
        w.service(0, Command.ServiceOrder.CONNECT, 0, w.trainKey, 0)
        w.service(0, Command.ServiceOrder.CONNECT, 0, 12345, day)
        w.service(0, Command.ServiceOrder.CONNECT, 0)
        assertTrue(s.connections.isEmpty())
        // Another company's vehicle can't be given one.
        w.apply(Command.ServiceOrder(1, bus, 0, Command.ServiceOrder.CONNECT, intArrayOf(0, w.trainKey, day)))
        assertTrue(s.connections.isEmpty())
        w.service(0, Command.ServiceOrder.CONNECT, 0, w.trainKey, day)
        w.service(0, Command.ServiceOrder.CONNECT, 0, w.trainKey, 2 * day)
        assertEquals(2 * day, s.connections.single().most)
        // Dropped when the other service goes.
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.UNSHARE))
        assertNull(w.services[w.serviceKey0()])
        assertTrue(s.connections.isEmpty())
    }

    private fun World.serviceKey0() = serviceKey(rail, 0)

    @Test
    fun orderEditsKeepConnectionsAndDeparturesLinedUp() {
        val w = interchange()
        w.service(0, Command.ServiceOrder.CONNECT, 0, w.trainKey, day)
        val s = w.serviceOf(0)!!
        s.lastLeft[0] = 100
        s.lastLeft[1] = 200
        // An order put in ahead: the connection and departures move with their stops.
        w.order(0, Command.TrainOrder.ORDERS, 1, 0, 2)
        assertEquals(3, s.orders.size)
        assertEquals(1, s.connections.single().order)
        assertContentEquals(longArrayOf(-1, 100, 200), s.lastLeft)
        // A conditional order put in takes no time and moves nothing else.
        w.order(0, Command.TrainOrder.ORDERS, 1, 0, Order.cond(Order.IF_ALWAYS, Order.EQUAL, 0, 0), 2)
        assertEquals(1, s.connections.single().order)
        assertContentEquals(longArrayOf(-1, 100, -1, 200), s.lastLeft)
        assertEquals(-1, s.measuredTravel[2])
        // Its stop taken out: the connection goes.
        w.order(0, Command.TrainOrder.ORDERS, 1, 2)
        assertTrue(s.connections.isEmpty())
        assertContentEquals(longArrayOf(-1, 200), s.lastLeft)
    }

    @Test
    fun aSaveKeepsItAllAndStaysInStep() {
        val w = interchange()
        w.service(0, Command.ServiceOrder.CONNECT, 0, w.trainKey, 2 * day)
        w.service(0, Command.ServiceOrder.SPACED, 1)
        w.apply(Command.CompanyValue(0, 1, 42, "Season"))
        w.order(0, Command.TrainOrder.ORDERS, 0, Order.cond(Order.IF_VALUE + 1, Order.MORE, 40, 0), 2)
        w.order(0, Command.TrainOrder.START_STOP)
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        w.run(8_000)
        val older = Save.write(w, 61)
        val c = Save.read(Save.write(w))
        assertEquals(w.checksum(), c.checksum())
        val a = w.serviceOf(0)!!
        val b = c.serviceOf(0)!!
        assertTrue(b.spaced)
        assertContentEquals(a.lastLeft, b.lastLeft)
        assertContentEquals(a.lastReached, b.lastReached)
        assertEquals(a.connections.single().made + a.connections.single().missed, b.connections.single().made + b.connections.single().missed)
        assertEquals(42, c.companies[0].values[1])
        assertEquals("Season", c.companies[0].valueNames[1])
        assertContentEquals(w.roadVehicles[0]!!.orders.toIntArray(), c.roadVehicles[0]!!.orders.toIntArray())
        repeat(10_000) { w.step(); c.step() }
        assertEquals(w.checksum(), c.checksum())
        // A save from before loads with nothing spaced, no connections and values 0.
        val old = Save.read(older)
        assertTrue(old.services.values.none { it.spaced || it.connections.isNotEmpty() })
        assertTrue(old.companies.all { co -> co.values.all { it == 0 } })
        old.run(2_000)
        // A conditional order that's no good in a save is left out.
        val v = w.roadVehicles[0]!!
        v.orders.add(Order.COND or (6 shl 23))
        v.orders.add(Order.cond(Order.IF_ALWAYS, Order.EQUAL, 0, 9))
        val d = Save.read(Save.write(w))
        assertEquals(3, d.roadVehicles[0]!!.orders.size)
    }
}
