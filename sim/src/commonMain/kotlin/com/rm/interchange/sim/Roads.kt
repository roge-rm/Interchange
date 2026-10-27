package com.rm.interchange.sim

/*
 * Roads the players build, on the same layer as the towns' ([World.road]):
 * the edges each tile's road reaches, and for every road tile a surface and
 * whether it's one-way ([World.roadKind]) and who built it ([World.roadOwner],
 * -1 for a town's). A company's road joins the towns' where they meet, and
 * road vehicles run on both.
 *
 * Track and road cross on a level crossing: a straight road over a straight
 * piece of track at right angles. Trains go first.
 *
 * Road stops are drive-through, on a straight road, and belong to a station
 * like platforms do, so a stop beside a platform makes one interchange.
 * Garages are depots for road vehicles, with a road out of one edge.
 */

object Surface {
    const val DIRT = 0
    const val GRAVEL = 1
    const val PAVED = 2
    const val HIGHWAY = 3

    /** The year a company can first build each, dollars a tile before inflation, and the fastest a vehicle goes on each, in km/h. */
    val FROM = intArrayOf(1836, 1836, 1915, 1950)
    val COST = longArrayOf(40, 90, 220, 420)
    val SPEED = intArrayOf(35, 55, 80, 110)

    /** The surfaces a company can build in [year]. */
    fun entries(year: Int) = (DIRT..HIGHWAY).filter { year >= FROM[it] }
}

/** A [World.roadKind] value: surface in the low two bits, one-way in the next two (0 both ways, 1 towards the higher edge, 2 the lower). */
fun roadKindOf(surface: Int, oneWay: Int) = surface or (oneWay shl 2)
fun surfaceOf(kind: Int) = kind and 3
fun oneWayOf(kind: Int) = (kind shr 2) and 3

/** The surface of the road on tile [i]. */
fun World.surfaceAt(i: Int) = surfaceOf(roadKind[i].toInt())

/** The road edges on tile [i], with what isn't an edge left off. */
fun World.roadEdges(i: Int) = road[i].toInt() and 15

/** Whether [i] has a straight road along x, along y, or neither: [Track.X], [Track.Y] or 0. */
fun World.straightRoad(i: Int): Int = when (roadEdges(i)) {
    roadBit(Track.XM) or roadBit(Track.XP) -> Track.X
    roadBit(Track.YM) or roadBit(Track.YP) -> Track.Y
    else -> 0
}

/** Whether a vehicle may leave tile [i] through [edge], for one-way roads. */
fun World.mayLeave(i: Int, edge: Int): Boolean = when (oneWayOf(roadKind[i].toInt())) {
    1 -> edge == Track.XP || edge == Track.YP
    2 -> edge == Track.XM || edge == Track.YM
    else -> true
}

/** Whether tile [i] is a level crossing: road and track on it. */
fun World.crossing(i: Int) = roadEdges(i) != 0 && track[i].toInt() != 0

/** Whether a straight piece of track [piece] can cross tile [i]'s road: the road straight, flat ground, at right angles. */
fun World.crossable(i: Int, piece: Int): Boolean {
    val r = straightRoad(i)
    return r != 0 && piece != r && (piece == Track.X || piece == Track.Y) && isFlat(i % width, i / width) &&
        house[i].toInt() == 0 && industryOf[i].toInt() == 0 && !roadStops.containsKey(i) && !depotTiles.containsKey(i)
}

/** What laying [c] would cost, or -1 if none of it can be done. */
fun World.roadCost(company: Company, c: Command.Road): Long {
    val tiles = roadLine(c) ?: return -1
    if (c.remove) return tiles.size * price(Prices.TRACK_REMOVE)
    if (c.surface !in Surface.DIRT..Surface.HIGHWAY || Calendar.year(startYear, day) < Surface.FROM[c.surface]) return -1
    return tiles.sumOf { (i, bits) -> price(Surface.COST[c.surface]) + treeCost(i) + foundationCost(i, (road[i].toInt() and 15) or bits) }
}

