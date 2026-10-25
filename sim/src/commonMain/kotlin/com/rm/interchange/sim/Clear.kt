package com.rm.interchange.sim

/*
 * The bulldozer and the selling tool. Clear takes everything of a company's
 * own off a tile, then its trees, or a town building for a price, and the town
 * thinks less of the company for the building. Remove only sells that property,
 * for scrap, about half what it cost, and leaves the trees, the town and
 * everyone else's. Industries stay put.
 */

/** Whether [company] has something on tile [i]: what it built there, or its road stop, garage, buoy or station building. A buoy keeps its owner on the station. */
fun World.owns(company: Int, i: Int): Boolean = owner[i].toInt() == company ||
    stationBuildingAt(i)?.owner == company ||
    roadStops[i]?.let { stations.getOrNull(it)?.owner == company } == true ||
    depotTiles[i]?.let { depots.getOrNull(it)?.owner == company } == true ||
    buoys[i]?.let { stations.getOrNull(it)?.owner == company } == true

/** Clears tile ([x], [y]) for [company]: its own property, sold for scrap, then its road, the trees, or a town building. */
internal fun World.clearAll(company: Company, x: Int, y: Int) {
    if (!inside(x, y)) return
    removeProperty(company, x, y)
    val i = index(x, y)
    if (Trees.count(trees[i].toInt()) > 0) {
        val cost = treeCost(i)
        if (company.money < cost) return
        spend(company, Books.CONSTRUCTION, cost)
        fellTrees(company, i)
    }
    demolish(company, x, y)
}

/** Clears every tile of [c]'s rectangle, until the money runs out. */
internal fun World.clearArea(company: Company, c: Command.ClearArea) {
    for (y in minOf(c.y0, c.y1)..maxOf(c.y0, c.y1)) for (x in minOf(c.x0, c.x1)..maxOf(c.x0, c.x1)) clearAll(company, x, y)
}

/** Sells [company]'s own property on tile ([x], [y]): what it built, then its road. Trees, town buildings and the height stay. */
internal fun World.removeProperty(company: Company, x: Int, y: Int) {
    if (!inside(x, y)) return
    val i = index(x, y)
    if (owns(company.id, i)) clearTile(company, Command.Clear(company.id, x, y))
    clearRoad(company, x, y)
    clearSalt(company, x, y)
}

/** Sells [company]'s own property on every tile of [c]'s rectangle, as [removeProperty] does one. */
internal fun World.removeArea(company: Company, c: Command.RemoveArea) {
    for (y in minOf(c.y0, c.y1)..maxOf(c.y0, c.y1)) for (x in minOf(c.x0, c.x1)..maxOf(c.x0, c.x1)) removeProperty(company, x, y)
}

/** Takes up [company]'s own road on tile ([x], [y]), paying scrap for its surface, and the ends of the road next door that led onto it. */
private fun World.clearRoad(company: Company, x: Int, y: Int) {
    val i = index(x, y)
    if (!roadClearable(company, i)) return
    scrap(company, Surface.COST[surfaceAt(i)])
    val bits = road[i].toInt()
    road[i] = 0
    roadKind[i] = 0
    roadOwner[i] = -1
    for (e in 0 until 4) {
        if (bits and roadBit(e) == 0) continue
        val nx = x + Track.dx(e)
        val ny = y + Track.dy(e)
        if (!inside(nx, ny)) continue
        val n = index(nx, ny)
        val left = road[n].toInt() and roadBit(Track.opposite(e)).inv()
        // A road left with no way on at all keeps its stub, so a town's road isn't lost.
        if (left and 15 != 0) road[n] = left.toByte()
        touched(nx, ny)
    }
    touched(x, y)
    roadsChanged()
}

private fun World.roadClearable(company: Company, i: Int) = road[i].toInt() != 0 && roadOwner[i].toInt() == company.id &&
    track[i].toInt() == 0 && !roadStops.containsKey(i) && !depotTiles.containsKey(i) && !roadVehicleOn(i)

