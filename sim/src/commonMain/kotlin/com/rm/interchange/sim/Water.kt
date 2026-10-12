package com.rm.interchange.sim

/*
 * Ships, and what they sail on and call at.
 *
 * Ships sail on open water (every tile at sea level), on canals the players
 * dig at any height, and through locks on straight slopes between levels. A
 * ship is anywhere on a tile, in 4096ths, sailing from one tile's middle to
 * the next along its route; open water lets it cut corners, canals keep it
 * square.
 *
 * Docks stand on the shore and belong to stations, as road stops do; a ship
 * calls at the water in front. Buoys are waypoints out on the water.
 * Shipyards stand on the shore and launch ships onto the water in front.
 *
 * Ice closes the water: past [SHIP_ICE] cm only an icebreaker strong enough,
 * or a hovercraft, goes on. Canals and locks freeze as lakes do, so the Seaway
 * closes each winter. An icebreaker clears the ice where it goes, and the
 * channel closes again as the cold thickens it. Ships stay in port in a
 * storm, and before radar slow down in fog.
 */

class Ship(
    val roster: Roster,
    override val id: Int,
    override val owner: Int,
    /** Its place in the roster. */
    val model: Int,
    var depot: Int,
    /** What it's set to carry, a [Cargo] number among those it can, or -1 for its first. */
    val cargoChoice: Int = -1,
) : Carrier {
    var name = ""
    val info get() = roster[model]
    var inDepot = true
    var running = false
    var waiting = 0
    /** Where it is, in 4096ths of a tile, and the way it faces, in eighths. */
    var x = 0
    var y = 0
    var heading = 0
    var speed = 0
    /** Ticks left rising or falling in a lock. */
    var lockWait = 0
    /** Slowed by fog today, with no radar. */
    var fogged = false
    override val cargo = Array(Cargo.COUNT) { ArrayList<Packet>() }
    override var profitThisYear = 0L
    var profitLastYear = 0L
    override val orders = ArrayList<Int>()
    override var order = 0
    var toDepot = false
    /** The tiles ahead, to the one it's making for, and how far along them it is. */
    var route: IntArray? = null
    var routeAt = 0
    var lostUntil = 0L
    var built = 0
    var serviced = 0
    var reliability = 8500
    var breakdowns = 0
    var brokenFor = 0
    var servicing = false
    var service = true

    val carries: Int get() = if (cargoChoice in info.cargoes) cargoChoice else info.cargoes.firstOrNull() ?: -1
    override fun capacity(c: Int) = if (c == carries) info.capacity else 0
    override val topSpeed get() = info.speed
    val maxSpeed get() = info.speed * 4096 * 16 / 2700
    val price get() = info.cost

    /** Where it's heading: a station id, -1 for its shipyard, or -2 for nowhere. */
    val target: Int get() = when {
        toDepot -> -1
        orders.isEmpty() -> -2
        else -> Order.station(orders[order % orders.size])
    }
}

/** The tile ship [s] is on. */
fun World.shipTile(s: Ship) = index((s.x / SHIP_UNIT).coerceIn(0, width - 1), (s.y / SHIP_UNIT).coerceIn(0, height - 1))

/** Whether tile [i] is water a ship can sail on, ice aside: open water, a canal or a lock. */
fun World.navigable(i: Int): Boolean {
    val x = i % width
    val y = i / width
    if (canals.contains(i) || locks.containsKey(i)) return true
    return isWater(x, y) && industryOf[i].toInt() == 0 && !depotTiles.containsKey(i)
}

/** Whether ship [s] can get through tile [i]'s ice. */
fun World.iceLets(s: Ship, i: Int): Boolean {
    val ice = weather.ice[i].toInt() and 255
    return ice < SHIP_ICE || s.info.hovercraft || ice <= s.info.icebreaker
}

/** The water's height at [edge] of tile [i]: sea level on open water, the canal's height, or a lock's end. */
private fun World.waterLevel(i: Int, edge: Int): Int {
    val x = i % width
    val y = i / width
    if (!locks.containsKey(i)) return if (canals.contains(i)) heightAt(x, y) else 0
    return when (edge) {
        Track.XM, Track.YM -> heightAt(x, y)
        Track.XP -> heightAt(x + 1, y)
        else -> heightAt(x, y + 1)
    }
}

