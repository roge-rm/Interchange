package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Servicing: a vehicle that comes due on its way to its second stop calls there as usual, then goes to the
 * depot nearest that stop, not its home one at the first; one that calls nowhere still goes in twice the time.
 */
class ServiceTest {
    /** What happened once [due] was set: whether it was at its second stop when it went, and where it was serviced. */
    private class Trip(var wentFrom: Boolean? = null, var dueTick: Long = 0, var serviced: Boolean = false)

    /**
     * Runs [w] till [v] (read through [order], [inDepot], [servicing] and [serviced]) is on its way to its second
     * stop, makes it due, then runs on till it's been serviced, noting whether [atSecond] held when it went.
     */
    private fun run(
        w: World, ticks: Int, order: () -> Int, moving: () -> Boolean, servicing: () -> Boolean,
        serviced: () -> Int, makeDue: () -> Unit, atSecond: () -> Boolean,
    ): Trip {
        val trip = Trip()
        var k = 0
        while (k++ < ticks && !(order() == 1 && moving())) w.step()
        assertTrue(order() == 1 && moving(), "never set off for its second stop")
        makeDue()
        trip.dueTick = w.tick
        val dueDay = w.workDay
        while (k++ < ticks) {
            w.step()
            if (trip.wentFrom == null && servicing()) trip.wentFrom = atSecond()
            if (serviced() >= dueDay) { trip.serviced = true; break }
        }
        return trip
    }

    private fun called(w: World, station: Int, since: Long) = w.stations[station]!!.lastCall.any { it >= since }

