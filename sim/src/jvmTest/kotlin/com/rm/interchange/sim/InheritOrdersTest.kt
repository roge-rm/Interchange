package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InheritOrdersTest {
    private val kinds = listOf(Command.CopyVehicle.TRAIN, Command.CopyVehicle.ROAD, Command.CopyVehicle.SHIP, Command.CopyVehicle.AIRCRAFT)
    private val stops = listOf(0, 1 or Order.FULL_LOAD)

    private class Fleet(val world: World, val kind: Int, val depot: Int, val model: Int, val wagon: Int = -1) {
        fun vehicles(): List<Carrier?> = when (kind) {
            Command.CopyVehicle.TRAIN -> world.trains
            Command.CopyVehicle.ROAD -> world.roadVehicles
            Command.CopyVehicle.SHIP -> world.ships
            else -> world.aircraft
        }

        fun vehicle(id: Int = 0): Carrier = vehicles()[id]!!

        fun buy(company: Int = 0, destination: Int = depot) {
            val cargo = world.roster[if (wagon >= 0) wagon else model].cargoes.first()
            world.apply(when (kind) {
                Command.CopyVehicle.TRAIN -> Command.BuyTrain(company, destination, model, wagon, 3, cargo)
                Command.CopyVehicle.ROAD -> Command.BuyRoadVehicle(company, destination, model, cargo)
                else -> Command.BuyCraft(company, destination, model, cargo)
            })
        }

        fun order(id: Int, what: Int, values: IntArray = intArrayOf(), company: Int = 0) {
            world.apply(when (kind) {
                Command.CopyVehicle.TRAIN -> Command.TrainOrder(company, id, what, values)
                Command.CopyVehicle.ROAD -> Command.RoadOrder(company, id, what, values)
                else -> Command.CraftOrder(company, kind == Command.CopyVehicle.AIRCRAFT, id, what, values)
            })
        }

        fun sell(id: Int = 0, company: Int = 0) = order(id, Command.TrainOrder.SELL, company = company)
        fun copy(id: Int = 0) = world.apply(Command.CopyVehicle(0, kind, id, depot))
        fun backup(company: Int = 0): OrderBackup? = world.companies[company].orderBackups[kind]
        fun inWorld(w: World) = Fleet(w, kind, depot, model, wagon)
    }

    private fun fleet(kind: Int): Fleet {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        var depot = 0
        val key = when (kind) {
            Command.CopyVehicle.TRAIN -> {
                w.apply(Command.Depot(0, 2, 10, Track.XP))
                w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
                w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
                w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
                "base:gp9"
            }
            Command.CopyVehicle.ROAD -> {
                w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
                w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
                w.apply(Command.Garage(0, 5, 23, Track.YM))
                for (x in listOf(8, 38)) w.apply(Command.RoadStop(0, x, 20))
                "base:semi_van"
            }
            Command.CopyVehicle.SHIP -> {
                for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 10..50 && y in 10..30) 0 else 1
                w.shoreChanged()
                w.apply(Command.Dock(0, 9, 20))
                w.apply(Command.Dock(0, 50, 22))
                w.apply(Command.Shipyard(0, 9, 15))
                "base:steam_laker"
            }
            else -> {
                w.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
                w.apply(Command.Airport(0, 40, 40, Airports.REGIONAL, Track.Y))
                depot = w.airports[0]!!.depot
                "base:dc3"
            }
        }
        val model = w.roster.engines.first { it.key == key }.id
        val wagon = if (kind == Command.CopyVehicle.TRAIN) w.roster.engines.first { it.key == "base:boxcar_steel" }.id else -1
        val f = Fleet(w, kind, depot, model, wagon)
        f.buy()
        assertEquals(1, f.vehicles().size)
        assertEquals(2, w.stations.size)
        f.order(0, Command.TrainOrder.ORDERS, stops.toIntArray())
        f.order(0, Command.TrainOrder.GAP, intArrayOf(4))
        return f
    }

    private fun outside(v: Carrier, value: Boolean = true) {
        when (v) {
            is Train -> v.inDepot = !value
            is RoadVehicle -> v.inDepot = !value
            is Ship -> v.inDepot = !value
            is Aircraft -> v.inDepot = !value
        }
    }

    private fun owner(v: Carrier, value: Int) {
        when (v) {
            is Train -> v.owner = value
            is RoadVehicle -> v.owner = value
            is Ship -> v.owner = value
            is Aircraft -> v.owner = value
        }
    }

    private fun assertFresh(v: Carrier) {
        assertEquals(0, v.order)
        assertEquals(0L, v.departed)
        assertEquals(0L, v.profitThisYear)
        val name = when (v) {
            is Train -> { assertNull(v.route); v.name }
            is RoadVehicle -> { assertNull(v.route); v.name }
            is Ship -> { assertNull(v.route); v.name }
            is Aircraft -> v.name
            else -> error("unknown vehicle")
        }
        assertEquals("", name)
        for (cargo in v.cargo) assertTrue(cargo.isEmpty())
    }

    private fun privateSale(kind: Int) {
        val f = fleet(kind)
        val sold = f.vehicle()
        val cargoes = f.world.roster[if (f.wagon >= 0) f.wagon else f.model].cargoes
        val choice = cargoes.last()
        sold.departed = 456
        sold.profitThisYear = 123
        sold.cargo[cargoes.first()] += Packet(1, 0, 5, 0)
        when (sold) {
            is Train -> { sold.name = "Freight"; sold.wagonCargo = choice; sold.order = 1; sold.route = intArrayOf(1) }
            is RoadVehicle -> { sold.name = "Freight"; sold.cargoChoice = choice; sold.order = 1; sold.route = intArrayOf(1) }
            is Ship -> { sold.name = "Freight"; sold.cargoChoice = choice; sold.order = 1; sold.route = intArrayOf(1) }
            is Aircraft -> { sold.name = "Freight"; sold.cargoChoice = choice; sold.order = 1 }
        }
        f.sell()
        assertNull(f.vehicles()[0])
        val backup = assertNotNull(f.backup())
        assertEquals(stops, backup.orders)
        assertEquals(4, backup.gap)
        assertEquals(-1, backup.shared)
        assertNotSame(sold.orders, backup.orders)
        sold.orders.clear()
        sold.gap = 8
        f.world.linksDirty = false
        f.buy()
        val made = f.vehicle(1)
        assertEquals(stops, made.orders)
        assertEquals(4, made.gap)
        assertEquals(-1, made.shared)
        assertFresh(made)
        assertNull(f.backup())
        assertTrue(f.world.linksDirty)
        val boughtChoice = when (made) {
            is Train -> made.wagonCargo
            is RoadVehicle -> made.cargoChoice
            is Ship -> made.cargoChoice
            is Aircraft -> made.cargoChoice
            else -> error("unknown vehicle")
        }
        assertEquals(cargoes.first(), boughtChoice)
        if (cargoes.size > 1) assertNotEquals(choice, boughtChoice)
        val number = when (made) {
            is Train -> made.number
            is RoadVehicle -> made.number
            is Ship -> made.number
            is Aircraft -> made.number
            else -> error("unknown vehicle")
        }
        assertEquals(1, number)
        f.buy()
        assertTrue(f.vehicle(2).orders.isEmpty())
        assertEquals(0, f.vehicle(2).gap)
        assertEquals(-1, f.vehicle(2).shared)
    }

    @Test fun trainInheritsPrivateOrders() = privateSale(Command.CopyVehicle.TRAIN)
    @Test fun roadVehicleInheritsPrivateOrders() = privateSale(Command.CopyVehicle.ROAD)
    @Test fun shipInheritsPrivateOrders() = privateSale(Command.CopyVehicle.SHIP)
    @Test fun aircraftInheritsPrivateOrders() = privateSale(Command.CopyVehicle.AIRCRAFT)

    @Test
    fun joinsTheSurvivorsCurrentOrdersAndGap() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.copy()
            assertEquals(0, f.vehicle(1).shared)
            f.sell()
            assertEquals(0, assertNotNull(f.backup()).shared)
            assertEquals(0, f.vehicle(1).shared)
            f.order(1, Command.TrainOrder.ORDERS, intArrayOf(1, 0 or Order.NO_LOAD))
            f.order(1, Command.TrainOrder.GAP, intArrayOf(7))
            f.vehicle(1).departed = 123
            f.buy()
            val made = f.vehicle(2)
            assertEquals(listOf(1, 0 or Order.NO_LOAD), made.orders)
            assertEquals(7, made.gap)
            assertEquals(0, made.shared)
            assertNotSame(f.vehicle(1).orders, made.orders)
            assertEquals(0, f.vehicle(1).shared)
            assertEquals(123L, f.vehicle(1).departed)
            assertFresh(made)
            assertNull(f.backup())
            f.order(2, Command.TrainOrder.ORDERS, stops.toIntArray())
            assertEquals(stops, f.vehicle(1).orders)
        }
    }

    @Test
    fun sellingTheLastGroupMemberRemembersPrivateOrders() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.copy()
            f.sell()
            f.sell(1)
            assertEquals(-1, assertNotNull(f.backup()).shared)
            f.buy()
            assertEquals(stops, f.vehicle(2).orders)
            assertEquals(4, f.vehicle(2).gap)
            assertEquals(-1, f.vehicle(2).shared)
        }
    }

    @Test
    fun aGroupThatDisappearsBeforeBuyingUsesTheSnapshot() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.copy()
            f.sell()
            f.order(1, Command.TrainOrder.UNSHARE)
            f.order(1, Command.TrainOrder.ORDERS, intArrayOf(1))
            f.order(1, Command.TrainOrder.GAP, intArrayOf(7))
            f.buy()
            assertEquals(stops, f.vehicle(2).orders)
            assertEquals(4, f.vehicle(2).gap)
            assertEquals(-1, f.vehicle(2).shared)
        }
    }

    @Test
    fun anEmptySecondSaleReplacesThePreviousBackup() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.buy()
            f.sell()
            f.sell(1)
            assertTrue(assertNotNull(f.backup()).orders.isEmpty())
            f.buy()
            assertTrue(f.vehicle(2).orders.isEmpty())
            assertEquals(0, f.vehicle(2).gap)
            assertNull(f.backup())
        }
    }

    @Test
    fun tooLittleMoneyKeepsTheBackup() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.sell()
            val backup = f.backup()
            f.world.companies[0].money = 0
            f.buy()
            assertEquals(1, f.vehicles().size)
            assertSame(backup, f.backup())
            f.world.companies[0].money = 100_000_000
            f.buy()
            assertEquals(stops, f.vehicle(1).orders)
            assertNull(f.backup())
        }
    }

    @Test
    fun aTrainBuyIntoARoadDepotKeepsTheBackup() {
        val f = fleet(Command.CopyVehicle.TRAIN)
        f.world.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        f.world.apply(Command.Garage(0, 5, 23, Track.YM))
        assertEquals(2, f.world.depots.size)
        f.sell()
        val backup = f.backup()
        f.buy(destination = 1)
        assertEquals(1, f.vehicles().size)
        assertSame(backup, f.backup())
        f.buy()
        assertEquals(stops, f.vehicle(1).orders)
    }

    @Test
    fun buyingAnotherKindLeavesTheBackupAndStartsBlank() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.sell()
            val backup = f.backup()
            val w = f.world
            val depot = w.depots.size
            val other = if (kind == Command.CopyVehicle.ROAD) {
                w.apply(Command.Depot(0, 52, 50, Track.XP))
                val model = w.roster.engines.first { it.key == "base:gp9" }.id
                val wagon = w.roster.engines.first { it.key == "base:boxcar_steel" }.id
                Fleet(w, Command.CopyVehicle.TRAIN, depot, model, wagon)
            } else {
                w.apply(Command.Road(0, 55, 50, 55, 52, Surface.PAVED, remove = false))
                w.apply(Command.Garage(0, 55, 53, Track.YM))
                Fleet(w, Command.CopyVehicle.ROAD, depot, w.roster.engines.first { it.key == "base:semi_van" }.id)
            }
            other.buy()
            assertTrue(other.vehicle().orders.isEmpty())
            assertSame(backup, f.backup())
            other.sell()
            assertSame(backup, f.backup())
            assertNotNull(other.backup())
            f.buy()
            assertEquals(stops, f.vehicle(1).orders)
            assertNotNull(other.backup())
        }
    }

    @Test
    fun anotherCompanysSaleAndBackupStaySeparate() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.world.companies += Company(1, "North", 100_000_000)
            owner(f.vehicle(), 1)
            f.sell(company = 1)
            assertNull(f.backup())
            val backup = assertNotNull(f.backup(1))
            f.buy()
            assertTrue(f.vehicle(1).orders.isEmpty())
            assertSame(backup, f.backup(1))
            f.world.depots[f.depot]!!.owner = 1
            f.buy(company = 1)
            assertEquals(stops, f.vehicle(2).orders)
            assertNull(f.backup(1))
        }
    }

    @Test
    fun copyingNeitherAppliesNorConsumesTheBackup() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.buy()
            f.order(1, Command.TrainOrder.ORDERS, intArrayOf(1))
            f.order(1, Command.TrainOrder.GAP, intArrayOf(7))
            f.sell()
            val backup = f.backup()
            f.copy(1)
            assertEquals(listOf(1), f.vehicle(2).orders)
            assertEquals(7, f.vehicle(2).gap)
            assertEquals(1, f.vehicle(1).shared)
            assertEquals(1, f.vehicle(2).shared)
            assertSame(backup, f.backup())
            f.buy()
            assertEquals(stops, f.vehicle(3).orders)
            assertEquals(-1, f.vehicle(3).shared)
            assertNull(f.backup())
        }
    }

    private fun wreck(v: Carrier) {
        v.shared = -1
        outside(v)
        when (v) {
            is Train -> v.wreckedFor = 1
            is RoadVehicle -> v.wreckedFor = 1
            is Aircraft -> v.wreckedFor = 1
        }
    }

    private fun clearWreck(f: Fleet) {
        when (f.kind) {
            Command.CopyVehicle.TRAIN -> f.world.moveTrains()
            Command.CopyVehicle.ROAD -> f.world.moveRoadVehicles()
            Command.CopyVehicle.AIRCRAFT -> f.world.moveAircraft()
        }
    }

    @Test
    fun clearingWrecksDoesNotStoreOrders() {
        for (kind in kinds.filter { it != Command.CopyVehicle.SHIP }) {
            val f = fleet(kind)
            wreck(f.vehicle())
            clearWreck(f)
            assertNull(f.vehicles()[0])
            assertNull(f.backup())
            f.buy()
            assertTrue(f.vehicle(1).orders.isEmpty())
            assertEquals(0, f.vehicle(1).gap)
        }
    }

    @Test
    fun aWreckDoesNotKeepASharedGroupAlive() {
        for (kind in kinds.filter { it != Command.CopyVehicle.SHIP }) {
            val f = fleet(kind)
            f.copy()
            wreck(f.vehicle(1))
            f.sell()
            assertEquals(-1, assertNotNull(f.backup()).shared)
            f.buy()
            assertEquals(stops, f.vehicle(2).orders)
            assertEquals(-1, f.vehicle(2).shared)
        }
    }

    @Test
    fun sellingOnTheLineOrSellingAWreckKeepsTheBackup() {
        for (kind in kinds) {
            val f = fleet(kind)
            outside(f.vehicle())
            f.sell()
            assertNotNull(f.vehicles()[0])
            assertNull(f.backup())
            outside(f.vehicle(), false)
            f.buy()
            f.sell()
            val backup = assertNotNull(f.backup())
            outside(f.vehicle(1))
            f.sell(1)
            assertNotNull(f.vehicles()[1])
            assertSame(backup, f.backup())
            if (kind != Command.CopyVehicle.SHIP) {
                wreck(f.vehicle(1))
                outside(f.vehicle(1), false)
                f.sell(1)
                assertNotNull(f.vehicles()[1])
                assertSame(backup, f.backup())
                outside(f.vehicle(1))
                clearWreck(f)
                assertNull(f.vehicles()[1])
                assertSame(backup, f.backup())
            }
        }
    }

    @Test
    fun saleAndPurchaseCanBeSeparatedBySaving() {
        assertEquals(46, Save.VERSION)
        for (kind in kinds) for (shared in listOf(false, true)) {
            val f = fleet(kind)
            if (shared) f.copy()
            f.sell()
            val loaded = f.inWorld(Save.read(Save.write(f.world)))
            val backup = assertNotNull(loaded.backup())
            assertEquals(stops, backup.orders)
            assertEquals(4, backup.gap)
            assertEquals(if (shared) 0 else -1, backup.shared)
            val next = loaded.vehicles().size
            if (shared) loaded.order(1, Command.TrainOrder.ORDERS, intArrayOf(1))
            loaded.buy()
            assertEquals(if (shared) listOf(1) else stops, loaded.vehicle(next).orders)
            assertEquals(if (shared) 0 else -1, loaded.vehicle(next).shared)
            assertNull(loaded.backup())
            val old = f.inWorld(Save.read(Save.write(f.world, version = 43)))
            for (company in old.world.companies) for (slot in company.orderBackups) assertNull(slot)
            old.buy()
            assertTrue(old.vehicle(next).orders.isEmpty())
        }
    }

    @Test
    fun saveKeepsAllFourSlotsForEachCompanyIncludingEmptyOrders() {
        val w = fleet(Command.CopyVehicle.TRAIN).world
        w.companies += Company(1, "North", 100_000_000)
        for (c in w.companies) for (kind in kinds) {
            c.orderBackups[kind] = OrderBackup(if (kind == Command.CopyVehicle.ROAD) arrayListOf() else arrayListOf(c.id, kind), kind + 1, c.id + kind)
        }
        val loaded = Save.read(Save.write(w))
        for (c in w.companies) for (kind in kinds) {
            val before = assertNotNull(c.orderBackups[kind])
            val after = assertNotNull(loaded.companies[c.id].orderBackups[kind])
            assertEquals(before.orders, after.orders)
            assertEquals(before.gap, after.gap)
            assertEquals(before.shared, after.shared)
        }
        val old = Save.read(Save.write(w, version = 43))
        for (c in old.companies) for (slot in c.orderBackups) assertNull(slot)
    }

    @Test
    fun backupsDoNotEnterTheChecksum() {
        val f = fleet(Command.CopyVehicle.TRAIN)
        f.sell()
        val other = Save.read(Save.write(f.world))
        other.companies[0].orderBackups.fill(null)
        assertEquals(f.world.checksum(), other.checksum())
    }

    @Test
    fun aRestartedCompanyDoesNotInheritSoldOrders() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.sell()
            assertNotNull(f.backup())
            f.world.restart(f.world.companies[0])
            assertNull(f.backup())
            f.world.companies[0].money = 100_000_000
            f.buy()
            assertTrue(f.vehicle(1).orders.isEmpty())
            assertEquals(0, f.vehicle(1).gap)
        }
    }

    @Test
    fun goingUnderDropsRememberedOrders() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.sell()
            assertNotNull(f.backup())
            f.world.dissolve(f.world.companies[0])
            assertNull(f.backup())
        }
    }

    @Test
    fun editorPurchasesInheritOrders() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.world.editor = true
            f.world.companies[0].money = 0
            f.sell()
            f.buy()
            assertEquals(stops, f.vehicle(1).orders)
            assertNull(f.backup())
            assertEquals(0L, f.world.companies[0].money)
        }
    }
}
