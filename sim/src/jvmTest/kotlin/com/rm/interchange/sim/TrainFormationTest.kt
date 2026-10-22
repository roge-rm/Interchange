package com.rm.interchange.sim

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrainFormationTest {
    private val roster = Roster.BASE
    private fun model(key: String) = roster.engines.first { it.key == "base:$key" }
    private fun car(key: String, cargo: Int = -1) = TrainCar(model(key).id, cargo)

    private fun line(): World {
        val w = World(64, 64, 13, startYear = 1955)
        w.corners.fill(3)
        w.companies[0].money = 100_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 50, 10, Track.X, false))
        w.apply(Command.Station(0, 15, 10, 22, 10, Track.X))
        w.apply(Command.Station(0, 43, 10, 50, 10, Track.X))
        w.apply(Command.BuyTrain(0, 0, model("gp9").id, model("coach_steel").id, 2))
        assertEquals(1, w.trains.size)
        return w
    }

    private fun mixed() = listOf(car("gp9"), car("coach_steel"), car("baggage_steel"), car("boxcar_steel", Cargo.GOODS), car("boxcar_steel", Cargo.PAPER))
    private fun World.edit(cars: List<TrainCar>, company: Int = 0) = apply(Command.EditTrain(company, 0, cars))

    @Test
    fun mixedCarsKeepSeparateCargoChoicesAndContributeTheirFullCostAndWeight() {
        val w = line()
        val t = w.trains[0]!!
        w.edit(mixed())
        assertEquals(mixed(), t.formation)
        assertEquals(Cargo.GOODS, t.carCargo(3))
        assertEquals(Cargo.PAPER, t.carCargo(4))
        assertEquals(model("coach_steel").capacity, t.capacity(Cargo.PASSENGERS))
        assertEquals(model("baggage_steel").capacity, t.capacity(Cargo.MAIL))
        assertEquals(model("boxcar_steel").capacity, t.capacity(Cargo.GOODS))
        assertEquals(model("boxcar_steel").capacity, t.capacity(Cargo.PAPER))
        assertEquals(t.carEngines.sumOf { roster[it].cost }, t.price)
        assertEquals(t.carEngines.sumOf { roster[it].weight }, t.weight)
        assertEquals(t.carEngines.sumOf { roster[it].length } * FIX, t.length)
        assertEquals(t.weight * 1000L, t.massKg())
        assertEquals(mixed(), w.view().trains.single().formation)
    }

    @Test
    fun eachLocomotiveAddsItsOwnPullAndTheSlowestSetsTheSpeedLimit() {
        val w = line()
        val t = w.trains[0]!!
        w.edit(listOf(car("gp9")))
        val pull = t.tractiveForce(2_000, 65)
        val power = model("gp9").power
        w.edit(listOf(car("gp9"), car("gp9")))
        assertEquals(2 * pull, t.tractiveForce(2_000, 65))
        assertEquals(2 * power, t.formation.sumOf { roster[it.model].power })
        val slower = roster.available(1955, pulls = true).first { it.speed < model("gp9").speed && it.traction != Traction.ELECTRIC }
        w.edit(listOf(car("gp9"), TrainCar(slower.id)))
        assertEquals(slower.speed, t.topSpeed)
        assertEquals(slower.speed, w.view().trains.single().topSpeed)
    }

    @Test
    fun tendersStayWithEachLocomotiveAndOnlyLeadingPlowsClearSnow() {
        val w = line()
        val t = w.trains[0]!!
        val steam = roster.available(1955, pulls = true).first { it.tender >= 0 }
        val cars = listOf(car("rotary_plow"), TrainCar(steam.id), car("gp9"), TrainCar(steam.id), car("boxcar_steel"))
        w.edit(cars)
        assertContentEquals(intArrayOf(model("rotary_plow").id, steam.id, steam.tender, model("gp9").id, steam.id, steam.tender, model("boxcar_steel").id), t.carEngines)
        assertEquals(Plows.ROTARY, t.plow)
        w.edit(cars.drop(1) + cars.first())
        assertEquals(Plows.NONE, t.plow)
        assertTrue(t.steam)
    }

    @Test
    fun movingAndRefittingRetainedCarsCostsNothingAndDoesNotRenewTheTrain() {
        val w = line()
        val t = w.trains[0]!!
        w.edit(mixed())
        t.name = "Mixed service"
        t.built = -3650
        t.serviced = -20
        t.shared = t.id
        t.gap = 3
        t.group = 7
        t.orders += listOf(0, 1)
        val before = w.companies[0].money
        val checksum = w.checksum()
        val reordered = mixed().let { listOf(it[0], it[4].copy(cargo = Cargo.GRAIN), it[2], it[1], it[3]) }
        w.edit(reordered)
        assertEquals(reordered, t.formation)
        assertEquals(before, w.companies[0].money)
        assertEquals(-3650, t.built)
        assertEquals(-20, t.serviced)
        assertEquals("Mixed service", t.name)
        assertEquals(7, t.group)
        assertEquals(3, t.gap)
        assertEquals(0, t.shared)
        assertEquals(listOf(0, 1), t.orders)
        assertNotEquals(checksum, w.checksum())
    }

    @Test
    fun addingAndRemovingCarsUsesNewPricesAndDepreciatedSaleValues() {
        val w = line()
        val t = w.trains[0]!!
        val before = w.companies[0].money
        val wagon = model("baggage_steel")
        w.edit(t.formation + TrainCar(wagon.id))
        assertEquals(before - w.price(wagon.cost), w.companies[0].money)
        val bought = w.companies[0].money
        w.edit(t.formation.dropLast(1))
        assertEquals(bought + w.price(wagon.cost) / 2, w.companies[0].money)
    }

    @Test
    fun invalidUnaffordableAndForeignEditsAreAtomic() {
        val w = line()
        val t = w.trains[0]!!
        fun rejected(cars: List<TrainCar>, company: Int = 0) {
            val before = t.formation
            val money = w.companies.map { it.money }
            val checksum = w.checksum()
            w.edit(cars, company)
            assertEquals(before, t.formation)
            assertEquals(money, w.companies.map { it.money })
            assertEquals(checksum, w.checksum())
        }
        rejected(emptyList())
        rejected(listOf(car("coach_steel")))
        rejected(List(MAX_LOCOMOTIVES + 1) { car("gp9") })
        rejected(listOf(car("gp9")) + List(MAX_WAGONS + 1) { car("coach_steel") })
        rejected(listOf(car("gp9"), TrainCar(-1)))
        rejected(listOf(car("gp9"), car("boxcar_steel", Cargo.COAL)))
        val tender = roster.engines.first { it.isTender }
        rejected(listOf(car("gp9"), TrainCar(tender.id)))
        rejected(mixed(), company = 1)
        w.companies[0].money = 0
        rejected(t.formation + car("gp9"))
        t.inDepot = false
        rejected(mixed())
        t.inDepot = true
        t.wreckedFor = 10
        rejected(mixed())
    }

    @Test
    fun loadedCargoCannotBeLostThroughRemovingOrRefittingCars() {
        val w = line()
        w.edit(mixed())
        val t = w.trains[0]!!
        val cargo = Packet(1, 0, 10, 0)
        t.cargo[Cargo.PAPER] += cargo
        val before = t.formation
        w.edit(t.formation.dropLast(1))
        assertEquals(before, t.formation)
        w.edit(t.formation.map { if (it.cargo == Cargo.PAPER) it.copy(cargo = Cargo.GRAIN) else it })
        assertEquals(before, t.formation)
        w.edit(t.formation.reversed())
        assertEquals(before.reversed(), t.formation)
        assertSame(cargo, t.cargo[Cargo.PAPER].single())
    }

    @Test
    fun anElectricHelperRequiresWiresEvenBehindADiesel() {
        val w = line()
        val electric = roster.available(1955, pulls = true).first { it.traction == Traction.ELECTRIC }
        val t = w.trains[0]!!
        val cars = t.formation + TrainCar(electric.id)
        val before = t.formation
        w.edit(cars)
        assertEquals(before, t.formation)
        w.apply(Command.Wire(0, 2, 10, 50, 10, Track.X, false))
        w.edit(cars)
        assertEquals(cars, t.formation)
        assertTrue(t.electric)
        w.apply(Command.Wire(0, 2, 10, 50, 10, Track.X, true))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        assertTrue(t.inDepot)
        assertTrue(w.view().trains.single().needsWires)
    }

    @Test
    fun savingAndCopyingPreserveEveryCarAndRefitAndOldSavesStillLoad() {
        val w = line()
        val legacy = w.trains[0]!!.formation
        val old = Save.read(Save.write(w, version = 45))
        assertEquals(legacy, old.trains[0]!!.formation)
        assertEquals(w.checksum(), old.checksum())
        val cars = mixed() + car("gp9")
        w.edit(cars)
        val back = Save.read(Save.write(w))
        assertEquals(cars, back.trains[0]!!.formation)
        assertEquals(w.checksum(), back.checksum())
        w.apply(Command.CopyVehicle(0, Command.CopyVehicle.TRAIN, 0, 0))
        assertEquals(cars, w.trains[1]!!.formation)
        assertContentEquals(w.trains[0]!!.carEngines, w.trains[1]!!.carEngines)
        assertEquals(w.trains[0]!!.shared, w.trains[1]!!.shared)
        assertEquals(2, w.trains[1]!!.number)
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.RENEW))
        assertEquals(cars, w.trains[0]!!.formation)
        val replacement = roster.available(1955, pulls = true).first { it.id != model("gp9").id && it.traction != Traction.ELECTRIC }
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.REPLACE, intArrayOf(replacement.id)))
        assertEquals(TrainCar(replacement.id), w.trains[0]!!.formation.first())
        assertEquals(cars.drop(1), w.trains[0]!!.formation.drop(1))
    }

    @Test
    fun newerPacksRemapEveryCarByKey() {
        val w = line()
        w.edit(mixed() + car("gp9"))
        val saved = Save.write(w)
        val base = Packs.BASE
        val newer = Pack(base.id, base.version + 1, base.names, base.descriptions, base.author, base.licence,
            base.needs, base.disables, base.vehicles.reversed(), base.houses, base.industries, base.sprites, base.hash + 1)
        val loaded = Save.read(saved) { newer }
        val before = w.trains[0]!!
        val after = loaded.trains[0]!!
        assertEquals(before.formation.map { roster[it.model].key to it.cargo }, after.formation.map { loaded.roster[it.model].key to it.cargo })
        assertEquals(before.topSpeed, after.topSpeed)
        for (cargo in 0 until Cargo.COUNT) assertEquals(before.capacity(cargo), after.capacity(cargo))
    }

    @Test
    fun retiredStockCanBeMovedAndRefittedButNoMoreCanBeBought() {
        val w = line()
        w.edit(mixed())
        w.tick = Calendar.TICKS_PER_DAY * 365L * 40
        val t = w.trains[0]!!
        val reordered = t.formation.reversed()
        w.edit(reordered)
        assertEquals(reordered, t.formation)
        w.edit(reordered + car("gp9"))
        assertEquals(reordered, t.formation)
    }

    @Test
    fun aMixedTrainRunsAndStaysInStepAfterANetworkCommandAndSave() {
        val w = line()
        val command = Command.EditTrain(0, 0, mixed() + car("gp9"))
        val decoded = Codec.decode(Codec.encode(command))
        assertEquals(command, decoded)
        w.apply(decoded)
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        System.getProperty("interchange.formationPreview")?.let { path ->
            File(path).apply { parentFile.mkdirs() }.writeBytes(Save.write(w))
        }
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        repeat(1_000) { w.step() }
        val back = Save.read(Save.write(w))
        val calls = HashSet<Int>()
        repeat(30_000) {
            w.step(); back.step()
            val t = w.trains[0]!!
            if (t.waiting > 0) t.steps.lastOrNull()?.let { s -> w.stationAt(stepTile(s))?.id?.let { calls += it } }
        }
        assertEquals(setOf(0, 1), calls)
        assertEquals(w.checksum(), back.checksum())
        assertFalse(w.trains[0]!!.inDepot)
    }

    @Test
    fun commandDecoderRejectsOversizedAndTruncatedFormations() {
        val bytes = ByteWriter(32)
        bytes.byte(62); bytes.int(0); bytes.int(0); bytes.uvar(MAX_TRAIN_CARS + 1L)
        assertFailsWith<BadData> { Codec.decode(bytes.toByteArray()) }
        assertFailsWith<BadData> { Codec.decode(Codec.encode(Command.EditTrain(0, 0, mixed())).dropLast(1).toByteArray()) }
    }
}
