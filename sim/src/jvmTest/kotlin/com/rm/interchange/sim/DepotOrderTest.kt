package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Orders to a depot or garage: going via it, being serviced there only when due, and stopping in it. */
class DepotOrderTest {
    /** A line from depot 0 through stations 0 and 1 to depot 1 past the far end, and a train in depot 0. */
    private fun line(year: Int = 1900): World {
        val w = World(64, 64, 1, startYear = year)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        w.apply(Command.Depot(0, 40, 10, Track.XM))
        assertEquals(2, w.depots.size)
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:american" }.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 3))
        return w
    }

    private fun World.orders(vararg o: Int, train: Int = 0) = apply(Command.TrainOrder(0, train, Command.TrainOrder.ORDERS, o))
    private fun World.start(train: Int = 0) = apply(Command.TrainOrder(0, train, Command.TrainOrder.START_STOP))

    /** Runs [w] till [done] or [ticks] are up; true if it came. */
    private fun until(w: World, ticks: Int, done: () -> Boolean): Boolean {
        repeat(ticks) {
            if (done()) return true
            w.step()
        }
        return done()
    }

    private fun atStation(w: World, t: Train, s: Int) = !t.inDepot && t.waiting > 0 && w.stationTiles[stepTile(t.steps.last())] == s

    @Test
    fun aTrainGoesViaADepotAndStraightOn() {
        val w = line()
        w.orders(0, Order.toDepot(1, Order.DEPOT_VIA), 1)
        w.start()
        val t = w.trains[0]!!
        assertTrue(until(w, 20_000) { t.order == 1 && !t.inDepot && t.speed > 0 }, "never set off for the depot")
        t.serviced = w.workDay - 10
        val since = w.workDay
        var wasIn = false
        assertTrue(until(w, 20_000) { if (t.inDepot) wasIn = true; atStation(w, t, 1) }, "never got to station 1 after the depot")
        assertTrue(wasIn, "never went in the depot")
        assertEquals(2, t.order)
        assertEquals(1, t.depot)
        assertTrue(t.serviced >= since, "not serviced on the way through")
        assertTrue(t.running)
    }

    @Test
    fun aServiceOrderIsSkippedTillItsDue() {
        val w = line()
        w.orders(0, Order.toDepot(1, Order.DEPOT_SERVICE), 1)
        w.start()
        val t = w.trains[0]!!
        var wasIn = false
        assertTrue(until(w, 20_000) { if (t.inDepot && t.depot == 1) wasIn = true; atStation(w, t, 1) }, "never got to station 1")
        assertTrue(!wasIn, "went in to be serviced when it wasn't due")
        assertEquals(2, t.order)
        // Due on its way back to station 0: next time round it goes in.
        assertTrue(until(w, 20_000) { t.order == 0 && t.waiting == 0 && t.speed > 0 }, "never set off back")
        t.serviced = w.workDay - SERVICE_DAYS
        assertTrue(until(w, 40_000) { t.inDepot && t.depot == 1 }, "never went in when due")
        assertEquals(2, t.order)
        assertTrue(t.running)
        assertTrue(w.workDay - t.serviced < 2, "not serviced")
    }

    @Test
    fun aStopOrderStopsInTheDepotAndGoesOnWhenStarted() {
        val w = line()
        w.orders(0, Order.toDepot(1, Order.DEPOT_STOP), 1)
        w.start()
        val t = w.trains[0]!!
        assertTrue(until(w, 20_000) { t.inDepot && t.depot == 1 }, "never went in")
        repeat(500) { w.step() }
        assertTrue(t.inDepot && !t.running, "didn't stop inside")
        assertEquals(2, t.order)
        w.start()
        assertTrue(until(w, 20_000) { atStation(w, t, 1) }, "never went on to station 1")
    }