/** Whether a ship can go straight from tile [a] across [edge] into the next: both water, at one level, and a lock only along its length. */
private fun World.waterJoins(a: Int, edge: Int): Int {
    val x = a % width + Track.dx(edge)
    val y = a / width + Track.dy(edge)
    if (!inside(x, y)) return -1
    val b = index(x, y)
    if (!navigable(b)) return -1
    val along = if (edge == Track.XM || edge == Track.XP) Track.X else Track.Y
    if ((locks[a] ?: along) != along || (locks[b] ?: along) != along) return -1
    if (waterLevel(a, edge) != waterLevel(b, Track.opposite(edge))) return -1
    return b
}

/** The tiles a ship on tile [a] can sail to next, with what each costs: square to its edges, or corner to corner over open water. */
private fun World.shipNeighbours(a: Int, s: Ship, out: (Int, Int) -> Unit) {
    val open = isWater(a % width, a / width) && !canals.contains(a)
    val sides = IntArray(4) { -1 }
    for (e in 0 until 4) {
        val b = waterJoins(a, e)
        if (b < 0 || !iceLets(s, b)) continue
        sides[e] = b
        out(b, if (locks.containsKey(b)) 10 + LOCK_COST else 10)
    }
    if (!open) return
    for ((ex, ey) in listOf(Track.XM to Track.YM, Track.XM to Track.YP, Track.XP to Track.YM, Track.XP to Track.YP)) {
        val bx = sides[ex]
        val by = sides[ey]
        if (bx < 0 || by < 0) continue
        val x = a % width + Track.dx(ex)
        val y = a / width + Track.dy(ey)
        val c = index(x, y)
        if (isWater(bx % width, bx / width) && isWater(by % width, by / width) && isWater(x, y) && navigable(c) && !canals.contains(c) && iceLets(s, c)) out(c, 14)
    }
}

/** The water ships call at for station [id]: in front of each dock, and on each buoy. */
fun World.berths(id: Int): List<Int> {
    val st = stations.getOrNull(id) ?: return emptyList()
    return st.docks.map { d ->
        if (buoys.containsKey(d)) d else {
            val e = dockFacing[d] ?: 0
            index(d % width + Track.dx(e), d / width + Track.dy(e))
        }
    }
}

/** The water in front of shipyard [d], where its ships come and go. */
fun World.shipyardWater(d: Depot) = index(d.tile % width + Track.dx(d.edge), d.tile / width + Track.dy(d.edge))

/** The edge of land tile ([x], [y]) that faces open water or a canal, or -1. */
private fun World.waterSide(x: Int, y: Int): Int {
    for (e in intArrayOf(Track.XM, Track.XP, Track.YM, Track.YP)) {
        val nx = x + Track.dx(e)
        val ny = y + Track.dy(e)
        if (inside(nx, ny) && navigable(index(nx, ny)) && !locks.containsKey(index(nx, ny))) return e
    }
    return -1
}

/** Whether land tile [i] is clear to build on: not water, and nothing there. */
private fun World.clearLand(i: Int): Boolean {
    val x = i % width
    val y = i / width
    return !isWater(x, y) && track[i].toInt() == 0 && road[i].toInt() == 0 && house[i].toInt() == 0 && industryOf[i].toInt() == 0 &&
        !stationTiles.containsKey(i) && !depotTiles.containsKey(i) && !canals.contains(i) && !locks.containsKey(i) && !docks.containsKey(i) &&
        !airportTiles.containsKey(i) && !saltDepots.contains(i) && !deck.containsKey(i) && !spanHeads.containsKey(i)
}

/** Digs a canal along [c]'s line on flat land, or fills in the company's own. */
internal fun World.buildCanal(company: Company, c: Command.Canal) {
    if (c.sx != c.ex && c.sy != c.ey) return
    if (!inside(c.sx, c.sy) || !inside(c.ex, c.ey)) return
    val dx = (c.ex - c.sx).coerceIn(-1, 1)
    val dy = (c.ey - c.sy).coerceIn(-1, 1)
    val n = maxOf(kotlin.math.abs(c.ex - c.sx), kotlin.math.abs(c.ey - c.sy))
    for (k in 0..n) {
        val x = c.sx + dx * k
        val y = c.sy + dy * k
        val i = index(x, y)
        if (c.remove) {
            if (!canals.contains(i) || owner[i].toInt() != company.id || ships.any { it != null && !it.inDepot && shipTile(it) == i }) continue
            if (company.money < price(Prices.CLEAR)) return
            spend(company, Books.CONSTRUCTION, price(Prices.CLEAR))
            canals.remove(i)
            owner[i] = -1
            weather.ice[i] = 0
            touched(x, y)
            continue
        }
        if (!isFlat(x, y) || !clearLand(i)) continue
        val cost = price(CANAL_COST) + treeCost(i)
        if (company.money < cost) return
        spend(company, Books.CONSTRUCTION, cost)
        fellTrees(company, i)
        canals += i
        owner[i] = company.id.toByte()
        touched(x, y)
    }
    shipsChanged()
}