    @Test
    fun aTrainFinishesItsLegThenServicesAtTheNearestDepot() {
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        // A second depot behind the far station.
        w.apply(Command.Depot(0, 40, 10, Track.XM))
        assertEquals(2, w.depots.size)
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:american" }.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        val trip = run(
            w, 40_000, { t.order }, { !t.inDepot && t.waiting == 0 && t.speed > 0 }, { t.servicing }, { t.serviced },
            { t.serviced = w.workDay - SERVICE_DAYS }, { w.stationTiles[stepTile(t.steps.last())] == 1 && t.order == 0 },
        )
        assertEquals(true, trip.wentFrom, "went for its service before calling at its second stop")
        assertTrue(called(w, 1, trip.dueTick))
        assertTrue(trip.serviced, "never serviced")
        assertEquals(1, t.depot)
        // Back to its orders.
        repeat(Calendar.TICKS_PER_DAY * 4) { w.step() }
        assertTrue(!t.inDepot && t.running)
    }

    /** A train out on the line with its orders taken away, so it stands and calls nowhere. */
    private fun standing(service: Boolean): Pair<World, Train> {
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:american" }.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 1))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        t.service = service
        repeat(400) { w.step() }
        assertTrue(!t.inDepot)
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, IntArray(0)))
        return w to t
    }

    @Test
    fun aTrainCallingNowhereStillGoesInTwiceTheTime() {
        val (w, t) = standing(true)
        t.serviced = w.workDay - SERVICE_DAYS
        repeat(Calendar.TICKS_PER_DAY * 10) { w.step() }
        assertTrue(!t.servicing && w.workDay - t.serviced >= SERVICE_DAYS, "went though only due, with no stop to finish at")
        t.serviced = w.workDay - SERVICE_DAYS * 2
        val before = w.workDay
        repeat(Calendar.TICKS_PER_DAY * 30) { w.step() }
        assertTrue(t.serviced >= before, "never went in")
    }

    @Test
    fun aTrainSetNotToBeServicedNeverGoes() {
        val (w, t) = standing(false)
        t.serviced = w.workDay - SERVICE_DAYS * 3
        val before = t.serviced
        repeat(Calendar.TICKS_PER_DAY * 30) { w.step() }
        assertTrue(t.serviced == before && !t.servicing && !t.inDepot, "set not to be serviced, but went")
    }

    @Test
    fun aBusFinishesItsLegThenServicesAtTheNearestGarage() {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        w.apply(Command.RoadStop(0, 8, 20))
        w.apply(Command.RoadStop(0, 38, 20))
        // A second garage at the far end.
        w.apply(Command.Road(0, 40, 20, 40, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 40, 23, Track.YM))
        assertEquals(2, w.depots.size)
        w.apply(Command.BuyRoadVehicle(0, 0, w.roster.engines.first { it.key == "base:new_look" }.id))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.START_STOP))
        val v = w.roadVehicles[0]!!
        v.seasonalTires = false
        val trip = run(
            w, 40_000, { v.order }, { !v.inDepot && v.waiting == 0 && v.speed > 0 }, { v.servicing }, { v.serviced },
            { v.serviced = w.workDay - SERVICE_DAYS }, { w.roadStops[roadStepTile(v.at)] == 1 && v.order == 0 },
        )
        assertEquals(true, trip.wentFrom, "went for its service before calling at its second stop")
        assertTrue(called(w, 1, trip.dueTick))
        assertTrue(trip.serviced, "never serviced")
        assertEquals(1, v.depot)
    }

    /** A closer garage on a long spur and a garage beside the road between the bus's stops. */
    private fun garageDetour(): Pair<World, RoadVehicle> {
        val w = World(64, 64, 1, startYear = 1965)
        w.corners.fill(3)
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 10, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 36, 20, 36, 24, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 36, 25, Track.YM))
        w.apply(Command.Garage(0, 25, 21, Track.YM))
        w.apply(Command.RoadStop(0, 12, 20))
        w.apply(Command.RoadStop(0, 38, 20))
        assertEquals(2, w.depots.size)
        assertEquals(2, w.stations.size)
        w.apply(Command.BuyRoadVehicle(0, 0, w.roster.engines.first { it.key == "base:new_look" }.id))
        val v = w.roadVehicles[0]!!
        v.at = roadStep(w.index(38, 20), Track.XP, Track.XM)
        v.inDepot = false
        v.orders.addAll(listOf(0, 1))
        v.toDepot = true
        v.servicing = true
        v.seasonalTires = false
        return w to v
    }

    private fun chosenGarage(w: World, v: RoadVehicle) =
        w.depotTiles[roadStepTile(w.planRoadRoute(v)!!.last())]

    @Test
    fun aBusServicesWithTheSmallestDetourToItsNextStop() {
        val (w, v) = garageDetour()
        assertEquals(1, chosenGarage(w, v), "chose the closer garage on the long spur")
        // Planning on workers must leave the vehicle's orders and depot alone.
        assertEquals(0, v.depot)
        assertEquals(0, v.order)
        assertEquals(listOf(0, 1), v.orders)
        v.running = true
        v.serviced = w.workDay - SERVICE_DAYS
        var ticks = 0
        while (ticks++ < 20_000 && v.serviced < 0) w.step()
        assertEquals(1, v.depot)
        assertTrue(v.serviced >= 0, "never serviced")
        while (ticks++ < 20_000 && !called(w, 0, 0)) w.step()
        assertTrue(called(w, 0, 0), "never resumed its orders")
    }

    @Test
    fun aBusGarageChoiceFollowsItsNextOrder() {
        val (w, v) = garageDetour()
        v.order = 1
        assertEquals(0, chosenGarage(w, v))
    }

    @Test
    fun aBusWithNoOrdersServicesAtTheNearestGarage() {
        val (w, v) = garageDetour()
        v.orders.clear()
        assertEquals(0, chosenGarage(w, v))
    }

    @Test
    fun aBusSentInByHandStillGoesToItsOwnGarage() {
        val (w, v) = garageDetour()
        v.servicing = false
        assertEquals(0, chosenGarage(w, v))
    }

    @Test
    fun aBusWithAnUnreachableNextStopStillGetsServiced() {
        val (w, v) = garageDetour()
        w.apply(Command.Road(0, 10, 40, 15, 40, Surface.PAVED, remove = false))
        w.apply(Command.RoadStop(0, 12, 40))
        assertEquals(3, w.stations.size)
        v.orders[0] = 2
        assertEquals(0, chosenGarage(w, v))
    }

    @Test
    fun aBusWithNoOrdersStillGoesInTwiceTheTime() {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        w.apply(Command.RoadStop(0, 38, 20))
        w.apply(Command.BuyRoadVehicle(0, 0, w.roster.engines.first { it.key == "base:new_look" }.id))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0)))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.START_STOP))
        val v = w.roadVehicles[0]!!
        v.seasonalTires = false
        repeat(200) { w.step() }
        assertTrue(!v.inDepot)
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, IntArray(0)))
        v.serviced = w.workDay - SERVICE_DAYS * 2
        val before = w.workDay
        repeat(Calendar.TICKS_PER_DAY * 80) { w.step() }
        assertTrue(v.serviced >= before, "never went in")
    }

    @Test
    fun aShipFinishesItsCrossingThenServicesAtTheNearestShipyard() {
        val w = World(64, 64, 1, startYear = 1965)
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 10..50 && y in 10..30) 0 else 1
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        w.apply(Command.Dock(0, 9, 20))
        w.apply(Command.Dock(0, 50, 22))
        w.apply(Command.Shipyard(0, 9, 15))
        // A second shipyard on the far shore.
        w.apply(Command.Shipyard(0, 50, 16))
        assertEquals(2, w.depots.size)
        w.apply(Command.BuyCraft(0, 0, w.roster.engines.first { it.key == "base:steam_laker" }.id))
        w.apply(Command.CraftOrder(0, false, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.CraftOrder(0, false, 0, Command.TrainOrder.START_STOP))
        val v = w.ships[0]!!
        val trip = run(
            w, 60_000, { v.order }, { !v.inDepot && v.waiting == 0 && v.speed > 0 }, { v.servicing }, { v.serviced },
            { v.serviced = w.workDay - SERVICE_DAYS }, { w.shipTile(v) in w.berths(1) && v.order == 0 },
        )
        assertEquals(true, trip.wentFrom, "went for its service before calling at its second dock")
        assertTrue(called(w, 1, trip.dueTick))
        assertTrue(trip.serviced, "never serviced")
        assertEquals(1, v.depot)
    }

    @Test
    fun anAircraftFinishesItsFlightThenServicesInTheHangarThere() {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 1
        w.companies[0].money = 100_000_000
        w.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
        w.apply(Command.Airport(0, 40, 40, Airports.REGIONAL, Track.Y))
        w.apply(Command.BuyCraft(0, w.airports[0]!!.depot, w.roster.engines.first { it.key == "base:dc3" }.id))
        w.apply(Command.CraftOrder(0, true, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.CraftOrder(0, true, 0, Command.TrainOrder.START_STOP))
        val v = w.aircraft[0]!!
        val trip = run(
            w, 40_000, { v.order }, { !v.inDepot && v.flying }, { v.servicing }, { v.serviced },
            { v.serviced = w.workDay - SERVICE_DAYS }, { v.state == Flight.GATE && w.airports[v.at]!!.station == 1 && v.order == 0 },
        )
        assertEquals(true, trip.wentFrom, "went for its service before calling at its second airport")
        assertTrue(called(w, 1, trip.dueTick))
        assertTrue(trip.serviced, "never serviced")
        assertEquals(w.airports[1]!!.depot, v.depot)
    }

    @Test
    fun anAircraftWithNoOrdersStillGoesInTwiceTheTime() {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 1
        w.companies[0].money = 100_000_000
        w.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
        w.apply(Command.BuyCraft(0, w.airports[0]!!.depot, w.roster.engines.first { it.key == "base:dc3" }.id))
        w.apply(Command.CraftOrder(0, true, 0, Command.TrainOrder.START_STOP))
        val v = w.aircraft[0]!!
        repeat(10) { w.step() }
        assertTrue(!v.inDepot)
        v.serviced = w.workDay - SERVICE_DAYS * 2
        val before = w.workDay
        repeat(Calendar.TICKS_PER_DAY * 3) { w.step() }
        assertTrue(v.serviced >= before, "never went in")
    }

    @Test
    fun aTrainWithNoDepotItCanReachKeepsToItsOrders() {
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        // A depot off on its own, with no track to it.
        w.apply(Command.Depot(0, 2, 30, Track.XP))
        assertEquals(2, w.depots.size)
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:american" }.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        repeat(400) { w.step() }
        // Its home taken down while it's out: it's based at the other, which it can't reach.
        w.apply(Command.Clear(0, 2, 10))
        assertEquals(null, w.depots[0])
        assertEquals(1, t.depot)
        t.serviced = w.workDay - SERVICE_DAYS * 2
        var calls = 0
        var was = false
        repeat(Calendar.TICKS_PER_DAY * 80) {
            w.step()
            if (t.waiting > 0 && !was) calls++
            was = t.waiting > 0
        }
        assertTrue(calls >= 3, "called only $calls times")
        assertTrue(!t.toDepot && t.noDepotUntil > 0)
    }

    @Test
    fun aBushPlaneServicedAtASeaplaneBaseComesOutOnGearForItsOrders() {
        val w = World(64, 64, 1, startYear = 1965)
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 20..30 && y in 50..60) 0 else 1
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        // Summer, so floats are wanted only for a seaplane base in its orders.
        w.tick = Calendar.TICKS_PER_DAY * 200L
        w.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
        w.apply(Command.Airport(0, 40, 40, Airports.REGIONAL, Track.Y))
        w.apply(Command.Airport(0, 19, 55, Airports.SEAPLANE, 0))
        val base = w.airports.last()!!
        w.apply(Command.BuyCraft(0, base.depot, w.roster.engines.first { it.key == "base:beaver" }.id))
        val v = w.aircraft[0]!!
        assertEquals(Gear.FLOATS, v.gear)
        w.apply(Command.CraftOrder(0, true, 0, Command.TrainOrder.ORDERS, intArrayOf(w.airports[0]!!.station, w.airports[1]!!.station)))
        w.apply(Command.CraftOrder(0, true, 0, Command.TrainOrder.START_STOP))
        assertTrue(!v.inDepot && v.state == Flight.GATE && v.at == base.id)
        // Due as it leaves the base: on floats only the base's own hangar will do.
        v.serviced = w.workDay - SERVICE_DAYS
        v.waiting = 1
        val before = w.workDay
        repeat(4) { w.step() }
        assertTrue(v.serviced >= before, "never serviced")
        assertEquals(Gear.WHEELS, v.gear)
        assertEquals(w.wantedGear(v), v.gear)
    }

    @Test
    fun aGarageVisitedByAnotherBusCanComeDownAndItsBusesGoHome() {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        w.apply(Command.RoadStop(0, 8, 20))
        w.apply(Command.RoadStop(0, 38, 20))
        w.apply(Command.Road(0, 40, 20, 40, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 40, 23, Track.YM))
        w.apply(Command.BuyRoadVehicle(0, 0, w.roster.engines.first { it.key == "base:new_look" }.id))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.START_STOP))
        val v = w.roadVehicles[0]!!
        repeat(200) { w.step() }
        // Based at the far garage now, as after a service there.
        v.depot = 1
        w.apply(Command.Clear(0, 40, 23))
        assertEquals(null, w.depots[1])
        assertEquals(0, v.depot)
        // Sent in, it finds its way to the garage that's left.
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.TO_DEPOT))
        repeat(Calendar.TICKS_PER_DAY * 60) { if (!v.inDepot) w.step() }
        assertTrue(v.inDepot)
    }
}
