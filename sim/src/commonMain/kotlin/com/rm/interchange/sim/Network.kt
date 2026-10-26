package com.rm.interchange.sim

/*
 * Stations, depots and signals: what's built on and beside the track.
 *
 * A station platform is a tile of straight track with a platform beside it,
 * flat, and belonging to a station; platforms next to each other belong to
 * the same one. A depot is a tile of straight track with a shed over it, open
 * at one edge only. Trains can only go in and out at that edge.
 *
 * A signal sits on a track piece at one of its edges, facing trains coming
 * into the piece through that edge. A two-way signal also stops trains leaving
 * through that edge from the other side; a one-way one lets nothing through
 * the other way. Signals only go on tiles where the track doesn't branch.
 */

/** A station: a name, the platforms that make it up, and what's waiting there, by [Cargo]. */
class Station(val id: Int, var owner: Int, var name: String, val signX: Int, val signY: Int, val waypoint: Boolean = false) {
    /** Its platform tiles, as tile indexes, in the order they were built, its road stops, its docks and its airports' tiles. */
    val tiles = ArrayList<Int>()
    val stops = ArrayList<Int>()
    val docks = ArrayList<Int>()
    val aero = ArrayList<Int>()
    /** Lots waiting, oldest first, by cargo. */
    val cargo = Array(Cargo.COUNT) { ArrayList<Packet>() }
    /** How well it serves each cargo, 0 to 255, and when a train last called that could take it and how fast that train goes. */
    val rating = IntArray(Cargo.COUNT) { START_RATING }
    val lastCall = LongArray(Cargo.COUNT) { -1 }
    val lastSpeed = IntArray(Cargo.COUNT)
    /** Its station building's tile, or -1; the year it went up, for its style; the edge of its tile that faces the platform. */
    var building = -1
    var buildingYear = 0
    var buildingFacing = 0
}

/** Every tile the station has, for its catchment. */
val Station.places get() = tiles + stops + docks + aero

/** Whether nothing's left of it. */
val Station.empty get() = tiles.isEmpty() && stops.isEmpty() && docks.isEmpty() && aero.isEmpty()

/** A depot, open at [edge] of its tile: an engine house, a garage, a shipyard or a hangar, by [Depots] kind. */
class Depot(val id: Int, var owner: Int, val tile: Int, val edge: Int, val kind: Int = Depots.RAIL) {
    val road get() = kind == Depots.ROAD
}

object Depots {
    const val RAIL = 0
    const val ROAD = 1
    const val SHIPYARD = 2
    const val HANGAR = 3
}

/** What a signal does. */
object Signals {
    /** Block signals, two-way and one-way. */
    const val TWO_WAY = 0
    const val ONE_WAY = 1
    /** Path signals, two-way and one-way: a train reserves its way through to the next signal, and trains share a block when their ways don't cross. */
    const val PATH = 2
    const val ONE_WAY_PATH = 3
    /** Block signals for a block with more than one way out: an entry signal clears only if a way out of the block ahead is clear, at an exit signal; a combo signal is both. */
    const val ENTRY = 4
    const val EXIT = 5
    const val COMBO = 6

    fun oneWay(signal: Int) = kind(signal) == ONE_WAY || kind(signal) == ONE_WAY_PATH
    fun path(signal: Int) = kind(signal) == PATH || kind(signal) == ONE_WAY_PATH
    /** The kind a placing tool asks for, with one-way left to tapping again. */
    val PLACED = intArrayOf(TWO_WAY, PATH, ENTRY, EXIT, COMBO)

    /** A signal packed into an int: the edge it faces, and its kind. */
    fun pack(facing: Int, kind: Int) = facing or (kind shl 2)
    fun facing(signal: Int) = signal and 3
    fun kind(signal: Int) = signal shr 2
}

/** The key of piece [piece] on tile [tile], for maps that hold something per piece. */
fun pieceKey(tile: Int, piece: Int, level: Int = 0) = if (level == 0) tile * 8 + Track.index(piece) else -(tile * 8 + Track.index(piece)) - 1

