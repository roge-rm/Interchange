package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnergyTest {
    /** The base roster with [key] made a [traction] vehicle of [range] tiles. */
    private fun roster(key: String, traction: Int, range: Int): Roster {
        val base = Roster.BASE
        val e = base.engines.first { it.key == key }
        val models = base.engines.toMutableList()
        models[e.id] = Engine(
            e.id, e.key, e.kind, e.names, e.plurals, e.types, e.from, e.until, e.speed, e.power, e.weight,
            e.cost, e.running, e.length, e.carries, e.capacity, e.sprite, e.tender, e.enabled,
            tractive = e.tractive, traction = traction, life = e.life, reliability = e.reliability, range = range,
        )
        return Roster(base.packs, models, base.sprites)
    }

    /** A line with stations A and C 30 tiles apart, no wires, a depot behind A, and a battery GP9 with three coaches; handling off. */
    private fun line(range: Int = 1_000, energy: Boolean = true): World {
        val roster = roster("base:gp9", Traction.BATTERY, range)
        val w = World(NewGame(64, 64, 1, 1960, towns = 0, industries = 0, weather = false, handling = false, eraCosts = false, energy = energy), roster)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 50, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 40, 10, 43, 10, Track.X))
        w.apply(Command.BuyTrain(0, 0, roster.engines.first { it.key == "base:gp9" }.id, roster.engines.first { it.key == "base:coach_steel" }.id, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        return w
    }

    /** Runs [w] till train 0 is out on the line and moving. */
    private fun outOnTheLine(w: World): Train {
        val t = w.trains[0]!!
        var k = 0
        while (k++ < 5_000 && (t.inDepot || t.waiting > 0 || t.speed == 0 || stepTile(t.steps.last()) % w.width < 15)) w.step()
        assertTrue(!t.inDepot && t.speed > 0, "never set off")
        return t
    }

    @Test
    fun anOldSaveLoadsWithoutEnergyAndKeepsInStep() {
        val g = NewGame(128, 128, 6, 2020, Region.MIXED, towns = 8, industries = 6, rivals = 2, rivalLevel = RivalLevel.HARD)
        val old = Save.read(Save.write(World(g), version = 71))
        assertFalse(old.settings.energy)
        val off = Save.read(Save.write(World(g.copy(energy = false, future = false))))
        assertEquals(off.checksum(), old.checksum())
        while (old.day < 365) { old.step(); off.step() }
        assertEquals(off.checksum(), old.checksum())
        for (list in listOf<List<Carrier?>>(old.trains, old.roadVehicles, old.ships)) for (v in list) if (v != null) {
            assertEquals(Int.MAX_VALUE, v.charge)
            assertNull(old.energyView(v))
        }
        // Off, battery and hydrogen pay what a diesel would.
        val van = old.roster.engines.first { it.key == "base:electric_van" }
        assertEquals(EraCosts.DIESEL, EraCosts.fuelOf(van, old.settings.energy))
        assertEquals(-1, old.storeOf(van))
    }

    @Test
    fun aBatteryTrainRunsABranchWithNoWires() {
        val w = line()
        val t = outOnTheLine(w)
        val tile = stepTile(t.steps.last())
        repeat(Calendar.TICKS_PER_DAY * 4) { w.step() }
        assertTrue(stepTile(t.steps.last()) != tile, "didn't move")
        assertEquals(Traction.BATTERY, w.storeOf(t))
        val view = w.energyView(t)
        assertNotNull(view)
        assertTrue(view.charge in 1 until 1_000, "${view.charge}")
        assertFalse(view.flat)
        // Off, the same train has no store and pays diesel.
        val off = line(energy = false)
        assertNull(off.energyView(off.trains[0]!!))
        assertEquals(EraCosts.DIESEL, EraCosts.fuelOf(off.roster[off.trains[0]!!.engine], false))
        assertEquals(EraCosts.ELECTRIC, EraCosts.fuelOf(w.roster[t.engine], true))
    }

    /** Train 0 standing at A, with [charge] left. */
    private fun standingAtA(w: World, charge: Int): Train {
        val t = w.trains[0]!!
        t.inDepot = false
        t.running = true
        t.steps.clear()
        t.steps += step(w.index(11, 10), 0, 0)
        t.leftFrom = 0
        t.leftAt = -1
        t.waiting = 10
        t.charge = charge
        t.chargeTile = w.index(11, 10)
        return t
    }

    @Test
    fun aBatteryTrainChargesUnderWiresOrAtACharger() {
        // Nothing to charge from: it keeps what it has.
        var w = line()
        var t = standingAtA(w, 10)
        w.energyTick()
        assertEquals(10, t.charge)
        // Wires over the platform.
        w = line()
        t = standingAtA(w, 10)
        for (x in 10..13) w.wires[w.index(x, 10)] = -1
        w.energyTick()
        assertEquals(10 + Energy.WIRE_RATE, t.charge)
        assertTrue(w.energyView(t)!!.charging)
        // A charger, built with handling off; handling's parts aren't.
        w = line()
        assertFalse(w.settings.handling)
        w.apply(Command.Equip(0, 0, Equipment.CRANE))
        assertEquals(0, w.stations[0]!!.equipment)
        t = standingAtA(w, 10)
        val money = w.companies[0].money
        w.apply(Command.Equip(0, 0, Equipment.CHARGER))
        assertTrue(Equipment.has(w.stations[0]!!.equipment, Equipment.CHARGER))
        assertEquals(money - w.price(Equipment.COST[Equipment.CHARGER]), w.companies[0].money)
        w.energyTick()
        assertEquals(10 + Energy.CHARGER_RATE, t.charge)
        assertTrue(w.view().stations.first { it.id == 0 }.charger!!.built)
        // Removing it pays half back.
        val before = w.companies[0].money
        w.apply(Command.Equip(0, 0, Equipment.CHARGER, remove = true))
        assertEquals(0, w.stations[0]!!.equipment)
        assertTrue(w.companies[0].money > before)
        // With energy off there are no chargers.
        val off = line(energy = false)
        off.apply(Command.Equip(0, 0, Equipment.CHARGER))
        assertEquals(0, off.stations[0]!!.equipment)
        assertNull(off.view().stations.first { it.id == 0 }.charger)
    }

    @Test
    fun aBatteryTrainCrawlsWhenFlatAndSaysSo() {
        val w = line()
        val t = outOnTheLine(w)
        t.charge = 1
        val news = w.news.size
        val top = t.maxSpeed
        repeat(Calendar.TICKS_PER_DAY * 2) { w.step() }
        assertEquals(0, t.charge)
        assertTrue(w.flat(t))
        assertTrue(t.speed in 1..top / Energy.CRAWL, "${t.speed} of $top")
        val tile = stepTile(t.steps.last())
        repeat(Calendar.TICKS_PER_DAY * 4) { w.step() }
        assertTrue(stepTile(t.steps.last()) != tile, "stopped")
        val flats = w.news.drop(news).filter { it.kind == News.RAN_FLAT }
        assertEquals(1, flats.size)
        assertEquals(Autorenew.TRAIN shl Autorenew.KIND_SHIFT or t.id, flats[0].about)
    }

    /** A road with stops 30 tiles apart, a garage at each end, and a hydrogen bus of [range] running between them. */
    private fun busLine(range: Int): World {
        val roster = roster("base:new_look", Traction.HYDROGEN, range)
        val w = World(NewGame(64, 64, 1, 1965, towns = 0, industries = 0, weather = false, eraCosts = false), roster)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        w.apply(Command.RoadStop(0, 8, 20))
        w.apply(Command.RoadStop(0, 38, 20))
        w.apply(Command.Road(0, 40, 20, 40, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 40, 23, Track.YM))
        w.apply(Command.BuyRoadVehicle(0, 0, roster.engines.first { it.key == "base:new_look" }.id))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.START_STOP))
        w.roadVehicles[0]!!.seasonalTires = false
        return w
    }

    @Test
    fun aHydrogenBusGoesEarlyWhenLowAndFillsAtTheGarage() {
        val w = busLine(400)
        val v = w.roadVehicles[0]!!
        var k = 0
        while (k++ < 20_000 && !(v.order == 1 && !v.inDepot && v.waiting == 0 && v.speed > 0)) w.step()
        assertTrue(v.order == 1 && !v.inDepot, "never set off for its second stop")
        // Not due a service, but low.
        assertFalse(w.due(v.service, v.serviced))
        v.charge = 400 * Energy.UNIT / 10
        assertTrue(w.lowCharge(v))
        val called = w.tick
        var went = false
        var filled = false
        while (k++ < 40_000) {
            w.step()
            if (v.servicing) went = true
            if (went && v.charge == 400 * Energy.UNIT) { filled = true; break }
        }
        assertTrue(went, "never went for a service")
        assertTrue(filled, "never filled")
        assertTrue(w.stations[1]!!.lastCall.any { it >= called }, "went before its call")
        assertTrue(v.serviced >= (called / Calendar.TICKS_PER_DAY).toInt())
        // A hydrogen bus doesn't charge at a charger.
        w.apply(Command.Equip(0, 0, Equipment.CHARGER))
        v.leftFrom = 0
        v.leftAt = -1
        v.inDepot = false
        assertEquals(0, w.chargeRate(v, Traction.HYDROGEN))
    }

    @Test
    fun hydrogenCostsThreeTimesDieselIn2025AndLessBy2070() {
        fun ratio(year: Int) = EraCosts.fuel(EraCosts.HYDROGEN, year) * 100 / EraCosts.fuel(EraCosts.DIESEL, year)
        assertTrue(ratio(2025) in 280..320, "${ratio(2025)}")
        assertTrue(ratio(2050) in 95..105, "${ratio(2050)}")
        assertTrue(ratio(2070) in 75..85, "${ratio(2070)}")
        assertTrue(ratio(2030) > ratio(2040))
        // A battery van runs on the electric price, a hydrogen one on hydrogen, with energy on.
        val w = World(NewGame(64, 64, 1, 2025, towns = 0, industries = 0, weather = false))
        val van = w.roster.engines.first { it.key == "base:electric_van" }
        assertEquals(EraCosts.ELECTRIC, EraCosts.fuelOf(van, true))
        assertEquals(Traction.BATTERY, w.storeOf(w.roster.engines.first { it.key == "base:electric_bus" }))
        assertEquals(-1, w.storeOf(w.roster.engines.first { it.key == "base:flexity" }))
        val h = roster("base:low_floor", Traction.HYDROGEN, 400)
        assertEquals(EraCosts.HYDROGEN, EraCosts.fuelOf(h.engines.first { it.key == "base:low_floor" }, true))
        assertEquals(EraCosts.DIESEL, EraCosts.fuelOf(h.engines.first { it.key == "base:low_floor" }, false))
        assertNotNull(w.pricesView()!!.hydrogenHistory)
    }

    @Test
    fun rivalsSkipBatteryVehiclesForLongRoutes() {
        val w = World(NewGame(64, 64, 1, 2025, towns = 0, industries = 0, weather = false))
        val bus = w.roster.engines.first { it.key == "base:electric_bus" }
        assertTrue(w.rivalFits(bus, 30))
        assertFalse(w.rivalFits(bus, 60))
        assertFalse(w.rivalFits(bus, -1))
        assertEquals(bus.id, w.bestModel(Cargo.PASSENGERS, 30)?.id)
        assertTrue(w.bestModel(Cargo.PASSENGERS, 60)?.let { w.storeOf(it) } != Traction.BATTERY)
        assertTrue(w.bestModel(Cargo.PASSENGERS)?.let { w.storeOf(it) } != Traction.BATTERY)
        // Hydrogen by its range too, and anything with energy off.
        val h = roster("base:low_floor", Traction.HYDROGEN, 10)
        assertTrue(w.rivalFits(h.engines.first { it.key == "base:low_floor" }, 3))
        assertFalse(w.rivalFits(h.engines.first { it.key == "base:low_floor" }, 4))
        val off = World(NewGame(64, 64, 1, 2025, towns = 0, industries = 0, weather = false, energy = false))
        assertTrue(off.rivalFits(bus, 500))
    }

    @Test
    fun ordersAreCheckedAgainstTheRange() {
        val w = line(range = 50)
        val t = w.trains[0]!!
        // A to C and back, 30 tiles each way, with nowhere to charge.
        var check = w.rangeCheck(t)!!
        assertFalse(check.charges)
        assertEquals(60, check.longest)
        assertTrue(check.short)
        // A charger at A: the longest run is there and back.
        w.apply(Command.Equip(0, 0, Equipment.CHARGER))
        check = w.rangeCheck(t)!!
        assertTrue(check.charges)
        assertEquals(60, check.longest)
        // And one at C too.
        w.apply(Command.Equip(0, 1, Equipment.CHARGER))
        check = w.rangeCheck(t)!!
        assertEquals(30, check.longest)
        assertFalse(check.short)
        assertEquals(30, w.energyView(t)!!.check.longest)
    }

    @Test
    fun aSavedGameWithEnergyKeepsInStep() {
        val g = NewGame(128, 128, 6, 2022, Region.MIXED, towns = 8, industries = 6, rivals = 2, rivalLevel = RivalLevel.HARD)
        val w = World(g)
        assertTrue(w.settings.energy)
        while (w.day < 120) w.step()
        val s = w.stations.firstOrNull { it != null && !it.waypoint }
        if (s != null) s.equipment = s.equipment or (1 shl Equipment.CHARGER)
        for (v in w.roadVehicles) if (v != null) v.charge = 777
        val copy = Save.read(Save.write(w))
        assertTrue(copy.settings.energy)
        assertEquals(w.checksum(), copy.checksum())
        while (w.day < 300) { w.step(); copy.step() }
        assertEquals(w.checksum(), copy.checksum())
    }

    @Test
    fun aVehicleSetNotToBeServicedIsNeverSentForCharge() {
        val w = busLine(400)
        val v = w.roadVehicles[0]!!
        var k = 0
        while (k++ < 20_000 && !(v.order == 1 && !v.inDepot && v.waiting == 0 && v.speed > 0)) w.step()
        v.service = false
        v.charge = 400 * Energy.UNIT / 10
        assertTrue(w.lowCharge(v))
        assertFalse(w.serviceDue(v, v.service, v.serviced))
        var went = false
        repeat(Calendar.TICKS_PER_DAY * 60) { w.step(); if (v.servicing || v.inDepot) went = true }
        assertFalse(went, "sent for a service with servicing off")
        assertTrue(w.flat(v), "never ran flat")
    }

    @Test
    fun aRivalTrainLowOnChargeGetsTheServiceTurn() {
        val w = line()
        val roster = w.roster
        w.apply(Command.BuyTrain(0, 0, roster.engines.first { it.key == "base:gp9" }.id, roster.engines.first { it.key == "base:coach_steel" }.id, 3))
        val due = standingAtA(w, 1_000 * Energy.UNIT)
        due.serviced = w.workDay - SERVICE_DAYS * 2
        val low = w.trains[1]!!
        low.inDepot = false
        low.running = true
        low.steps += step(w.index(42, 10), 0, 0)
        low.waiting = 10
        low.charge = Energy.UNIT * 10
        low.serviced = w.workDay
        assertTrue(w.lowCharge(low))
        w.serviceTurn(w.companies[0], listOf(due, low), -1)
        assertTrue(low.service)
        assertFalse(due.service)
        // With none low, the one longest since its service.
        low.charge = 1_000 * Energy.UNIT
        w.serviceTurn(w.companies[0], listOf(due, low), -1)
        assertTrue(due.service)
        assertFalse(low.service)
    }

    @Test
    fun aServiceIfDueOrderIsTakenWhenLowAndCountsOnlyWithServicingOn() {
        val w = line(range = 50)
        val t = w.trains[0]!!
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, Order.toDepot(0, Order.DEPOT_SERVICE), 1)))
        t.order = 1
        // Full and not due: skipped.
        assertTrue(w.skipService(t))
        t.order = 1
        t.charge = Energy.UNIT * 5
        assertFalse(w.skipService(t))
        assertTrue(w.rangeCheck(t)!!.charges)
        // Servicing off: low doesn't make it due, so it's skipped and isn't a charge point.
        t.service = false
        assertTrue(w.skipService(t))
        assertFalse(w.rangeCheck(t)!!.charges)
    }

    @Test
    fun aHydrogenRoundTripWarnsOnlyWithServicingOffOrALongLeg() {
        val w = busLine(50)
        val v = w.roadVehicles[0]!!
        // 30 tiles each way, no depot order: refills at its services.
        var check = w.rangeCheck(v)!!
        assertEquals(30, check.longest)
        assertFalse(check.short)
        v.service = false
        check = w.rangeCheck(v)!!
        assertEquals(60, check.longest)
        assertTrue(check.short)
        // A single leg past the range warns even with servicing on.
        v.service = true
        assertTrue(w.rangeCheck(v.orders.toIntArray(), Traction.HYDROGEN, 20, false, true).short)
    }
}
