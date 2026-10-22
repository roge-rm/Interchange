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

// Models and train lengths are fixed at construction, so rebuild in the same slot.
// The buy functions would consume order backups and must not be used here.
internal fun World.replaceTrain(company: Company, old: Train, model: Int?) {
    if (!old.inDepot) return
    val e = replacementModel(model, VehicleKind.ENGINE) ?: return
    if (e.id == old.engine) return
    val depot = depots.getOrNull(old.depot) ?: return
    if (e.traction == Traction.ELECTRIC && !depotWired(depot)) return
    val cars = old.formation.toMutableList()
    val lead = cars.indexOfFirst { roster[it.model].pulls }
    cars[lead] = TrainCar(e.id)
    val made = Train(roster, old.id, old.owner, e.id, old.wagon, 0, old.depot, formation = cars)
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
    made.order = old.order
    made.toDepot = old.toDepot
    made.route = old.route
    made.routeAt = old.routeAt
    made.turnRound = old.turnRound
    made.lostUntil = old.lostUntil
    made.snowbound = old.snowbound
    made.headOn = old.headOn
    made.stuck = old.stuck
    made.held = old.held
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
    made.onTiles = old.onTiles
    made.onPieces = old.onPieces
    made.onBlocks = old.onBlocks
    made.copyLoadAndOrders(old)
    made.built = workDay
    made.serviced = workDay
    made.reliability = made.newReliability
    val cost = price(made.price) - value(old)
    if (company.money < cost) return
    trains[old.id] = made
    spend(company, Books.NEW_TRAINS, cost)
    linksDirty = true
}

internal fun World.replaceRoadVehicle(company: Company, old: RoadVehicle, model: Int?) {
    if (!old.inDepot) return
    val e = replacementModel(model, VehicleKind.ROAD) ?: return
    if (e.id == old.model) return
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
    made.inDepot = old.inDepot
    made.running = old.running
    made.waiting = old.waiting
    made.at = old.at
    made.along = old.along
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
    made.tires = old.tires
    made.seasonalTires = old.seasonalTires
    made.slid = old.slid
    made.wreckedFor = old.wreckedFor
    made.cargoChoice = replacementCargo(old.cargoChoice, e)
    made.copyLoadAndOrders(old)
    made.built = workDay
    made.serviced = workDay
    made.reliability = e.reliability * 100
    val cost = price(made.price) - roadValue(old)
    if (company.money < cost) return
    roadVehicles[old.id] = made
    spend(company, Books.NEW_TRAINS, cost)
    linksDirty = true
}

internal fun World.replaceShip(company: Company, old: Ship, model: Int?) {
    if (!old.inDepot) return
    val e = replacementModel(model, VehicleKind.SHIP) ?: return
    if (e.id == old.model) return
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
    made.renewAsked = old.renewAsked
    made.renewFailed = old.renewFailed
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
    val cost = price(made.price) - craftValue(old.price, old.built, old.info.life)
    if (company.money < cost) return
    ships[old.id] = made
    spend(company, Books.NEW_TRAINS, cost)
    linksDirty = true
}

internal fun World.replaceAircraft(company: Company, old: Aircraft, model: Int?) {
    if (!old.inDepot) return
    val e = replacementModel(model, VehicleKind.AIRCRAFT) ?: return
    if (e.id == old.model) return
    val airport = airports.firstOrNull { it != null && it.depot == old.depot } ?: return
    if (!airport.serves(e)) return
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
    made.renewAsked = old.renewAsked
    made.renewFailed = old.renewFailed
    made.inDepot = old.inDepot
    made.running = old.running
    made.waiting = old.waiting
    made.x = old.x
    made.y = old.y
    made.altitude = old.altitude
    made.heading = old.heading
    made.speed = old.speed
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
    val cost = price(made.price) - craftValue(old.price, old.built, old.info.life)
    if (company.money < cost) return
    aircraft[old.id] = made
    spend(company, Books.NEW_TRAINS, cost)
    linksDirty = true
}
