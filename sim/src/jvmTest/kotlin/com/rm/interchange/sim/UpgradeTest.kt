package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Upgrade rules: vehicles of one model rebuilt as another at their next service, with a reason kept when they can't be. */
class UpgradeTest {
    private val train = Command.CopyVehicle.TRAIN
    private val road = Command.CopyVehicle.ROAD

    private fun World.days(n: Int) = repeat(Calendar.TICKS_PER_DAY * n) { step() }

    /** A bus line, the bus set going if [start], else left in its garage. */
    private fun bus(start: Boolean = true): World {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        w.apply(Command.RoadStop(0, 8, 20))
        w.apply(Command.RoadStop(0, 38, 20))
        w.apply(Command.BuyRoadVehicle(0, 0, newLook(w).id))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        if (start) w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.START_STOP))
        w.roadVehicles[0]!!.seasonalTires = false
        return w
    }

    private fun newLook(w: World) = w.roster.engines.first { it.key == "base:new_look" }

    /** Buses on sale other than the New Look, streetcars left out. */
    private fun otherBuses(w: World) = w.roster.engines.filter { it.kind == VehicleKind.ROAD && !it.tram && w.onSale(it) && it.id != newLook(w).id }

    /** A line with a depot, two stations of [platform] tiles, and a train of [engine] and [wagons] of [wagon]. */
    private fun railLine(year: Int, engine: (World) -> Engine, wagon: (World) -> Engine, wagons: Int = 3, platform: Int = 4): World {
        val w = World(64, 64, 1, startYear = year)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 50, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 10 + platform - 1, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 36 + platform - 1, 10, Track.X))
        w.apply(Command.BuyTrain(0, 0, engine(w).id, wagon(w).id, wagons))
        assertNotNull(w.trains.getOrNull(0), "no train")
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        return w
    }

    private fun steamEngines(w: World) = w.roster.engines.filter { it.kind == VehicleKind.ENGINE && w.onSale(it) && it.traction != Traction.ELECTRIC }
    private fun wagons(w: World) = w.roster.engines.filter { it.kind == VehicleKind.WAGON && w.onSale(it) && it.plow == Plows.NONE }

    /** [v] in for a service at its depot: what the depot does, then the end of the tick. */
    private fun service(w: World, v: Carrier) {
        when (v) {
            is Train -> w.autorenew(v)
            is RoadVehicle -> w.autorenew(v)
            is Ship -> w.autorenew(v)
            is Aircraft -> w.autorenew(v)
        }
        w.upgradeDue()
    }

    @Test
    fun aFleetRuleSwapsABusAtItsNextServiceKeepingOrdersGroupAndService() {
        val w = bus()
        val to = otherBuses(w).first()
        w.apply(Command.CreateGroup(0, road, 0, "Town"))
        w.apply(Command.Upgrade(0, road, newLook(w).id, to.id))
        val old = w.roadVehicles[0]!!
        old.name = "Downtown"
        old.gap = 7
        w.days(20)
        assertEquals(to.id, w.upgradeTo(old))
        // Due a service, so it goes in by the overdue path.
        old.serviced = w.workDay - SERVICE_DAYS
        var made: RoadVehicle? = null
        for (d in 0 until 120) {
            w.days(1)
            made = w.roadVehicles[0]!!.takeIf { it.model == to.id }
            if (made != null) break
        }
        assertNotNull(made, "never upgraded")
        assertNotSame(old, made)
        assertEquals("Downtown", made.name)
        assertEquals(old.number, made.number)
        assertContentEquals(intArrayOf(0, 1), made.orders.toIntArray())
        assertEquals(old.group, made.group)
        assertTrue(made.group >= 0)
        assertEquals(7, made.gap)
        assertTrue(made.service)
        assertEquals(old.shared, made.shared)
        assertEquals(UpgradeWhy.NONE, made.upgradeWhy)
        assertEquals(-1, w.upgradeTo(made))
        w.days(10)
        assertTrue(!made.inDepot && made.running, "didn't go back to work")
    }

    @Test
    fun aGroupRuleWinsOverTheFleetRule() {
        val w = bus(start = false)
        val (a, b) = otherBuses(w).take(2)
        w.apply(Command.CopyVehicle(0, road, 0, 0))
        w.apply(Command.CreateGroup(0, road, 0, "Town"))
        val group = w.roadVehicles[0]!!.group
        w.apply(Command.Upgrade(0, road, newLook(w).id, a.id))
        w.apply(Command.Upgrade(0, road, newLook(w).id, b.id, group))
        assertEquals(2, w.companies[0].upgrades.size)
        assertEquals(b.id, w.upgradeTo(w.roadVehicles[0]!!))
        assertEquals(a.id, w.upgradeTo(w.roadVehicles[1]!!))
        service(w, w.roadVehicles[0]!!)
        service(w, w.roadVehicles[1]!!)
        assertEquals(b.id, w.roadVehicles[0]!!.model)
        assertEquals(a.id, w.roadVehicles[1]!!.model)
        val view = w.view(0)
        assertEquals(2, view.upgrades.size)
        assertEquals("Town", view.upgrades.first { it.group == group }.groupName)
        assertTrue(view.company!!.upgrade)
    }

    @Test
    fun anEngineRuleSwapsEveryCarOfThatEngineAndACarRuleEveryWagonOfThatModel() {
        val w = railLine(1900, { it.roster.engines.first { e -> e.key == "base:american" } }, { wagons(it).first() })
        val american = w.roster.engines.first { it.key == "base:american" }
        val other = steamEngines(w).first { it.id != american.id }
        val (wagon, second) = wagons(w).take(2)
        val third = wagons(w).first { it.id != wagon.id && it.id != second.id }
        w.apply(Command.EditTrain(0, 0, listOf(TrainCar(american.id), TrainCar(american.id), TrainCar(wagon.id), TrainCar(third.id), TrainCar(wagon.id))))
        assertEquals(5, w.trains[0]!!.formation.size)
        w.apply(Command.Upgrade(0, train, american.id, other.id))
        w.apply(Command.Upgrade(0, train, wagon.id, second.id))
        val old = w.trains[0]!!
        assertEquals(other.id, w.upgradeTo(old))
        assertEquals(UpgradeWhy.OK, old.upgradeWhy)
        val money = w.companies[0].money
        val cost = w.trainSwapCost(old, listOf(TrainCar(other.id), TrainCar(other.id), TrainCar(second.id), TrainCar(third.id), TrainCar(second.id)))
        service(w, old)
        val made = w.trains[0]!!
        assertNotSame(old, made)
        assertEquals(listOf(other.id, other.id, second.id, third.id, second.id), made.formation.map { it.model })
        assertEquals(money - cost, w.companies[0].money)
        assertEquals(w.workDay, made.built)
    }

    @Test
    fun theReserveStopsItWithNoMoneyAndNewsOnce() {
        val w = bus(start = false)
        val to = otherBuses(w).first()
        val c = w.companies[0]
        w.apply(Command.SetAutorenew(0, true, -6, c.money + 1_000_000))
        w.apply(Command.Upgrade(0, road, newLook(w).id, to.id))
        val v = w.roadVehicles[0]!!
        assertEquals(UpgradeWhy.NO_MONEY, v.upgradeWhy)
        assertEquals(UpgradeWhy.NO_MONEY, w.upgradeCheck(v, c.upgrades.single()))
        service(w, v)
        service(w, v)
        assertEquals(newLook(w).id, w.roadVehicles[0]!!.model)
        assertEquals(1, w.chronicle.count { it.kind == News.UPGRADE_FAILED })
        assertEquals(Autorenew.ROAD shl Autorenew.KIND_SHIFT or v.id, w.chronicle.last { it.kind == News.UPGRADE_FAILED }.about)
        assertEquals(UpgradeWhy.NO_MONEY, w.view(0).roadVehicles[0].upgradeWhy)
        // With the reserve down it goes.
        w.apply(Command.SetAutorenew(0, true, -6, 0))
        service(w, v)
        assertEquals(to.id, w.roadVehicles[0]!!.model)
    }

    @Test
    fun anElectricEngineOverUnwiredStationsHasNoWires() {
        val w = railLine(1965, { steamEngines(it).first() }, { wagons(it).first() })
        val electric = w.roster.engines.first { it.kind == VehicleKind.ENGINE && it.traction == Traction.ELECTRIC && w.onSale(it) }
        val t = w.trains[0]!!
        w.apply(Command.Upgrade(0, train, t.engine, electric.id))
        assertEquals(UpgradeWhy.NO_WIRES, t.upgradeWhy)
        // Wires over the depot only.
        val depot = w.depots[0]!!
        w.wires[depot.tile] = Track.straight(depot.edge).toByte()
        assertTrue(w.depotWired(depot))
        assertEquals(UpgradeWhy.NO_WIRES, w.upgradeState(t))
        service(w, t)
        assertEquals(UpgradeWhy.NO_WIRES, w.trains[0]!!.upgradeWhy)
        assertTrue(w.trains[0]!!.formation.none { it.model == electric.id })
        // And over a platform of each station.
        for (s in w.stations) w.wires[s!!.tiles.first()] = Track.X.toByte()
        assertEquals(UpgradeWhy.OK, w.upgradeState(t))
    }

    @Test
    fun aTrainLongerThanItsPlatformsIsTooLong() {
        val w = railLine(1900, { it.roster.engines.first { e -> e.key == "base:american" } }, { wagons(it).minBy { e -> e.length } })
        val t = w.trains[0]!!
        val longer = wagons(w).maxBy { it.length }
        assertTrue(longer.length > t.wagonInfo.length)
        // Enough wagons that the longer ones overrun the four tile platforms, which the train fits now.
        var n = t.wagons
        val tile = 16 * FIX
        fun len(wagon: Engine, k: Int) = (t.carEngines.filter { w.roster[it].kind != VehicleKind.WAGON }.sumOf { w.roster[it].length } + wagon.length * k) * FIX
        while (len(longer, n) <= 4 * tile && len(t.wagonInfo, n + 1) <= 4 * tile) n++
        assertTrue(len(t.wagonInfo, n) <= 4 * tile && len(longer, n) > 4 * tile, "no wagon count to test with")
        w.apply(Command.EditTrain(0, 0, t.formation.filter { w.roster[it.model].pulls } + List(n) { TrainCar(t.wagon) }))
        val fits = w.trains[0]!!
        w.apply(Command.Upgrade(0, train, fits.wagon, longer.id))
        assertEquals(UpgradeWhy.TOO_LONG, fits.upgradeWhy)
        service(w, fits)
        assertEquals(t.wagonInfo.id, w.trains[0]!!.wagon)
    }

    @Test
    fun anAircraftRuleToAModelASmallAirportCantTakeIsRefused() {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 1
        w.companies[0].money = 100_000_000
        w.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
        w.apply(Command.Airport(0, 40, 40, Airports.REGIONAL, Track.Y))
        val dc3 = w.roster.engines.first { it.key == "base:dc3" }
        w.apply(Command.BuyCraft(0, w.airports[0]!!.depot, dc3.id))
        w.apply(Command.CraftOrder(0, true, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        val jet = w.roster.engines.first { it.kind == VehicleKind.AIRCRAFT && w.onSale(it) && !w.airports[0]!!.serves(it) && it.runway != Runways.HELI }
        w.apply(Command.Upgrade(0, Command.CopyVehicle.AIRCRAFT, dc3.id, jet.id))
        val v = w.aircraft[0]!!
        assertEquals(UpgradeWhy.AIRPORT, v.upgradeWhy)
        service(w, v)
        assertEquals(dc3.id, w.aircraft[0]!!.model)
        // Replacing by hand gives the same reason in the view.
        assertEquals(UpgradeWhy.AIRPORT, w.view(0).aircraft[0].replaceWhy[jet.id])
    }

    @Test
    fun autorenewStillRenewsVehiclesWithNoRule() {
        val w = bus(start = false)
        val to = otherBuses(w).first()
        val other = otherBuses(w).last()
        w.apply(Command.Upgrade(0, road, to.id, other.id))
        val v = w.roadVehicles[0]!!
        assertEquals(UpgradeWhy.NONE, v.upgradeWhy)
        v.built = w.workDay - v.info.life * 365 - 12 * WORK_MONTH
        w.days(1)
        service(w, v)
        assertTrue(w.roadVehicles[0] === v)
        assertEquals(newLook(w).id, v.model)
        assertEquals(w.workDay, v.built)
    }

    @Test
    fun upgradesOffOrAutorenewOffForTheVehicleLeaveIt() {
        val w = bus(start = false)
        val to = otherBuses(w).first()
        w.apply(Command.Upgrade(0, road, newLook(w).id, to.id))
        w.apply(Command.SetUpgrade(0, false))
        val v = w.roadVehicles[0]!!
        assertEquals(-1, w.upgradeTo(v))
        service(w, v)
        assertEquals(newLook(w).id, w.roadVehicles[0]!!.model)
        w.apply(Command.SetUpgrade(0, true))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.AUTORENEW))
        assertTrue(v.renewOff)
        service(w, v)
        assertEquals(newLook(w).id, w.roadVehicles[0]!!.model)
    }

    @Test
    fun badRulesAreIgnored() {
        val w = bus()
        val c = w.companies[0]
        val to = otherBuses(w).first()
        val tram = w.roster.engines.firstOrNull { it.kind == VehicleKind.ROAD && it.tram && w.onSale(it) }
        val wagon = wagons(w).first()
        val offSale = w.roster.engines.first { it.kind == VehicleKind.ROAD && !w.onSale(it) }
        for (bad in listOf(
            Command.Upgrade(0, road, newLook(w).id, newLook(w).id),
            Command.Upgrade(0, road, newLook(w).id, wagon.id),
            Command.Upgrade(0, train, newLook(w).id, to.id),
            Command.Upgrade(0, road, newLook(w).id, offSale.id),
            Command.Upgrade(0, road, newLook(w).id, to.id, group = 5),
            Command.Upgrade(0, 9, newLook(w).id, to.id),
            Command.Upgrade(0, road, -1, to.id),
            Command.Upgrade(0, road, newLook(w).id, Int.MAX_VALUE),
        )) w.apply(bad)
        assertTrue(c.upgrades.isEmpty())
        // A streetcar rule is kept, but the bus can't take it.
        if (tram != null) {
            w.apply(Command.Upgrade(0, road, newLook(w).id, tram.id))
            assertEquals(UpgradeWhy.TRAM, w.roadVehicles[0]!!.upgradeWhy)
            w.apply(Command.Upgrade(0, road, newLook(w).id, tram.id, remove = true))
        }
        // Changing one there keeps one; removing it empties the list.
        w.apply(Command.Upgrade(0, road, newLook(w).id, to.id))
        w.apply(Command.Upgrade(0, road, newLook(w).id, otherBuses(w).last().id))
        assertEquals(listOf(UpgradeRule(road, newLook(w).id, otherBuses(w).last().id)), c.upgrades.toList())
        w.apply(Command.Upgrade(0, road, newLook(w).id, 0, remove = true))
        assertTrue(c.upgrades.isEmpty())
        // At most 64.
        val buses = w.roster.engines.filter { it.kind == VehicleKind.ROAD }
        for (from in buses) for (target in otherBuses(w)) if (from.id != target.id) w.apply(Command.Upgrade(0, road, from.id, target.id))
        assertTrue(c.upgrades.size <= UPGRADE_RULES_MOST)
        assertEquals(c.upgrades.sortedWith(compareBy({ it.kind }, { it.group }, { it.from })), c.upgrades.toList())
        // Another company's command changes only its own list.
        w.companies += Company(1, "North", 1_000_000)
        w.apply(Command.Upgrade(1, road, newLook(w).id, to.id))
        assertEquals(1, w.companies[1].upgrades.size)
    }

    @Test
    fun aGroupGoingTakesItsRules() {
        val w = bus()
        val to = otherBuses(w).first()
        w.apply(Command.CreateGroup(0, road, 0, "Town"))
        val group = w.roadVehicles[0]!!.group
        w.apply(Command.Upgrade(0, road, newLook(w).id, to.id, group))
        assertEquals(1, w.companies[0].upgrades.size)
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.GROUP, intArrayOf(-1)))
        assertEquals(-1, w.roadVehicles[0]!!.group)
        assertTrue(w.companies[0].upgrades.isEmpty())
    }

    @Test
    fun commandsGoThroughTheCodec() {
        for (c in listOf(Command.Upgrade(2, 1, 40, 41, 3, remove = true), Command.Upgrade(0, 0, 4, 5), Command.SetUpgrade(1, false))) {
            assertEquals(c, Codec.decode(Codec.encode(c)))
        }
    }

    @Test
    fun saveKeepsRulesAndStaysInStep() {
        assertEquals(63, Save.VERSION)
        val w = bus(start = false)
        val to = otherBuses(w).first()
        w.apply(Command.SetAutorenew(0, true, -6, w.companies[0].money + 1_000_000))
        w.apply(Command.Upgrade(0, road, newLook(w).id, to.id))
        w.apply(Command.SetUpgrade(0, false))
        w.apply(Command.SetUpgrade(0, true))
        service(w, w.roadVehicles[0]!!)
        w.days(3)
        val back = Save.read(Save.write(w))
        assertEquals(w.companies[0].upgrades.toList(), back.companies[0].upgrades.toList())
        assertTrue(back.companies[0].upgrade)
        assertEquals(UpgradeWhy.NO_MONEY, back.roadVehicles[0]!!.upgradeWhy)
        assertTrue(back.roadVehicles[0]!!.upgradeTold)
        assertEquals(w.checksum(), back.checksum())
        w.days(5)
        back.days(5)
        assertEquals(w.checksum(), back.checksum())
        // A save from before has no rules and the switch on.
        val old = Save.read(Save.write(w, version = 62))
        assertTrue(old.companies[0].upgrades.isEmpty())
        assertTrue(old.companies[0].upgrade)
        assertEquals(UpgradeWhy.NONE, old.roadVehicles[0]!!.upgradeWhy)
        assertFalse(old.roadVehicles[0]!!.upgradeTold)
        // Off is kept too.
        w.apply(Command.SetUpgrade(0, false))
        assertFalse(Save.read(Save.write(w)).companies[0].upgrade)
    }
}
