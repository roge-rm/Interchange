package com.rm.interchange.sim

/*
 * The rules for where track can go.
 *
 * Track lies on the land where the land suits it: a flat tile, or a single
 * straight piece running straight up a slope. Anywhere else the tile is built
 * up on a foundation. Under a single straight piece the foundation slopes
 * along it, each end level with the higher corner at that end, so a line can
 * climb across uneven land; under anything else it's flat, level with the
 * tile's highest corner.
 *
 * Where track crosses from one tile to the next, both sides have to be at the
 * same height there, or a train couldn't pass. A piece that would leave a step
 * at a join with track already there isn't built.
 *
 * A road goes up on a foundation by the same rules, lying as the track piece
 * [roadPieces] says. On a tile with track, the track decides.
 */

// The corners of a tile, in the order the surface heights come in.
const val CORNER_N = 0
const val CORNER_E = 1
const val CORNER_S = 2
const val CORNER_W = 3

/** The track piece road [bits] lie like: straight or a dead end along x or y as that piece, anything else as a junction. 0 for none. */
fun roadPieces(bits: Int): Int {
    val b = bits and 15
    if (b == 0) return 0
    val alongX = roadBit(Track.XM) or roadBit(Track.XP)
    val alongY = roadBit(Track.YM) or roadBit(Track.YP)
    return when {
        b and alongY == 0 -> Track.X
        b and alongX == 0 -> Track.Y
        else -> Track.X or Track.Y
    }
}

/** Track [pieces] on tile ([x], [y]), or with none the road there as [roadPieces] has it. */
private fun World.builtPieces(x: Int, y: Int, pieces: Int) = if (pieces != 0) pieces else roadPieces(road[index(x, y)].toInt())

/** Whether track [trackPieces] on tile ([x], [y]), or with none its road, need a foundation. */
fun World.needsFoundation(x: Int, y: Int, trackPieces: Int): Boolean {
    val pieces = builtPieces(x, y, trackPieces)
    if (pieces == 0 || isFlat(x, y)) return false
    // Platforms are always level.
    if (stationTiles.containsKey(index(x, y))) return true
    val n = heightAt(x, y)
    val e = heightAt(x + 1, y)
    val s = heightAt(x + 1, y + 1)
    val w = heightAt(x, y + 1)
    return when (pieces) {
        Track.X -> !(n == w && e == s)
        Track.Y -> !(n == e && w == s)
        else -> true
    }
}

fun World.hasFoundation(x: Int, y: Int) = needsFoundation(x, y, trackAt(x, y))

/**
 * The height of what track on tile ([x], [y]) lies on at [corner], with
 * [trackPieces] on it, or with none what its road lies on: the land, or the top of
 * its foundation.
 */
fun World.surfaceHeight(x: Int, y: Int, trackPieces: Int, corner: Int): Int {
    val pieces = builtPieces(x, y, trackPieces)
    // A tunnel's portal runs level into the hill at the foot of its slope.
    spanHeads[index(x, y)]?.let { id -> spans[id]?.let { if (it.tunnel) return it.height } }
    val n = heightAt(x, y)
    val e = heightAt(x + 1, y)
    val s = heightAt(x + 1, y + 1)
    val w = heightAt(x, y + 1)
    if (!needsFoundation(x, y, pieces)) return when (corner) { CORNER_N -> n; CORNER_E -> e; CORNER_S -> s; else -> w }
    if (stationTiles.containsKey(index(x, y))) return maxOf(maxOf(n, e), maxOf(s, w))
    return when (pieces) {
        Track.X -> if (corner == CORNER_N || corner == CORNER_W) maxOf(n, w) else maxOf(e, s)
        Track.Y -> if (corner == CORNER_N || corner == CORNER_E) maxOf(n, e) else maxOf(w, s)
        else -> maxOf(maxOf(n, e), maxOf(s, w))
    }
}

