package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RefitTest {
    private val kinds = listOf(Command.CopyVehicle.TRAIN, Command.CopyVehicle.ROAD, Command.CopyVehicle.SHIP, Command.CopyVehicle.AIRCRAFT)

    private class Fleet(val world: World, val kind: Int, val depot: Int, val cargoes: IntArray) {
        fun vehicle(id: Int = 0, w: World = world): Carrier = when (kind) {
            Command.CopyVehicle.TRAIN -> w.trains[id]!!
            Command.CopyVehicle.ROAD -> w.roadVehicles[id]!!
            Command.CopyVehicle.SHIP -> w.ships[id]!!
            else -> w.aircraft[id]!!
        }

        fun order(what: Int, values: IntArray = intArrayOf(), company: Int = 0) {
            world.apply(when (kind) {
                Command.CopyVehicle.TRAIN -> Command.TrainOrder(company, 0, what, values)
                Command.CopyVehicle.ROAD -> Command.RoadOrder(company, 0, what, values)
                else -> Command.CraftOrder(company, kind == Command.CopyVehicle.AIRCRAFT, 0, what, values)
            })
        }

        fun refit(cargo: Int) = order(Command.TrainOrder.REFIT, intArrayOf(cargo))
        fun copy() = world.apply(Command.CopyVehicle(0, kind, 0, depot))
    }

    private fun choice(v: Carrier): Int = when (v) {
        is Train -> v.wagonCargo
        is RoadVehicle -> v.cargoChoice
        is Ship -> v.cargoChoice
        is Aircraft -> v.cargoChoice
        else -> error("unknown vehicle")
    }

    private fun outside(v: Carrier, value: Boolean) {
        when (v) {
            is Train -> v.inDepot = !value
            is RoadVehicle -> v.inDepot = !value
            is Ship -> v.inDepot = !value
            is Aircraft -> v.inDepot = !value
        }
    }

    private fun wreck(v: Carrier) {
        when (v) {
            is Train -> v.wreckedFor = 1
            is RoadVehicle -> v.wreckedFor = 1
            is Aircraft -> v.wreckedFor = 1
        }
    }

    private fun fleet(kind: Int): Fleet? {
        val modelKind = when (kind) {
            Command.CopyVehicle.TRAIN -> VehicleKind.WAGON
            Command.CopyVehicle.ROAD -> VehicleKind.ROAD
            Command.CopyVehicle.SHIP -> VehicleKind.SHIP
            else -> VehicleKind.AIRCRAFT
        }
        val models = Roster.BASE.engines.filter { it.kind == modelKind && it.cargoes.size > 1 }
        if (models.isEmpty()) {
            assertNotEquals(Command.CopyVehicle.TRAIN, kind)
            println("No base model with several cargoes for $modelKind")
            return null
        }
        val model = models.firstOrNull { 1965 in it.from until it.until } ?: models.first()
        val year = if (1965 in model.from until model.until) 1965 else model.from
        val w = World(64, 64, 1, startYear = year)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        var depot = 0
        when (kind) {
            Command.CopyVehicle.TRAIN -> {
                w.apply(Command.Depot(0, 2, 10, Track.XP))
                val engine = w.roster.engines.first { it.key == "base:gp9" }.id
                w.apply(Command.BuyTrain(0, depot, engine, model.id, 3, model.cargoes.first()))
                assertEquals(1, w.trains.size)
            }
            Command.CopyVehicle.ROAD -> {
                w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
                w.apply(Command.Garage(0, 5, 23, Track.YM))
                w.apply(Command.BuyRoadVehicle(0, depot, model.id, model.cargoes.first()))
                assertEquals(1, w.roadVehicles.size)
            }
            Command.CopyVehicle.SHIP -> {
                for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 10..50 && y in 10..30) 0 else 1
                w.shoreChanged()
                w.apply(Command.Shipyard(0, 9, 15))
                w.apply(Command.BuyCraft(0, depot, model.id, model.cargoes.first()))
                assertEquals(1, w.ships.size)
            }
            Command.CopyVehicle.AIRCRAFT -> {
                w.apply(Command.Airport(0, 5, 5, Airports.INTERNATIONAL, Track.X))
                depot = w.airports[0]!!.depot
                w.apply(Command.BuyCraft(0, depot, model.id, model.cargoes.first()))
                assertEquals(1, w.aircraft.size)
            }
        }
        return Fleet(w, kind, depot, model.cargoes)
    }

    private fun changesAndSaves(kind: Int) {
        val f = fleet(kind) ?: return
        val w = f.world
        val before = w.checksum()
        f.refit(f.cargoes[1])
        assertEquals(f.cargoes[1], choice(f.vehicle()))
        assertTrue(f.vehicle().capacity(f.cargoes[1]) > 0)
        if (kind == Command.CopyVehicle.TRAIN) assertNotEquals(before, w.checksum()) else assertEquals(before, w.checksum())
        assertEquals(48, Save.VERSION)
        val saved = Save.read(Save.write(w))
        assertEquals(f.cargoes[1], choice(f.vehicle(w = saved)))
        assertEquals(f.vehicle().capacity(f.cargoes[1]), f.vehicle(w = saved).capacity(f.cargoes[1]))
        if (kind == Command.CopyVehicle.TRAIN) assertEquals(f.cargoes[1], w.view().trains.single().wagonCargo)
    }

    @Test fun trainRefitsAndSaves() = changesAndSaves(Command.CopyVehicle.TRAIN)
    @Test fun roadVehicleRefitsAndSaves() = changesAndSaves(Command.CopyVehicle.ROAD)
    @Test fun shipRefitsAndSaves() = changesAndSaves(Command.CopyVehicle.SHIP)
    @Test fun aircraftRefitsAndSaves() = changesAndSaves(Command.CopyVehicle.AIRCRAFT)

    @Test
    fun cargoWithoutCapacityIsDumpedWithoutPayment() {
        for (kind in kinds) {
            val f = fleet(kind) ?: continue
            val v = f.vehicle()
            v.cargo[f.cargoes[0]] += Packet(1, 0, 5, 0, credit = 500)
            val retained = Packet(1, 0, 3, 0, credit = 300)
            v.cargo[f.cargoes[1]] += retained
            val money = f.world.companies[0].money
            val books = f.world.companies[0].thisYear.copyOf()
            val profit = v.profitThisYear
            f.refit(f.cargoes[1])
            assertEquals(0, v.capacity(f.cargoes[0]))
            assertTrue(v.cargo[f.cargoes[0]].isEmpty())
            assertSame(retained, v.cargo[f.cargoes[1]].single())
            assertEquals(money, f.world.companies[0].money)
            assertContentEquals(books, f.world.companies[0].thisYear)
            assertEquals(profit, v.profitThisYear)
        }
    }

    @Test
    fun theCurrentCargoKeepsItsPackets() {
        for (kind in kinds) {
            val f = fleet(kind) ?: continue
            val packet = Packet(1, 0, 5, 0, credit = 500)
            f.vehicle().cargo[f.cargoes[0]] += packet
            val before = Save.write(f.world)
            f.refit(f.cargoes[0])
            assertSame(packet, f.vehicle().cargo[f.cargoes[0]].single())
            assertContentEquals(before, Save.write(f.world))
        }
    }

    private fun unchanged(f: Fleet, action: () -> Unit) {
        val before = Save.write(f.world)
        action()
        assertContentEquals(before, Save.write(f.world), "kind ${f.kind}")
    }

    @Test
    fun invalidCargoMissingCargoAndOtherOwnersDoNothing() {
        for (kind in kinds) {
            val f = fleet(kind) ?: continue
            f.vehicle().cargo[f.cargoes[0]] += Packet(1, 0, 5, 0)
            val invalid = (0 until Cargo.COUNT).first { it !in f.cargoes }
            unchanged(f) { f.refit(invalid) }
            unchanged(f) { f.refit(-1) }
            unchanged(f) { f.refit(Cargo.COUNT) }
            unchanged(f) { f.order(Command.TrainOrder.REFIT) }
            f.world.companies += Company(1, "North", 100_000_000)
            unchanged(f) { f.order(Command.TrainOrder.REFIT, intArrayOf(f.cargoes[1]), company = 1) }
        }
    }

    @Test
    fun vehiclesOnTheLineAndWrecksDoNothing() {
        for (kind in kinds) {
            val f = fleet(kind) ?: continue
            f.vehicle().cargo[f.cargoes[0]] += Packet(1, 0, 5, 0)
            outside(f.vehicle(), true)
            unchanged(f) { f.refit(f.cargoes[1]) }
            outside(f.vehicle(), false)
            if (kind != Command.CopyVehicle.SHIP) {
                wreck(f.vehicle())
                unchanged(f) { f.refit(f.cargoes[1]) }
            }
        }
    }

    @Test
    fun sharedOrdersRefitOnlyThisVehicleAndKeepItsOtherState() {
        for (kind in kinds) {
            val f = fleet(kind) ?: continue
            val v = f.vehicle()
            v.orders += listOf(0, 1 or Order.FULL_LOAD)
            v.gap = 4
            v.departed = 123
            when (v) {
                is Train -> { v.name = "Freight"; v.service = false; v.built = -100; v.order = 1 }
                is RoadVehicle -> { v.name = "Freight"; v.service = false; v.built = -100; v.order = 1 }
                is Ship -> { v.name = "Freight"; v.service = false; v.built = -100; v.order = 1 }
                is Aircraft -> { v.name = "Freight"; v.service = false; v.built = -100; v.order = 1 }
            }
            f.copy()
            assertEquals(v.shared, f.vehicle(1).shared)
            assertTrue(v.shared >= 0)
            val before = Save.write(f.world)
            f.refit(f.cargoes[1])
            assertEquals(f.cargoes[1], choice(v))
            assertEquals(f.cargoes[0], choice(f.vehicle(1)))
            assertEquals(listOf(0, 1 or Order.FULL_LOAD), v.orders)
            assertEquals(4, v.gap)
            assertEquals(123L, v.departed)
            assertEquals(1, v.order)
            f.refit(f.cargoes[0])
            assertContentEquals(before, Save.write(f.world))
        }
    }

    @Test
    fun copyKeepsTheRefittedCargo() {
        for (kind in kinds) {
            val f = fleet(kind) ?: continue
            f.refit(f.cargoes[1])
            f.copy()
            assertEquals(f.cargoes[1], choice(f.vehicle(1)))
            assertTrue(f.vehicle(1).capacity(f.cargoes[1]) > 0)
        }
    }

    @Test
    fun engineAndTenderKeepTheirFirstCargoAndPackets() {
        val base = Roster.BASE
        val engine = base.engines.first { it.key == "base:american" }
        val wagon = base.engines.first { it.key == "base:boxcar_steel" }
        // Give the engine and tender loads so their capacity matters during refitting.
        fun loaded(e: Engine, cargoes: List<String>) = Engine(
            e.id, e.key, e.kind, e.names, e.plurals, e.types, e.from, e.until, e.speed, e.power, e.weight,
            e.cost, e.running, e.length, cargoes, 10, e.sprite, e.tender, e.enabled,
        )
        val models = base.engines.toMutableList()
        models[engine.id] = loaded(engine, listOf("goods", "paper"))
        models[engine.tender] = loaded(base[engine.tender], listOf("mail", "grain"))
        val roster = Roster(base.packs, models, base.sprites)
        val w = World(NewGame(64, 64, 1, 1965, towns = 0, industries = 0, weather = false), roster)
        val t = Train(roster, 0, 0, engine.id, wagon.id, 3, 0, Cargo.GOODS)
        w.trains += t
        val goods = Packet(1, 0, 5, 0)
        val mail = Packet(1, 0, 5, 0)
        t.cargo[Cargo.GOODS] += goods
        t.cargo[Cargo.MAIL] += mail
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.REFIT, intArrayOf(Cargo.PAPER)))
        assertEquals(Cargo.PAPER, t.wagonCargo)
        assertEquals(Cargo.GOODS, t.carCargo(t.carEngines.indexOf(engine.id)))
        assertEquals(Cargo.MAIL, t.carCargo(t.carEngines.indexOf(engine.tender)))
        assertEquals(10, t.capacity(Cargo.GOODS))
        assertEquals(10, t.capacity(Cargo.MAIL))
        assertSame(goods, t.cargo[Cargo.GOODS].single())
        assertSame(mail, t.cargo[Cargo.MAIL].single())
        assertEquals(wagon.capacity * 3, t.capacity(Cargo.PAPER))
    }

    @Test
    fun refitUsesTheExistingOrderCodecs() {
        for (command in listOf(
            Command.TrainOrder(0, 7, Command.TrainOrder.REFIT, intArrayOf(Cargo.PAPER)),
            Command.RoadOrder(0, 7, Command.TrainOrder.REFIT, intArrayOf(Cargo.PAPER)),
            Command.CraftOrder(0, false, 7, Command.TrainOrder.REFIT, intArrayOf(Cargo.PAPER)),
            Command.CraftOrder(0, true, 7, Command.TrainOrder.REFIT, intArrayOf(Cargo.PAPER)),
        )) {
            val encoded = Codec.encode(command)
            val back = Codec.decode(encoded)
            assertContentEquals(encoded, Codec.encode(back))
        }
        assertEquals(10, Command.TrainOrder.REFIT)
        assertEquals(20, Command.RoadOrder.TIRES)
        assertEquals(21, Command.CraftOrder.GEAR)
    }
}
