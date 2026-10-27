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
    return planLine(c, line, HashMap())
}

/** [planRail] for [line], leaving in [planned] each tile's track as it would be after. */
private fun World.planLine(c: Command.Rail, line: IntArray, planned: HashMap<Int, Int>): BooleanArray {
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

/**
 * The junctions laying [c] would add where it meets other track, as [railJoins] has them, or the junction curves
 * lifting it would leave as stubs and so lifts too, as [railStubs] has them. Money isn't counted.
 */
fun World.planJoins(c: Command.Rail): List<Join> {
    if (c.join and Command.Rail.JOIN == 0) return emptyList()
    val line = Track.line(this, c.sx, c.sy, c.ex, c.ey, c.first) ?: return emptyList()
    val planned = HashMap<Int, Int>()
    val laid = planLine(c, line, planned)
    val trackOf = { i: Int -> planned[i] ?: track[i].toInt() }
    if (c.remove) return railStubs(c, line) { trackOf(it) }
    for (i in intArrayOf(line.first() / 64, line.last() / 64)) cornerCurve(c.company, i, trackOf).let { if (it != 0) planned[i] = it }
    return railJoins(c, line, laid, trackOf) { hasSignals(it) }
}

internal fun World.buildRail(company: Company, c: Command.Rail) {
    val line = Track.line(this, c.sx, c.sy, c.ex, c.ey, c.first) ?: return
    // Junctions aren't made on tiles that had signals.
    val signalled = line.map { it / 64 }.filter { hasSignals(it) }.toSet()
    val laid = BooleanArray(line.size)
    for ((k, entry) in line.withIndex()) {
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
            if (track[i].toInt() == 0) {
                owner[i] = -1
                // A snow shed stands over track; with none left it goes too.
                sheds.remove(i)
                shedYears.remove(i)
            }
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
            laid[k] = true
        }
        touched(x, y)
        networkChanged()
    }
    if (!c.remove && line.isNotEmpty()) {
        joinCorner(company, line.first() / 64)
        joinCorner(company, line.last() / 64)
        if (c.join and Command.Rail.JOIN != 0) {
            for (j in railJoins(c, line, laid, { track[it].toInt() }) { it in signalled || hasSignals(it) }) {
                // Each is checked again as the ones before may have changed the tile.
                if (track[j.tile].toInt() != j.from || !piecesFit(j.tile % width, j.tile / width, j.to)) continue
                if (company.money < j.cost) return
                spend(company, Books.CONSTRUCTION, j.cost)
                track[j.tile] = j.to.toByte()
                wires[j.tile] = (wires[j.tile].toInt() and j.to).toByte()
                for (p in Track.PIECES) signals.remove(pieceKey(j.tile, p))
                touched(j.tile % width, j.tile / width)
                networkChanged()
            }
        }
    }
    if (c.remove && c.join and Command.Rail.JOIN != 0 && line.isNotEmpty()) {
        for (j in railStubs(c, line) { track[it].toInt() }) {
            if (track[j.tile].toInt() != j.from) continue
            if (company.money < j.cost) return
            spend(company, Books.CONSTRUCTION, j.cost)
            track[j.tile] = j.to.toByte()
            wires[j.tile] = (wires[j.tile].toInt() and j.to).toByte()
            for (p in Track.PIECES) if (j.to and p == 0) signals.remove(pieceKey(j.tile, p))
            touched(j.tile % width, j.tile / width)
            networkChanged()
        }
    }
}

private fun World.hasSignals(i: Int) = Track.PIECES.any { signals.containsKey(pieceKey(i, it)) }

/** Whether track on the tile across [edge] of tile [i] leads back into it, or a bridge or tunnel starts there. */
private fun World.leadsAcross(i: Int, edge: Int, trackOf: (Int) -> Int): Boolean {
    val n = tileAcross(i % width, i / width, edge)
    if (n < 0) return false
    val there = trackOf(n)
    return Track.PIECES.any { there and it != 0 && Track.has(it, Track.opposite(edge)) } || spanHeads.containsKey(n)
}

/**
 * Where two straight lines meet at a corner, each ending on the tile, they cross there with nothing beyond; a curve
 * from one to the other is what was meant. The curve tile [i]'s crossing would become, if each straight only leads
 * off it one way; 0 if none.
 */
