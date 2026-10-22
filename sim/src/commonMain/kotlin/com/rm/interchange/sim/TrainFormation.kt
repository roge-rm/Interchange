package com.rm.interchange.sim

/** A locomotive or wagon and its cargo choice. Tenders stay with their locomotive. */
data class TrainCar(val model: Int, val cargo: Int = -1)

const val MAX_LOCOMOTIVES = 4
const val MAX_TRAIN_CARS = MAX_WAGONS + MAX_LOCOMOTIVES

internal fun legacyFormation(roster: Roster, engine: Int, wagon: Int, wagons: Int, cargo: Int): List<TrainCar> = buildList {
    val plows = roster[wagon].plow != Plows.NONE
    if (plows) repeat(wagons) { add(TrainCar(wagon, cargo)) }
    add(TrainCar(engine))
    if (!plows) repeat(wagons) { add(TrainCar(wagon, cargo)) }
}

internal fun formationModels(roster: Roster, cars: List<TrainCar>): IntArray = buildList {
    for (car in cars) {
        add(car.model)
        roster[car.model].tender.takeIf { it >= 0 }?.let { add(it) }
    }
}.toIntArray()

internal fun formationCargoes(roster: Roster, cars: List<TrainCar>): IntArray = buildList {
    for (car in cars) {
        val model = roster[car.model]
        add(if (car.cargo in model.cargoes) car.cargo else model.cargoes.firstOrNull() ?: -1)
        roster.getOrNull(model.tender)?.let { add(it.cargoes.firstOrNull() ?: -1) }
    }
}.toIntArray()

fun validTrainFormation(roster: Roster, cars: List<TrainCar>): Boolean {
    if (cars.size !in 1..MAX_TRAIN_CARS) return false
    var engines = 0
    var wagons = 0
    for (car in cars) {
        val model = roster.getOrNull(car.model) ?: return false
        when (model.kind) {
            VehicleKind.ENGINE -> engines++
            VehicleKind.WAGON -> wagons++
            else -> return false
        }
        if (car.cargo != -1 && car.cargo !in model.cargoes) return false
    }
    return engines in 1..MAX_LOCOMOTIVES && wagons <= MAX_WAGONS
}

/** Charges only for added stock and credits removed stock at the train's age. Moving or refitting cars is free. */
fun trainFormationCost(roster: Roster, before: List<TrainCar>, after: List<TrainCar>, age: Int, price: (Long) -> Long): Long {
    val counts = before.groupingBy { it.model }.eachCount().toMutableMap()
    var bought = 0L
    for (car in after) {
        val had = counts[car.model] ?: 0
        if (had > 0) counts[car.model] = had - 1
        else roster[car.model].let { bought += it.cost + (roster.getOrNull(it.tender)?.cost ?: 0) }
    }
    val sold = counts.entries.sumOf { (id, n) ->
        val model = roster[id]
        val factor = maxOf(10, 100 - 100 * age / model.life.coerceAtLeast(1))
        price((model.cost + (roster.getOrNull(model.tender)?.cost ?: 0)) * n) * factor / 200
    }
    return price(bought) - sold
}

internal fun World.editTrain(company: Company, c: Command.EditTrain) {
    val train = trains.getOrNull(c.train) ?: return
    if (train.owner != company.id || !train.inDepot || train.wreckedFor > 0 || !validTrainFormation(roster, c.cars)) return
    val depot = depots.getOrNull(train.depot) ?: return
    if (depot.owner != company.id || depot.kind != Depots.RAIL) return
    val kept = train.formation.groupingBy { it.model }.eachCount().toMutableMap()
    for (car in c.cars) {
        val count = kept[car.model] ?: 0
        if (count > 0) kept[car.model] = count - 1
        else {
            val model = roster[car.model]
            if (!onSale(model) || (model.pulls && model.traction == Traction.ELECTRIC && !depotWired(depot))) return
        }
    }
    val draft = Train(roster, train.id, train.owner, train.engine, train.wagon, 0, train.depot, formation = c.cars)
    // Cars may be changed with cargo aboard only when every packet still fits.
    if ((0 until Cargo.COUNT).any { train.load(it) > draft.capacity(it) }) return
    val cost = trainFormationCost(roster, train.formation, c.cars, age(train), ::price)
    if (cost > 0 && cost > company.money) return
    if (cost >= 0) spend(company, Books.NEW_TRAINS, cost) else earn(company, Books.TRAINS_SOLD, -cost)
    train.setFormation(c.cars)
    train.reliability = minOf(train.reliability, train.newReliability)
    train.route = null
    train.renewFailed = false
    linksDirty = true
}

internal fun writeFormation(w: ByteWriter, cars: List<TrainCar>) {
    w.uvar(cars.size.toLong())
    for (car in cars) { w.int(car.model); w.int(car.cargo) }
}

internal fun readFormation(r: ByteReader): List<TrainCar> {
    val count = r.uvar()
    if (count !in 1L..MAX_TRAIN_CARS.toLong()) throw BadData("invalid train formation size")
    return List(count.toInt()) { TrainCar(r.int(), r.int()) }
}
