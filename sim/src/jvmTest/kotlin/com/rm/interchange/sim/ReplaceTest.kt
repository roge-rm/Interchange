package com.rm.interchange.sim

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReplaceTest {
    private val kinds = listOf(Command.CopyVehicle.TRAIN, Command.CopyVehicle.ROAD, Command.CopyVehicle.SHIP, Command.CopyVehicle.AIRCRAFT)

    private fun model(v: Carrier): Int = when (v) {
        is Train -> v.engine
        is RoadVehicle -> v.model
        is Ship -> v.model
        is Aircraft -> v.model
        else -> error("unknown vehicle")
    }

    private fun changed(
        e: Engine, cost: Long = e.cost, from: Int = 1800, until: Int = 2100, enabled: Boolean = true,
        cargoes: List<String> = listOf("goods", "paper"), capacity: Int = 40, traction: Int = e.traction,
        runway: Int = Runways.REGIONAL,
    ) = Engine(
        e.id, e.key, e.kind, e.names, e.plurals, e.types, from, until, e.speed, e.power, e.weight,
        cost, e.running, e.length, cargoes, capacity, e.sprite, e.tender, enabled,
        traction = traction, reliability = 93, runway = runway,
    )

    private class Fleet(val world: World, val kind: Int, val depot: Int, val oldModel: Int, val newModel: Int, val wagon: Int) {
        val company get() = world.companies[0]
        fun vehicles(w: World = world): List<Carrier?> = when (kind) {
            Command.CopyVehicle.TRAIN -> w.trains
            Command.CopyVehicle.ROAD -> w.roadVehicles
            Command.CopyVehicle.SHIP -> w.ships
            else -> w.aircraft
        }
        fun vehicle(id: Int = 0, w: World = world) = vehicles(w)[id]!!
        fun order(what: Int, values: IntArray = intArrayOf(), company: Int = 0) {
            world.apply(when (kind) {
                Command.CopyVehicle.TRAIN -> Command.TrainOrder(company, 0, what, values)
                Command.CopyVehicle.ROAD -> Command.RoadOrder(company, 0, what, values)
                else -> Command.CraftOrder(company, kind == Command.CopyVehicle.AIRCRAFT, 0, what, values)
            })
        }
        fun replace(model: Int = newModel, company: Int = 0) = order(Command.TrainOrder.REPLACE, intArrayOf(model), company)
        fun value(v: Carrier = vehicle()) = when (v) {
            is Train -> world.value(v)
            is RoadVehicle -> world.roadValue(v)
            is Ship -> world.craftValue(v.price, v.built, v.info.life)
            is Aircraft -> world.craftValue(v.price, v.built, v.info.life)
            else -> error("unknown vehicle")
        }
        fun price(v: Carrier = vehicle()) = when (v) {
            is Train -> world.price(v.price)
            else -> world.price(world.roster[model(v)].cost)
        }
        private fun model(v: Carrier) = when (v) {
            is RoadVehicle -> v.model
            is Ship -> v.model
            is Aircraft -> v.model
            else -> error("unknown vehicle")
        }
    }

    private fun fleet(
        kind: Int, cost: Long = 30_000, from: Int = 1800, until: Int = 2100, enabled: Boolean = true,
        cargoes: List<String> = listOf("goods", "paper"), capacity: Int = 40,
        electric: Boolean = false, runway: Int = Runways.REGIONAL, plow: Boolean = false,
    ): Fleet {
        val base = Roster.BASE
        val key = when (kind) {
            Command.CopyVehicle.TRAIN -> "base:gp9"
            Command.CopyVehicle.ROAD -> "base:semi_van"
            Command.CopyVehicle.SHIP -> "base:steam_laker"
            else -> "base:dc3"
        }
        val old = base.engines.first { it.key == key }
        val next = if (kind == Command.CopyVehicle.TRAIN) base.engines.first { it.key == "base:american" }
            else base.engines.first { it.kind == old.kind && it.id != old.id }
        val wagon = base.engines.first { if (plow) it.plow != Plows.NONE else it.key == "base:boxcar_50" }
        val models = base.engines.toMutableList()
        models[old.id] = changed(old, cost = 40_000)
        models[next.id] = changed(next, cost, from, until, enabled, cargoes, capacity, if (electric) Traction.ELECTRIC else Traction.DIESEL, runway)
        if (next.tender >= 0) models[next.tender] = changed(base[next.tender], cost = 2_000, cargoes = listOf("mail"))
        val roster = Roster(base.packs, models, base.sprites)
        val w = World(NewGame(64, 64, 1, 1965, towns = 0, industries = 0, weather = false), roster)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        var depot = 0
        when (kind) {
            Command.CopyVehicle.TRAIN -> w.apply(Command.Depot(0, 2, 10, Track.XP))
            Command.CopyVehicle.ROAD -> {
                w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
                w.apply(Command.Garage(0, 5, 23, Track.YM))
            }
            Command.CopyVehicle.SHIP -> {
                for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 10..50 && y in 10..30) 0 else 1
                w.shoreChanged()
                w.apply(Command.Shipyard(0, 9, 15))
            }
            else -> {
                w.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
                depot = w.airports[0]!!.depot
            }
        }
        w.apply(when (kind) {
            Command.CopyVehicle.TRAIN -> Command.BuyTrain(0, depot, old.id, wagon.id, 3, Cargo.GOODS)
            Command.CopyVehicle.ROAD -> Command.BuyRoadVehicle(0, depot, old.id, Cargo.GOODS)
            else -> Command.BuyCraft(0, depot, old.id, Cargo.GOODS)
        })
        val f = Fleet(w, kind, depot, old.id, next.id, wagon.id)
        assertEquals(1, f.vehicles().size)
        w.tick = 3650L * Calendar.TICKS_PER_DAY
        return f
    }

    // Formation and its derived values are rebuilt for the new model, so seed only the operating state.
    private val formationFields = setOf("formation", "locomotives", "carEngines", "carCargoes", "plow", "length", "electric", "steam", "life", "newReliability", "topSpeed", "dieselSpeed")
    private fun fields(v: Carrier) = v.javaClass.declaredFields.filter {
        !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) && (v !is Train || it.name !in formationFields)
    }.onEach { it.isAccessible = true }

    private fun seed(v: Carrier) {
        for (f in fields(v)) when (f.type) {
            Int::class.javaPrimitiveType -> f.setInt(v, 3)
            Long::class.javaPrimitiveType -> f.setLong(v, 123)
            Boolean::class.javaPrimitiveType -> f.setBoolean(v, true)
            String::class.java -> f.set(v, "Freight")
            IntArray::class.java -> f.set(v, intArrayOf(17, 29))
        }
        set(v, "owner", 0)
        set(v, "depot", 0)
        set(v, "built", 0)
        set(v, "order", 1)
        set(v, "wreckedFor", 0)
        v.orders += listOf(0, 1 or Order.FULL_LOAD)
    }

    private fun set(v: Carrier, name: String, value: Any) {
        fields(v).firstOrNull { it.name == name }?.set(v, value)
    }

    private fun preservesState(kind: Int) {
        val f = fleet(kind)
        f.world.apply(Command.CopyVehicle(0, kind, 0, f.depot))
        val old = f.vehicle()
        seed(old)
        old.shared = 0
        val partner = f.vehicle(1)
        partner.shared = 0
        partner.orders += listOf(0, 1 or Order.FULL_LOAD)
        val partnerOrders = partner.orders.toList()
        val backup = OrderBackup(arrayListOf(9), 7, 1)
        f.company.orderBackups[kind] = backup
        val money = f.company.money
        val book = f.company.thisYear[Books.NEW_TRAINS]
        val value = f.value()
        f.replace()
        val made = f.vehicle()
        assertNotSame(old, made)
        assertEquals(f.newModel, model(made))
        assertEquals(old.id, made.id)
        assertEquals(2, f.vehicles().size)
        assertEquals(old.orders, made.orders)
        assertNotSame(old.orders, made.orders)
        for (field in fields(old)) {
            val name = field.name
            val actual = field.get(made)
            when (name) {
                "built", "serviced" -> assertEquals(f.world.workDay, actual, name)
                "reliability" -> assertEquals(9300, actual, name)
                "cargoChoice" -> assertEquals(Cargo.GOODS, actual, name)
                else -> if (actual is IntArray) assertContentEquals(field.get(old) as IntArray, actual, name)
                    else assertEquals(field.get(old), actual, name)
            }
        }
        if (made is Train) {
            assertEquals(f.wagon, made.wagon)
            assertEquals(3, made.wagons)
            assertTrue(made.steps.isEmpty())
            assertEquals(f.world.roster[made.engine].tender, made.carEngines[1])
        }
        assertSame(partner, f.vehicle(1))
        assertEquals(f.oldModel, model(partner))
        assertEquals(partnerOrders, partner.orders)
        assertSame(backup, f.company.orderBackups[kind])
        val cost = f.price() - value
        assertEquals(money - cost, f.company.money)
        assertEquals(book - cost, f.company.thisYear[Books.NEW_TRAINS])
    }

    @Test fun trainKeepsItsStateAndWagons() = preservesState(Command.CopyVehicle.TRAIN)
    @Test fun roadVehicleKeepsItsState() = preservesState(Command.CopyVehicle.ROAD)
    @Test fun shipKeepsItsStateAndPosition() = preservesState(Command.CopyVehicle.SHIP)
    @Test fun aircraftKeepsItsStateAndFlight() = preservesState(Command.CopyVehicle.AIRCRAFT)

    private fun refused(f: Fleet, action: () -> Unit) {
        val old = f.vehicle()
        val money = f.company.money
        val books = f.company.thisYear.copyOf()
        val backup = OrderBackup(arrayListOf(9), 7)
        f.company.orderBackups[f.kind] = backup
        val before = fields(old).associate { it.name to it.get(old) }
        val orders = old.orders.toList()
        f.world.linksDirty = false
        action()
        assertEquals(false, f.world.linksDirty)
        assertSame(old, f.vehicle())
        assertEquals(money, f.company.money)
        assertContentEquals(books, f.company.thisYear)
        assertSame(backup, f.company.orderBackups[f.kind])
        assertEquals(orders, old.orders)
        for (field in fields(old)) assertEquals(before[field.name], field.get(old), field.name)
    }

    @Test
    fun invalidModelsDoNothing() {
        for (kind in kinds) {
            val f = fleet(kind)
            refused(f) { f.replace(f.oldModel) }
            refused(f) { f.replace(-1) }
            refused(f) { f.replace(Int.MAX_VALUE) }
            refused(f) { f.order(Command.TrainOrder.REPLACE) }
            for (wrong in VehicleKind.entries.filter { it != f.world.roster[f.oldModel].kind }) {
                val id = f.world.roster.engines.first { it.kind == wrong }.id
                refused(f) { f.replace(id) }
            }
            val year = Calendar.year(f.world.startYear, f.world.day)
            for (offSale in listOf(fleet(kind, enabled = false), fleet(kind, from = year + 1), fleet(kind, until = year))) {
                refused(offSale) { offSale.replace() }
            }
        }
    }

    @Test
    fun outsideWreckedAndForeignVehiclesDoNothing() {
        for (kind in kinds) {
            val f = fleet(kind)
            set(f.vehicle(), "inDepot", false)
            refused(f) { f.replace() }
            set(f.vehicle(), "inDepot", true)
            if (kind != Command.CopyVehicle.SHIP) {
                set(f.vehicle(), "wreckedFor", 5)
                refused(f) { f.replace() }
                set(f.vehicle(), "wreckedFor", 0)
            }
            f.world.companies += Company(1, "North", 100_000_000)
            refused(f) { f.replace(company = 1) }
            set(f.vehicle(), "owner", 1)
            refused(f) { f.replace() }
        }
    }

    @Test
    fun tooLittleMoneyChangesNothing() {
        for (kind in kinds) {
            val f = fleet(kind)
            val base = if (kind == Command.CopyVehicle.TRAIN) Train(f.world.roster, 0, 0, f.newModel, f.wagon, 3, f.depot).price
                else f.world.roster[f.newModel].cost
            val cost = f.world.price(base) - f.value()
            assertTrue(cost > 0)
            f.company.money = cost - 1
            refused(f) { f.replace() }
            f.company.money = cost
            f.replace()
            assertEquals(f.newModel, model(f.vehicle()))
            assertEquals(0L, f.company.money)
        }
    }

    @Test
    fun cheaperModelsRefundTheDifference() {
        for (kind in kinds) {
            val f = fleet(kind, cost = 1)
            f.world.tick = 0
            val value = f.value()
            val money = f.company.money
            val book = f.company.thisYear[Books.NEW_TRAINS]
            f.replace()
            val cost = f.price() - value
            assertTrue(cost < 0)
            assertEquals(money - cost, f.company.money)
            assertEquals(book - cost, f.company.thisYear[Books.NEW_TRAINS])
        }
    }

    @Test
    fun electricEngineNeedsWires() {
        val f = fleet(Command.CopyVehicle.TRAIN, electric = true)
        refused(f) { f.replace() }
    }

    @Test
    fun aircraftNeedsAnAirportThatServesIt() {
        val f = fleet(Command.CopyVehicle.AIRCRAFT, runway = Runways.INTERNATIONAL)
        refused(f) { f.replace() }
    }

    @Test
    fun compatibleCargoStaysAndIncompatibleCargoIsDumpedWithoutPayment() {
        for (kind in kinds) {
            val f = fleet(kind, cargoes = listOf("paper", "goods"))
            set(f.vehicle(), "cargoChoice", Cargo.GOODS)
            val kept = Packet(5, 0, 9, 1)
            f.vehicle().cargo[Cargo.GOODS] += kept
            f.vehicle().cargo[Cargo.PASSENGERS] += Packet(20, 0, 9, 1)
            val value = f.value()
            val money = f.company.money
            f.replace()
            assertSame(kept, f.vehicle().cargo[Cargo.GOODS].single())
            assertTrue(f.vehicle().cargo[Cargo.PASSENGERS].isEmpty())
            assertEquals(money - (f.price() - value), f.company.money)
        }
    }

    @Test
    fun cargoChoiceFallsBackOrKeepsAutomaticChoice() {
        for (kind in kinds.filter { it != Command.CopyVehicle.TRAIN }) for (choice in listOf(-1, Cargo.GOODS)) {
            val f = fleet(kind, cargoes = listOf("mail", "paper"))
            set(f.vehicle(), "cargoChoice", choice)
            f.vehicle().cargo[Cargo.GOODS] += Packet(5, 0, 9, 1)
            f.replace()
            val actual = fields(f.vehicle()).first { it.name == "cargoChoice" }.get(f.vehicle())
            assertEquals(if (choice == -1) -1 else Cargo.MAIL, actual)
            assertTrue(f.vehicle().cargo[Cargo.GOODS].isEmpty())
        }
        for (kind in kinds.filter { it != Command.CopyVehicle.TRAIN }) {
            val f = fleet(kind, cargoes = emptyList())
            f.replace()
            assertEquals(-1, fields(f.vehicle()).first { it.name == "cargoChoice" }.get(f.vehicle()))
        }
    }

    @Test
    fun zeroCapacityDumpsCargo() {
        for (kind in kinds) {
            val f = fleet(kind, cargoes = listOf("mail"), capacity = 0, plow = kind == Command.CopyVehicle.TRAIN)
            f.vehicle().cargo[Cargo.GOODS] += Packet(5, 0, 9, 1)
            val value = f.value()
            val money = f.company.money
            f.replace()
            assertTrue(f.vehicle().cargo[Cargo.GOODS].isEmpty())
            assertEquals(money - (f.price() - value), f.company.money)
        }
    }

    @Test
    fun trainUsesNewEngineAndTenderCargoAndKeepsPlows() {
        for (plow in listOf(false, true)) {
            val f = fleet(Command.CopyVehicle.TRAIN, cargoes = listOf("paper"), plow = plow)
            val old = f.vehicle() as Train
            old.wagonCargo = Cargo.GOODS
            val goods = Packet(5, 0, 9, 1)
            val mail = Packet(5, 0, 9, 1)
            old.cargo[Cargo.GOODS] += goods
            old.cargo[Cargo.MAIL] += mail
            val value = f.value()
            val money = f.company.money
            f.replace()
            val made = f.vehicle() as Train
            assertEquals(f.wagon, made.wagon)
            assertEquals(3, made.wagons)
            assertEquals(Cargo.GOODS, made.wagonCargo)
            assertEquals(40, made.capacity(Cargo.PAPER))
            assertEquals(40, made.capacity(Cargo.MAIL))
            assertSame(mail, made.cargo[Cargo.MAIL].single())
            if (plow) {
                assertTrue(made.cargo[Cargo.GOODS].isEmpty())
                assertEquals(listOf(f.wagon, f.wagon, f.wagon, f.newModel, made.engineInfo.tender), made.carEngines.toList())
            } else assertSame(goods, made.cargo[Cargo.GOODS].single())
            assertEquals(money - (f.price() - value), f.company.money)
        }
    }

    @Test
    fun renewStillRebuildsTheSameModel() {
        for (kind in kinds) {
            val f = fleet(kind)
            set(f.vehicle(), "reliability", 100)
            val price = f.price()
            val value = f.value()
            val money = f.company.money
            f.order(Command.TrainOrder.RENEW)
            assertEquals(f.oldModel, model(f.vehicle()))
            assertEquals(f.world.workDay, fields(f.vehicle()).first { it.name == "built" }.get(f.vehicle()))
            assertEquals(9300, fields(f.vehicle()).first { it.name == "reliability" }.get(f.vehicle()))
            assertEquals(money - (price - value), f.company.money)
        }
    }

    @Test
    fun saveKeepsTheReplacementModel() {
        assertEquals(49, Save.VERSION)
        for (kind in kinds) {
            val f = fleet(kind)
            f.replace()
            val loaded = Save.read(Save.write(f.world))
            assertEquals(f.newModel, model(f.vehicle(w = loaded)))
            assertEquals(f.vehicles().size, f.vehicles(loaded).size)
        }
    }

    @Test
    fun editorReplacementIsFree() {
        for (kind in kinds) {
            val f = fleet(kind)
            f.world.editor = true
            f.company.money = 0
            val books = f.company.thisYear.copyOf()
            f.replace()
            assertEquals(f.newModel, model(f.vehicle()))
            assertEquals(0L, f.company.money)
            assertContentEquals(books, f.company.thisYear)
        }
    }

    @Test
    fun replaceUsesExistingOrderCodecs() {
        assertEquals(11, Command.TrainOrder.REPLACE)
        for (command in listOf(
            Command.TrainOrder(0, 7, Command.TrainOrder.REPLACE, intArrayOf(42)),
            Command.RoadOrder(0, 7, Command.TrainOrder.REPLACE, intArrayOf(42)),
            Command.CraftOrder(0, false, 7, Command.TrainOrder.REPLACE, intArrayOf(42)),
            Command.CraftOrder(0, true, 7, Command.TrainOrder.REPLACE, intArrayOf(42)),
        )) {
            val encoded = Codec.encode(command)
            val back = Codec.decode(encoded)
            assertContentEquals(encoded, Codec.encode(back))
            when (back) {
                is Command.TrainOrder -> { assertEquals(11, back.what); assertContentEquals(intArrayOf(42), back.stations) }
                is Command.RoadOrder -> { assertEquals(11, back.what); assertContentEquals(intArrayOf(42), back.stations) }
                is Command.CraftOrder -> { assertEquals(11, back.what); assertContentEquals(intArrayOf(42), back.stations) }
                else -> error("wrong command")
            }
        }
    }
}