/** Sells [company]'s salt depot on tile ([x], [y]). The depot is theirs when the road owner on that tile is, and the tile has no road. */
private fun World.clearSalt(company: Company, x: Int, y: Int) {
    val i = index(x, y)
    if (!saltDepots.contains(i) || roadOwner[i].toInt() != company.id) return
    scrap(company, SALT_DEPOT_COST)
    saltDepots.remove(i)
    roadOwner[i] = -1
    touched(x, y)
}

/** The town whose building stands on tile [i], or null if none does. */
private fun World.buildingTown(i: Int): Town? =
    if (house[i].toInt() == 0) null else towns.getOrNull(townOf[i].toInt())

/** What pulling down the town building on tile [i] costs, or -1 if there's none or its town won't have it. */
fun World.demolishCost(company: Int, i: Int): Long {
    val t = buildingTown(i) ?: return -1
    if (t.approval[company] <= APPROVAL_REFUSES) return -1
    val h = roster.houses.getOrNull(houseOf(house[i].toInt())) ?: return -1
    if (h.monument) return -1
    return price(Prices.DEMOLISH_PER_PERSON) * maxOf(1, h.residents + h.jobs)
}

/** Pulls down the town building on tile ([x], [y]), if its town thinks well enough of [company] to let it. */
private fun World.demolish(company: Company, x: Int, y: Int) {
    val i = index(x, y)
    val cost = demolishCost(company.id, i)
    if (cost < 0 || company.money < cost) return
    val t = buildingTown(i) ?: return
    val h = roster.houses[houseOf(house[i].toInt())]
    spend(company, Books.CONSTRUCTION, cost)
    house[i] = 0
    houseYear[i] = 0
    t.houses = maxOf(0, t.houses - 1)
    t.population = maxOf(0, t.population - h.residents)
    t.approval[company.id] = maxOf(-1000, t.approval[company.id] - DEMOLISH_APPROVAL)
    catchmentDirty = true
    townsChanged = true
    touched(x, y)
}

/** What selling the company's track, platform or depot on tile [i] pays, with its signals, wires and snow shed. */
private inline fun World.eachOwnedBase(i: Int, pay: (Long) -> Unit) {
    val base = when {
        depotTiles.containsKey(i) -> Prices.DEPOT
        isPlatform(i) -> Prices.PLATFORM
        track[i].toInt() != 0 -> Prices.TRACK
        else -> 0L
    }
    if (base != 0L) pay(base)
    for (p in Track.PIECES) if (signals.containsKey(pieceKey(i, p))) pay(Prices.SIGNAL)
    if (wires[i].toInt() != 0) pay(Prices.WIRE)
    if (i in sheds) pay(SHED_COST)
}

/** Pays scrap for the track, platform or depot on tile [i], and for signals, wires and a snow shed on it. */
internal fun World.scrapOwnedTrack(company: Company, i: Int) {
    eachOwnedBase(i) { scrap(company, it) }
}

private fun World.trackScrapBack(i: Int): Long {
    var back = 0L
    eachOwnedBase(i) { back += scrapBack(it) }
    return back
}

/**
 * What selling tile [i] would pay back, and which blockers come off with it.
 * Spans and airports already sold earlier in the walk are not paid again.
 */
private class Sale(
    val back: Long = 0,
    val cleared: Boolean = false,
    val trackGone: Boolean = false,
    val stopGone: Boolean = false,
    val depotGone: Boolean = false,
    val roadTaken: Boolean = false,
)

