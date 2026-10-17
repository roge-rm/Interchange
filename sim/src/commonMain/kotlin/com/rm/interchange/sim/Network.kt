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
 * next to it if there's one of this company's, or makes a new one.
 */
internal fun World.buildStation(company: Company, c: Command.Station) {
    if (c.axis != Track.X && c.axis != Track.Y) return
    val xs = minOf(c.x0, c.x1)..maxOf(c.x0, c.x1)
    val ys = minOf(c.y0, c.y1)..maxOf(c.y0, c.y1)
    if (xs.last - xs.first >= MAX_STATION || ys.last - ys.first >= MAX_STATION) return
    if (townRefuses(company, xs.first, ys.first)) return
    val tiles = ArrayList<Int>()
    for (y in ys) for (x in xs) {
        if (!inside(x, y) || isWater(x, y)) return
        val i = index(x, y)
        if (stationTiles.containsKey(i) || depotTiles.containsKey(i) || townBuilt(i)) return
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
        // Any track already there must run the same way, checked above, so the platform's track joins it.
        track[i] = c.axis.toByte()
        owner[i] = company.id.toByte()
        stationTiles[i] = s.id
        s.tiles += i
        touched(i % width, i / width)
    }
    networkChanged()
}

/**
 * Builds a depot on tile ([x], [y]) open at [edge]. The tile has to be flat,
 * dry and empty.
 */
internal fun World.buildDepot(company: Company, c: Command.Depot) {
    if (!inside(c.x, c.y) || !isFlat(c.x, c.y) || isWater(c.x, c.y) || c.edge !in 0..3) return
    val i = index(c.x, c.y)
    if (trackAt(c.x, c.y) != 0 || stationTiles.containsKey(i) || depotTiles.containsKey(i) || townBuilt(i)) return
    val piece = Track.straight(c.edge)
    if (!piecesFit(c.x, c.y, piece)) return
    if (company.money < price(Prices.DEPOT) + treeCost(i)) return
    spend(company, Books.CONSTRUCTION, price(Prices.DEPOT) + fellTrees(company, i))
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
 * platform or a depot. Not while a train is on it or in the depot.
 */
internal fun World.clearTile(company: Company, c: Command.Clear) {
    if (!inside(c.x, c.y)) return
    val i = index(c.x, c.y)
    if (!owns(company.id, i) || trainOnTile(i)) return
    depotAt(i)?.let { d -> if (trains.any { it != null && it.depot == d.id && it.inDepot }) return }
    if (company.money < price(Prices.CLEAR)) return
    // A road stop comes off its road; a garage goes, with its bit of road.
    roadStops[i]?.let { id ->
        if (roadVehicleOn(i)) return
        spend(company, Books.CONSTRUCTION, price(Prices.CLEAR))
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
        spend(company, Books.CONSTRUCTION, price(Prices.CLEAR))
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
    // A bridge or tunnel goes all at once, from either head.
    spanHeads[i]?.let { id ->
        val s = spans[id] ?: return
        if (removeSpan(s)) spend(company, Books.CONSTRUCTION, price(Prices.CLEAR))
        return
    }
    spend(company, Books.CONSTRUCTION, price(Prices.CLEAR))
    for (p in Track.PIECES) signals.remove(pieceKey(i, p))
    stationTiles.remove(i)?.let { id ->
        val s = stations[id]!!
        s.tiles.remove(i)
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
    stations[id] = null
    for (t in carriers()) t.orders.removeAll { Order.station(it) == id }
}

/** Everything that calls at stations. */
fun World.carriers(): List<Carrier> = trains.filterNotNull() + roadVehicles.filterNotNull() + ships.filterNotNull() + aircraft.filterNotNull()