fun World.stationAt(tile: Int): Station? = stationTiles[tile]?.let { stations[it] }

fun World.depotAt(tile: Int): Depot? = depotTiles[tile]?.let { depots[it] }

/** Whether [tile] is a station's platform, as against plain track or a waypoint. */
fun World.isPlatform(tile: Int): Boolean = stationTiles[tile]?.let { stations.getOrNull(it)?.waypoint == false } == true

/** The signal on [piece] of [tile], packed, or -1. */
fun World.signalAt(tile: Int, piece: Int): Int = signals[pieceKey(tile, piece)] ?: -1

/**
 * Whether a train may run along [piece] of [tile] from [entry] to its other
 * edge: not against a one-way signal.
 */
fun World.mayPass(tile: Int, piece: Int, entry: Int): Boolean {
    val s = signalAt(tile, piece)
    return s < 0 || !Signals.oneWay(s) || Signals.facing(s) == entry
}

/** Whether a signal could go on [piece] of tile ([x], [y]): plain track that doesn't branch. */
fun World.signalFits(x: Int, y: Int, piece: Int): Boolean {
    if (!inside(x, y)) return false
    val i = index(x, y)
    if (stationTiles.containsKey(i) || depotTiles.containsKey(i)) return false
    val t = trackAt(x, y)
    if (t and piece == 0) return false
    return t == piece || t == (Track.N or Track.S) || t == (Track.E or Track.W)
}

/**
 * Lays platforms over the rectangle from ([x0], [y0]) to ([x1], [y1]), each
 * running along [axis] ([Track.X] or [Track.Y]). Each tile has to be flat and
 * dry, and empty or already straight track along [axis]. Joins the station
 * next to it if there's one of this company's, or makes a new one. A rectangle
 * of one station's platforms turns that whole station onto [axis], for nothing.
 */
internal fun World.buildStation(company: Company, c: Command.Station) {
    if (c.axis != Track.X && c.axis != Track.Y) return
    val xs = minOf(c.x0, c.x1)..maxOf(c.x0, c.x1)
    val ys = minOf(c.y0, c.y1)..maxOf(c.y0, c.y1)
    if (xs.last - xs.first >= MAX_STATION || ys.last - ys.first >= MAX_STATION) return
    if (turnWholeStation(company, c, xs, ys)) return
    if (townRefuses(company, xs.first, ys.first)) return
    val tiles = ArrayList<Int>()
    for (y in ys) for (x in xs) {
        if (!inside(x, y) || isWater(x, y)) return
        val i = index(x, y)
        if (stationTiles.containsKey(i) || depotTiles.containsKey(i) || townBuilt(i) || othersBuilding(company.id, i)) return
        val t = trackAt(x, y)
        if (t != 0 && (t != c.axis || owner[i].toInt() != company.id)) return
        if (signals.containsKey(pieceKey(i, c.axis))) return
        tiles += i
    }
    // Platforms on sloping land stand on a foundation, level with the highest corner; their track has to meet any already next door.
    val sloping = tiles.count { !isFlat(it % width, it / width) }
    val cost = price(Prices.PLATFORM) * tiles.size + price(Prices.FOUNDATION) * sloping + tiles.sumOf { treeCost(it) }
    if (company.money < cost) return
    for (i in tiles) stationTiles[i] = -1
    val fits = tiles.all { i ->
        val x = i % width
        val y = i / width
        piecesFit(x, y, c.axis) { t -> if (t in tiles) c.axis else track[t].toInt() }
    }
    for (i in tiles) stationTiles.remove(i)
    if (!fits) return
    // A station of this company's touching the rectangle, or within a tile of it.
    var station: Station? = null
    for (y in ys.first - 1..ys.last + 1) for (x in xs.first - 1..xs.last + 1) {
        if (station == null && inside(x, y)) station = stationAt(index(x, y))?.takeIf { it.owner == company.id }
    }
    val s = station ?: Station(stations.size, company.id, stationName(xs.first, ys.first), xs.first, ys.first).also { stations += it }
    spend(company, Books.CONSTRUCTION, cost)
    for (i in tiles) {
        fellTrees(company, i)
        clearOwnBuilding(company.id, i)
        // Any track already there must run the same way, checked above, so the platform's track joins it.
        track[i] = c.axis.toByte()
        owner[i] = company.id.toByte()
        stationTiles[i] = s.id
        s.tiles += i
        touched(i % width, i / width)
    }
    placeStationBuilding(s, c.axis)
    networkChanged()
}

