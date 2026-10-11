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
 */

// The corners of a tile, in the order the surface heights come in.
const val CORNER_N = 0
const val CORNER_E = 1
const val CORNER_S = 2
const val CORNER_W = 3

/** Whether track [pieces] on tile ([x], [y]) need a foundation. */
fun World.needsFoundation(x: Int, y: Int, pieces: Int): Boolean {
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
 * [pieces] on it: the land, or the top of its foundation.
 */
fun World.surfaceHeight(x: Int, y: Int, pieces: Int, corner: Int): Int {
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
private fun World.across(x: Int, y: Int, edge: Int): Int {
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
            val n = across(x, y, edge)
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
        val special = stationTiles.containsKey(i) || depotTiles.containsKey(i) || townBuilt(i)
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
        if (stationTiles.containsKey(i) || depotTiles.containsKey(i) || townBuilt(i)) continue
        if (c.remove) {
            if (has and piece == 0 || owner[i].toInt() != company.id || trainOnTile(i)) continue
            if (company.money < Prices.TRACK_REMOVE) return
            company.money -= Prices.TRACK_REMOVE
            track[i] = (has and piece.inv()).toByte()
            signals.remove(pieceKey(i, piece))
            if (track[i].toInt() == 0) owner[i] = -1
        } else {
            if (has and piece != 0 || (has != 0 && owner[i].toInt() != company.id)) continue
            if (!piecesFit(x, y, has or piece)) continue
            val cost = Prices.TRACK + treeCost(i) + if (!needsFoundation(x, y, has) && needsFoundation(x, y, has or piece)) Prices.FOUNDATION else 0
            if (company.money < cost) return
            company.money -= cost
            clearTrees(i)
            track[i] = (has or piece).toByte()
            owner[i] = company.id.toByte()
            // A tile that now branches can't keep its signals.
            if (!signalFits(x, y, piece)) for (p in Track.PIECES) signals.remove(pieceKey(i, p))
        }
        touched(x, y)
        networkChanged()
    }
}