/** A lock on a straight slope at ([x], [y]), lifting ships a step between the water at its two ends. */
internal fun World.buildLock(company: Company, c: Command.Lock) {
    if (!inside(c.x, c.y)) return
    val i = index(c.x, c.y)
    if (!clearLand(i)) return
    val n = heightAt(c.x, c.y)
    val e = heightAt(c.x + 1, c.y)
    val s = heightAt(c.x + 1, c.y + 1)
    val w = heightAt(c.x, c.y + 1)
    val axis = when {
        n == w && e == s && kotlin.math.abs(n - e) == 1 -> Track.X
        n == e && w == s && kotlin.math.abs(n - w) == 1 -> Track.Y
        else -> return
    }
    val cost = price(LOCK_DOLLARS) + treeCost(i)
    if (company.money < cost) return
    spend(company, Books.CONSTRUCTION, cost)
    fellTrees(company, i)
    locks[i] = axis
    owner[i] = company.id.toByte()
    touched(c.x, c.y)
    shipsChanged()
}

/** A dock on the shore at ([x], [y]), facing the water; it joins a station of the company's within a tile, or starts one. */
internal fun World.buildDock(company: Company, c: Command.Dock) {
    if (!inside(c.x, c.y)) return
    val i = index(c.x, c.y)
    if (!clearLand(i)) return
    val facing = waterSide(c.x, c.y)
    if (facing < 0 || townRefuses(company, c.x, c.y) || company.money < price(DOCK_COST) + treeCost(i)) return
    var station: Station? = null
    for (y in c.y - 1..c.y + 1) for (x in c.x - 1..c.x + 1) {
        if (station != null || !inside(x, y)) continue
        val n = index(x, y)
        val id = stationTiles[n] ?: roadStops[n] ?: docks[n] ?: airportTiles[n]?.let { airports[it]?.station } ?: continue
        station = stations.getOrNull(id)?.takeIf { it.owner == company.id && !it.waypoint }
    }
    spend(company, Books.CONSTRUCTION, price(DOCK_COST) + fellTrees(company, i))
    val s = station ?: Station(stations.size, company.id, stationName(c.x, c.y), c.x, c.y).also { stations += it }
    s.docks += i
    docks[i] = s.id
    dockFacing[i] = facing
    owner[i] = company.id.toByte()
    touched(c.x, c.y)
    networkChanged()
    shipsChanged()
}

/** A shipyard on the shore at ([x], [y]), launching onto the water in front. */
internal fun World.buildShipyard(company: Company, c: Command.Shipyard) {
    if (!inside(c.x, c.y)) return
    val i = index(c.x, c.y)
    if (!clearLand(i)) return
    val facing = waterSide(c.x, c.y)
    if (facing < 0 || company.money < price(SHIPYARD_COST) + treeCost(i)) return
    spend(company, Books.CONSTRUCTION, price(SHIPYARD_COST) + fellTrees(company, i))
    val d = Depot(depots.size, company.id, i, facing, Depots.SHIPYARD)
    depots += d
    depotTiles[i] = d.id
    owner[i] = company.id.toByte()
    touched(c.x, c.y)
    shipsChanged()
}

/** A buoy on open water at ([x], [y]): a waypoint for ships' orders. */
internal fun World.buildBuoy(company: Company, c: Command.Buoy) {
    if (!inside(c.x, c.y)) return
    val i = index(c.x, c.y)
    if (!isWater(c.x, c.y) || buoys.containsKey(i) || industryOf[i].toInt() != 0 || company.money < price(BUOY_COST)) return
    spend(company, Books.CONSTRUCTION, price(BUOY_COST))
    val s = Station(stations.size, company.id, stationName(c.x, c.y), c.x, c.y, waypoint = true)
    stations += s
    s.docks += i
    buoys[i] = s.id
    touched(c.x, c.y)
}