/** The height track meets [edge] of tile ([x], [y]) at, with [pieces] on it. Every edge a piece uses has both its corners level. */
fun World.edgeHeight(x: Int, y: Int, pieces: Int, edge: Int): Int = when (edge) {
    Track.XM, Track.YM -> surfaceHeight(x, y, pieces, CORNER_N)
    Track.XP -> surfaceHeight(x, y, pieces, CORNER_E)
    else -> surfaceHeight(x, y, pieces, CORNER_W)
}

/** The tile across [edge] of ([x], [y]), packed as an index, or -1 off the map. */
private fun World.tileAcross(x: Int, y: Int, edge: Int): Int {
    val nx = x + when (edge) { Track.XM -> -1; Track.XP -> 1; else -> 0 }
    val ny = y + when (edge) { Track.YM -> -1; Track.YP -> 1; else -> 0 }
    return if (inside(nx, ny)) index(nx, ny) else -1
}

/**
 * Whether tile ([x], [y]) could hold [pieces], with the track round it as
 * [trackOf] says: dry, and every join with track next door level.
 */
fun World.piecesFit(x: Int, y: Int, pieces: Int, trackOf: (Int) -> Int = { track[it].toInt() }): Boolean {
    if (!inside(x, y) || isWater(x, y)) return false
    for (p in Track.PIECES) {
        if (pieces and p == 0) continue
        val e = Track.edges(p)
        for (edge in intArrayOf(e and 3, e shr 2)) {
            val n = tileAcross(x, y, edge)
            if (n < 0) continue
            val theirs = trackOf(n)
            val back = Track.opposite(edge)
            if (Track.PIECES.none { theirs and it != 0 && Track.has(it, back) }) continue
            if (edgeHeight(n % width, n / width, theirs, back) != edgeHeight(x, y, pieces, edge)) return false
        }
    }
    return true
}

/**
 * What a [Command.Rail] would do, piece by piece, as if the line were built in
 * order: for each entry of its line, whether it'd go down (or come up). Null
 * if the line makes no sense. Money isn't counted.
 */
fun World.planRail(c: Command.Rail): BooleanArray? {
    val line = Track.line(this, c.sx, c.sy, c.ex, c.ey, c.first) ?: return null
    val planned = HashMap<Int, Int>()
    val trackOf = { i: Int -> planned[i] ?: track[i].toInt() }
    return BooleanArray(line.size) { k ->
        val i = line[k] / 64
        val piece = line[k] % 64
        val has = trackOf(i)
        // Town roads and buildings stay put; level crossings come with roads of the player's own.
        // Across a road only on a level crossing.
        val special = stationTiles.containsKey(i) || depotTiles.containsKey(i) || (townBuilt(i) && !(road[i].toInt() != 0 && crossable(i, piece))) || spanHeads.containsKey(i) ||
            othersBuilding(c.company, i)
        val ok = !special && if (c.remove) {
            has and piece != 0 && owner[i].toInt() == c.company
        } else {
            has and piece == 0 && (has == 0 || owner[i].toInt() == c.company) && piecesFit(i % width, i / width, has or piece, trackOf)
        }
        if (ok) planned[i] = if (c.remove) has and piece.inv() else has or piece
        ok
    }
}

