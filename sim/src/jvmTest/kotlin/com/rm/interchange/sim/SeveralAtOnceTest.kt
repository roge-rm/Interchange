package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SeveralAtOnceTest {
    private val kinds = listOf(Command.CopyVehicle.TRAIN, Command.CopyVehicle.ROAD, Command.CopyVehicle.SHIP, Command.CopyVehicle.AIRCRAFT)
    private val stops = listOf(0, 1 or Order.FULL_LOAD)

    private class Fleet(val world: World, val kind: Int, val depot: Int, val model: Int, val wagon: Int = -1) {
        val company get() = world.companies[0]
        val backup get() = company.orderBackups[kind]

        fun vehicles(): List<Carrier?> = when (kind) {
            Command.CopyVehicle.TRAIN -> world.trains
            Command.CopyVehicle.ROAD -> world.roadVehicles
            Command.CopyVehicle.SHIP -> world.ships
            else -> world.aircraft
        }

        fun vehicle(id: Int = 0) = vehicles()[id]!!

        val cost: Long get() = world.price(when (val v = vehicles().firstNotNullOf { it }) {
            is Train -> v.price
            is RoadVehicle -> v.price
            is Ship -> v.price
            is Aircraft -> v.price
            else -> error("unknown vehicle")
        })

        fun buy(count: Int = 1, destination: Int = depot) = world.apply(when (kind) {
            Command.CopyVehicle.TRAIN -> Command.BuyTrain(0, destination, model, wagon, 3, Cargo.PAPER, count)
            Command.CopyVehicle.ROAD -> Command.BuyRoadVehicle(0, destination, model, Cargo.FOOD, count)
            Command.CopyVehicle.SHIP -> Command.BuyCraft(0, destination, model, Cargo.COAL, count)
            else -> Command.BuyCraft(0, destination, model, Cargo.MAIL, count)
        })

        fun copy(count: Int, id: Int = 0) = world.apply(Command.CopyVehicle(0, kind, id, depot, count))

        fun order(id: Int, what: Int, values: IntArray = intArrayOf()) = world.apply(when (kind) {
            Command.CopyVehicle.TRAIN -> Command.TrainOrder(0, id, what, values)
            Command.CopyVehicle.ROAD -> Command.RoadOrder(0, id, what, values)
            else -> Command.CraftOrder(0, kind == Command.CopyVehicle.AIRCRAFT, id, what, values)
        })

        fun sell(id: Int = 0) = order(id, Command.TrainOrder.SELL)
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
        w.costScale = 3L shl 15
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

    private fun number(v: Carrier) = when (v) {
        is Train -> v.number
        is RoadVehicle -> v.number
        is Ship -> v.number
        is Aircraft -> v.number
        else -> error("unknown vehicle")
    }

    private fun position(v: Carrier): List<Any> = when (v) {
        is Train -> listOf(v.depot, v.inDepot, v.running, v.steps.toList(), v.nose, v.speed)
        is RoadVehicle -> listOf(v.depot, v.inDepot, v.running, v.at, v.along, v.speed)
        is Ship -> listOf(v.depot, v.inDepot, v.running, v.x, v.y, v.speed)
        is Aircraft -> listOf(v.depot, v.inDepot, v.running, v.x, v.y, v.speed)
        else -> error("unknown vehicle")
    }

    private fun assertInherited(f: Fleet, id: Int) {
        val v = f.vehicle(id)
        assertEquals(stops, v.orders)
        assertEquals(4, v.gap)
        assertEquals(-1, v.shared)
        assertNull(f.backup)
    }

    private fun buysFour(kind: Int) {
        val f = fleet(kind)
        val cost = f.cost
        f.sell()
        val backup = assertNotNull(f.backup)
        val money = f.company.money
        val books = f.company.thisYear[Books.NEW_TRAINS]
        val monthOut = f.company.monthOut
        f.buy(4)
        assertEquals(5, f.vehicles().size)
        assertNull(f.vehicles()[0])
        assertEquals(listOf(1, 2, 3, 4), f.vehicles().drop(1).map { number(it!!) })
        assertEquals(money - 4 * cost, f.company.money)
        assertEquals(books - 4 * cost, f.company.thisYear[Books.NEW_TRAINS])
        assertEquals(monthOut + 4 * cost, f.company.monthOut)
        assertInherited(f, 1)
        assertNotSame(backup.orders, f.vehicle(1).orders)
        for (id in 2..4) {
            assertTrue(f.vehicle(id).orders.isEmpty())
            assertEquals(-1, f.vehicle(id).shared)
            assertEquals(0, f.vehicle(id).gap)
        }
    }

    @Test fun buysFourTrains() = buysFour(Command.CopyVehicle.TRAIN)
    @Test fun buysFourRoadVehicles() = buysFour(Command.CopyVehicle.ROAD)
    @Test fun buysFourShips() = buysFour(Command.CopyVehicle.SHIP)
    @Test fun buysFourAircraft() = buysFour(Command.CopyVehicle.AIRCRAFT)

    private fun copiesThree(kind: Int) {
        val f = fleet(kind)
        f.buy()
        f.order(1, Command.TrainOrder.ORDERS, intArrayOf(1))
        f.sell(1)
        val backup = assertNotNull(f.backup)
        val src = f.vehicle()
        val position = position(src)
        val number = number(src)
        val money = f.company.money
        val books = f.company.thisYear[Books.NEW_TRAINS]
        val cost = f.cost
        f.copy(3)
        assertEquals(5, f.vehicles().size)
        assertSame(src, f.vehicle())
        assertEquals(position, position(src))
        assertEquals(number, number(src))
        assertEquals(money - 3 * cost, f.company.money)
        assertEquals(books - 3 * cost, f.company.thisYear[Books.NEW_TRAINS])
        assertSame(backup, f.backup)
        assertEquals(listOf(1), backup.orders)
        for (id in listOf(0, 2, 3, 4)) {
            assertEquals(src.id, f.vehicle(id).shared)
            assertEquals(stops, f.vehicle(id).orders)
            assertEquals(4, f.vehicle(id).gap)
        }
        f.order(2, Command.TrainOrder.ORDERS, intArrayOf(1, 0))
        for (id in listOf(0, 2, 3, 4)) assertEquals(listOf(1, 0), f.vehicle(id).orders)
        assertSame(backup, f.backup)
    }

    @Test fun copiesThreeTrains() = copiesThree(Command.CopyVehicle.TRAIN)
    @Test fun copiesThreeRoadVehicles() = copiesThree(Command.CopyVehicle.ROAD)
    @Test fun copiesThreeShips() = copiesThree(Command.CopyVehicle.SHIP)
    @Test fun copiesThreeAircraft() = copiesThree(Command.CopyVehicle.AIRCRAFT)

    @Test
    fun nonpositiveCountsLeaveTheWorldAndBackupAlone() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.buy()
            f.sell(1)
            val backup = assertNotNull(f.backup)
            val before = Save.write(f.world)
            for (count in listOf(0, -1, Int.MIN_VALUE)) {
                f.buy(count)
                f.copy(count)
                assertSame(backup, f.backup)
                assertContentEquals(before, Save.write(f.world))
            }
        }
    }

    @Test
    fun buysAtMostTwenty() {
        assertEquals(20, MAX_AT_ONCE)
        for (kind in kinds) {
            val f = fleet(kind)
            val cost = f.cost
            f.sell()
            val money = f.company.money
            f.buy(100)
            assertEquals(21, f.vehicles().size)
            assertEquals((1..20).toList(), f.vehicles().drop(1).map { number(it!!) })
            assertEquals(money - 20 * cost, f.company.money)
            assertInherited(f, 1)
            for (v in f.vehicles().drop(2)) {
                assertTrue(v!!.orders.isEmpty())
                assertEquals(-1, v.shared)
            }
        }
    }

    @Test
    fun copiesAtMostTwentyAndKeepsTheBackup() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.buy()
            f.sell(1)
            val backup = assertNotNull(f.backup)
            val money = f.company.money
            val cost = f.cost
            f.copy(100)
            assertEquals(22, f.vehicles().size)
            assertEquals(money - 20 * cost, f.company.money)
            for (v in f.vehicles().drop(2)) assertEquals(f.vehicle().shared, v!!.shared)
            assertSame(backup, f.backup)
        }
    }

    @Test
    fun buysOnlyWhatTheCompanyCanAfford() {
        for (kind in kinds) {
            val f = fleet(kind)
            val cost = f.cost
            f.sell()
            f.company.money = 2 * cost
            val books = f.company.thisYear[Books.NEW_TRAINS]
            f.buy(4)
            assertEquals(3, f.vehicles().size)
            assertEquals(0L, f.company.money)
            assertEquals(books - 2 * cost, f.company.thisYear[Books.NEW_TRAINS])
            assertInherited(f, 1)
            assertTrue(f.vehicle(2).orders.isEmpty())
            assertEquals(-1, f.vehicle(2).shared)
            f.company.money = cost
            f.buy()
            assertEquals(3, f.vehicle(3).id)
            assertEquals(3, number(f.vehicle(3)))
            assertTrue(f.vehicle(3).orders.isEmpty())
        }
    }

    @Test
    fun copiesOnlyWhatTheCompanyCanAfford() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.buy()
            f.sell(1)
            val backup = assertNotNull(f.backup)
            val src = f.vehicle()
            val position = position(src)
            val cost = f.cost
            f.company.money = 2 * cost
            val books = f.company.thisYear[Books.NEW_TRAINS]
            f.copy(4)
            assertEquals(4, f.vehicles().size)
            assertEquals(0L, f.company.money)
            assertEquals(books - 2 * cost, f.company.thisYear[Books.NEW_TRAINS])
            assertSame(backup, f.backup)
            assertSame(src, f.vehicle())
            assertEquals(position, position(src))
            for (id in 2..3) assertEquals(src.shared, f.vehicle(id).shared)
        }
    }

    @Test
    fun aTrainBuyIntoARoadDepotKeepsTheBackup() {
        val f = fleet(Command.CopyVehicle.TRAIN)
        f.world.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        f.world.apply(Command.Garage(0, 5, 23, Track.YM))
        assertEquals(2, f.world.depots.size)
        f.sell()
        val backup = assertNotNull(f.backup)
        val before = Save.write(f.world)
        f.buy(4, destination = 1)
        assertEquals(1, f.vehicles().size)
        assertSame(backup, f.backup)
        assertContentEquals(before, Save.write(f.world))
    }

    @Test
    fun editorBatchesKeepMoneyAndBooks() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.sell()
            f.world.editor = true
            f.company.money = 17
            val books = f.company.thisYear.copyOf()
            val lastYear = f.company.lastYear.copyOf()
            val monthIn = f.company.monthIn
            val monthOut = f.company.monthOut
            f.buy(3)
            assertEquals(4, f.vehicles().size)
            assertInherited(f, 1)
            assertTrue(f.vehicle(2).orders.isEmpty())
            assertTrue(f.vehicle(3).orders.isEmpty())
            assertEquals(17L, f.company.money)
            assertContentEquals(books, f.company.thisYear)
            assertContentEquals(lastYear, f.company.lastYear)
            assertEquals(monthIn, f.company.monthIn)
            assertEquals(monthOut, f.company.monthOut)
            f.copy(3, id = 1)
            assertEquals(7, f.vehicles().size)
            assertEquals(17L, f.company.money)
            assertContentEquals(books, f.company.thisYear)
            assertEquals(monthIn, f.company.monthIn)
            assertEquals(monthOut, f.company.monthOut)
        }
    }

    @Test
    fun anOrdinaryBuyStillInheritsOnce() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.sell()
            f.buy()
            assertEquals(2, f.vehicles().size)
            assertInherited(f, 1)
            f.buy()
            assertTrue(f.vehicle(2).orders.isEmpty())
            assertEquals(-1, f.vehicle(2).shared)
        }
    }

    @Test
    fun singleCommandsKeepTheirBytesAndLegacyTrainsStillDecode() {
        val commands = listOf(
            Command.BuyTrain(2, 3, 4, 5, 6, Cargo.PAPER) to listOf(9, 2, 3, 4, 5, 6, Cargo.PAPER),
            Command.BuyRoadVehicle(2, 3, 4, Cargo.FOOD) to listOf(23, 2, 3, 4, Cargo.FOOD),
            Command.BuyCraft(2, 3, 4, Cargo.MAIL) to listOf(34, 2, 3, 4, Cargo.MAIL),
            Command.CopyVehicle(2, Command.CopyVehicle.AIRCRAFT, 17, 8) to listOf(56, 2, Command.CopyVehicle.AIRCRAFT, 17, 8),
        )
        for ((command, fields) in commands) {
            val expected = ByteWriter(32)
            expected.byte(fields.first())
            for (field in fields.drop(1)) expected.int(field)
            assertContentEquals(expected.toByteArray(), Codec.encode(command))
            assertEquals(command, Codec.decode(expected.toByteArray()))
        }
        assertEquals(9, Codec.encode(Command.BuyTrain(2, 3, 4, 5, 6))[0].toInt())
        val legacy = ByteWriter(32)
        legacy.byte(7)
        for (field in listOf(2, 3, 4, 5, 6)) legacy.int(field)
        assertEquals(Command.BuyTrain(2, 3, 4, 5, 6), Codec.decode(legacy.toByteArray()))
    }

    @Test
    fun batchesRoundTripWithTheAppendedKinds() {
        for (count in listOf(4, 0, -1, 100)) {
            val commands = listOf(
                Command.BuyTrain(2, 3, 4, 5, 6, Cargo.PAPER, count) to 57,
                Command.BuyRoadVehicle(2, 3, 4, Cargo.FOOD, count) to 58,
                Command.BuyCraft(2, 3, 4, Cargo.MAIL, count) to 59,
                Command.CopyVehicle(2, Command.CopyVehicle.AIRCRAFT, 17, 8, count) to 60,
            )
            for ((command, kind) in commands) {
                val bytes = Codec.encode(command)
                assertEquals(kind, bytes[0].toInt())
                assertEquals(command, Codec.decode(bytes))
            }
        }
    }
}
