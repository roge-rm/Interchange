package com.rm.interchange.sim

private fun World.replacementModel(id: Int?, kind: VehicleKind): Engine? {
    val e = roster.getOrNull(id ?: return null) ?: return null
    val year = Calendar.year(startYear, day)
    return e.takeIf { it.enabled && it.kind == kind && year >= it.from && year < it.until }
}

private fun replacementCargo(choice: Int, e: Engine) =
    if (choice == -1 || choice in e.cargoes) choice else e.cargoes.firstOrNull() ?: -1

private fun Carrier.copyLoadAndOrders(old: Carrier) {
    orders.addAll(old.orders)
    for (c in cargo.indices) if (capacity(c) > 0) cargo[c].addAll(old.cargo[c])
}

/** [t]'s cars with its lead engine made [engine]. */
internal fun leadSwapped(t: Train, engine: Int): List<TrainCar> {
    val cars = t.formation.toMutableList()
    val lead = cars.indexOfFirst { t.roster[it.model].pulls }
    cars[lead] = TrainCar(engine)
    return cars
}

// Models and train lengths are fixed at construction, so rebuild in the same slot.
// The buy functions would consume order backups and must not be used here.
internal fun World.replaceTrain(company: Company, old: Train, model: Int?) {
    if (!old.inDepot) return
    val e = replacementModel(model, VehicleKind.ENGINE) ?: return
    if (e.id == old.engine) return
    depots.getOrNull(old.depot) ?: return
    val cars = leadSwapped(old, e.id)
    if (trainWhy(old, cars) != UpgradeWhy.OK) return
    val made = rebuildTrain(old, cars)
    val cost = price(made.price) - value(old)
    if (company.money < cost) return
    trains[old.id] = made
    spend(company, Books.NEW_TRAINS, cost)
    refreshUpgrade(made)
    linksDirty = true
}

/** [old] rebuilt new as a train of [cars], keeping all it was doing. */
internal fun World.rebuildTrain(old: Train, cars: List<TrainCar>): Train {
    val made = Train(roster, old.id, old.owner, old.engine, old.wagon, 0, old.depot, formation = cars)
    made.owner = old.owner
    made.depot = old.depot
    made.name = old.name
    made.number = old.number
    made.inDepot = old.inDepot
    made.running = old.running
    made.waiting = old.waiting
    made.nose = old.nose
    made.speed = old.speed
    made.profitThisYear = old.profitThisYear
    made.profitLastYear = old.profitLastYear
    made.group = old.group
    made.shared = old.shared
    made.gap = old.gap
    made.departed = old.departed
    made.leftFrom = old.leftFrom
    made.leftAt = old.leftAt
    made.heldTicks = old.heldTicks
    made.orderLeft = old.orderLeft
    made.orderLeftAt = old.orderLeftAt
    made.orderReachedAt = old.orderReachedAt
    made.late = old.late
    made.order = old.order
    made.toDepot = old.toDepot
    made.route = old.route
    made.routeAt = old.routeAt
    made.turnRound = old.turnRound
    made.turnIn = old.turnIn
    made.choseAt = old.choseAt
    made.leave = old.leave
    made.lostUntil = old.lostUntil
    made.snowbound = old.snowbound
    made.dugDays = old.dugDays
    made.headOn = old.headOn
    made.stuck = old.stuck
    made.aside = old.aside
    made.asideTicks = old.asideTicks
    made.held = old.held
    made.curveKmh = old.curveKmh
    made.signalled = old.signalled
    made.wreckedFor = old.wreckedFor
    made.built = old.built
    made.serviced = old.serviced
    made.reliability = old.reliability
    made.breakdowns = old.breakdowns
    made.brokenFor = old.brokenFor
    made.servicing = old.servicing
    made.service = old.service
    made.noDepotUntil = old.noDepotUntil
    made.renewAsked = old.renewAsked
    made.renewFailed = old.renewFailed
    made.renewOff = old.renewOff
    made.upgradeWhy = old.upgradeWhy
    made.upgradeTold = old.upgradeTold
    made.onTiles = old.onTiles
    made.onPieces = old.onPieces
    made.onBlocks = old.onBlocks
    made.copyLoadAndOrders(old)
    made.built = workDay
    made.serviced = workDay
    made.reliability = made.newReliability
    return made
}