/** Clears what's on tile [i] of water and air: a dock, buoy, shipyard, canal, lock or a whole airport. True if it was one of those. */
internal fun World.clearWaterOrAir(company: Company, i: Int): Boolean {
    val x = i % width
    val y = i / width
    fun pay(): Boolean {
        if (company.money < price(Prices.CLEAR)) return false
        spend(company, Books.CONSTRUCTION, price(Prices.CLEAR))
        return true
    }
    docks[i]?.let { id ->
        val s = stations[id] ?: return true
        if (s.owner != company.id || !pay()) return true
        docks.remove(i); dockFacing.remove(i); owner[i] = -1
        s.docks.remove(i)
        if (s.empty) dropStation(id)
        touched(x, y); networkChanged(); shipsChanged()
        return true
    }
    buoys[i]?.let { id ->
        val s = stations[id] ?: return true
        if (s.owner != company.id || !pay()) return true
        buoys.remove(i)
        s.docks.remove(i)
        dropStation(id)
        touched(x, y); shipsChanged()
        return true
    }
    depotAt(i)?.takeIf { it.kind == Depots.SHIPYARD }?.let { d ->
        if (d.owner != company.id || ships.any { it != null && it.depot == d.id } || !pay()) return true
        depotTiles.remove(i); depots[d.id] = null; owner[i] = -1
        touched(x, y); shipsChanged()
        return true
    }
    if (canals.contains(i) || locks.containsKey(i)) {
        if (owner[i].toInt() != company.id || ships.any { it != null && !it.inDepot && shipTile(it) == i } || !pay()) return true
        canals.remove(i); locks.remove(i); owner[i] = -1; weather.ice[i] = 0
        touched(x, y); shipsChanged()
        return true
    }
    airportTiles[i]?.let { id ->
        airports.getOrNull(id)?.let { a -> if (a.owner == company.id) removeAirport(company, a) }
        return true
    }
    return false
}

/** Ships' routes are found again before the next move. */
internal fun World.shipsChanged() {
    for (s in ships) s?.route = null
}

/** Buys a ship at a shipyard. */
internal fun World.buyShip(company: Company, depot: Depot, e: Engine, cargo: Int) {
    val v = Ship(roster, ships.size, company.id, e.id, depot.id, if (cargo in e.cargoes) cargo else -1)
    if (company.money < price(v.price)) return
    spend(company, Books.NEW_TRAINS, price(v.price))
    v.built = day
    v.serviced = day
    v.reliability = e.reliability * 100
    ships += v
}

internal fun World.shipCommand(company: Company, c: Command.CraftOrder) {
    val v = ships.getOrNull(c.vehicle) ?: return
    if (v.owner != company.id) return
    linksDirty = true
    when (c.what) {
        Command.TrainOrder.START_STOP -> if (v.inDepot) { if (v.running) v.running = false else launch(v) } else v.running = !v.running
        Command.TrainOrder.ORDERS -> {
            v.orders.clear()
            for (o in c.stations) if (stations.getOrNull(Order.station(o))?.owner == company.id) v.orders += o
            v.order = 0
            v.route = null
        }
        Command.TrainOrder.TO_DEPOT -> if (!v.inDepot) { v.toDepot = !v.toDepot; v.servicing = false; v.route = null }
        Command.TrainOrder.SELL -> if (v.inDepot) {
            earn(company, Books.TRAINS_SOLD, craftValue(v.price, v.built, v.info.life))
            ships[v.id] = null
        }
        Command.TrainOrder.SERVICE -> v.service = !v.service
        Command.TrainOrder.RENEW -> if (v.inDepot) {
            val cost = price(v.price) - craftValue(v.price, v.built, v.info.life)
            if (company.money < cost) return
            spend(company, Books.NEW_TRAINS, cost)
            v.built = day
            v.serviced = day
            v.reliability = v.info.reliability * 100
        }
    }
}

/** What a ship or aircraft of [price] built on [built] with a working life of [life] years fetches sold. */
fun World.craftValue(price: Long, built: Int, life: Int): Long {
    val age = (day - built).coerceAtLeast(0) / 365
    return price(price) * maxOf(10, 100 - 100 * age / life.coerceAtLeast(1)) / 200
}