/** The station whose building stands on tile [i], or null. */
fun World.stationBuildingAt(i: Int): Station? = stationBuildings[i]?.let { stations.getOrNull(it) }

/** Whether another company's station building stands on tile [i], which keeps [company] off it. */
internal fun World.othersBuilding(company: Int, i: Int): Boolean = stationBuildingAt(i)?.let { it.owner != company } == true

/** Takes [company]'s own station building off tile [i], if one stands there, so its track or road can go on. */
internal fun World.clearOwnBuilding(company: Int, i: Int) {
    stationBuildingAt(i)?.takeIf { it.owner == company }?.let { dropStationBuilding(it) }
}

/** Takes station [s]'s building down, if it has one. */
internal fun World.dropStationBuilding(s: Station) {
    val i = s.building
    if (i < 0) return
    stationBuildings.remove(i)
    s.building = -1
    s.buildingYear = 0
    s.buildingFacing = 0
    touched(i % width, i / width)
    touchStation(s)
}

/** Marks every platform tile of [s] changed, so its canopies bake again with the building's year. */
private fun World.touchStation(s: Station) {
    for (t in s.tiles) touched(t % width, t / width)
}

/** Whether station [s]'s building, if it has one, still faces a platform of its own. */
private fun World.facesPlatform(s: Station): Boolean {
    val b = s.building
    if (b < 0) return true
    val x = b % width + Track.dx(s.buildingFacing)
    val y = b / width + Track.dy(s.buildingFacing)
    return inside(x, y) && stationTiles[index(x, y)] == s.id
}

/** The style of a station building put up in [year]: 0 wooden depot, 1 standard plan, 2 brick and stone, 3 modern flat roof, 4 glass. */
fun stationEra(year: Int): Int = when {
    year < 1885 -> 0
    year < 1915 -> 1
    year < 1955 -> 2
    year < 1990 -> 3
    else -> 4
}

/**
 * Puts up station [s]'s building, if it has none, on a free tile just outside
 * its platforms' bounding box: the middle along [axis] first, then a step
 * either way, minus first, and so on. At each step the side with fewer track
 * tiles within 2 tiles goes first, the minus side on a tie. Free, from the
 * world's state alone; no fitting tile leaves it without one.
 */
internal fun World.placeStationBuilding(s: Station, axis: Int) {
    if (s.waypoint || s.building >= 0 || s.tiles.isEmpty()) return
    var x0 = width; var y0 = height; var x1 = -1; var y1 = -1
    for (t in s.tiles) {
        val x = t % width
        val y = t / width
        x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
    }
    val alongX = axis == Track.X
    val lo = if (alongX) x0 else y0
    val hi = if (alongX) x1 else y1
    val mid = (lo + hi) / 2
    for (k in 0..2 * (hi - lo)) {
        val a = if (k % 2 == 1) mid - (k + 1) / 2 else mid + k / 2
        if (a !in lo..hi) continue
        // The tile outside the box on the minus side and on the plus side, each with the platform tile it backs onto and the edge facing it.
        val minus = if (alongX) intArrayOf(a, y0 - 1, a, y0, Track.YP) else intArrayOf(x0 - 1, a, x0, a, Track.XP)
        val plus = if (alongX) intArrayOf(a, y1 + 1, a, y1, Track.YM) else intArrayOf(x1 + 1, a, x1, a, Track.XM)
        val sides = if (trackNear(s, plus[0], plus[1]) < trackNear(s, minus[0], minus[1])) listOf(plus, minus) else listOf(minus, plus)
        for (c in sides) {
            if (!buildingFits(s, c[0], c[1], c[2], c[3])) continue
            val i = index(c[0], c[1])
            clearTrees(i)
            s.building = i
            s.buildingYear = Calendar.year(startYear, day)
            s.buildingFacing = c[4]
            stationBuildings[i] = s.id
            touched(c[0], c[1])
            touchStation(s)
            return
        }
    }
}

