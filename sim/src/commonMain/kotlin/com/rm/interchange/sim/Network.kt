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
class Station(val id: Int, val owner: Int, var name: String, val signX: Int, val signY: Int) {
    /** Its platform tiles, as tile indexes, in the order they were built. */
    val tiles = ArrayList<Int>()
    val waiting = IntArray(Cargo.COUNT)
}

/** A train depot, open at [edge] of its tile. */
class Depot(val id: Int, val owner: Int, val tile: Int, val edge: Int)

/** What a signal does. */
object Signals {
    const val TWO_WAY = 0
    const val ONE_WAY = 1

    /** A signal packed into an int: the edge it faces, and its kind. */
    fun pack(facing: Int, kind: Int) = facing or (kind shl 2)
    fun facing(signal: Int) = signal and 3
    fun kind(signal: Int) = signal shr 2
}

/** The key of piece [piece] on tile [tile], for maps that hold something per piece. */
fun pieceKey(tile: Int, piece: Int) = tile * 8 + Track.index(piece)

fun World.stationAt(tile: Int): Station? = stationTiles[tile]?.let { stations[it] }

fun World.depotAt(tile: Int): Depot? = depotTiles[tile]?.let { depots[it] }

/** The signal on [piece] of [tile], packed, or -1. */
fun World.signalAt(tile: Int, piece: Int): Int = signals[pieceKey(tile, piece)] ?: -1

/**
 * Whether a train may run along [piece] of [tile] from [entry] to its other
 * edge: not against a one-way signal.
 */
fun World.mayPass(tile: Int, piece: Int, entry: Int): Boolean {
    val s = signalAt(tile, piece)
    return s < 0 || Signals.kind(s) != Signals.ONE_WAY || Signals.facing(s) == entry
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
    val cost = Prices.PLATFORM * tiles.size + Prices.FOUNDATION * sloping + tiles.sumOf { treeCost(it) }
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
    company.money -= cost
    for (i in tiles) {
        clearTrees(i)
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
    if (company.money < Prices.DEPOT + treeCost(i)) return
    company.money -= Prices.DEPOT + clearTrees(i)
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
    when {
        had == null -> {
            if (company.money < Prices.SIGNAL) return
            company.money -= Prices.SIGNAL
            signals[key] = Signals.pack(c.facing, Signals.TWO_WAY)
        }
        Signals.facing(had) != c.facing -> signals[key] = Signals.pack(c.facing, Signals.kind(had))
        Signals.kind(had) == Signals.TWO_WAY -> signals[key] = Signals.pack(c.facing, Signals.ONE_WAY)
        else -> signals.remove(key)
    }
    networkChanged()
}

/**
 * Clears tile ([x], [y]) of what this company has on it: track, signals, a
 * platform or a depot. Not while a train is on it or in the depot.
 */
internal fun World.clearTile(company: Company, c: Command.Clear) {
    if (!inside(c.x, c.y)) return
    val i = index(c.x, c.y)
    if (owner[i].toInt() != company.id || trainOnTile(i)) return
    depotAt(i)?.let { d -> if (trains.any { it != null && it.depot == d.id && it.inDepot }) return }
    if (company.money < Prices.CLEAR) return
    company.money -= Prices.CLEAR
    for (p in Track.PIECES) signals.remove(pieceKey(i, p))
    stationTiles.remove(i)?.let { id ->
        val s = stations[id]!!
        s.tiles.remove(i)
        if (s.tiles.isEmpty()) {
            stations[id] = null
            for (t in trains) t?.orders?.removeAll { it == id }
        }
    }
    depotTiles.remove(i)?.let { id -> depots[id] = null }
    track[i] = 0
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
private fun World.stationName(x: Int, y: Int): String {
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