/** What road [bits] on tile [i] would add for a foundation the tile doesn't have yet. */
private fun World.foundationCost(i: Int, bits: Int): Long {
    val x = i % width
    val y = i / width
    return if (track[i].toInt() == 0 && !needsFoundation(x, y, 0) && needsFoundation(x, y, roadPieces(bits))) price(Prices.FOUNDATION) else 0L
}

/**
 * Whether road [bits] on tile ([x], [y]) meet the road next door level at every edge they share, the tile up on a
 * foundation where the land doesn't suit it, as track is.
 */
fun World.roadJoinsLevel(x: Int, y: Int, bits: Int): Boolean {
    val pieces = roadPieces(bits)
    for (edge in 0 until 4) {
        if (bits and roadBit(edge) == 0) continue
        val nx = x + Track.dx(edge)
        val ny = y + Track.dy(edge)
        if (!inside(nx, ny)) continue
        val n = index(nx, ny)
        val back = Track.opposite(edge)
        if (road[n].toInt() and roadBit(back) == 0) continue
        if (edgeHeight(nx, ny, track[n].toInt(), back) != edgeHeight(x, y, pieces, edge)) return false
    }
    return true
}

/** The tiles of [c]'s line and the road edges each gets, or null if it's not a straight line along x or y. */
private fun World.roadLine(c: Command.Road): List<Pair<Int, Int>>? {
    if (c.sx != c.ex && c.sy != c.ey) return null
    if (!inside(c.sx, c.sy) || !inside(c.ex, c.ey)) return null
    val dx = (c.ex - c.sx).coerceIn(-1, 1)
    val dy = (c.ey - c.sy).coerceIn(-1, 1)
    val n = maxOf(kotlin.math.abs(c.ex - c.sx), kotlin.math.abs(c.ey - c.sy))
    val fwd = if (dx > 0) Track.XP else if (dx < 0) Track.XM else if (dy > 0) Track.YP else Track.YM
    val back = Track.opposite(fwd)
    // A single tile on its own gets a road along x.
    if (n == 0) return listOf(index(c.sx, c.sy) to (roadBit(Track.XM) or roadBit(Track.XP)))
    return (0..n).map { k ->
        var bits = 0
        if (k > 0) bits = bits or roadBit(back)
        if (k < n) bits = bits or roadBit(fwd)
        index(c.sx + dx * k, c.sy + dy * k) to bits
    }
}

/**
 * Lays road along [c]'s line, joining what's there, or with [Command.Road.remove] lifts the company's own.
 * Each tile has to take the road: dry, level with the road next door, nothing built there but road, or track
 * the road can cross at right angles. Where the land doesn't suit it, it goes up on a foundation, as track
 * does. A surface better than what's there is laid over it.
 */
internal fun World.buildRoad(company: Company, c: Command.Road) {
    val line = roadLine(c) ?: return
    if (c.tram) {
        tramRails(company, line.map { it.first }, c.remove)
        return
    }
    if (!c.remove && (c.surface !in Surface.DIRT..Surface.HIGHWAY || Calendar.year(startYear, day) < Surface.FROM[c.surface])) return
    for ((i, bits) in line) {
        val x = i % width
        val y = i / width
        val has = road[i].toInt()
        if (c.remove) {
            if (roadOwner[i].toInt() != company.id || has and bits == 0 || roadStops.containsKey(i) || trainOnTile(i) || roadVehicleOn(i)) continue
            if (company.money < price(Prices.TRACK_REMOVE)) return
            spend(company, Books.CONSTRUCTION, price(Prices.TRACK_REMOVE))
            val left = has and bits.inv()
            road[i] = (if (left and 15 == 0) 0 else left).toByte()
            if (left and 15 == 0) { roadKind[i] = 0; roadOwner[i] = -1 }
            touched(x, y)
            continue
        }
        if (isWater(x, y) && !iceRoadFits(i)) continue
        if (house[i].toInt() != 0 || industryOf[i].toInt() != 0 || stationTiles.containsKey(i) || depotTiles.containsKey(i) || deck.containsKey(i) || spanHeads.containsKey(i)) continue
        if (othersBuilding(company.id, i)) continue
        val joined = (has and 15) or bits
        if (!roadJoinsLevel(x, y, joined)) continue
        val t = track[i].toInt()
        if (t != 0) {
            // Only a level crossing: straight road over straight track at right angles, on the level.
            val alongX = joined == (roadBit(Track.XM) or roadBit(Track.XP))
            val alongY = joined == (roadBit(Track.YM) or roadBit(Track.YP))
            if (!isFlat(x, y) || !((alongX && t == Track.Y) || (alongY && t == Track.X)) || stationTiles.containsKey(i)) continue
        }
        val surface = if (has != 0) maxOf(c.surface, surfaceAt(i)) else c.surface
        val upgrading = has != 0 && surface > surfaceAt(i)
        if (has and 15 == joined && !upgrading) continue
        val cost = price(Surface.COST[c.surface]) + treeCost(i) + if (t == 0) foundationCost(i, joined) else 0L
        if (company.money < cost) return
        spend(company, Books.CONSTRUCTION, cost)
        fellTrees(company, i)
        clearOwnBuilding(company.id, i)
        road[i] = (joined or (if (surface >= Surface.PAVED) PAVED else 0)).toByte()
        roadKind[i] = (roadKindOf(surface, oneWayOf(roadKind[i].toInt())) or (roadKind[i].toInt() and TRAM)).toByte()
        if (has == 0) roadOwner[i] = company.id.toByte()
        if (has == 0) condition[i] = -1
        touched(x, y)
    }
    roadsChanged()
}