/** Tiles with track within 2 tiles of ([x], [y]), leaving out [s]'s own platforms. */
private fun World.trackNear(s: Station, x: Int, y: Int): Int {
    var n = 0
    for (ty in y - 2..y + 2) for (tx in x - 2..x + 2) {
        if (!inside(tx, ty)) continue
        val i = index(tx, ty)
        if (track[i].toInt() != 0 && stationTiles[i] != s.id) n++
    }
    return n
}

/** Whether tile ([x], [y]) could take [s]'s building, backing onto its platform at ([px], [py]): dry, flat, level with the platform's top and empty. */
private fun World.buildingFits(s: Station, x: Int, y: Int, px: Int, py: Int): Boolean {
    if (!inside(x, y) || !inside(px, py) || stationTiles[index(px, py)] != s.id) return false
    if (isWater(x, y) || !isFlat(x, y)) return false
    val top = maxOf(maxOf(heightAt(px, py), heightAt(px + 1, py)), maxOf(heightAt(px + 1, py + 1), heightAt(px, py + 1)))
    if (heightAt(x, y) != top) return false
    val i = index(x, y)
    return track[i].toInt() == 0 && owner[i].toInt() == -1 && roadOwner[i].toInt() == -1 && !townBuilt(i) &&
        !stationTiles.containsKey(i) && !depotTiles.containsKey(i) && !roadStops.containsKey(i) && !docks.containsKey(i) &&
        !airportTiles.containsKey(i) && !canals.contains(i) && !locks.containsKey(i) && !saltDepots.contains(i) &&
        !spanHeads.containsKey(i) && !deck.containsKey(i) && !stationBuildings.containsKey(i) && !fences.contains(i)
}

/**
 * Turns the one station covering [xs] by [ys] onto [c]'s axis, when every tile
 * there is its platform and [company] owns it. Returns false when the rectangle
 * is anything else. Costs nothing, and leaves the station where a train is on
 * it or the new axis would not fit.
 */
private fun World.turnWholeStation(company: Company, c: Command.Station, xs: IntRange, ys: IntRange): Boolean {
    var station: Station? = null
    for (y in ys) for (x in xs) {
        if (!inside(x, y)) return false
        val s = stationAt(index(x, y)) ?: return false
        if (s.waypoint || s.owner != company.id) return false
        val held = station
        if (held != null && held.id != s.id) return false
        station = s
    }
    val s = station ?: return false
    if (s.tiles.any { trainOnTile(it) }) return true
    if (s.tiles.all { track[it].toInt() == c.axis }) return true
    val ours = s.tiles.toHashSet()
    val fits = s.tiles.all { i ->
        piecesFit(i % width, i / width, c.axis) { t -> if (t in ours) c.axis else track[t].toInt() }
    }
    if (!fits) return true
    var changed = false
    for (i in s.tiles) {
        if (track[i].toInt() == c.axis) continue
        // The old axis is gone, so every signal on the tile comes off with it.
        for (p in Track.PIECES) signals.remove(pieceKey(i, p))
        track[i] = c.axis.toByte()
        touched(i % width, i / width)
        changed = true
    }
    if (changed) {
        // The building stood beside the old axis, so it goes up again beside the new one.
        if (s.building >= 0) {
            dropStationBuilding(s)
            placeStationBuilding(s, c.axis)
        }
        networkChanged()
    }
    return true
}

/**
 * Builds a depot on tile ([x], [y]) open at [edge]. The tile has to be flat,
 * dry and empty.
 */
