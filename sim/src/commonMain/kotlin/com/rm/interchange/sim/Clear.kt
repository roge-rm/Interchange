package com.rm.interchange.sim

/*
 * The bulldozer: everything of a company's own on a tile in one go, its
 * trees, or a town building for a price, the town thinking less of the company
 * for it. Industries stay put.
 */

/** Whether [company] has something on tile [i]: what it built there, or its road stop or garage. */
fun World.owns(company: Int, i: Int): Boolean = owner[i].toInt() == company ||
    roadStops[i]?.let { stations.getOrNull(it)?.owner == company } == true ||
    depotTiles[i]?.let { depots.getOrNull(it)?.owner == company } == true

/** Clears tile ([x], [y]) for [company]: its own track and the rest, then its road, the trees, or a town building. */
internal fun World.clearAll(company: Company, x: Int, y: Int) {
    if (!inside(x, y)) return
    val i = index(x, y)
    if (owns(company.id, i)) clearTile(company, Command.Clear(company.id, x, y))
    clearRoad(company, x, y)
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

/** Takes up [company]'s own road on tile ([x], [y]), and the ends of the road next door that led onto it. */
private fun World.clearRoad(company: Company, x: Int, y: Int) {
    val i = index(x, y)
    if (!roadClearable(company, i)) return
    val cost = price(Prices.TRACK_REMOVE)
    if (company.money < cost) return
    spend(company, Books.CONSTRUCTION, cost)
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

/** What clearing [c]'s rectangle would cost, and how many of its tiles have something to clear. Money isn't checked. */
fun World.clearCost(company: Int, c: Command.ClearArea): Pair<Long, Int> {
    val co = companies.getOrNull(company) ?: return 0L to 0
    var cost = 0L
    var n = 0
    for (y in minOf(c.y0, c.y1)..maxOf(c.y0, c.y1)) for (x in minOf(c.x0, c.x1)..maxOf(c.x0, c.x1)) {
        if (!inside(x, y)) continue
        val i = index(x, y)
        var here = 0L
        var any = false
        if (owns(company, i)) {
            here += price(Prices.CLEAR)
            any = true
        }
        if (road[i].toInt() != 0 && roadOwner[i].toInt() == company && !roadStops.containsKey(i) && !depotTiles.containsKey(i)) {
            here += price(Prices.TRACK_REMOVE)
            any = true
        }
        if (Trees.count(trees[i].toInt()) > 0) {
            here += treeCost(i)
            any = true
        }
        val d = demolishCost(co.id, i)
        if (d >= 0) {
            here += d
            any = true
        }
        if (any) {
            cost += here
            n++
        }
    }
    return cost to n
}
