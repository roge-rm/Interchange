package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedOrdersTest {
    private fun road(): World {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        for (x in listOf(8, 23, 38)) w.apply(Command.RoadStop(0, x, 20))
        assertEquals(3, w.stations.size)
        return w
    }

    private fun World.buyRoad(company: Int = 0): RoadVehicle {
        val model = roster.engines.first { it.key == "base:new_look" }.id
        val id = roadVehicles.size
        apply(Command.BuyRoadVehicle(company, 0, model))
        assertEquals(id + 1, roadVehicles.size)
        return roadVehicles[id]!!
    }

    private fun lake(): World {
        val w = World(64, 64, 1, startYear = 1965)
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 10..50 && y in 10..30) 0 else 1
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        w.apply(Command.Dock(0, 9, 20))
        w.apply(Command.Dock(0, 50, 22))
        w.apply(Command.Shipyard(0, 9, 15))
        assertEquals(2, w.stations.size)
        val model = w.roster.engines.first { it.key == "base:steam_laker" }.id
        repeat(2) { w.apply(Command.BuyCraft(0, 0, model)) }
        assertEquals(2, w.ships.size)
        return w
    }

    private fun airports(): World {
        val w = World(64, 64, 1, startYear = 1965)
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 20..30 && y in 50..60) 0 else 1
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        w.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
        w.apply(Command.Airport(0, 40, 40, Airports.REGIONAL, Track.Y))
        assertEquals(2, w.stations.size)
        val model = w.roster.engines.first { it.key == "base:dc3" }.id
        repeat(2) { w.apply(Command.BuyCraft(0, w.airports[0]!!.depot, model)) }
        assertEquals(2, w.aircraft.size)
        return w
    }

    @Test
    fun roadOrdersChangeTogetherUntilUnshared() {
        val w = road()
        val a = w.buyRoad()
        val b = w.buyRoad()
        w.apply(Command.RoadOrder(0, a.id, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        b.order = 1
        b.route = intArrayOf(12)
        w.apply(Command.RoadOrder(0, b.id, Command.TrainOrder.SHARE, intArrayOf(a.id)))
        assertEquals(a.id, a.shared)
        assertEquals(a.shared, b.shared)
        assertEquals(listOf(0, 1), b.orders)
        assertEquals(0, b.order)
        assertNull(b.route)
        a.order = 1
        b.order = 1
        a.route = intArrayOf(12)
        b.route = intArrayOf(13)
        val replacement = intArrayOf(2 or Order.FULL_LOAD_ANY, 0, 999)
        w.apply(Command.RoadOrder(0, b.id, Command.TrainOrder.ORDERS, replacement))
        assertEquals(replacement.take(2), a.orders)
        assertEquals(a.orders, b.orders)
        assertEquals(0, a.order)
        assertEquals(0, b.order)
        assertNull(a.route)
        assertNull(b.route)
        w.apply(Command.RoadOrder(0, b.id, Command.TrainOrder.UNSHARE))
        assertEquals(-1, b.shared)
        assertEquals(a.orders, b.orders)
        w.apply(Command.RoadOrder(0, b.id, Command.TrainOrder.ORDERS, intArrayOf(1)))
        assertEquals(listOf(1), b.orders)
        assertEquals(replacement.take(2), a.orders)
    }

    @Test
    fun aRoadVehicleCannotShareWithATrainAtTheSameId() {
        val w = road()
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        val engine = w.roster.engines.first { it.key == "base:gp9" }.id
        val wagon = w.roster.engines.first { it.key == "base:coach_steel" }.id
        w.apply(Command.BuyTrain(0, 1, engine, wagon, 2))
        assertEquals(1, w.trains.size)
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        assertEquals(listOf(0, 1), w.trains[0]!!.orders)
        val bus = w.buyRoad()
        assertEquals(0, bus.id)
        w.apply(Command.RoadOrder(0, bus.id, Command.TrainOrder.SHARE, intArrayOf(0)))
        assertEquals(-1, bus.shared)
        assertTrue(bus.orders.isEmpty())
    }

    @Test
    fun anotherCompanysRoadVehicleIsRefused() {
        val w = road()
        val a = w.buyRoad()
        w.companies += Company(1, "North", 10_000_000)
        w.depots[0]!!.owner = 1
        val b = w.buyRoad(1)
        w.apply(Command.RoadOrder(0, a.id, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.RoadOrder(0, a.id, Command.TrainOrder.SHARE, intArrayOf(b.id)))
        assertEquals(-1, a.shared)
        assertEquals(-1, b.shared)
        assertEquals(listOf(0, 1), a.orders)
        assertTrue(b.orders.isEmpty())
    }

    @Test
    fun shipsShareOrdersAndResetRoutes() {
        val w = lake()
        val a = w.ships[0]!!
        val b = w.ships[1]!!
        w.apply(Command.CraftOrder(0, false, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        b.order = 1
        b.route = intArrayOf(12)
        w.apply(Command.CraftOrder(0, false, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        assertEquals(0, a.shared)
        assertEquals(a.shared, b.shared)
        assertEquals(listOf(0, 1), b.orders)
        assertEquals(0, b.order)
        assertNull(b.route)
        a.order = 1
        b.order = 1
        a.route = intArrayOf(12)
        b.route = intArrayOf(13)
        w.apply(Command.CraftOrder(0, false, 1, Command.TrainOrder.ORDERS, intArrayOf(1, 0 or Order.FULL_LOAD)))
        assertEquals(listOf(1, 0 or Order.FULL_LOAD), a.orders)
        assertEquals(a.orders, b.orders)
        assertEquals(0, a.order)
        assertEquals(0, b.order)
        assertNull(a.route)
        assertNull(b.route)
        w.apply(Command.CraftOrder(0, false, 1, Command.TrainOrder.UNSHARE))
        assertEquals(-1, b.shared)
        w.apply(Command.CraftOrder(0, false, 0, Command.TrainOrder.ORDERS, intArrayOf(0)))
        assertEquals(listOf(1, 0 or Order.FULL_LOAD), b.orders)
    }

    @Test
    fun aircraftShareOrders() {
        val w = airports()
        val a = w.aircraft[0]!!
        val b = w.aircraft[1]!!
        w.apply(Command.CraftOrder(0, true, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        b.order = 1
        w.apply(Command.CraftOrder(0, true, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        assertEquals(0, a.shared)
        assertEquals(a.shared, b.shared)
        assertEquals(listOf(0, 1), b.orders)
        assertEquals(0, b.order)
        a.order = 1
        b.order = 1
        w.apply(Command.CraftOrder(0, true, 1, Command.TrainOrder.ORDERS, intArrayOf(1, 0 or Order.FULL_LOAD)))
        assertEquals(listOf(1, 0 or Order.FULL_LOAD), a.orders)
        assertEquals(a.orders, b.orders)
        assertEquals(0, a.order)
        assertEquals(0, b.order)
        w.apply(Command.CraftOrder(0, true, 1, Command.TrainOrder.UNSHARE))
        assertEquals(-1, b.shared)
        w.apply(Command.CraftOrder(0, true, 0, Command.TrainOrder.ORDERS, intArrayOf(0)))
        assertEquals(listOf(1, 0 or Order.FULL_LOAD), b.orders)
    }

    @Test
    fun currentSavesAndViewsKeepEachKindsSharedId() {
        val road = road()
        repeat(2) { road.buyRoad() }
        road.apply(Command.RoadOrder(0, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        val roadBack = Save.read(Save.write(road))
        assertEquals(listOf(0, 0), roadBack.roadVehicles.map { it!!.shared })
        assertEquals(listOf(0, 0), roadBack.view().roadVehicles.map { it.shared })
        roadBack.apply(Command.RoadOrder(0, 1, Command.TrainOrder.ORDERS, intArrayOf(1)))
        assertEquals(listOf(1), roadBack.roadVehicles[0]!!.orders)

        val water = lake()
        water.apply(Command.CraftOrder(0, false, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        val waterBack = Save.read(Save.write(water))
        assertEquals(listOf(0, 0), waterBack.ships.map { it!!.shared })
        assertEquals(listOf(0, 0), waterBack.view().ships.map { it.shared })

        val air = airports()
        air.apply(Command.CraftOrder(0, true, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        val airBack = Save.read(Save.write(air))
        assertEquals(listOf(0, 0), airBack.aircraft.map { it!!.shared })
        assertEquals(listOf(0, 0), airBack.view().aircraft.map { it.shared })
    }

    @Test
    fun version41LeavesSharedIdsUnsetAndChecksumMatches() {
        val road = road()
        repeat(2) { road.buyRoad() }
        road.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        road.apply(Command.RoadOrder(0, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        val roadBack = Save.read(Save.write(road, version = 41))
        assertEquals(road.checksum(), roadBack.checksum())
        assertEquals(listOf(-1, -1), roadBack.roadVehicles.map { it!!.shared })
        assertEquals(listOf(1, 2), roadBack.roadVehicles.map { it!!.number })
        assertEquals(listOf(0, 1), roadBack.roadVehicles[1]!!.orders)

        val water = lake()
        water.apply(Command.CraftOrder(0, false, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        val waterBack = Save.read(Save.write(water, version = 41))
        assertEquals(water.checksum(), waterBack.checksum())
        assertEquals(listOf(-1, -1), waterBack.ships.map { it!!.shared })
        assertEquals(listOf(1, 2), waterBack.ships.map { it!!.number })

        val air = airports()
        air.apply(Command.CraftOrder(0, true, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        val airBack = Save.read(Save.write(air, version = 41))
        assertEquals(air.checksum(), airBack.checksum())
        assertEquals(listOf(-1, -1), airBack.aircraft.map { it!!.shared })
        assertEquals(listOf(1, 2), airBack.aircraft.map { it!!.number })
    }

    @Test
    fun sellingTheLeaderKeepsTheRemainingGroupTogether() {
        val w = road()
        repeat(3) { w.buyRoad() }
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.RoadOrder(0, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        w.apply(Command.RoadOrder(0, 2, Command.TrainOrder.SHARE, intArrayOf(1)))
        assertEquals(listOf(0, 0, 0), w.roadVehicles.map { it!!.shared })
        assertEquals(listOf(0, 1), w.roadVehicles[2]!!.orders)
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.SELL))
        assertNull(w.roadVehicles[0])
        assertEquals(0, w.roadVehicles[1]!!.shared)
        assertEquals(0, w.roadVehicles[2]!!.shared)
        w.apply(Command.RoadOrder(0, 2, Command.TrainOrder.ORDERS, intArrayOf(2)))
        assertEquals(listOf(2), w.roadVehicles[1]!!.orders)
        assertEquals(listOf(2), w.roadVehicles[2]!!.orders)
    }

    @Test
    fun aRoadWreckLeavesItsSharedOrders() {
        val w = road()
        val a = w.buyRoad()
        val b = w.buyRoad()
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.RoadOrder(0, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        w.wreckRoad(b)
        assertEquals(-1, b.shared)
        assertTrue(b.orders.isEmpty())
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(2)))
        assertTrue(b.orders.isEmpty())
        assertEquals(0, a.shared)
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.SHARE, intArrayOf(1)))
        assertEquals(0, a.shared)
        assertEquals(listOf(2), a.orders)
        w.apply(Command.RoadOrder(0, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        assertEquals(-1, b.shared)
        assertTrue(b.orders.isEmpty())
    }

    @Test
    fun missingTargetsLeaveOrdersAlone() {
        val w = road()
        val a = w.buyRoad()
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0)))
        for (target in listOf(intArrayOf(), intArrayOf(-1), intArrayOf(999))) {
            w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.SHARE, target))
            assertEquals(-1, a.shared)
            assertEquals(listOf(0), a.orders)
        }
    }

    @Test
    fun craftRefuseSelfForeignMissingAndWreckedTargets() {
        for (air in listOf(false, true)) {
            val w = if (air) airports() else lake()
            val a: Carrier = if (air) w.aircraft[0]!! else w.ships[0]!!
            val b: Carrier = if (air) w.aircraft[1]!! else w.ships[1]!!
            w.apply(Command.CraftOrder(0, air, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
            if (air) w.aircraft[1]!!.owner = 1 else w.ships[1]!!.owner = 1
            for (target in listOf(intArrayOf(), intArrayOf(0), intArrayOf(1), intArrayOf(999))) {
                w.apply(Command.CraftOrder(0, air, 0, Command.TrainOrder.SHARE, target))
                assertEquals(listOf(0, 1), a.orders)
                assertEquals(-1, if (air) w.aircraft[0]!!.shared else w.ships[0]!!.shared)
                assertEquals(-1, if (air) w.aircraft[1]!!.shared else w.ships[1]!!.shared)
            }
            if (air) {
                w.aircraft[1]!!.owner = 0
                w.aircraft[1]!!.wreckedFor = 1
                w.apply(Command.CraftOrder(0, true, 0, Command.TrainOrder.SHARE, intArrayOf(1)))
                w.apply(Command.CraftOrder(0, true, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
                assertEquals(-1, w.aircraft[0]!!.shared)
                assertEquals(-1, w.aircraft[1]!!.shared)
                assertEquals(listOf(0, 1), a.orders)
                assertTrue(b.orders.isEmpty())
            }
        }
    }
}