/** Lays tram rails in the road on [tiles], or takes them up; the road has to be there, the company's or a town's. */
private fun World.tramRails(company: Company, tiles: List<Int>, remove: Boolean) {
    if (!remove && Calendar.year(startYear, day) < TRAMS_FROM) return
    for (i in tiles) {
        val owned = roadOwner[i].toInt()
        if (roadEdges(i) == 0 || (owned >= 0 && owned != company.id) || isWater(i % width, i / width)) continue
        val has = roadKind[i].toInt() and TRAM != 0
        if (has != remove) continue
        if (!remove) {
            if (company.money < price(Prices.WIRE)) return
            spend(company, Books.CONSTRUCTION, price(Prices.WIRE))
        }
        roadKind[i] = (roadKind[i].toInt() xor TRAM).toByte()
        touched(i % width, i / width)
    }
    roadsChanged()
}

/** The year street railways can first be laid. */
const val TRAMS_FROM = 1885

/** Turns tile ([x], [y])'s straight road one-way, the other way, or back to both ways. */
internal fun World.roadOneWay(company: Company, c: Command.RoadOneWay) {
    if (!inside(c.x, c.y)) return
    val i = index(c.x, c.y)
    if (roadOwner[i].toInt() != company.id || straightRoad(i) == 0 || roadStops.containsKey(i)) return
    val k = roadKind[i].toInt()
    roadKind[i] = (roadKindOf(surfaceOf(k), (oneWayOf(k) + 1) % 3) or (k and TRAM)).toByte()
    touched(c.x, c.y)
    roadsChanged()
}

/**
 * A drive-through stop on straight road at ([x], [y]), the company's or a town's. Joins a station of the
 * company's within a tile, platforms or stops, or starts one.
 */
internal fun World.buildRoadStop(company: Company, c: Command.RoadStop) {
    if (!inside(c.x, c.y)) return
    val i = index(c.x, c.y)
    if (straightRoad(i) == 0 || track[i].toInt() != 0 || roadStops.containsKey(i) || !isFlat(c.x, c.y)) return
    val owned = roadOwner[i].toInt()
    if (owned >= 0 && owned != company.id) return
    if (townRefuses(company, c.x, c.y)) return
    if (company.money < price(Prices.ROAD_STOP)) return
    var station: Station? = null
    for (y in c.y - 1..c.y + 1) for (x in c.x - 1..c.x + 1) {
        if (station != null || !inside(x, y)) continue
        val n = index(x, y)
        val id = stationTiles[n] ?: roadStops[n] ?: continue
        station = stations.getOrNull(id)?.takeIf { it.owner == company.id && !it.waypoint }
    }
    spend(company, Books.CONSTRUCTION, price(Prices.ROAD_STOP))
    val s = station ?: Station(stations.size, company.id, stationName(c.x, c.y), c.x, c.y).also { stations += it }
    s.stops += i
    roadStops[i] = s.id
    touched(c.x, c.y)
    networkChanged()
    roadsChanged()
}