internal fun World.replaceRoadVehicle(company: Company, old: RoadVehicle, model: Int?) {
    if (!old.inDepot) return
    val e = replacementModel(model, VehicleKind.ROAD) ?: return
    if (e.id == old.model || roadWhy(old, e) != UpgradeWhy.OK) return
    val cost = price(e.cost) - roadValue(old)
    if (company.money < cost) return
    val made = rebuildRoadVehicle(old, e)
    roadVehicles[old.id] = made
    spend(company, Books.NEW_TRAINS, cost)
    refreshUpgrade(made)
    linksDirty = true
}

internal fun World.rebuildRoadVehicle(old: RoadVehicle, e: Engine): RoadVehicle {
    val made = RoadVehicle(roster, old.id, old.owner, e.id, old.depot, old.cargoChoice)
    made.owner = old.owner
    made.depot = old.depot
    made.cargoChoice = old.cargoChoice
    made.name = old.name
    made.number = old.number
    made.group = old.group
    made.shared = old.shared
    made.gap = old.gap
    made.departed = old.departed
    made.leftFrom = old.leftFrom
    made.leftAt = old.leftAt
    made.heldTicks = old.heldTicks
    made.orderLeft = old.orderLeft
    made.orderLeftAt = old.orderLeftAt
    made.orderReachedAt = old.orderReachedAt
    made.late = old.late
    made.inDepot = old.inDepot
    made.running = old.running
    made.waiting = old.waiting
    made.at = old.at
    made.along = old.along
    made.leave = old.leave
    made.speed = old.speed
    made.profitThisYear = old.profitThisYear
    made.profitLastYear = old.profitLastYear
    made.order = old.order
    made.toDepot = old.toDepot
    made.route = old.route
    made.routeAt = old.routeAt
    made.lostUntil = old.lostUntil
    made.built = old.built
    made.serviced = old.serviced
    made.reliability = old.reliability
    made.breakdowns = old.breakdowns
    made.brokenFor = old.brokenFor
    made.servicing = old.servicing
    made.service = old.service
    made.noDepotUntil = old.noDepotUntil
    made.renewAsked = old.renewAsked
    made.renewFailed = old.renewFailed
    made.renewOff = old.renewOff
    made.upgradeWhy = old.upgradeWhy
    made.upgradeTold = old.upgradeTold
    made.tires = old.tires
    made.seasonalTires = old.seasonalTires
    made.slid = old.slid
    made.wreckedFor = old.wreckedFor
    made.cargoChoice = replacementCargo(old.cargoChoice, e)
    made.copyLoadAndOrders(old)
    made.built = workDay
    made.serviced = workDay
    made.reliability = e.reliability * 100
    return made
}

internal fun World.replaceShip(company: Company, old: Ship, model: Int?) {
    if (!old.inDepot) return
    val e = replacementModel(model, VehicleKind.SHIP) ?: return
    if (e.id == old.model) return
    val cost = craftSwapCost(old.price, old.built, old.info.life, e)
    if (company.money < cost) return
    val made = rebuildShip(old, e)
    ships[old.id] = made
    spend(company, Books.NEW_TRAINS, cost)
    refreshUpgrade(made)
    linksDirty = true
}