internal fun World.buildDepot(company: Company, c: Command.Depot) {
    if (!inside(c.x, c.y) || !isFlat(c.x, c.y) || isWater(c.x, c.y) || c.edge !in 0..3) return
    val i = index(c.x, c.y)
    if (trackAt(c.x, c.y) != 0 || stationTiles.containsKey(i) || depotTiles.containsKey(i) || townBuilt(i) || othersBuilding(company.id, i)) return
    val piece = Track.straight(c.edge)
    if (!piecesFit(c.x, c.y, piece)) return
    if (company.money < price(Prices.DEPOT) + treeCost(i)) return
    spend(company, Books.CONSTRUCTION, price(Prices.DEPOT) + fellTrees(company, i))
    clearOwnBuilding(company.id, i)
    val d = Depot(depots.size, company.id, i, c.edge)
    depots += d
    depotTiles[i] = d.id
    track[i] = piece.toByte()
    owner[i] = company.id.toByte()
    touched(c.x, c.y)
    networkChanged()
}

/**
 * Puts a signal on [Command.Signal.piece] facing [Command.Signal.facing], or
 * moves an existing one on: two-way, then one-way, then gone.
 */
internal fun World.placeSignal(company: Company, c: Command.Signal) {
    if (!signalFits(c.x, c.y, c.piece) || !Track.has(c.piece, c.facing)) return
    val i = index(c.x, c.y)
    if (owner[i].toInt() != company.id) return
    val key = pieceKey(i, c.piece)
    val had = signals[key]
    val kind = if (c.kind in Signals.PLACED) c.kind else Signals.TWO_WAY
    // The kind asked for, with block and path signals going one-way on a second tap and away on a third.
    val base = had?.let { when (Signals.kind(it)) { Signals.ONE_WAY -> Signals.TWO_WAY; Signals.ONE_WAY_PATH -> Signals.PATH; else -> Signals.kind(it) } }
    when {
        had == null -> {
            if (company.money < price(Prices.SIGNAL)) return
            spend(company, Books.CONSTRUCTION, price(Prices.SIGNAL))
            signals[key] = Signals.pack(c.facing, kind)
        }
        Signals.facing(had) != c.facing -> signals[key] = Signals.pack(c.facing, Signals.kind(had))
        base != kind -> signals[key] = Signals.pack(c.facing, kind)
        Signals.kind(had) == Signals.TWO_WAY -> signals[key] = Signals.pack(c.facing, Signals.ONE_WAY)
        Signals.kind(had) == Signals.PATH -> signals[key] = Signals.pack(c.facing, Signals.ONE_WAY_PATH)
        else -> signals.remove(key)
    }
    networkChanged()
}

/** Where a signal of a run goes: tile ([x], [y]), the [piece] and the edge it [facing]. */
data class SignalSpot(val x: Int, val y: Int, val piece: Int, val facing: Int)

/** The most signals a run may be spaced to, and the least. */
const val SIGNAL_SPACING_MIN = 2
const val SIGNAL_SPACING_MAX = 8

/**
 * The signals [c] would place, in order along its line: every spacing'th tile
 * from the first that takes one, on the company's own track, facing trains that
 * come the way the line goes. Empty for a removal or a line that makes no sense.
 */
fun World.planSignals(c: Command.SignalRun): List<SignalSpot> {
    if (c.remove) return emptyList()
    val line = Track.line(this, c.sx, c.sy, c.ex, c.ey, c.first) ?: return emptyList()
    val step = c.spacing.coerceIn(SIGNAL_SPACING_MIN, SIGNAL_SPACING_MAX)
    val out = ArrayList<SignalSpot>()
    for (k in line.indices step step) {
        val i = line[k] / 64
        val piece = line[k] % 64
        val x = i % width
        val y = i / width
        if (owner[i].toInt() != c.company || !signalFits(x, y, piece)) continue
        // Trains go in at the edge that leads back to the previous tile, or for the first tile, the one that doesn't lead to the next.
        val edges = intArrayOf(Track.edges(piece) and 3, Track.edges(piece) shr 2)
        val toward = { tile: Int -> edges.firstOrNull { inside(x + Track.dx(it), y + Track.dy(it)) && index(x + Track.dx(it), y + Track.dy(it)) == tile } }
        val facing = when {
            k > 0 -> toward(line[k - 1] / 64)
            line.size > 1 -> toward(line[1] / 64)?.let { Track.other(piece, it) }
            else -> null
        } ?: edges[0]
        out += SignalSpot(x, y, piece, facing)
    }
    return out
}