/** A garage for road vehicles on empty flat land at ([x], [y]), its door at [edge] onto a road there. */
internal fun World.buildGarage(company: Company, c: Command.Garage) {
    if (!inside(c.x, c.y) || !isFlat(c.x, c.y) || isWater(c.x, c.y) || c.edge !in 0..3) return
    val i = index(c.x, c.y)
    if (track[i].toInt() != 0 || road[i].toInt() != 0 || stationTiles.containsKey(i) || depotTiles.containsKey(i) || house[i].toInt() != 0 || industryOf[i].toInt() != 0) return
    if (othersBuilding(company.id, i)) return
    val nx = c.x + Track.dx(c.edge)
    val ny = c.y + Track.dy(c.edge)
    if (!inside(nx, ny)) return
    val n = index(nx, ny)
    val joined = road[n].toInt() and 15 or roadBit(Track.opposite(c.edge))
    // Onto road vehicles can drive along: not another garage's own bit of road.
    if (road[n].toInt() == 0 || !roadFits(nx, ny, joined) || track[n].toInt() != 0 || roadStops.containsKey(n) || depotTiles.containsKey(n)) return
    if (company.money < price(Prices.DEPOT) + treeCost(i)) return
    spend(company, Books.CONSTRUCTION, price(Prices.DEPOT) + fellTrees(company, i))
    clearOwnBuilding(company.id, i)
    val d = Depot(depots.size, company.id, i, c.edge, Depots.ROAD).also { it.built = Calendar.year(startYear, day) }
    depots += d
    depotTiles[i] = d.id
    road[n] = (road[n].toInt() or roadBit(Track.opposite(c.edge))).toByte()
    road[i] = roadBit(c.edge).toByte()
    roadKind[i] = roadKindOf(surfaceAt(n), 0).toByte()
    roadOwner[i] = company.id.toByte()
    touched(c.x, c.y)
    touched(nx, ny)
    roadsChanged()
}

/** Whether a road can go over water tile [i]: an ice road, in winter, on ice thick enough to carry trucks. */
fun World.iceRoadFits(i: Int) = isWater(i % width, i / width) && (weather.ice[i].toInt() and 255) >= ICE_ROAD_CM

/** Ice, in cm, an ice road needs to be built and to stay open. */
const val ICE_ROAD_CM = 35

/** Road vehicle routes and who's where are worked out again before the next move. */
internal fun World.roadsChanged() {
    roadNetDirty = true
    for (v in roadVehicles) v?.route = null
}

/**
 * The roads' day: salt or sand spread from each depot on the company's roads round it, potholes from thaw
 * and frost on paved roads, mended by the company's crews or the towns', and ice roads opened and closed
 * as the ice comes and goes.
 */