internal fun World.buildRail(company: Company, c: Command.Rail) {
    val line = Track.line(this, c.sx, c.sy, c.ex, c.ey, c.first) ?: return
    for (entry in line) {
        val i = entry / 64
        val piece = entry % 64
        val x = i % width
        val y = i / width
        val has = track[i].toInt()
        // Platforms and depots are left for the Clear tool.
        if (stationTiles.containsKey(i) || depotTiles.containsKey(i) || (townBuilt(i) && !(road[i].toInt() != 0 && crossable(i, piece) && !c.remove)) || spanHeads.containsKey(i)) continue
        if (othersBuilding(company.id, i)) continue
        if (c.remove) {
            if (has and piece == 0 || owner[i].toInt() != company.id || trainOnTile(i)) continue
            if (company.money < price(Prices.TRACK_REMOVE)) return
            spend(company, Books.CONSTRUCTION, price(Prices.TRACK_REMOVE))
            track[i] = (has and piece.inv()).toByte()
            wires[i] = (wires[i].toInt() and piece.inv()).toByte()
            signals.remove(pieceKey(i, piece))
            if (track[i].toInt() == 0) owner[i] = -1
        } else {
            if (has and piece != 0 || (has != 0 && owner[i].toInt() != company.id)) continue
            if (!piecesFit(x, y, has or piece)) continue
            val cost = price(Prices.TRACK) + treeCost(i) + if (!needsFoundation(x, y, has) && needsFoundation(x, y, has or piece)) price(Prices.FOUNDATION) else 0
            if (company.money < cost) return
            spend(company, Books.CONSTRUCTION, cost)
            fellTrees(company, i)
            clearOwnBuilding(company.id, i)
            track[i] = (has or piece).toByte()
            owner[i] = company.id.toByte()
            condition[i] = -1
            if (fences.remove(i)) fenceCoverCache = null
            // Laid on cleared ground.
            if ((weather.snow[i].toInt() and 255) > PLOWED) weather.snow[i] = PLOWED.toByte()
            // A tile that now branches can't keep its signals.
            if (!signalFits(x, y, piece)) for (p in Track.PIECES) signals.remove(pieceKey(i, p))
        }
        touched(x, y)
        networkChanged()
    }
    if (!c.remove && line.isNotEmpty()) {
        joinCorner(company, line.first() / 64)
        joinCorner(company, line.last() / 64)
    }
}

/**
 * Where two straight lines meet at a corner, each ending on the tile, they cross there with nothing beyond; a curve
 * from one to the other is what was meant. Tile [i]'s crossing becomes that curve if each straight only leads off it
 * one way.
 */
private fun World.joinCorner(company: Company, i: Int) {
    if (track[i].toInt() != Track.X or Track.Y || owner[i].toInt() != company.id || trainOnTile(i) || crossing(i)) return
    val x = i % width
    val y = i / width
    fun leads(edge: Int): Boolean {
        val nx = x + Track.dx(edge)
        val ny = y + Track.dy(edge)
        if (!inside(nx, ny)) return false
        val n = index(nx, ny)
        val there = track[n].toInt()
        return Track.PIECES.any { there and it != 0 && Track.has(it, Track.opposite(edge)) } || spanHeads.containsKey(n)
    }
    val alongX = intArrayOf(Track.XM, Track.XP).filter(::leads)
    val alongY = intArrayOf(Track.YM, Track.YP).filter(::leads)
    if (alongX.size != 1 || alongY.size != 1) return
    val curve = Track.pieceOf(alongX[0], alongY[0])
    if (!piecesFit(x, y, curve)) return
    track[i] = curve.toByte()
    wires[i] = (wires[i].toInt() and curve).toByte()
    for (p in Track.PIECES) signals.remove(pieceKey(i, p))
    touched(x, y)
    networkChanged()
}

/** What laying (or lifting) [c] would cost and how many pieces of it can be done, as a pair; -1 cost if it's no line at all. */
fun World.railCost(c: Command.Rail): Pair<Long, Int> {
    val line = Track.line(this, c.sx, c.sy, c.ex, c.ey, c.first) ?: return -1L to 0
    val ok = planRail(c) ?: return -1L to 0
    var cost = 0L
    var n = 0
    val planned = HashMap<Int, Int>()
    for ((k, entry) in line.withIndex()) {
        if (!ok[k]) continue
        val i = entry / 64
        val piece = entry % 64
        n++
        if (c.remove) { cost += price(Prices.TRACK_REMOVE); continue }
        val has = planned[i] ?: track[i].toInt()
        cost += price(Prices.TRACK) + (if (has == 0) treeCost(i) else 0)
        if (!needsFoundation(i % width, i / width, has) && needsFoundation(i % width, i / width, has or piece)) cost += price(Prices.FOUNDATION)
        planned[i] = has or piece
    }
    return cost to n
}