    @Test
    fun sharedOrdersCopyDepotOrders() {
        val w = line()
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:american" }.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 3))
        val orders = intArrayOf(0, Order.toDepot(1, Order.DEPOT_STOP), 1, Order.toDepot(0, Order.DEPOT_SERVICE))
        w.orders(*orders)
        w.apply(Command.TrainOrder(0, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        assertContentEquals(orders, w.trains[1]!!.orders.toIntArray())
        // Changed for one, they change for both.
        val changed = intArrayOf(Order.toDepot(1, Order.DEPOT_VIA), 1)
        w.orders(*changed, train = 1)
        assertContentEquals(changed, w.trains[0]!!.orders.toIntArray())
    }

    @Test
    fun ordersAreRefusedForADepotThatIsntYoursOrDoesntFit() {
        val w = line(year = 1914)
        // Stray bits are cleared and a kind there isn't is turned away.
        w.orders(Order.toDepot(1, Order.DEPOT_STOP) or Order.SKIP_IF_NOTHING, Order.DEPOT or (3 shl 16) or 1, 0)
        assertContentEquals(intArrayOf(Order.toDepot(1, Order.DEPOT_STOP), 0), w.trains[0]!!.orders.toIntArray())
        w.depots[1]!!.owner = 1
        w.orders(0, Order.toDepot(1), 1)
        assertContentEquals(intArrayOf(0, 1), w.trains[0]!!.orders.toIntArray())
        // A garage isn't for trains.
        w.depots[1]!!.owner = 0
        w.apply(Command.Road(0, 20, 20, 20, 22, Surface.GRAVEL, remove = false))
        w.apply(Command.Garage(0, 20, 23, Track.YM))
        val garage = w.depots.indexOfFirst { it?.kind == Depots.ROAD }
        assertTrue(garage >= 0)
        w.orders(0, Order.toDepot(garage), 1)
        assertContentEquals(intArrayOf(0, 1), w.trains[0]!!.orders.toIntArray())
        // An electric train needs wires over the depot.
        w.apply(Command.Wire(0, 2, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:boxcab" }.id, w.roster.engines.first { it.key == "base:coach_steel" }.id, 3))
        val electric = w.trains.indexOfLast { it != null }
        assertTrue(w.trains[electric]!!.electric)
        w.orders(0, Order.toDepot(1), 1, Order.toDepot(0), train = electric)
        assertContentEquals(intArrayOf(0, 1, Order.toDepot(0)), w.trains[electric]!!.orders.toIntArray())
    }

    @Test
    fun linksAndNextStopsLeaveDepotsOut() {
        val w = line()
        w.orders(0, Order.toDepot(1), 1, Order.toDepot(0, Order.DEPOT_STOP))
        assertEquals(1, w.nextHop(0, 1))
        assertEquals(0, w.nextHop(1, 0))
        // Depot 1's id is a station's too: no link to it is made.
        w.orders(Order.toDepot(1), 0)
        assertEquals(-1, w.nextHop(0, 1))
    }

    @Test
    fun goneDepotsLeaveTheOrders() {
        val w = line()
        w.orders(0, Order.toDepot(1), 1)
        w.apply(Command.Clear(0, 40, 10))
        assertContentEquals(intArrayOf(0, 1), w.trains[0]!!.orders.toIntArray())
    }

    @Test
    fun aSaveWithDepotOrdersCarriesOnTheSame() {
        val a = line()
        a.orders(0, Order.toDepot(1, Order.DEPOT_VIA), 1, Order.toDepot(0, Order.DEPOT_SERVICE))
        a.start()
        repeat(2_000) { a.step() }
        val b = Save.read(Save.write(a))
        assertContentEquals(a.trains[0]!!.orders.toIntArray(), b.trains[0]!!.orders.toIntArray())
        assertEquals(a.checksum(), b.checksum())
        repeat(6_000) { a.step(); b.step() }
        assertEquals(a.checksum(), b.checksum())
        assertContentEquals(Save.write(a), Save.write(b))
    }

    @Test
    fun aVersion54SaveStillLoadsAndRuns() {
        fun world(): World {
            val w = line()
            w.orders(0, 1)
            w.start()
            repeat(1_000) { w.step() }
            return w
        }
        val a = Save.read(Save.write(world(), version = 54))
        val b = Save.read(Save.write(world(), version = 54))
        assertContentEquals(intArrayOf(0, 1), a.trains[0]!!.orders.toIntArray())
        repeat(2_000) { a.step(); b.step() }
        assertEquals(a.checksum(), b.checksum())
    }

    @Test
    fun depotOrdersComeBackThroughTheCodec() {
        val orders = intArrayOf(0, Order.toDepot(1, Order.DEPOT_STOP), Order.toDepot(65_535, Order.DEPOT_SERVICE))
        val back = Codec.decode(Codec.encode(Command.TrainOrder(0, 3, Command.TrainOrder.ORDERS, orders))) as Command.TrainOrder
        assertContentEquals(orders, back.stations)
        assertEquals(65_535, Order.depot(orders[2]))
        assertEquals(Order.DEPOT_SERVICE, Order.depotKind(orders[2]))
        assertEquals(-1, Order.station(orders[1]))
        assertEquals(-1, Order.depot(orders[0]))
    }

    /**
     * A paved road along y = 20 from x = 5 to 40, stops 0 and 1 at x = 8 and 38, garage 0 below its west end and
     * garage 1 below its east end, and a bus in garage 0 that keeps its tires all year.
     */
    private fun road(): World {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 40, 20, 40, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        w.apply(Command.Garage(0, 40, 23, Track.YM))
        w.apply(Command.RoadStop(0, 8, 20))
        w.apply(Command.RoadStop(0, 38, 20))
        assertEquals(2, w.depots.size)
        w.apply(Command.BuyRoadVehicle(0, 0, w.roster.engines.first { it.key == "base:new_look" }.id))
        w.roadVehicles[0]!!.seasonalTires = false
        return w
    }

    private fun World.roadOrders(vararg o: Int, vehicle: Int = 0) = apply(Command.RoadOrder(0, vehicle, Command.TrainOrder.ORDERS, o))
    private fun World.roadStart(vehicle: Int = 0) = apply(Command.RoadOrder(0, vehicle, Command.TrainOrder.START_STOP))
    private fun atStop(w: World, v: RoadVehicle, s: Int) = !v.inDepot && v.waiting > 0 && w.roadStops[roadStepTile(v.at)] == s

    @Test
    fun aBusGoesViaAGarageAndStraightOn() {
        val w = road()
        w.roadOrders(0, Order.toDepot(1, Order.DEPOT_VIA), 1)
        w.roadStart()
        val v = w.roadVehicles[0]!!
        assertTrue(until(w, 20_000) { v.order == 1 && !v.inDepot && v.speed > 0 }, "never set off for the garage")
        v.serviced = w.workDay - 10
        val since = w.workDay
        var wasIn = false
        assertTrue(until(w, 20_000) { if (v.inDepot) wasIn = true; atStop(w, v, 1) }, "never got to stop 1 after the garage")
        assertTrue(wasIn, "never went in the garage")
        assertEquals(2, v.order)
        assertEquals(1, v.depot)
        assertTrue(v.serviced >= since, "not serviced on the way through")
        assertTrue(v.running)
    }

    @Test
    fun aBusServiceOrderIsSkippedTillItsDue() {
        val w = road()
        w.roadOrders(0, Order.toDepot(1, Order.DEPOT_SERVICE), 1)
        w.roadStart()
        val v = w.roadVehicles[0]!!
        var wasIn = false
        assertTrue(until(w, 20_000) { if (v.inDepot && v.depot == 1) wasIn = true; atStop(w, v, 1) }, "never got to stop 1")
        assertTrue(!wasIn, "went in to be serviced when it wasn't due")
        assertEquals(2, v.order)
        assertTrue(until(w, 20_000) { v.order == 0 && v.waiting == 0 && v.speed > 0 }, "never set off back")
        v.serviced = w.workDay - SERVICE_DAYS
        assertTrue(until(w, 40_000) { v.inDepot && v.depot == 1 }, "never went in when due")
        assertEquals(2, v.order)
        assertTrue(v.running)
        assertTrue(w.workDay - v.serviced < 2, "not serviced")
    }

    @Test
    fun aBusStopOrderStopsInTheGarageAndGoesOnWhenStarted() {
        val w = road()
        w.roadOrders(0, Order.toDepot(1, Order.DEPOT_STOP), 1)
        w.roadStart()
        val v = w.roadVehicles[0]!!
        assertTrue(until(w, 20_000) { v.inDepot && v.depot == 1 }, "never went in")
        repeat(500) { w.step() }
        assertTrue(v.inDepot && !v.running, "didn't stop inside")
        assertEquals(2, v.order)
        w.roadStart()
        assertTrue(until(w, 20_000) { atStop(w, v, 1) }, "never went on to stop 1")
    }

    @Test
    fun roadOrdersAreRefusedForAGarageThatIsntYoursOrARailDepot() {
        val w = road()
        w.roadOrders(Order.toDepot(1, Order.DEPOT_STOP) or Order.SKIP_IF_NOTHING, Order.DEPOT or (3 shl 16) or 1, 0)
        assertContentEquals(intArrayOf(Order.toDepot(1, Order.DEPOT_STOP), 0), w.roadVehicles[0]!!.orders.toIntArray())
        w.depots[1]!!.owner = 1
        w.roadOrders(0, Order.toDepot(1), 1)
        assertContentEquals(intArrayOf(0, 1), w.roadVehicles[0]!!.orders.toIntArray())
        w.depots[1]!!.owner = 0
        w.apply(Command.Rail(0, 20, 30, 30, 30, Track.X, remove = false))
        w.apply(Command.Depot(0, 19, 30, Track.XP))
        val rail = w.depots.indexOfFirst { it?.kind == Depots.RAIL }
        assertTrue(rail >= 0)
        w.roadOrders(0, Order.toDepot(rail), 1)
        assertContentEquals(intArrayOf(0, 1), w.roadVehicles[0]!!.orders.toIntArray())
    }

    @Test
    fun goneGaragesLeaveTheOrders() {
        val w = road()
        w.roadOrders(0, Order.toDepot(1), 1)
        w.apply(Command.Clear(0, 40, 23))
        assertContentEquals(intArrayOf(0, 1), w.roadVehicles[0]!!.orders.toIntArray())
    }

    @Test
    fun aSaveWithRoadDepotOrdersCarriesOnTheSame() {
        val a = road()
        a.roadOrders(0, Order.toDepot(1, Order.DEPOT_VIA), 1, Order.toDepot(0, Order.DEPOT_SERVICE))
        a.roadStart()
        repeat(2_000) { a.step() }
        val b = Save.read(Save.write(a))
        assertContentEquals(a.roadVehicles[0]!!.orders.toIntArray(), b.roadVehicles[0]!!.orders.toIntArray())
        assertEquals(a.checksum(), b.checksum())
        repeat(6_000) { a.step(); b.step() }
        assertEquals(a.checksum(), b.checksum())
        assertContentEquals(Save.write(a), Save.write(b))
    }
}