/** What a [Command.SignalRun] would cost: the price of a signal for each tile that gets a new one, and how many. */
fun World.signalRunCost(c: Command.SignalRun): Pair<Long, Int> {
    val n = planSignals(c).count { signals[pieceKey(index(it.x, it.y), it.piece)] == null }
    return price(Prices.SIGNAL) * n to n
}

/**
 * Places a [Command.SignalRun], or takes signals off. A signal already on a spot
 * is changed to the kind and way asked for, free. Nothing is done if the new
 * ones cost more than the company has.
 */
internal fun World.placeSignalRun(company: Company, c: Command.SignalRun) {
    if (c.remove) {
        val line = Track.line(this, c.sx, c.sy, c.ex, c.ey, c.first) ?: return
        var any = false
        for (entry in line) {
            val i = entry / 64
            if (owner[i].toInt() != company.id) continue
            if (signals.remove(pieceKey(i, entry % 64)) != null) any = true
        }
        if (any) networkChanged()
        return
    }
    val spots = planSignals(c)
    if (spots.isEmpty()) return
    val (cost, _) = signalRunCost(c)
    if (company.money < cost) return
    val kind = if (c.kind in Signals.PLACED) c.kind else Signals.TWO_WAY
    if (cost > 0) spend(company, Books.CONSTRUCTION, cost)
    for (s in spots) signals[pieceKey(index(s.x, s.y), s.piece)] = Signals.pack(s.facing, kind)
    networkChanged()
}

/** Marks a tile of the company's plain straight track as a waypoint: a station with no platform that trains run through. */
internal fun World.buildWaypoint(company: Company, c: Command.Waypoint) {
    if (!inside(c.x, c.y)) return
    val i = index(c.x, c.y)
    val t = track[i].toInt()
    if (owner[i].toInt() != company.id || (t != Track.X && t != Track.Y) || !isFlat(c.x, c.y) || stationTiles.containsKey(i) || depotTiles.containsKey(i)) return
    if (company.money < price(Prices.SIGNAL)) return
    spend(company, Books.CONSTRUCTION, price(Prices.SIGNAL))
    val s = Station(stations.size, company.id, stationName(c.x, c.y), c.x, c.y, waypoint = true)
    s.tiles += i
    stations += s
    stationTiles[i] = s.id
    touched(c.x, c.y)
    networkChanged()
}

/**
 * Clears tile ([x], [y]) of what this company has on it: track, signals, a
 * platform or a depot. Not while a train is on it or in the depot. Selling
 * it pays scrap, about half what it cost, and a company with no money can still sell.
 */