internal fun World.rebuildShip(old: Ship, e: Engine): Ship {
    val made = Ship(roster, old.id, old.owner, e.id, old.depot, old.cargoChoice)
    made.owner = old.owner
    made.depot = old.depot
    made.cargoChoice = old.cargoChoice
    made.name = old.name
    made.number = old.number
    made.group = old.group
    made.shared = old.shared
    made.gap = old.gap
    made.departed = old.departed
    made.leftFrom = old.leftFrom
    made.leftAt = old.leftAt
    made.heldTicks = old.heldTicks
    made.orderLeft = old.orderLeft
    made.orderLeftAt = old.orderLeftAt
    made.orderReachedAt = old.orderReachedAt
    made.late = old.late
    made.renewAsked = old.renewAsked
    made.renewFailed = old.renewFailed
    made.renewOff = old.renewOff
    made.upgradeWhy = old.upgradeWhy
    made.upgradeTold = old.upgradeTold
    made.inDepot = old.inDepot
    made.running = old.running
    made.waiting = old.waiting
    made.x = old.x
    made.y = old.y
    made.heading = old.heading
    made.speed = old.speed
    made.lockWait = old.lockWait
    made.fogged = old.fogged
    made.profitThisYear = old.profitThisYear
    made.profitLastYear = old.profitLastYear
    made.order = old.order
    made.toDepot = old.toDepot
    made.route = old.route
    made.routeAt = old.routeAt
    made.lostUntil = old.lostUntil
    made.built = old.built
    made.serviced = old.serviced
    made.reliability = old.reliability
    made.breakdowns = old.breakdowns
    made.brokenFor = old.brokenFor
    made.servicing = old.servicing
    made.service = old.service
    made.noDepotUntil = old.noDepotUntil
    made.cargoChoice = replacementCargo(old.cargoChoice, e)
    made.copyLoadAndOrders(old)
    made.built = workDay
    made.serviced = workDay
    made.reliability = e.reliability * 100
    return made
}

internal fun World.replaceAircraft(company: Company, old: Aircraft, model: Int?) {
    if (!old.inDepot) return
    val e = replacementModel(model, VehicleKind.AIRCRAFT) ?: return
    if (e.id == old.model) return
    hangarAirport(old) ?: return
    if (aircraftWhy(old, e) != UpgradeWhy.OK) return
    val cost = craftSwapCost(old.price, old.built, old.info.life, e)
    if (company.money < cost) return
    val made = rebuildAircraft(old, e)
    aircraft[old.id] = made
    spend(company, Books.NEW_TRAINS, cost)
    refreshUpgrade(made)
    linksDirty = true
}

internal fun World.rebuildAircraft(old: Aircraft, e: Engine): Aircraft {
    val made = Aircraft(roster, old.id, old.owner, e.id, old.depot, old.cargoChoice)
    made.owner = old.owner
    made.depot = old.depot
    made.cargoChoice = old.cargoChoice
    made.name = old.name
    made.number = old.number
    made.group = old.group
    made.shared = old.shared
    made.gap = old.gap
    made.departed = old.departed
    made.leftFrom = old.leftFrom
    made.leftAt = old.leftAt
    made.heldTicks = old.heldTicks
    made.orderLeft = old.orderLeft
    made.orderLeftAt = old.orderLeftAt
    made.orderReachedAt = old.orderReachedAt
    made.late = old.late
    made.renewAsked = old.renewAsked
    made.renewFailed = old.renewFailed
    made.renewOff = old.renewOff
    made.upgradeWhy = old.upgradeWhy
    made.upgradeTold = old.upgradeTold
    made.inDepot = old.inDepot
    made.running = old.running
    made.waiting = old.waiting
    made.x = old.x
    made.y = old.y
    made.altitude = old.altitude
    made.heading = old.heading
    made.speed = old.speed
    made.landing = old.landing
    made.route = old.route
    made.node = old.node
    made.stand = old.stand
    made.state = old.state
    made.at = old.at
    made.holding = old.holding
    made.gear = old.gear
    made.seasonalGear = old.seasonalGear
    made.profitThisYear = old.profitThisYear
    made.profitLastYear = old.profitLastYear
    made.order = old.order
    made.toDepot = old.toDepot
    made.built = old.built
    made.serviced = old.serviced
    made.reliability = old.reliability
    made.breakdowns = old.breakdowns
    made.brokenFor = old.brokenFor
    made.servicing = old.servicing
    made.service = old.service
    made.wreckedFor = old.wreckedFor
    made.cargoChoice = replacementCargo(old.cargoChoice, e)
    made.copyLoadAndOrders(old)
    made.built = workDay
    made.serviced = workDay
    made.reliability = e.reliability * 100
    return made
}