private fun World.cornerCurve(company: Int, i: Int, trackOf: (Int) -> Int): Int {
    if (trackOf(i) != Track.X or Track.Y || owner[i].toInt() != company || trainOnTile(i) || crossing(i)) return 0
    val alongX = intArrayOf(Track.XM, Track.XP).filter { leadsAcross(i, it, trackOf) }
    val alongY = intArrayOf(Track.YM, Track.YP).filter { leadsAcross(i, it, trackOf) }
    if (alongX.size != 1 || alongY.size != 1) return 0
    val curve = Track.pieceOf(alongX[0], alongY[0])
    return if (piecesFit(i % width, i / width, curve, trackOf)) curve else 0
}

/** Turns tile [i]'s crossing into the curve [cornerCurve] finds. */
private fun World.joinCorner(company: Company, i: Int) {
    val curve = cornerCurve(company.id, i) { track[it].toInt() }
    if (curve == 0) return
    track[i] = curve.toByte()
    wires[i] = (wires[i].toInt() and curve).toByte()
    for (p in Track.PIECES) signals.remove(pieceKey(i, p))
    touched(i % width, i / width)
    networkChanged()
}

/**
 * The [Command.Rail] bits saying which way the curves at a line's ends turn, with the finger [su], [sv] tiles off the
 * middle of the start tile in x and y, [eu], [ev] off the middle of the end tile, and the drag having gone [dx], [dy]
 * in all. Each turns to the side the finger is on, but within [JOIN_CENTRE] of the middle to the side the drag
 * heads, as a finger there can't say.
 */
fun joinSides(su: Float, sv: Float, eu: Float, ev: Float, dx: Float, dy: Float): Int {
    fun low(off: Float, drift: Float) = if (off < -JOIN_CENTRE || off > JOIN_CENTRE || drift == 0f) off < 0f else drift < 0f
    return (if (low(su, dx)) Command.Rail.START_X_LOW else 0) or (if (low(sv, dy)) Command.Rail.START_Y_LOW else 0) or
        (if (low(eu, dx)) Command.Rail.END_X_LOW else 0) or (if (low(ev, dy)) Command.Rail.END_Y_LOW else 0)
}

/** How near a tile's middle, in tiles, a finger is too near to pick a side by. */
const val JOIN_CENTRE = 0.15f

/** A junction the track tool makes where its line meets other track: tile [tile]'s track goes from [from] to [to], for [cost]. */
class Join(val tile: Int, val from: Int, val to: Int, val cost: Long)

/**
 * The junctions line [line] of [c] makes with the track it meets, with [laid] saying which of its entries went down
 * and [trackOf] the track after. Each tile has to be the company's own plain track: no platform, depot, bridge or
 * tunnel end, road or signal (as [signalled] says).
 *
 * - A straight end of the line on another line becomes a curve into it, turning as [c]'s bits for that end say.
 * - An end on the tile next to a straight line at right angles to it adds a curve to that line, turning the same way.
 * - A straight across a straight line, or an end that goes on past it, stays a plain crossing.
 *
 * A train on the tile doesn't stop a junction, as each only adds pieces or takes away the one the line just laid,
 * which no train can be on yet; laying a piece beside a train is allowed anyway.
 */