/** Out of the shipyard onto the water in front, if the ice lets it. */
private fun World.launch(v: Ship) {
    val d = depots.getOrNull(v.depot) ?: return
    v.running = true
    val w = shipyardWater(d)
    if (!iceLets(v, w)) return
    v.x = (w % width) * SHIP_UNIT + SHIP_UNIT / 2
    v.y = (w / width) * SHIP_UNIT + SHIP_UNIT / 2
    v.heading = heading(Track.dx(d.edge), Track.dy(d.edge))
    v.speed = 0
    v.inDepot = false
    v.route = null
}

/** Moves every ship a tick: routes first, across the cores, then the moves in order. */
internal fun World.moveShips() {
    val list = ships.filterNotNull()
    if (list.isEmpty()) return
    for (v in list) if (v.inDepot && v.running) launch(v)
    val planning = list.filter { !it.inDepot && it.running && it.route == null && tick >= it.lostUntil && it.target != -2 && it.waiting == 0 && it.lockWait == 0 }
    val routes = arrayOfNulls<IntArray>(planning.size)
    Workers.run(planning.size) { i -> routes[i] = planShipRoute(planning[i]) }
    for ((i, v) in planning.withIndex()) {
        val r = routes[i]
        if (r == null) v.lostUntil = tick + LOST_RETRY * 4 else { v.route = r; v.routeAt = 0 }
    }
    for (v in list) if (!v.inDepot) sail(v)
}

private fun World.sail(v: Ship) {
    if (v.waiting > 0) {
        // Ships stay in port while a storm blows.
        if (v.waiting == 1 && weather.wind >= STORM_WIND && !v.info.hovercraft) return
        if (--v.waiting == 0) {
            if (v.orders.isNotEmpty()) v.order = (v.order + 1) % v.orders.size
            v.route = null
        }
        return
    }
    if (v.brokenFor > 0) { v.brokenFor--; v.speed = 0; return }
    if (v.lockWait > 0) { v.lockWait--; return }
    if (!v.running || v.target == -2) { v.speed = (v.speed - BRAKE).coerceAtLeast(0); return }
    val r = v.route ?: run { v.speed = (v.speed - BRAKE).coerceAtLeast(0); return }
    val limit = if (v.fogged) v.maxSpeed / 2 else v.maxSpeed
    val accel = (v.info.power.toLong() * 40 / (v.info.weight.coerceAtLeast(1) * 10)).toInt().coerceIn(2, 30)
    v.speed = minOf(v.speed + accel, limit)
    var move = v.speed / 16
    while (move > 0 && v.routeAt < r.size) {
        val next = r[v.routeAt]
        // Ice come in across the way since the route was found: look again.
        if (!iceLets(v, next)) { v.route = null; v.speed = 0; return }
        val cx = (next % width) * SHIP_UNIT + SHIP_UNIT / 2
        val cy = (next / width) * SHIP_UNIT + SHIP_UNIT / 2
        val dx = cx - v.x
        val dy = cy - v.y
        val dist = isqrt(dx.toLong() * dx + dy.toLong() * dy).toInt()
        if (dist > 0) v.heading = shipHeading(dx, dy)
        if (dist <= move) {
            v.x = cx
            v.y = cy
            move -= dist
            v.routeAt++
            enteredWater(v, next)
            if (v.lockWait > 0) break
        } else {
            v.x += (dx.toLong() * move / dist).toInt()
            v.y += (dy.toLong() * move / dist).toInt()
            move = 0
        }
    }
    if (v.routeAt >= r.size) arriveShip(v)
}

/** The way, in eighths, of a move of ([dx], [dy]): straight along an axis unless it's well off it. */
private fun shipHeading(dx: Int, dy: Int): Int {
    val ax = kotlin.math.abs(dx)
    val ay = kotlin.math.abs(dy)
    return when {
        ax > ay * 2 -> heading(dx, 0)
        ay > ax * 2 -> heading(0, dy)
        else -> heading(dx, dy)
    }
}

/** On coming into tile [i]: an icebreaker clears the ice round it, and a lock holds the ship while it fills or empties. */
private fun World.enteredWater(v: Ship, i: Int) {
    if (v.info.icebreaker > 0) {
        val x0 = i % width
        val y0 = i / width
        for ((dx, dy) in listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val x = x0 + dx
            val y = y0 + dy
            if (!inside(x, y)) continue
            val n = index(x, y)
            val ice = weather.ice[n].toInt() and 255
            if (ice > 0 && ice <= v.info.icebreaker && navigable(n)) {
                weather.ice[n] = 0
                weather.snow[n] = 0
                touched(x, y)
            }
        }
    }
    if (locks.containsKey(i)) v.lockWait = LOCK_TICKS
}

