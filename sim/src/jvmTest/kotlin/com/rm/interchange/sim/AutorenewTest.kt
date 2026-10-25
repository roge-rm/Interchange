package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Autorenew: a vehicle near the end of its life is renewed at its next service, the same model, keeping its orders,
 * name and cargo; skipped with one news line when the company would fall below its floor; never when it's off.
 */
class AutorenewTest {
    private fun World.days(n: Int) = repeat(Calendar.TICKS_PER_DAY * n) { step() }

    /** A line with two stations and a depot, and a train of [wagons] wagons set to carry what [wagon] carries second, if anything. */
    private fun railLine(wagons: Int = 3): World {
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        val wagon = w.roster.engines.firstOrNull { it.kind == VehicleKind.WAGON && it.cargoes.size >= 2 && w.onSale(it) && it.plow == Plows.NONE }
            ?: w.roster.engines.first { it.key == "base:coach_wood" }
        val cargo = wagon.cargoes.getOrElse(1) { -1 }
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:american" }.id, wagon.id, wagons, cargo))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        return w
    }

    private fun bus(): World {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        w.apply(Command.RoadStop(0, 8, 20))
        w.apply(Command.RoadStop(0, 38, 20))
        w.apply(Command.BuyRoadVehicle(0, 0, w.roster.engines.first { it.key == "base:new_look" }.id))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.START_STOP))
        w.roadVehicles[0]!!.seasonalTires = false
        return w
    }

    private fun ship(): World {
        val w = World(64, 64, 1, startYear = 1965)
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 10..50 && y in 10..30) 0 else 1
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        w.apply(Command.Dock(0, 9, 20))
        w.apply(Command.Dock(0, 50, 22))
        w.apply(Command.Shipyard(0, 9, 15))
        w.apply(Command.BuyCraft(0, 0, w.roster.engines.first { it.key == "base:steam_laker" }.id))
        w.apply(Command.CraftOrder(0, false, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.CraftOrder(0, false, 0, Command.TrainOrder.START_STOP))
        return w
    }

    private fun plane(): World {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 1
        w.companies[0].money = 100_000_000
        w.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
        w.apply(Command.Airport(0, 40, 40, Airports.REGIONAL, Track.Y))
        w.apply(Command.BuyCraft(0, w.airports[0]!!.depot, w.roster.engines.first { it.key == "base:dc3" }.id))
        w.apply(Command.CraftOrder(0, true, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.CraftOrder(0, true, 0, Command.TrainOrder.START_STOP))
        return w
    }

    /** Runs [w] a while, then makes the vehicle due with [age], and runs till [renewed] or [days] work days go by. */
    private fun runTillRenewed(w: World, days: Int, age: () -> Unit, renewed: () -> Boolean): Boolean {
        w.days(20)
        age()
        repeat(days) {
            w.days(1)
            if (renewed()) return true
        }
        return false
    }

    private fun bought(w: World) = -w.companies[0].thisYear[Books.NEW_TRAINS]

    @Test
    fun aTrainIsRenewedWholeKeepingItsOrdersNameAndCargo() {
        val w = railLine(wagons = 3)
        val t = w.trains[0]!!
        t.name = "Flyer"
        val cargo = t.wagonCargo
        var cost = 0L
        var spent = 0L
        var mark = 0
        val done = runTillRenewed(w, 120, {
            t.built = w.workDay - t.engineInfo.life * 365 + 6 * WORK_MONTH
            t.reliability = 3000
            mark = w.workDay
            cost = w.renewCost(t)
            spent = bought(w)
        }) { t.built >= mark }
        assertTrue(done, "never renewed")
        assertTrue(w.trains[0] === t)
        assertEquals("Flyer", t.name)
        assertEquals(cargo, t.wagonCargo)
        assertEquals(3, t.wagons)
        assertContentEquals(intArrayOf(0, 1), t.orders.toIntArray())
        // Engine and cars: what it cost is the whole train's price new, less what it fetched.
        assertEquals(cost, bought(w) - spent)
        assertTrue(cost > w.price(t.engineInfo.cost) - w.value(t))
        assertTrue(t.reliability >= t.engineInfo.reliability * 100 - WEAR_A_DAY * 2, "reliability ${t.reliability}")
        assertEquals(0, w.age(t))
        w.days(10)
        assertTrue(!t.inDepot && t.running, "didn't go back to work")
    }

    @Test
    fun sharedOrdersStayShared() {
        val w = railLine(wagons = 1)
        // A second train sharing the first one's orders, kept in the depot.
        w.apply(Command.BuyTrain(0, 0, w.trains[0]!!.engine, w.trains[0]!!.wagon, 1))
        w.apply(Command.TrainOrder(0, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        val t = w.trains[0]!!
        val other = w.trains[1]!!
        assertTrue(t.shared >= 0 && t.shared == other.shared)
        var mark = 0
        assertTrue(runTillRenewed(w, 120, { t.built = w.workDay - t.engineInfo.life * 365; mark = w.workDay }) { t.built >= mark }, "never renewed")
        assertEquals(other.shared, t.shared)
        assertContentEquals(other.orders.toIntArray(), t.orders.toIntArray())
        // Orders set on one still go to both.
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(1, 0)))
        assertContentEquals(intArrayOf(1, 0), other.orders.toIntArray())
    }

    @Test
    fun aBusIsRenewedAtItsGarage() {
        val w = bus()
        val v = w.roadVehicles[0]!!
        v.name = "Crosstown"
        var mark = 0
        var cost = 0L
        var spent = 0L
        assertTrue(runTillRenewed(w, 120, {
            v.built = w.workDay - v.info.life * 365
            mark = w.workDay; cost = w.renewCost(v); spent = bought(w)
        }) { v.built >= mark }, "never renewed")
        assertEquals("Crosstown", v.name)
        assertContentEquals(intArrayOf(0, 1), v.orders.toIntArray())
        assertEquals(cost, bought(w) - spent)
        assertTrue(w.roadVehicles[0] === v)
    }

    @Test
    fun aShipIsRenewedAtItsShipyard() {
        val w = ship()
        val v = w.ships[0]!!
        v.name = "Lady Elgin"
        var mark = 0
        assertTrue(runTillRenewed(w, 200, { v.built = w.workDay - v.info.life * 365; mark = w.workDay }) { v.built >= mark }, "never renewed")
        assertEquals("Lady Elgin", v.name)
        assertContentEquals(intArrayOf(0, 1), v.orders.toIntArray())
        assertTrue(v.reliability >= v.info.reliability * 100 - WEAR_A_DAY * 2, "reliability ${v.reliability}")
    }

    @Test
    fun anAircraftIsRenewedInAHangar() {
        val w = plane()
        val v = w.aircraft[0]!!
        v.name = "Bluenose"
        var mark = 0
        assertTrue(runTillRenewed(w, 120, { v.built = w.workDay - v.info.life * 365; mark = w.workDay }) { v.built >= mark }, "never renewed")
        assertEquals("Bluenose", v.name)
        assertContentEquals(intArrayOf(0, 1), v.orders.toIntArray())
    }

    @Test
    fun notRenewedWhenOff() {
        val w = bus()
        val v = w.roadVehicles[0]!!
        w.apply(Command.SetAutorenew(0, false, -6, 0))
        assertTrue(!w.companies[0].autorenew)
        val old = w.workDay - v.info.life * 365 - 400
        v.built = old
        w.days(SERVICE_DAYS * 2)
        assertEquals(old, v.built)
        assertTrue(w.workDay - v.serviced < SERVICE_DAYS * 2, "not serviced as usual")
        assertEquals(0, w.chronicle.count { it.kind == News.RENEW_FAILED })
    }

    @Test
    fun belowTheFloorItsSkippedWithOneNewsLineThenRenewedWhenThereIsMoney() {
        val w = bus()
        val v = w.roadVehicles[0]!!
        val c = w.companies[0]
        w.apply(Command.SetAutorenew(0, true, 0, c.money + 1_000_000))
        val old = w.workDay - v.info.life * 365 - 10
        v.built = old
        var trips = 0
        var was = false
        // Long enough for a service or two of its own, where it's tried again.
        repeat(SERVICE_DAYS * 2) {
            w.days(1)
            if (v.servicing && !was) trips++
            was = v.servicing
        }
        assertEquals(old, v.built)
        assertEquals(1, w.chronicle.count { it.kind == News.RENEW_FAILED })
        assertEquals(Autorenew.ROAD shl Autorenew.KIND_SHIFT or v.id, w.chronicle.last { it.kind == News.RENEW_FAILED }.about)
        assertTrue(v.renewFailed)
        // Only its own services: about one a SERVICE_DAYS, not one a work month.
        assertTrue(trips <= 3, "sent in $trips times")
        // Money again: renewed at its next service, and a later failure would be told again.
        w.apply(Command.SetAutorenew(0, true, 0, 0))
        var mark = w.workDay
        assertTrue(runTillRenewed(w, 120, { mark = w.workDay }) { v.built >= mark }, "never renewed")
        assertTrue(!v.renewFailed)
    }

    @Test
    fun aModelNoLongerOnSaleIsntRenewed() {
        val w = railLine(wagons = 1)
        val t = w.trains[0]!!
        // Run on past the engine's last year.
        val until = t.engineInfo.until
        if (until > 2100) return
        w.tick = Calendar.TICKS_PER_DAY.toLong() * 366 * (until - 1900 + 1)
        val old = w.workDay - t.engineInfo.life * 365 - 10
        t.built = old
        assertTrue(!w.renewDue(t))
        t.built = w.workDay
        assertTrue(!w.renewDue(t))
    }

    @Test
    fun theSettingTravelsAndIsSaved() {
        val c = Command.SetAutorenew(2, true, 3, 25_000)
        assertEquals(c, Codec.decode(Codec.encode(c)))
        val w = railLine()
        val sum = w.checksum()
        w.apply(Command.SetAutorenew(0, true, 3, 25_000))
        assertTrue(w.checksum() != sum)
        w.trains[0]!!.renewFailed = true
        w.trains[0]!!.renewAsked = 7
        val back = Save.read(Save.write(w))
        assertEquals(3, back.companies[0].renewMonths)
        assertEquals(25_000, back.companies[0].renewFloor)
        assertTrue(back.trains[0]!!.renewFailed)
        assertEquals(7, back.trains[0]!!.renewAsked)
        assertEquals(w.checksum(), back.checksum())
        // An older save has it on, as a new game does.
        val older = Save.read(Save.write(w, version = 39))
        assertTrue(older.companies[0].autorenew)
        assertEquals(Autorenew.MONTHS_DEFAULT, older.companies[0].renewMonths)
    }

    @Test
    fun rivalsRenewTheirVehicles() {
        val w = World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 2))
        w.days(400)
        val aged = ArrayList<RoadVehicle>()
        for (v in w.roadVehicles) {
            if (v == null || !w.rivals.any { it.company == v.owner } || !v.service || !w.onSale(v.info)) continue
            v.built = w.workDay - v.info.life * 365
            aged += v
        }
        assertTrue(aged.isNotEmpty(), "rivals have no road vehicles")
        val mark = w.workDay
        w.days(SERVICE_DAYS + 60)
        val renewed = aged.count { v -> w.roadVehicles.getOrNull(v.id) === v && v.built >= mark }
        val left = aged.count { v -> w.roadVehicles.getOrNull(v.id) === v }
        assertTrue(renewed * 2 >= left && renewed > 0, "$renewed of $left renewed")
        for (r in w.rivals) assertTrue(w.companies[r.company].autorenew)
    }

    @Test
    fun aRivalsTwoTrainLineHasItsTrainsRenewedOneAtATime() {
        val w = World(NewGame(160, 160, 1, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        val r = w.rivals[0]
        val c = w.companies[r.company]
        c.money = 10_000_000
        r.thinkOn = Int.MAX_VALUE
        val serves = { st: Station, _: Boolean -> (w.stationPeople[st.id] ?: 0) > 0 }
        val towns = w.towns.sortedByDescending { it.population }
        var route: RivalRoute? = null
        for (t1 in towns) for (t2 in towns) {
            if (route != null || t1.id == t2.id) continue
            if (w.tryRail(c, railSite(t1, t2.x, t2.y), railSite(t2, t1.x, t1.y), Cargo.PASSENGERS, 100, t1.id, t2.id, serves, payback = 0) { r.routes += it } == Tried.BUILT) route = r.routes.last()
        }
        val line = route ?: error("no line to test with")
        assertTrue(w.addLoop(c, line), "no loop laid")
        assertTrue(w.addTrain(c, line), "no second train")
        val on = w.trains.filterNotNull().filter { it.owner == r.company }
        assertEquals(2, on.size)
        w.days(30)
        assertTrue(on.none { it.service }, "a two-train line's trains are serviced")
        assertTrue(on.all { w.onSale(it.engineInfo) && w.onSale(w.roster[it.wagon]) }, "a model off sale")
        for (t in on) { t.built = w.workDay - t.engineInfo.life * 365; t.profitLastYear = 1_000 }
        val mark = w.workDay
        var inAtOnce = 0
        val calls = IntArray(2)
        val last = IntArray(2) { -1 }
        repeat(240) {
            w.days(1)
            inAtOnce = maxOf(inAtOnce, on.count { it.toDepot })
            for ((k, t) in on.withIndex()) if (t.order != last[k]) { calls[k]++; last[k] = t.order }
        }
        assertTrue(on.all { w.trains.getOrNull(it.id) === it && it.built >= mark }, "renewed: ${on.map { it.built >= mark }}")
        assertEquals(1, inAtOnce, "both went in at once")
        assertTrue(on.none { it.service }, "servicing turned back on")
        // The line kept running.
        w.days(60)
        assertTrue(calls.all { it >= 6 }, "calls ${calls.toList()}")
        assertTrue(on.all { !it.inDepot && it.running })
    }
}
