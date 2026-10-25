package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VehicleGroupTest {
    private val kinds = listOf(Command.CopyVehicle.TRAIN, Command.CopyVehicle.ROAD, Command.CopyVehicle.SHIP, Command.CopyVehicle.AIRCRAFT)

    private class Fleet(val world: World, val kind: Int, val depot: Int) {
        fun vehicle(id: Int = 0): Carrier = when (kind) {
            Command.CopyVehicle.TRAIN -> world.trains[id]!!
            Command.CopyVehicle.ROAD -> world.roadVehicles[id]!!
            Command.CopyVehicle.SHIP -> world.ships[id]!!
            else -> world.aircraft[id]!!
        }

        fun order(id: Int, what: Int, values: IntArray = intArrayOf(), company: Int = 0) {
            world.apply(when (kind) {
                Command.CopyVehicle.TRAIN -> Command.TrainOrder(company, id, what, values)
                Command.CopyVehicle.ROAD -> Command.RoadOrder(company, id, what, values)
                else -> Command.CraftOrder(company, kind == Command.CopyVehicle.AIRCRAFT, id, what, values)
            })
        }

        val groups get() = world.companies[0].groups[kind]
        fun create(name: String = "Freight", id: Int = 0, company: Int = 0) = world.apply(Command.CreateGroup(company, kind, id, name))
        fun join(id: Int, group: Int) = order(id, Command.TrainOrder.GROUP, intArrayOf(group))
        fun buy() {
            val v = world.groupVehicles(kind).filterNotNull().first()
            world.apply(when (v) {
                is Train -> Command.BuyTrain(0, depot, v.engine, v.wagon, v.wagons, v.wagonCargo)
                is RoadVehicle -> Command.BuyRoadVehicle(0, depot, v.model, v.cargoChoice)
                is Ship -> Command.BuyCraft(0, depot, v.model, v.cargoChoice)
                is Aircraft -> Command.BuyCraft(0, depot, v.model, v.cargoChoice)
                else -> error("unknown vehicle")
            })
        }
        fun copy(id: Int = 0) = world.apply(Command.CopyVehicle(0, kind, id, depot))
    }

    private fun fleet(kind: Int): Fleet {
        val w = World(NewGame(64, 64, 1, 1965, towns = 0, industries = 0, weather = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        var depot = 0
        when (kind) {
            Command.CopyVehicle.TRAIN -> {
                w.apply(Command.Depot(0, 2, 10, Track.XP))
                w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
                w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
                w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
                val engine = w.roster.engines.first { it.key == "base:gp9" }.id
                val wagon = w.roster.engines.first { it.key == "base:boxcar_steel" }.id
                w.apply(Command.BuyTrain(0, depot, engine, wagon, 3, Cargo.PAPER))
                assertEquals(1, w.trains.size)
            }
            Command.CopyVehicle.ROAD -> {
                w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
                w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
                w.apply(Command.Garage(0, 5, 23, Track.YM))
                for (x in listOf(8, 38)) w.apply(Command.RoadStop(0, x, 20))
                val model = w.roster.engines.first { it.key == "base:semi_van" }.id
                w.apply(Command.BuyRoadVehicle(0, depot, model, Cargo.FOOD))
                assertEquals(1, w.roadVehicles.size)
            }
            Command.CopyVehicle.SHIP -> {
                for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 10..50 && y in 10..30) 0 else 1
                w.shoreChanged()
                w.apply(Command.Dock(0, 9, 20))
                w.apply(Command.Dock(0, 50, 22))
                w.apply(Command.Shipyard(0, 9, 15))
                val model = w.roster.engines.first { it.key == "base:steam_laker" }.id
                w.apply(Command.BuyCraft(0, depot, model, Cargo.COAL))
                assertEquals(1, w.ships.size)
            }
            Command.CopyVehicle.AIRCRAFT -> {
                for (i in w.corners.indices) w.corners[i] = 1
                w.shoreChanged()
                w.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
                w.apply(Command.Airport(0, 40, 40, Airports.REGIONAL, Track.Y))
                depot = w.airports[0]!!.depot
                val model = w.roster.engines.first { it.key == "base:dc3" }.id
                w.apply(Command.BuyCraft(0, depot, model, Cargo.MAIL))
                assertEquals(1, w.aircraft.size)
            }
        }
        assertEquals(2, w.stations.size)
        val f = Fleet(w, kind, depot)
        f.order(0, Command.TrainOrder.ORDERS, intArrayOf(0, 1 or Order.FULL_LOAD))
        f.order(0, Command.TrainOrder.GAP, intArrayOf(4))
        return f
    }


    private fun createAndSave(kind: Int) {
        val f = fleet(kind)
        f.create("  Northern freight  ")
        assertEquals(listOf(VehicleGroup(0, "Northern freight")), f.groups)
        assertEquals(0, f.vehicle().group)
        val loaded = Save.read(Save.write(f.world, 45))
        assertEquals(f.groups, loaded.companies[0].groups[kind])
        assertEquals(0, loaded.groupVehicles(kind)[0]!!.group)
        val view = loaded.view()
        assertEquals(f.groups, view.groups[0][kind])
        val group = when (kind) {
            Command.CopyVehicle.TRAIN -> view.trains.single().group
            Command.CopyVehicle.ROAD -> view.roadVehicles.single().group
            Command.CopyVehicle.SHIP -> view.ships.single().group
            else -> view.aircraft.single().group
        }
        assertEquals(0, group)
        f.join(0, -1)
        assertEquals(1, view.groups[0][kind].size)
    }

    @Test fun trainGroupsSaveAndLoad() = createAndSave(Command.CopyVehicle.TRAIN)
    @Test fun roadGroupsSaveAndLoad() = createAndSave(Command.CopyVehicle.ROAD)
    @Test fun shipGroupsSaveAndLoad() = createAndSave(Command.CopyVehicle.SHIP)
    @Test fun aircraftGroupsSaveAndLoad() = createAndSave(Command.CopyVehicle.AIRCRAFT)

    @Test
    fun olderSavesLeaveGroupsEmpty() {
        assertEquals(47, Save.VERSION)
        for (kind in kinds) {
            val f = fleet(kind)
            f.create()
            val loaded = Save.read(Save.write(f.world, 44))
            assertEquals(-1, loaded.groupVehicles(kind)[0]!!.group)
            assertTrue(loaded.companies.all { c -> c.groups.all { it.isEmpty() } })
        }
    }

    @Test
    fun joiningLeavesAllOtherVehicleStateAlone() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.create()
            f.buy()
            val v = f.vehicle(1)
            v.orders += listOf(1, 0 or Order.FULL_LOAD)
            v.gap = 9
            v.shared = 17
            v.departed = 91
            v.cargo[Cargo.MAIL] += Packet(4, 0, 1, 0)
            set(v, "name", "Runner")
            set(v, "service", false)
            set(v, "inDepot", false)
            val fields = v.javaClass.declaredFields.filter { it.name != "group" }.onEach { it.isAccessible = true }
            val before = fields.map { it.get(v) }
            f.world.linksDirty = false
            f.join(1, 0)
            assertEquals(0, v.group)
            assertFalse(f.world.linksDirty)
            for ((field, value) in fields.zip(before)) assertEquals(value, field.get(v), field.name)
            assertEquals(listOf(1, 0 or Order.FULL_LOAD), v.orders)
            assertEquals(9, v.gap)
            assertEquals(17, v.shared)
            assertEquals(0, f.vehicle().group)
            f.join(1, -1)
            assertEquals(-1, v.group)
            assertEquals(1, f.groups.size)
        }
    }

    @Test
    fun sharingOrdersKeepsBothGroups() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.buy()
            f.create("First")
            f.create("Second", 1)
            f.order(1, Command.TrainOrder.SHARE, intArrayOf(0))
            assertEquals(f.vehicle().shared, f.vehicle(1).shared)
            assertEquals(0, f.vehicle().group)
            assertEquals(1, f.vehicle(1).group)
            assertEquals(2, f.groups.size)
            f.order(1, Command.TrainOrder.UNSHARE)
            assertEquals(1, f.vehicle(1).group)
        }
    }

    @Test
    fun namesAreCleanAndUniqueWithinTheKindAndCompany() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.create(" \n\t\u0000\uD800 ")
            assertTrue(f.groups.isEmpty())
            assertEquals(-1, f.vehicle().group)
            f.create("  Northern\u0000\uD800 freight\uDC00  ")
            assertEquals("Northern freight", f.groups.single().name)
            f.buy()
            f.create("Northern freight", 1)
            f.create(" northern FREIGHT ", 1)
            assertEquals(1, f.groups.size)
            assertEquals(-1, f.vehicle(1).group)
            f.world.companies += Company(1, "Other", 1_000_000)
            set(f.vehicle(1), "owner", 1)
            f.create("Northern freight", 1, 1)
            assertEquals("Northern freight", f.world.companies[1].groups[kind].single().name)
            assertEquals(0, f.vehicle(1).group)
            val otherKind = (kind + 1) % 4
            val other = fleet(otherKind).vehicle()
            when (other) {
                is Train -> f.world.trains += other
                is RoadVehicle -> f.world.roadVehicles += other
                is Ship -> f.world.ships += other
                is Aircraft -> f.world.aircraft += other
            }
            f.world.apply(Command.CreateGroup(0, otherKind, 0, "Northern freight"))
            assertEquals("Northern freight", f.world.companies[0].groups[otherKind].single().name)
        }
        assertEquals(24, GROUP_NAME_MOST)
        assertEquals("😀".repeat(24), cleanGroupName("  " + "😀".repeat(25) + "  "))
        assertEquals("x".repeat(24), cleanGroupName("x".repeat(25)))
        assertEquals("x".repeat(32), cleanCompanyName("x".repeat(33)))
    }

    @Test
    fun invalidIdsOwnersKindsAndWrecksDoNothing() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.create()
            f.world.companies += Company(1, "Other", 1_000_000)
            f.world.linksDirty = false
            f.order(0, Command.TrainOrder.GROUP)
            f.join(0, 99)
            f.join(0, -2)
            f.order(0, Command.TrainOrder.GROUP, intArrayOf(-1), company = 1)
            f.create("Foreign", company = 1)
            f.create("Missing", id = 99)
            f.world.apply(Command.CreateGroup(0, -1, 0, "Wrong kind"))
            f.world.apply(Command.CreateGroup(0, 4, 0, "Wrong kind"))
            f.world.apply(Command.CreateGroup(0, (kind + 1) % 4, 0, "Missing kind"))
            assertEquals(listOf(VehicleGroup(0, "Freight")), f.groups)
            assertEquals(0, f.vehicle().group)
            assertFalse(f.world.linksDirty)
            if (kind != Command.CopyVehicle.SHIP) {
                set(f.vehicle(), "wreckedFor", 2)
                f.create("Wreck")
                f.join(0, -1)
                assertEquals(0, f.vehicle().group)
                assertEquals(1, f.groups.size)
            }
        }
        val f = fleet(Command.CopyVehicle.ROAD)
        val train = fleet(Command.CopyVehicle.TRAIN).vehicle() as Train
        f.world.trains += train
        f.world.apply(Command.CreateGroup(0, Command.CopyVehicle.TRAIN, 0, "Rail"))
        assertEquals(0, train.group)
        f.join(0, 0)
        assertEquals(-1, f.vehicle().group)
    }

    @Test
    fun leavingAndCreatingPruneOnlyEmptyGroupsAndReuseLowestId() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.buy()
            f.create("First")
            f.create("Second", 1)
            f.join(0, -1)
            assertEquals(listOf(VehicleGroup(1, "Second")), f.groups)
            f.create("Third")
            assertEquals(0, f.vehicle().group)
            assertEquals(1, f.vehicle(1).group)
            f.create("Fourth")
            assertEquals(2, f.vehicle().group)
            assertEquals(listOf(1, 2), f.groups.map { it.id })
            f.join(1, 2)
            assertEquals(listOf(2), f.groups.map { it.id })
            f.create("Fifth")
            assertEquals(0, f.vehicle().group)
            assertEquals(listOf(2, 0), f.groups.map { it.id })
        }
    }

    @Test
    fun sellingPrunesTheLastMember() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.create()
            f.buy()
            f.join(1, 0)
            f.order(0, Command.TrainOrder.SELL)
            assertNull(f.world.groupVehicles(kind)[0])
            assertEquals(0, f.vehicle(1).group)
            assertEquals(1, f.groups.size)
            f.order(1, Command.TrainOrder.SELL)
            assertNull(f.world.groupVehicles(kind)[1])
            assertTrue(f.groups.isEmpty())
        }
    }

    @Test
    fun wrecksKeepMembershipUntilTheirSlotIsCleared() {
        for (kind in kinds.filter { it != Command.CopyVehicle.SHIP }) {
            val f = fleet(kind)
            f.create()
            f.buy()
            f.join(1, 0)
            set(f.vehicle(), "inDepot", false)
            set(f.vehicle(), "wreckedFor", 2)
            fun move() = when (kind) {
                Command.CopyVehicle.TRAIN -> f.world.moveTrains()
                Command.CopyVehicle.ROAD -> f.world.moveRoadVehicles()
                else -> f.world.moveAircraft()
            }
            move()
            assertEquals(0, f.vehicle().group)
            assertEquals(1, f.groups.size)
            val loaded = Save.read(Save.write(f.world))
            assertEquals(0, loaded.groupVehicles(kind)[0]!!.group)
            move()
            assertNull(f.world.groupVehicles(kind)[0])
            assertEquals(1, f.groups.size)
            set(f.vehicle(1), "inDepot", false)
            set(f.vehicle(1), "wreckedFor", 1)
            move()
            assertNull(f.world.groupVehicles(kind)[1])
            assertTrue(f.groups.isEmpty())
        }
    }

    @Test
    fun copyingAndBuyingAndOrderInheritanceLeaveNewVehiclesUngrouped() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.create()
            f.copy()
            assertEquals(-1, f.vehicle(1).group)
            assertEquals(0, f.vehicle().group)
            f.buy()
            assertEquals(-1, f.vehicle(2).group)
            assertEquals(1, f.groups.size)
            f.order(0, Command.TrainOrder.SELL)
            f.buy()
            assertEquals(-1, f.vehicle(3).group)
            assertEquals(listOf(0, 1 or Order.FULL_LOAD), f.vehicle(3).orders)
            assertTrue(f.groups.isEmpty())
        }
    }

    @Test
    fun replacementKeepsTheGroup() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.create()
            val old = f.vehicle()
            val roster = f.world.roster
            val model = when (old) {
                is Train -> roster.available(1965, pulls = true).first { it.id != old.engine && it.traction != Traction.ELECTRIC }
                is RoadVehicle -> roster.availableRoad(1965).first { it.id != old.model }
                is Ship -> roster.availableOf(VehicleKind.SHIP, 1965).first { it.id != old.model }
                is Aircraft -> roster.availableOf(VehicleKind.AIRCRAFT, 1965).first { it.id != old.model && f.world.airports[0]!!.serves(it) }
                else -> error("unknown vehicle")
            }
            f.order(0, Command.TrainOrder.REPLACE, intArrayOf(model.id))
            assertNotSame(old, f.vehicle())
            assertEquals(0, f.vehicle().group)
            assertEquals(1, f.groups.size)
        }
    }

    @Test
    fun groupsStayOutOfTheChecksumAndLinks() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.buy()
            val before = f.world.checksum()
            f.world.linksDirty = false
            f.create()
            assertEquals(before, f.world.checksum())
            f.join(1, 0)
            assertEquals(before, f.world.checksum())
            f.join(0, -1)
            assertEquals(before, f.world.checksum())
            f.create("Second", 1)
            assertEquals(before, f.world.checksum())
            assertFalse(f.world.linksDirty)
        }
    }

    @Test
    fun codecsKeepTheNameAndUseExistingGroupOrderCommands() {
        for (kind in kinds) {
            val c = Command.CreateGroup(2, kind, 7, "Freight 😀")
            val bytes = Codec.encode(c)
            assertEquals(61, bytes[0].toInt())
            assertEquals(c, Codec.decode(bytes))
            assertContentEquals(bytes, Codec.encode(Codec.decode(bytes)))
        }
        assertEquals(12, Command.TrainOrder.GROUP)
        assertEquals(10, Command.TrainOrder.REFIT)
        assertEquals(11, Command.TrainOrder.REPLACE)
        assertEquals(20, Command.RoadOrder.TIRES)
        assertEquals(21, Command.CraftOrder.GEAR)
        for (id in listOf(-1, 3)) for (c in listOf(
            Command.TrainOrder(0, 7, Command.TrainOrder.GROUP, intArrayOf(id)),
            Command.RoadOrder(0, 7, Command.TrainOrder.GROUP, intArrayOf(id)),
            Command.CraftOrder(0, false, 7, Command.TrainOrder.GROUP, intArrayOf(id)),
            Command.CraftOrder(0, true, 7, Command.TrainOrder.GROUP, intArrayOf(id)),
        )) {
            val encoded = Codec.encode(c)
            val back = Codec.decode(encoded)
            assertContentEquals(encoded, Codec.encode(back))
            when (back) {
                is Command.TrainOrder -> { assertEquals(8, encoded[0].toInt()); assertEquals(12, back.what); assertContentEquals(intArrayOf(id), back.stations) }
                is Command.RoadOrder -> { assertEquals(24, encoded[0].toInt()); assertEquals(12, back.what); assertContentEquals(intArrayOf(id), back.stations) }
                is Command.CraftOrder -> { assertEquals(35, encoded[0].toInt()); assertEquals(12, back.what); assertContentEquals(intArrayOf(id), back.stations) }
                else -> error("wrong command")
            }
        }
    }

    private fun set(v: Carrier, name: String, value: Any) {
        v.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(v, value)
    }
}