/** At the end of its route: calling at its station's berth, past a buoy, or into its shipyard. */
private fun World.arriveShip(v: Ship) {
    v.speed = 0
    v.route = null
    val tile = shipTile(v)
    val target = v.target
    if (target >= 0) {
        val s = stations.getOrNull(target) ?: return
        if (tile !in berths(target)) return
        if (s.waypoint) {
            v.order = (v.order + 1) % v.orders.size.coerceAtLeast(1)
            return
        }
        val o = v.orders.getOrNull(v.order % v.orders.size.coerceAtLeast(1)) ?: 0
        v.waiting = maxOf(Calendar.TICKS_PER_DAY, Calendar.TICKS_PER_DAY * Order.waitDays(o))
        callAt(v, s, o)
    } else if (target == -1) {
        val d = depots.getOrNull(v.depot) ?: return
        if (tile != shipyardWater(d)) return
        v.inDepot = true
        v.running = false
        v.toDepot = false
        if (v.servicing) {
            v.reliability = v.info.reliability * 100
            v.serviced = day
            v.servicing = false
            v.running = true
        }
    }
}

/**
 * The way from [v] to where it's going: an A* search over water tiles from the one it's on, square moves costing
 * 10, corner to corner 14 and a lock more, guided by the straight-line distance. Its station's berths, or its
 * shipyard's water, are where it can end.
 */
internal fun World.planShipRoute(v: Ship): IntArray? {
    val target = v.target
    val goals = when {
        target >= 0 -> berths(target).toHashSet()
        target == -1 -> depots.getOrNull(v.depot)?.let { hashSetOf(shipyardWater(it)) } ?: return null
        else -> return null
    }
    if (goals.isEmpty()) return null
    val start = shipTile(v)
    if (start in goals) return IntArray(0)
    val gx = goals.map { it % width }
    val gy = goals.map { it / width }
    fun guess(i: Int): Int {
        var best = Int.MAX_VALUE
        for (k in gx.indices) {
            val dx = kotlin.math.abs(i % width - gx[k])
            val dy = kotlin.math.abs(i / width - gy[k])
            best = minOf(best, 10 * maxOf(dx, dy) + 4 * minOf(dx, dy))
        }
        return best
    }
    val dist = HashMap<Int, Int>()
    val prev = HashMap<Int, Int>()
    val heap = WaterHeap()
    dist[start] = 0
    heap.push(guess(start), start)
    var looked = 0
    while (heap.size > 0) {
        val top = heap.pop()
        val a = top.toInt()
        val cost = dist[a] ?: continue
        if ((top ushr 32).toInt() > cost + guess(a)) continue
        if (a in goals) {
            val back = ArrayList<Int>()
            var k = a
            while (k != start) {
                back += k
                k = prev[k] ?: break
            }
            back.reverse()
            return back.toIntArray()
        }
        if (++looked > MAX_SHIP_LOOK) return null
        shipNeighbours(a, v) { b, step ->
            val c = cost + step
            if (c < (dist[b] ?: Int.MAX_VALUE)) {
                dist[b] = c
                prev[b] = a
                heap.push(c + guess(b), b)
            }
        }
    }
    return null
}

/** A min-heap of costs and tiles packed into longs. */
internal class WaterHeap {
    private var a = LongArray(64)
    var size = 0
        private set

    fun push(cost: Int, node: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        var i = size++
        a[i] = (cost.toLong() shl 32) or (node.toLong() and 0xffffffffL)
        while (i > 0) {
            val p = (i - 1) / 2
            if (a[p] <= a[i]) break
            val t = a[p]; a[p] = a[i]; a[i] = t
            i = p
        }
    }

    fun pop(): Long {
        val top = a[0]
        a[0] = a[--size]
        var i = 0
        while (true) {
            val l = i * 2 + 1
            val r = l + 1
            var m = i
            if (l < size && a[l] < a[m]) m = l
            if (r < size && a[r] < a[m]) m = r
            if (m == i) break
            val t = a[m]; a[m] = a[i]; a[i] = t
            i = m
        }
        return top
    }
}