private fun World.saleOf(company: Int, i: Int, spansGone: MutableSet<Int>, airportsGone: MutableSet<Int>): Sale {
    // The span or airport already sold from an earlier tile of this walk. The rest of it goes too, paid once.
    spanHeads[i]?.let { if (it in spansGone) return Sale(cleared = true, trackGone = true) }
    airportTiles[i]?.let { if (it in airportsGone) return Sale(cleared = true) }
    if (!owns(company, i) || trainOnTile(i)) return Sale()
    depotAt(i)?.let { d -> if (trains.any { it != null && it.depot == d.id && it.inDepot }) return Sale() }
    // The owner's station building comes down for nothing and leaves any track alone.
    if (stationBuildings.containsKey(i)) return Sale(cleared = true)
    if (roadStops.containsKey(i)) {
        if (roadVehicleOn(i)) return Sale()
        return Sale(back = scrapBack(Prices.ROAD_STOP), cleared = true, stopGone = true)
    }
    waterSale(company, i)?.let { w ->
        if (!w.goes) return Sale()
        if (w.airport >= 0) {
            airportsGone += w.airport
            return Sale(back = scrapBack(w.base), cleared = true)
        }
        val yard = depotAt(i)?.kind == Depots.SHIPYARD
        return Sale(back = scrapBack(w.base), cleared = true, depotGone = yard)
    }
    depotAt(i)?.takeIf { it.road }?.let { d ->
        if (roadVehicles.any { it != null && it.inDepot && it.depot == d.id }) return Sale()
        return Sale(back = scrapBack(Prices.DEPOT), cleared = true, depotGone = true, roadTaken = true)
    }
    spanHeads[i]?.let { id ->
        val s = spans[id] ?: return Sale()
        if (spanBlocked(s)) return Sale()
        spansGone += id
        return Sale(back = scrapBack(spanBuilt(s)), cleared = true, trackGone = true)
    }
    return Sale(back = trackScrapBack(i), cleared = true, trackGone = true, depotGone = depotTiles.containsKey(i))
}

/** Whether [clearRoad] would take the road once [sale] has come off the tile. */
private fun World.roadWouldGo(company: Int, i: Int, sale: Sale): Boolean {
    if (sale.roadTaken || road[i].toInt() == 0 || roadOwner[i].toInt() != company || roadVehicleOn(i)) return false
    if (track[i].toInt() != 0 && !sale.trackGone) return false
    if (roadStops.containsKey(i) && !sale.stopGone) return false
    if (depotTiles.containsKey(i) && !sale.depotGone) return false
    return true
}

/**
 * The net of what [clearAll] would do over the rectangle, scrap as money back, and how many tiles would change.
 * Money isn't checked. A removal a train, a vehicle or a ship blocks adds nothing.
 */
private fun World.areaNet(company: Int, x0: Int, y0: Int, x1: Int, y1: Int, withTrees: Boolean, withBuildings: Boolean): Pair<Long, Int> {
    if (companies.getOrNull(company) == null) return 0L to 0
    var net = 0L
    var n = 0
    val spansGone = HashSet<Int>()
    val airportsGone = HashSet<Int>()
    for (y in minOf(y0, y1)..maxOf(y0, y1)) for (x in minOf(x0, x1)..maxOf(x0, x1)) {
        if (!inside(x, y)) continue
        val i = index(x, y)
        val sale = saleOf(company, i, spansGone, airportsGone)
        var here = -sale.back
        var any = sale.cleared
        if (roadWouldGo(company, i, sale)) {
            here -= scrapBack(Surface.COST[surfaceAt(i)])
            any = true
        }
        if (saltDepots.contains(i) && roadOwner[i].toInt() == company) {
            here -= scrapBack(SALT_DEPOT_COST)
            any = true
        }
        if (withTrees && Trees.count(trees[i].toInt()) > 0) {
            here += treeCost(i)
            any = true
        }
        if (withBuildings) {
            val d = demolishCost(company, i)
            if (d >= 0) {
                here += d
                any = true
            }
        }
        if (any) {
            net += here
            n++
        }
    }
    return net to n
}

/** What clearing [c]'s rectangle would cost, scrap as money back, and how many of its tiles have something to clear. */
fun World.clearCost(company: Int, c: Command.ClearArea): Pair<Long, Int> =
    areaNet(company, c.x0, c.y0, c.x1, c.y1, withTrees = true, withBuildings = true)

/** What selling [c]'s rectangle would pay back, as a negative net or zero, and how many tiles would change. Trees and buildings stay. */
fun World.removeCost(company: Int, c: Command.RemoveArea): Pair<Long, Int> =
    areaNet(company, c.x0, c.y0, c.x1, c.y1, withTrees = false, withBuildings = false)