internal fun World.roadsDay() {
    val year = Calendar.year(startYear, day)
    val salt = year >= SALT_FROM
    // Each tile within reach of its owner's depots is salted once a day, and paid for only where the salt does something.
    val salted = HashSet<Int>()
    for (d in saltDepots.sorted()) {
        val c = companies.getOrNull(roadOwner[d].toInt().coerceAtLeast(0)) ?: continue
        val x0 = d % width
        val y0 = d / width
        var spread = 0
        for (y in y0 - SALT_REACH..y0 + SALT_REACH) for (x in x0 - SALT_REACH..x0 + SALT_REACH) {
            if (!inside(x, y)) continue
            val i = index(x, y)
            if (roadEdges(i) == 0 || roadOwner[i].toInt() != c.id || !salted.add(i)) continue
            val ice = weather.ice[i].toInt() and 255
            val snow = weather.snow[i].toInt() and 255
            // Salt melts ice and thin snow; sand only gives grip (see [sanded]) and leaves them. Neither does anything for deep snow.
            val thin = salt && snow in 1 until 20
            if (ice == 0 && !thin) continue
            if (salt) weather.ice[i] = 0
            if (thin) weather.snow[i] = 0
            spread++
        }
        // A calendar day's salt keeps the road clear for day length work days, so it's paid as that many, with upkeep.
        c.upkeepOwed += (if (salt) SALT_COST else SAND_COST) * spread * settings.dayLength
    }
    var changed = false
    val opens = iceRoadsOpen ?: BooleanArray(0)
    val now = ArrayList<Boolean>()
    var k = 0
    for (i in road.indices) {
        if (road[i].toInt() == 0) continue
        val x = i % width
        val y = i / width
        if (isWater(x, y)) {
            val open = iceRoadFits(i)
            now += open
            if (opens.getOrNull(k) != open) changed = true
            k++
            continue
        }
        if (track[i].toInt() != 0) continue
        var c = condition[i].toInt() and 255
        if (surfaceAt(i) >= Surface.PAVED && (weather.frost[i].toInt() and 255) > 0 && settings.weather && cellDice(x, y, day * 977 + seedBits + 21) < 60) {
            // Thaw under a paved road heaves it into potholes, a point for each work day in the calendar day.
            val t = skyAt(x, y).temperature
            if (t > 0) c -= settings.dayLength
        }
        val owner = roadOwner[i].toInt()
        // Vehicles wear roads by work time, so crews mend a calendar day's worth of work days.
        val mend = settings.dayLength * if (owner < 0) Upkeep.MEND[Upkeep.NORMAL] else Upkeep.MEND[companies.getOrNull(owner)?.upkeep ?: Upkeep.NORMAL]
        c += mend / 100 + if (cellDice(x, y, day * 977 + seedBits + 22) * 100 / 256 < mend % 100) 1 else 0
        condition[i] = c.coerceIn(0, 255).toByte()
    }
    iceRoadsOpen = now.toBooleanArray()
    if (changed) roadsChanged()
}

/** Builds a salt depot on empty land at ([x], [y]) next to the company's road, or takes it down. */
internal fun World.saltDepot(company: Company, c: Command.SaltDepot) {
    if (!inside(c.x, c.y)) return
    val i = index(c.x, c.y)
    if (saltDepots.contains(i)) {
        if (roadOwner[i].toInt() != company.id) return
        saltDepots.remove(i)
        roadOwner[i] = -1
        touched(c.x, c.y)
        return
    }
    if (!isFlat(c.x, c.y) || isWater(c.x, c.y) || track[i].toInt() != 0 || road[i].toInt() != 0 || house[i].toInt() != 0 || industryOf[i].toInt() != 0 || depotTiles.containsKey(i) || stationBuildings.containsKey(i)) return
    val byRoad = intArrayOf(Track.XM, Track.XP, Track.YM, Track.YP).any { e ->
        val nx = c.x + Track.dx(e)
        val ny = c.y + Track.dy(e)
        inside(nx, ny) && roadOwner[index(nx, ny)].toInt() == company.id
    }
    if (!byRoad || company.money < price(SALT_DEPOT_COST)) return
    spend(company, Books.CONSTRUCTION, price(SALT_DEPOT_COST) + fellTrees(company, i))
    saltDepots += i
    // Whose it is, kept where the road owner would be.
    roadOwner[i] = company.id.toByte()
    touched(c.x, c.y)
}

/** Whether road tile [i] is within reach of a sand depot of the road's owner, so it gives grip: sand before [SALT_FROM]. */
fun World.sanded(i: Int): Boolean {
    if (saltDepots.isEmpty() || Calendar.year(startYear, day) >= SALT_FROM) return false
    val owner = roadOwner[i].toInt()
    val x = i % width
    val y = i / width
    for (d in saltDepots) {
        if (roadOwner[d].toInt() == owner && kotlin.math.abs(d % width - x) <= SALT_REACH && kotlin.math.abs(d / width - y) <= SALT_REACH) return true
    }
    return false
}

/** Salt from this year, sand before; how far a depot's trucks spread it, in tiles; and what a tile of salt spread costs a work day, in tenths of a cent, and what sand costs. */
const val SALT_FROM = 1950
const val SALT_REACH = 8
const val SALT_COST = 50L
const val SAND_COST = 20L
/** A salt depot, in dollars before inflation. */
const val SALT_DEPOT_COST = 2_500L
