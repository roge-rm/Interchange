package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CopyVehicleTest {
    private val kinds = listOf(Command.CopyVehicle.TRAIN, Command.CopyVehicle.ROAD, Command.CopyVehicle.SHIP, Command.CopyVehicle.AIRCRAFT)

    private class Fleet(val world: World, val kind: Int, val depot: Int) {
        fun vehicle(id: Int = 0): Carrier = when (kind) {
            Command.CopyVehicle.TRAIN -> world.trains[id]!!
            Command.CopyVehicle.ROAD -> world.roadVehicles[id]!!
            Command.CopyVehicle.SHIP -> world.ships[id]!!
            else -> world.aircraft[id]!!
        }

        fun order(id: Int, what: Int, values: IntArray) {
            world.apply(when (kind) {
                Command.CopyVehicle.TRAIN -> Command.TrainOrder(0, id, what, values)
                Command.CopyVehicle.ROAD -> Command.RoadOrder(0, id, what, values)
                else -> Command.CraftOrder(0, kind == Command.CopyVehicle.AIRCRAFT, id, what, values)
            })
        }

        fun copy(id: Int = 0) = world.apply(Command.CopyVehicle(0, kind, id, depot))
    }

    private fun fleet(kind: Int): Fleet {
        val w = World(64, 64, 1, startYear = 1965)
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

    /** Give the source a history that a new purchase must leave behind. */
    private fun prepare(v: Carrier) {
        v.profitThisYear = 12345
        v.departed = 321L
        when (v) {
            is Train -> {
                v.name = "Mountain freight"; v.service = false; v.order = 1; v.route = intArrayOf(12, 13)
                v.built = -100; v.reliability = 1234; v.profitLastYear = 6789
            }
            is RoadVehicle -> {
                v.name = "Valley freight"; v.service = false; v.order = 1; v.route = intArrayOf(12, 13)
                v.built = -100; v.reliability = 1234; v.profitLastYear = 6789
                v.tires = Tires.CHAINS; v.seasonalTires = false
            }
            is Ship -> {
                v.name = "Lake freight"; v.service = false; v.order = 1; v.route = intArrayOf(12, 13)
                v.built = -100; v.reliability = 1234; v.profitLastYear = 6789
            }
            is Aircraft -> {
                v.name = "Northern mail"; v.service = false; v.order = 1
                v.built = -100; v.reliability = 1234; v.profitLastYear = 6789
                v.gear = Gear.SKIS; v.seasonalGear = false
            }
        }
    }

    /** Everything on the source except the shared id, which sharing may set. */
    private fun sourceState(v: Carrier): List<Any?> = listOf(v.orders.toList(), v.gap, v.departed, v.order, v.profitThisYear) + when (v) {
        is Train -> listOf(v.engine, v.wagon, v.wagons, v.wagonCargo, v.name, v.number, v.owner, v.depot, v.inDepot, v.running,
            v.service, v.route?.toList(), v.built, v.reliability, v.profitLastYear)
        is RoadVehicle -> listOf(v.model, v.cargoChoice, v.name, v.number, v.owner, v.depot, v.inDepot, v.running,
            v.service, v.route?.toList(), v.built, v.reliability, v.profitLastYear, v.tires, v.seasonalTires)
        is Ship -> listOf(v.model, v.cargoChoice, v.name, v.number, v.owner, v.depot, v.inDepot, v.running,
            v.service, v.route?.toList(), v.built, v.reliability, v.profitLastYear)
        is Aircraft -> listOf(v.model, v.cargoChoice, v.name, v.number, v.owner, v.depot, v.inDepot, v.running,
            v.service, v.built, v.reliability, v.profitLastYear, v.gear, v.seasonalGear)
        else -> error("unknown vehicle")
    }

    private fun setOutside(v: Carrier, outside: Boolean = true) {
        when (v) {
            is Train -> v.inDepot = !outside
            is RoadVehicle -> v.inDepot = !outside
            is Ship -> v.inDepot = !outside
            is Aircraft -> v.inDepot = !outside
        }
    }

    private fun assertFresh(f: Fleet, made: Carrier, depot: Int = f.depot) {
        assertEquals(depot, when (made) {
            is Train -> made.depot
            is RoadVehicle -> made.depot
            is Ship -> made.depot
            is Aircraft -> made.depot
            else -> error("unknown vehicle")
        })
        assertEquals(0L, made.profitThisYear)
        assertEquals(0L, made.departed)
        assertEquals(0, made.order)
        val state = when (made) {
            is Train -> {
                assertNull(made.route)
                listOf(made.name, made.number, made.service, made.inDepot, made.running, made.built, made.reliability, made.profitLastYear)
            }
            is RoadVehicle -> {
                assertNull(made.route)
                assertTrue(made.seasonalTires)
                val year = Calendar.year(f.world.startYear, f.world.day)
                val winter = Climate.look(f.world.settings.region, year, Calendar.dayOfYear(f.world.startYear, f.world.day)) == Climate.WINTER
                assertEquals(if (winter) Tires.WINTER else Tires.SUMMER, made.tires)
                listOf(made.name, made.number, made.service, made.inDepot, made.running, made.built, made.reliability, made.profitLastYear)
            }
            is Ship -> {
                assertNull(made.route)
                listOf(made.name, made.number, made.service, made.inDepot, made.running, made.built, made.reliability, made.profitLastYear)
            }
            is Aircraft -> {
                assertEquals(Gear.WHEELS, made.gear)
                assertTrue(made.seasonalGear)
                listOf(made.name, made.number, made.service, made.inDepot, made.running, made.built, made.reliability, made.profitLastYear)
            }
            else -> error("unknown vehicle")
        }
        val reliability = when (made) {
            is Train -> made.roster[made.engine].reliability
            is RoadVehicle -> made.info.reliability
            is Ship -> made.info.reliability
            is Aircraft -> made.info.reliability
            else -> error("unknown vehicle")
        } * 100
        assertEquals(listOf("", made.id + 1, true, true, false, f.world.workDay, reliability, 0L), state)
    }

    private fun copies(kind: Int, outside: Boolean = false) {
        val f = fleet(kind)
        val w = f.world
        val src = f.vehicle()
        prepare(src)
        setOutside(src, outside)
        val before = sourceState(src)
        w.costScale = 3L shl 15
        val money = w.companies[0].money
        f.copy()
        val made = f.vehicle(1)
        assertFresh(f, made)
        assertEquals(src.id, src.shared)
        assertEquals(src.shared, made.shared)
        assertEquals(src.orders, made.orders)
        assertEquals(src.gap, made.gap)
        assertEquals(before, sourceState(src))
        val price = when (src) {
            is Train -> {
                val copy = made as Train
                assertEquals(listOf(src.engine, src.wagon, src.wagons, src.wagonCargo), listOf(copy.engine, copy.wagon, copy.wagons, copy.wagonCargo))
                assertNotEquals(src.number, copy.number)
                copy.price
            }
            is RoadVehicle -> {
                val copy = made as RoadVehicle
                assertEquals(src.model, copy.model)
                assertEquals(src.cargoChoice, copy.cargoChoice)
                assertNotEquals(src.number, copy.number)
                copy.price
            }
            is Ship -> {
                val copy = made as Ship
                assertEquals(src.model, copy.model)
                assertEquals(src.cargoChoice, copy.cargoChoice)
                assertNotEquals(src.number, copy.number)
                copy.price
            }
            is Aircraft -> {
                val copy = made as Aircraft
                assertEquals(src.model, copy.model)
                assertEquals(src.cargoChoice, copy.cargoChoice)
                assertNotEquals(src.number, copy.number)
                copy.price
            }
            else -> error("unknown vehicle")
        }
        assertEquals(money - w.price(price), w.companies[0].money)
        f.copy()
        val second = f.vehicle(2)
        assertEquals(src.shared, second.shared)
        assertEquals(src.orders, second.orders)
        assertEquals(src.gap, second.gap)
        assertEquals(0L, second.departed)
        assertEquals(before, sourceState(src))
        f.order(made.id, Command.TrainOrder.ORDERS, intArrayOf(1, 0 or Order.NO_LOAD))
        for (v in listOf(src, made, second)) assertEquals(listOf(1, 0 or Order.NO_LOAD), v.orders)
    }

    @Test fun trainCopiesConsistAndSharesOrders() = copies(Command.CopyVehicle.TRAIN)
    @Test fun roadCopiesModelAndCargoAndSharesOrders() = copies(Command.CopyVehicle.ROAD)
    @Test fun shipCopiesModelAndCargoAndSharesOrders() = copies(Command.CopyVehicle.SHIP)
    @Test fun aircraftCopiesModelAndCargoAndSharesOrders() = copies(Command.CopyVehicle.AIRCRAFT)

    private fun assertNoCopy(f: Fleet, command: Command.CopyVehicle = Command.CopyVehicle(0, f.kind, 0, f.depot)) {
        val before = Save.write(f.world)
        f.world.apply(command)
        assertContentEquals(before, Save.write(f.world), "kind ${f.kind}, command $command")
    }

    @Test
    fun tooLittleMoneyLeavesTheSourceUnshared() {
        for (outside in listOf(false, true)) for (kind in kinds) {
            val f = fleet(kind)
            prepare(f.vehicle())
            setOutside(f.vehicle(), outside)
            f.world.companies[0].money = 0
            assertNoCopy(f)
            assertEquals(-1, f.vehicle().shared)
        }
    }

    private fun electricNeedsWires(outside: Boolean) {
        val w = World(64, 64, 1, startYear = 1920)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 20, 10, Track.X, remove = false))
        w.apply(Command.Wire(0, 2, 10, 20, 10, Track.X, remove = false))
        val boxcab = w.roster.engines.first { it.key == "base:boxcab" }.id
        val coach = w.roster.engines.first { it.key == "base:coach_steel" }.id
        w.apply(Command.BuyTrain(0, 0, boxcab, coach, 2))
        assertEquals(1, w.trains.size)
        setOutside(w.trains[0]!!, outside)
        w.apply(Command.Wire(0, 2, 10, 2, 10, Track.X, remove = true))
        val f = Fleet(w, Command.CopyVehicle.TRAIN, 0)
        assertNoCopy(f)
        assertEquals(-1, f.vehicle().shared)
        w.apply(Command.Wire(0, 2, 10, 2, 10, Track.X, remove = false))
        f.copy()
        assertEquals(2, w.trains.size)
        assertEquals(boxcab, w.trains[1]!!.engine)
        assertEquals(0, w.trains[1]!!.shared)
    }

    @Test fun electricCopyNeedsWiresOverTheDepot() = electricNeedsWires(false)
    @Test fun electricCopyFromTheLineNeedsWiresOverTheDepot() = electricNeedsWires(true)

    @Test
    fun modelsOffSaleLeaveTheSourceUnchanged() {
        for (outside in listOf(false, true)) for (kind in kinds) {
            val f = fleet(kind)
            prepare(f.vehicle())
            setOutside(f.vehicle(), outside)
            val w = f.world
            val model = when (val src = f.vehicle()) {
                is Train -> src.engine
                is RoadVehicle -> src.model
                is Ship -> src.model
                is Aircraft -> src.model
                else -> error("unknown vehicle")
            }
            val e = w.roster[model]
            assertTrue(w.startYear in e.from until e.until)
            var day = 0
            while (Calendar.year(w.startYear, day) < e.until) day++
            w.tick = day.toLong() * w.dayTicks
            assertEquals(e.until, Calendar.year(w.startYear, w.day))
            assertNoCopy(f)
            assertEquals(-1, f.vehicle().shared)
        }
    }

    @Test
    fun anAircraftMustFitItsAirport() {
        val f = fleet(Command.CopyVehicle.AIRCRAFT)
        val w = f.world
        val original = w.airports[0]!!
        val strip = Airport(original.id, original.owner, original.station, Airports.STRIP, original.x, original.y, original.axis, original.depot)
        assertFalse(strip.serves(w.aircraft[0]!!.info))
        w.airports[0] = strip
        assertNoCopy(f)
    }

    @Test
    fun sourcesOutsideTheDepotCanBeCopied() {
        for (kind in kinds) copies(kind, outside = true)
    }

    private fun copyIntoSecondDepot(f: Fleet, destination: Int) {
        val src = f.vehicle()
        prepare(src)
        val before = sourceState(src)
        f.world.apply(Command.CopyVehicle(0, f.kind, src.id, destination))
        val made = f.vehicle(1)
        assertFresh(f, made, destination)
        assertEquals(before, sourceState(src))
        assertEquals(src.id, src.shared)
        assertEquals(src.shared, made.shared)
        assertEquals(src.orders, made.orders)
        assertEquals(src.gap, made.gap)
    }

    @Test
    fun aTrainCanBeCopiedIntoAnotherDepot() {
        val f = fleet(Command.CopyVehicle.TRAIN)
        f.world.apply(Command.Depot(0, 2, 15, Track.XP))
        assertEquals(2, f.world.depots.size)
        copyIntoSecondDepot(f, 1)
    }

    @Test
    fun aRoadVehicleCanBeCopiedIntoAnotherGarage() {
        val f = fleet(Command.CopyVehicle.ROAD)
        f.world.apply(Command.Road(0, 30, 20, 30, 22, Surface.PAVED, remove = false))
        f.world.apply(Command.Garage(0, 30, 23, Track.YM))
        assertEquals(2, f.world.depots.size)
        copyIntoSecondDepot(f, 1)
    }

    @Test
    fun trainsAndRoadVehiclesNeedTheRightDepotKind() {
        val train = fleet(Command.CopyVehicle.TRAIN)
        train.world.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        train.world.apply(Command.Garage(0, 5, 23, Track.YM))
        assertEquals(2, train.world.depots.size)
        assertNoCopy(train, Command.CopyVehicle(0, train.kind, 0, 1))
        val road = fleet(Command.CopyVehicle.ROAD)
        road.world.apply(Command.Depot(0, 2, 15, Track.XP))
        assertEquals(2, road.world.depots.size)
        assertNoCopy(road, Command.CopyVehicle(0, road.kind, 0, 1))
    }

    @Test
    fun wrongDestinationKindsAreRefused() {
        for (kind in kinds) {
            val f = fleet(kind)
            setOutside(f.vehicle())
            val original = f.world.depots[f.depot]!!
            for (destinationKind in listOf(Depots.RAIL, Depots.ROAD, Depots.SHIPYARD, Depots.HANGAR).filter { it != original.kind }) {
                f.world.depots[f.depot] = Depot(original.id, original.owner, original.tile, original.edge, destinationKind)
                assertNoCopy(f)
            }
        }
    }

    @Test
    fun anAircraftCanBeCopiedIntoAnotherHangar() {
        val f = fleet(Command.CopyVehicle.AIRCRAFT)
        val destination = f.world.airports[1]!!
        assertTrue(destination.serves(f.world.aircraft[0]!!.info))
        assertNotEquals(f.depot, destination.depot)
        copyIntoSecondDepot(f, destination.depot)
    }

    @Test
    fun anAircraftMustFitTheDestinationAirport() {
        val f = fleet(Command.CopyVehicle.AIRCRAFT)
        val w = f.world
        val original = w.airports[1]!!
        val strip = Airport(original.id, original.owner, original.station, Airports.STRIP, original.x, original.y, original.axis, original.depot)
        assertTrue(w.airports[0]!!.serves(w.aircraft[0]!!.info))
        assertFalse(strip.serves(w.aircraft[0]!!.info))
        w.airports[1] = strip
        setOutside(f.vehicle())
        assertNoCopy(f, Command.CopyVehicle(0, f.kind, 0, strip.depot))
    }

    @Test
    fun wrongDepotOwnerKindAndMissingSourcesAreRefused() {
        for (outside in listOf(false, true)) for (kind in kinds) {
            val f = fleet(kind)
            prepare(f.vehicle())
            setOutside(f.vehicle(), outside)
            f.world.companies += Company(1, "North", 100_000_000)
            assertNoCopy(f, Command.CopyVehicle(0, kind, 0, -1))
            assertNoCopy(f, Command.CopyVehicle(0, kind, 0, 999))
            assertNoCopy(f, Command.CopyVehicle(1, kind, 0, f.depot))
            val depot = f.world.depots[f.depot]!!
            depot.owner = 1
            assertNoCopy(f)
            depot.owner = 0
            assertNoCopy(f, Command.CopyVehicle(0, -1, 0, f.depot))
            assertNoCopy(f, Command.CopyVehicle(0, 99, 0, f.depot))
            assertNoCopy(f, Command.CopyVehicle(0, kind, -1, f.depot))
            assertNoCopy(f, Command.CopyVehicle(0, kind, 999, f.depot))
            for (wrongKind in kinds.filter { it != kind }) assertNoCopy(f, Command.CopyVehicle(0, wrongKind, 0, f.depot))
        }
    }

    @Test
    fun wrecksAreRefusedEvenWhenMarkedInDepot() {
        for (outside in listOf(false, true)) for (kind in kinds.filter { it != Command.CopyVehicle.SHIP }) {
            val f = fleet(kind)
            setOutside(f.vehicle(), outside)
            when (val src = f.vehicle()) {
                is Train -> src.wreckedFor = 1
                is RoadVehicle -> src.wreckedFor = 1
                is Aircraft -> src.wreckedFor = 1
            }
            assertNoCopy(f)
        }
    }

    @Test
    fun aCopyJoinsTheSourcesExistingGroup() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.copy()
            f.order(0, Command.TrainOrder.UNSHARE, intArrayOf())
            f.order(1, Command.TrainOrder.UNSHARE, intArrayOf())
            f.order(1, Command.TrainOrder.ORDERS, intArrayOf(1, 0))
            f.order(1, Command.TrainOrder.GAP, intArrayOf(7))
            f.order(0, Command.TrainOrder.SHARE, intArrayOf(1))
            val src = f.vehicle()
            prepare(src)
            val before = sourceState(src)
            f.copy()
            val made = f.vehicle(2)
            assertEquals(1, src.shared)
            assertEquals(1, made.shared)
            for (v in listOf(src, f.vehicle(1), made)) {
                assertEquals(listOf(1, 0), v.orders)
                assertEquals(7, v.gap)
            }
            assertEquals(before, sourceState(src))
            assertEquals(0L, made.departed)
        }
    }

    @Test
    fun editorCopiesAreFree() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.world.editor = true
            f.world.companies[0].money = 0
            f.copy()
            assertEquals(0L, f.world.companies[0].money)
            assertEquals(0, f.vehicle(1).shared)
        }
    }

    @Test
    fun copyCommandRoundTripsWithItsAppendedKind() {
        val command = Command.CopyVehicle(2, Command.CopyVehicle.AIRCRAFT, 17, 8)
        val bytes = Codec.encode(command)
        assertEquals(56, bytes[0].toInt())
        val writer = ByteWriter(32)
        Codec.write(writer, command)
        val decoded = Codec.read(ByteReader(writer.toByteArray())) as Command.CopyVehicle
        assertEquals(command.company, decoded.company)
        assertEquals(command.kind, decoded.kind)
        assertEquals(command.vehicle, decoded.vehicle)
        assertEquals(command.depot, decoded.depot)
        assertEquals(command, Codec.decode(bytes))
    }
}