/** Each day: wear and breakdowns, trips to the shipyard, fog without radar, and running costs. */
internal fun World.shipsDay() {
    if (ships.isEmpty()) return
    val year = Calendar.year(startYear, day)
    for (v in ships) {
        if (v == null || v.inDepot) continue
        v.reliability = (v.reliability - WEAR_A_DAY).coerceAtLeast(LEAST_RELIABLE / 2)
        if (v.running && v.brokenFor == 0 && v.waiting == 0 && rng.below(BREAKDOWN_ODDS) < 10_000 - v.reliability) {
            v.brokenFor = Calendar.TICKS_PER_DAY
            v.breakdowns++
        }
        if (v.service && day - v.serviced >= SERVICE_DAYS && v.running && !v.toDepot && depots.getOrNull(v.depot) != null) {
            v.toDepot = true
            v.servicing = true
            v.route = null
        }
        val t = shipTile(v)
        v.fogged = year < RADAR_FROM && settings.weather && skyAt(t % width, t / width).fog
    }
    for (v in ships) {
        if (v == null || v.inDepot && !v.running) continue
        val c = companies.getOrNull(v.owner) ?: continue
        c.runningOwed += price(v.info.running)
        val today = c.runningOwed / 365
        spend(c, Books.RUNNING, today)
        v.profitThisYear -= today
        c.runningOwed %= 365
    }
}

/** Where ship [v] is, in tiles: x, y and the way it faces, in eighths. */
fun World.shipPosition(v: Ship) = floatArrayOf(v.x / SHIP_UNIT.toFloat(), v.y / SHIP_UNIT.toFloat(), v.heading.toFloat())

/** A ship's position units to a tile. */
const val SHIP_UNIT = 4096
/** Ice, in cm, that stops a ship that can't break it. */
const val SHIP_ICE = 15
/** Wind, in km/h, that keeps ships in port. */
const val STORM_WIND = 60
/** The year ships have radar and sail on through fog. */
const val RADAR_FROM = 1946
/** Ticks a ship spends in a lock, and what a lock adds to a route. */
const val LOCK_TICKS = 32
const val LOCK_COST = 30
const val MAX_SHIP_LOOK = 250_000
/** Building costs, in dollars before inflation. */
const val CANAL_COST = 600L
const val LOCK_DOLLARS = 4_000L
const val DOCK_COST = 1_500L
const val SHIPYARD_COST = 3_000L
const val BUOY_COST = 300L

/** Buys a ship at a shipyard or an aircraft in a hangar, whichever [c]'s depot is. */
internal fun World.buyCraft(company: Company, c: Command.BuyCraft) {
    val depot = depots.getOrNull(c.depot) ?: return
    if (depot.owner != company.id) return
    val e = roster.getOrNull(c.model) ?: return
    val year = Calendar.year(startYear, day)
    if (!e.enabled || year < e.from || year >= e.until) return
    when {
        depot.kind == Depots.SHIPYARD && e.kind == VehicleKind.SHIP -> buyShip(company, depot, e, c.cargo)
        depot.kind == Depots.HANGAR && e.kind == VehicleKind.AIRCRAFT -> buyAircraft(company, depot, e, c.cargo)
    }
}

/** What digging (or filling in) [c] would cost and how many tiles of it can be done. */
fun World.canalCost(company: Company, c: Command.Canal): Pair<Long, Int> {
    if (c.sx != c.ex && c.sy != c.ey || !inside(c.sx, c.sy) || !inside(c.ex, c.ey)) return -1L to 0
    val dx = (c.ex - c.sx).coerceIn(-1, 1)
    val dy = (c.ey - c.sy).coerceIn(-1, 1)
    val n = maxOf(kotlin.math.abs(c.ex - c.sx), kotlin.math.abs(c.ey - c.sy))
    var cost = 0L
    var count = 0
    for (k in 0..n) {
        val x = c.sx + dx * k
        val y = c.sy + dy * k
        val i = index(x, y)
        if (c.remove) {
            if (canals.contains(i) && owner[i].toInt() == company.id) { cost += price(Prices.CLEAR); count++ }
        } else if (isFlat(x, y) && clearLand(i)) {
            cost += price(CANAL_COST) + treeCost(i)
            count++
        }
    }
    return cost to count
}