internal fun World.clearTile(company: Company, c: Command.Clear) {
    if (!inside(c.x, c.y)) return
    val i = index(c.x, c.y)
    if (!owns(company.id, i) || trainOnTile(i)) return
    // A station building comes down by itself, for nothing.
    stationBuildingAt(i)?.let { s ->
        if (s.owner == company.id) dropStationBuilding(s)
        return
    }
    depotAt(i)?.let { d -> if (trains.any { it != null && it.depot == d.id && it.inDepot }) return }
    // A road stop comes off its road; a garage goes, with its bit of road.
    roadStops[i]?.let { id ->
        if (roadVehicleOn(i)) return
        scrap(company, Prices.ROAD_STOP)
        roadStops.remove(i)
        val s = stations[id]!!
        s.stops.remove(i)
        if (s.empty) dropStation(id)
        touched(c.x, c.y)
        networkChanged()
        roadsChanged()
        return
    }
    if (clearWaterOrAir(company, i)) return
    depotAt(i)?.takeIf { it.road }?.let { d ->
        if (roadVehicles.any { it != null && it.inDepot && it.depot == d.id }) return
        scrap(company, Prices.DEPOT)
        depotTiles.remove(i)
        depots[d.id] = null
        depotGone(d)
        road[i] = 0; roadKind[i] = 0; roadOwner[i] = -1
        val n = index(c.x + Track.dx(d.edge), c.y + Track.dy(d.edge))
        road[n] = (road[n].toInt() and roadBit(Track.opposite(d.edge)).inv()).toByte()
        touched(c.x, c.y)
        roadsChanged()
        return
    }
    // A bridge or tunnel goes all at once, from either head, and scrap is paid once for the span.
    spanHeads[i]?.let { id ->
        val s = spans[id] ?: return
        if (removeSpan(s)) scrap(company, spanBuilt(s))
        return
    }
    scrapOwnedTrack(company, i)
    for (p in Track.PIECES) signals.remove(pieceKey(i, p))
    stationTiles.remove(i)?.let { id ->
        val s = stations[id]!!
        s.tiles.remove(i)
        if (s.tiles.isEmpty()) dropStationBuilding(s) else if (!facesPlatform(s)) {
            dropStationBuilding(s)
            placeStationBuilding(s, track[s.tiles.first()].toInt())
        }
        if (s.empty) dropStation(id)
    }
    depotTiles.remove(i)?.let { id -> depots[id]?.let { d -> depots[id] = null; depotGone(d) } }
    track[i] = 0
    wires[i] = 0
    sheds.remove(i)
    owner[i] = -1
    touched(c.x, c.y)
    networkChanged()
}

/** A name for a new station: none yet, so the screen calls it by its number in its own language. Towns will name them. */
@Suppress("UNUSED_PARAMETER")
/**
 * A new station's name, after the nearest town within [NAMING_REACH] tiles:
 * its own name, then with which way the station lies from its middle, or
 * "Centre", as the town's language writes them. Empty if none fits, and the
 * screen calls it by its number.
 */
internal fun World.stationName(x: Int, y: Int): String {
    val t = towns.minByOrNull { maxOf(kotlin.math.abs(it.x - x), kotlin.math.abs(it.y - y)) } ?: return ""
    if (maxOf(kotlin.math.abs(t.x - x), kotlin.math.abs(t.y - y)) > NAMING_REACH) return ""
    val taken = stations.mapNotNull { it?.name }.toSet()
    if (t.name !in taken) return t.name
    val dx = x - t.x
    val dy = y - t.y
    // The way it lies first, then the others.
    val ways = listOf(
        if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) (if (dx > 0) 1 else 3) else (if (dy > 0) 2 else 0),
        0, 1, 2, 3, 4,
    ).distinct()
    val english = listOf(" North", " East", " South", " West", " Central")
    val french = listOf("-Nord", "-Est", "-Sud", "-Ouest", "-Centre")
    for (w in ways) {
        val name = t.name + (if (t.french) french else english)[w]
        if (name !in taken) return name
    }
    return ""
}

/** How far from a town's middle a station is still named after it, in tiles. */
private const val NAMING_REACH = 20

/** The most tiles a station's rectangle spans either way in one go. */
const val MAX_STATION = 16

/** Takes station [id] off the map and out of every order. */
internal fun World.dropStation(id: Int) {
    stations[id]?.let { dropStationBuilding(it) }
    stations[id] = null
    for (t in carriers()) t.orders.removeAll { Order.station(it) == id }
}

/** Everything that calls at stations. */
fun World.carriers(): List<Carrier> = trains.filter { it != null && it.wreckedFor == 0 }.map { it!! } + roadVehicles.filter { it != null && it.wreckedFor == 0 }.map { it!! } +
    ships.filterNotNull() + aircraft.filter { it != null && it.wreckedFor == 0 }.map { it!! }