private fun World.railJoins(c: Command.Rail, line: IntArray, laid: BooleanArray, trackOf: (Int) -> Int, signalled: (Int) -> Boolean): List<Join> {
    if (c.remove || c.join and Command.Rail.JOIN == 0 || line.size < 2) return emptyList()
    val out = ArrayList<Join>()
    // The track with the joins so far made, so each is planned as the build will make it, and each tile used once.
    val joined = HashMap<Int, Int>()
    val now = { i: Int -> joined[i] ?: trackOf(i) }
    fun usable(t: Int) = t !in joined && joinable(c.company, t, trains = false) && !signalled(t)
    // The edge a curve at the start (or end) turns out of, across [axisEdge]'s axis.
    fun turn(axisEdge: Int, atStart: Boolean): Int = if (axisEdge == Track.XM || axisEdge == Track.XP) {
        if (c.join and (if (atStart) Command.Rail.START_Y_LOW else Command.Rail.END_Y_LOW) != 0) Track.YM else Track.YP
    } else {
        if (c.join and (if (atStart) Command.Rail.START_X_LOW else Command.Rail.END_X_LOW) != 0) Track.XM else Track.XP
    }
    fun add(t: Int, from: Int, to: Int, removed: Int) {
        if (!usable(t) || !piecesFit(t % width, t / width, to, now)) return
        // Taking away a piece that was there before needs the tile clear of trains.
        if (from and to.inv() and removed.inv() != 0 && trainOnTile(t)) return
        val x = t % width
        val y = t / width
        // A curve in place of a straight just laid costs nothing more.
        val pieces = Track.PIECES.count { to and it != 0 && from and it == 0 } - Track.PIECES.count { removed and it != 0 }
        val cost = price(Prices.TRACK) * maxOf(0, pieces) + if (!needsFoundation(x, y, from) && needsFoundation(x, y, to)) price(Prices.FOUNDATION) else 0
        out += Join(t, from, to, cost)
        joined[t] = to
    }
    for (k in line.indices) {
        if (!laid[k]) continue
        val t = line[k] / 64
        val p = line[k] % 64
        val has = now(t)
        // A corner may have taken the piece's place.
        if (has and p == 0) continue
        val atStart = k == 0
        val end = atStart || k == line.size - 1
        // The edge towards where the line came from; at the start, towards where it goes.
        val back = backEdge(line, k)
        val beyond = Track.other(p, back)
        if ((p == Track.X || p == Track.Y) && has == Track.X or Track.Y) {
            if (end && !leadsAcross(t, beyond, now)) add(t, has, (has and p.inv()) or Track.pieceOf(back, turn(back, atStart)), p)
        } else if (end) {
            val n = tileAcross(t % width, t / width, beyond)
            if (n < 0) continue
            val across = if (Track.straight(beyond) == Track.X) Track.Y else Track.X
            if (now(n) != across) continue
            val entry = Track.opposite(beyond)
            add(n, across, across or Track.pieceOf(entry, turn(entry, atStart)), 0)
        }
    }
    return out
}

/** Whether the track tool may change tile [t] to make or lift a junction: [company]'s own plain track, with no train on it if [trains]. */
private fun World.joinable(company: Int, t: Int, trains: Boolean = true) = owner[t].toInt() == company && !stationTiles.containsKey(t) && !depotTiles.containsKey(t) &&
    !spanHeads.containsKey(t) && !townBuilt(t) && !othersBuilding(company, t) && !(trains && trainOnTile(t))

/** The edge of entry [k]'s tile towards the entry before it in [line]; for the first, towards the one after. */
private fun World.backEdge(line: IntArray, k: Int): Int {
    val t = line[k] / 64
    val n = line[if (k == 0) 1 else k - 1] / 64
    return when (n - t) { -1 -> Track.XM; 1 -> Track.XP; -width -> Track.YM; else -> Track.YP }
}

/**
 * The junction curves lifting [line] of [c] leaves as stubs, with [trackOf] the track after: on the line's tiles and
 * the tiles past its ends, a curve of the company's own leading into a tile of the line that no longer leads back.
 * A curve that's all there is on its tile stays, as it's a corner of a line of its own.
 */
private fun World.railStubs(c: Command.Rail, line: IntArray, trackOf: (Int) -> Int): List<Join> {
    val tiles = line.map { it / 64 }
    val onLine = tiles.toSet()
    val near = ArrayList(tiles.distinct())
    if (line.size > 1) for (k in intArrayOf(0, line.size - 1)) {
        val t = line[k] / 64
        val n = tileAcross(t % width, t / width, Track.other(line[k] % 64, backEdge(line, k)))
        if (n >= 0 && n !in onLine) near += n
    }
    val out = ArrayList<Join>()
    for (t in near) {
        if (!joinable(c.company, t)) continue
        val from = trackOf(t)
        var to = from
        for (p in intArrayOf(Track.N, Track.E, Track.S, Track.W)) {
            if (to and p == 0 || to == p) continue
            val e = Track.edges(p)
            val stub = intArrayOf(e and 3, e shr 2).any { edge ->
                val n = tileAcross(t % width, t / width, edge)
                n in onLine && !leadsAcross(t, edge, trackOf)
            }
            if (stub) to = to and p.inv()
        }
        if (to != from) out += Join(t, from, to, price(Prices.TRACK_REMOVE) * Track.PIECES.count { from and it != 0 && to and it == 0 })
    }
    return out
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
    return cost + planJoins(c).sumOf { it.cost } to n
}
