package com.rm.interchange.sim

/*
 * Bridges and tunnels: spans of straight track from one head tile to another,
 * over or under the tiles between. Heads are ordinary track on the ground;
 * the tiles between carry the span's track on a level of their own, level 1,
 * so track on the ground there, a road or water, goes on underneath. A step
 * says which level it's on, and so do piece keys.
 *
 * A bridge's deck is level with where its heads meet it, over land no higher,
 * some of it lower. A tunnel runs level from a portal at the foot of a slope
 * into the hill, out of the first slope beyond at the same height going down,
 * with land above it all the way.
 */

/** A bridge or a tunnel from head ([x0], [y0]) to head ([x1], [y1]), its track at [height] steps. */
class Span(
    val id: Int,
    var owner: Int,
    val tunnel: Boolean,
    /** A [Spans] style. */
    val style: Int,
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int,
    val height: Int,
) {
    /** Wired for electric engines. */
    var wired = false
    /** The straight piece it runs along. */
    val axis get() = if (y0 == y1) Track.X else Track.Y
    val length get() = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)) - 1
}

object Spans {
    /** Bridges: timber trestles, then steel trusses and concrete; tunnels have one look of their own. */
    const val TRESTLE = 0
    const val TRUSS = 1
    const val CONCRETE = 2
    const val TUNNEL = 3

    val FROM = intArrayOf(1836, 1870, 1940, 1836)
    /** The longest of each between its heads, in tiles. */
    val LONGEST = intArrayOf(10, 18, 30, 24)
    /** What each tile between the heads costs, in dollars before inflation, and the fastest across, in km/h. */
    val COST = longArrayOf(1_500, 4_000, 6_000, 9_000)
    val SPEED = intArrayOf(60, 110, 200, 300)
}

/** The track pieces on [tile] at [level]: the ground's, or on level 1 the span's piece over or under it. */
fun World.piecesAt(tile: Int, level: Int): Int =
    if (level == 0) track[tile].toInt() else deck[tile]?.let { spans[it]?.axis } ?: 0

/** The tile and level across [edge] from [tile] at [level], packed as tile * 2 + level, or -1 off the map. */
fun World.across(tile: Int, level: Int, edge: Int): Int {
    val x = tile % width + Track.dx(edge)
    val y = tile / width + Track.dy(edge)
    if (!inside(x, y)) return -1
    val n = index(x, y)
    // Along a span, or off a head onto it; anything else is on the ground.
    val span = if (level == 1) deck[tile] else spanHeads[tile]
    return if (span != null && deck[n] == span) n * 2 + 1 else n * 2
}

/** The steps a train could take next after step [cur]. */
fun World.waysOn(cur: Int): IntArray {
    val a = across(stepTile(cur), stepLevel(cur), stepExit(cur))
    if (a < 0) return IntArray(0)
    return waysIn(a / 2, Track.opposite(stepExit(cur)), a % 2)
}

/** Which span step [s] is on, or null on plain ground. */
fun World.spanOf(s: Int): Span? = if (stepLevel(s) == 1) deck[stepTile(s)]?.let { spans[it] } else null

/** Whether step [s] is out in the weather: anywhere but inside a tunnel. */
fun World.exposed(s: Int): Boolean = spanOf(s)?.tunnel != true

/** The key of a step's piece: its tile's and piece's, the level told apart. */
fun stepKey(s: Int) = pieceKey(stepTile(s), stepPiece(s), stepLevel(s))

/** The key of the place a step is in, for counting trains: its tile, the level told apart. */
fun tileKey(s: Int) = if (stepLevel(s) == 0) stepTile(s) else -stepTile(s) - 1

/** The tile and piece of key [key], and its level. */
fun keyTile(key: Int) = (if (key >= 0) key else -key - 1) / 8
fun keyPiece(key: Int) = Track.PIECES[(if (key >= 0) key else -key - 1) % 8]
fun keyLevel(key: Int) = if (key >= 0) 0 else 1

/** The height track on [tile] at the inner edge of a head facing [edge], in steps. */
private fun World.headHeight(x: Int, y: Int, axis: Int, edge: Int) = edgeHeight(x, y, axis, edge)

/** Whether head tile ([x], [y]) could take straight track [axis] for a span: empty or that track, the company's, nothing else there. */
private fun World.headFree(company: Company, x: Int, y: Int, axis: Int): Boolean {
    if (!inside(x, y) || isWater(x, y)) return false
    val i = index(x, y)
    val t = track[i].toInt()
    if (t != 0 && (t != axis || owner[i].toInt() != company.id)) return false
    return !stationTiles.containsKey(i) && !depotTiles.containsKey(i) && !townBuilt(i) && industryOf[i].toInt() == 0 && !spanHeads.containsKey(i) && !deck.containsKey(i)
}

/** What building [c] would cost, or -1 if it can't be built. */
fun World.bridgeCost(company: Company, c: Command.Bridge): Long = bridgeSurvey(company, c).coerceAtLeast(-1)

/** Why [company] can't build bridge [c], a [Refusal]; [Refusal.NONE] if it can. */
fun World.bridgeRefusal(company: Company, c: Command.Bridge): Int {
    val cost = bridgeSurvey(company, c)
    return if (cost < 0) (-cost).toInt() else if (company.money < cost) Refusal.MONEY else Refusal.NONE
}

/** What building [c] would cost, or less than 0 if it can't be built: minus its [Refusal]. */
private fun World.bridgeSurvey(company: Company, c: Command.Bridge): Long {
    val year = Calendar.year(startYear, day)
    if (c.style !in Spans.TRESTLE..Spans.CONCRETE || year < Spans.FROM[c.style]) return -Refusal.NOT_YET.toLong()
    if (c.x0 != c.x1 && c.y0 != c.y1) return -Refusal.NOT_STRAIGHT.toLong()
    val axis = if (c.y0 == c.y1) Track.X else Track.Y
    val len = maxOf(kotlin.math.abs(c.x1 - c.x0), kotlin.math.abs(c.y1 - c.y0)) - 1
    if (len < 1) return -Refusal.NOTHING_TO_SPAN.toLong()
    if (len > Spans.LONGEST[c.style]) return -Refusal.TOO_LONG.toLong()
    if (!headFree(company, c.x0, c.y0, axis) || !headFree(company, c.x1, c.y1, axis)) return -Refusal.ENDS.toLong()
    val toward = if (axis == Track.X) (if (c.x1 > c.x0) Track.XP else Track.XM) else (if (c.y1 > c.y0) Track.YP else Track.YM)
    val h = headHeight(c.x0, c.y0, axis, toward)
    if (headHeight(c.x1, c.y1, axis, Track.opposite(toward)) != h) return -Refusal.NOT_LEVEL.toLong()
    val dx = Track.dx(toward)
    val dy = Track.dy(toward)
    var cost = 0L
    var lowest = h
    for (k in 1..len) {
        val x = c.x0 + dx * k
        val y = c.y0 + dy * k
        val i = index(x, y)
        // Over the land, nowhere above the deck, and nothing in the way.
        if (maxOf(maxOf(heightAt(x, y), heightAt(x + 1, y)), maxOf(heightAt(x + 1, y + 1), heightAt(x, y + 1))) > h) return -Refusal.HILL.toLong()
        lowest = minOf(lowest, minOf(minOf(heightAt(x, y), heightAt(x + 1, y)), minOf(heightAt(x + 1, y + 1), heightAt(x, y + 1))))
        if (deck.containsKey(i) || spanHeads.containsKey(i) || stationTiles.containsKey(i) || depotTiles.containsKey(i) || house[i].toInt() != 0 || industryOf[i].toInt() != 0) return -Refusal.IN_THE_WAY.toLong()
        cost += price(Spans.COST[c.style])
    }
    if (lowest >= h) return -Refusal.NOTHING_TO_SPAN.toLong()
    return cost + 2 * price(Prices.TRACK) + treeCost(index(c.x0, c.y0)) + treeCost(index(c.x1, c.y1))
}

internal fun World.buildBridge(company: Company, c: Command.Bridge) {
    val cost = bridgeCost(company, c)
    if (cost < 0 || company.money < cost) return
    spend(company, Books.CONSTRUCTION, cost)
    val axis = if (c.y0 == c.y1) Track.X else Track.Y
    val toward = if (axis == Track.X) (if (c.x1 > c.x0) Track.XP else Track.XM) else (if (c.y1 > c.y0) Track.YP else Track.YM)
    open(company, Span(spans.size, company.id, false, c.style, c.x0, c.y0, c.x1, c.y1, headHeight(c.x0, c.y0, axis, toward)))
}

/**
 * Where a tunnel from portal ([x], [y]) would come out, and its height, as a span with no id yet; null if
 * there's no way through. The portal is a slope rising straight away from where the track comes in.
 */
fun World.tunnelFrom(company: Company, x: Int, y: Int): Span? {
    if (!inside(x, y)) return null
    val into = rises(x, y)
    if (into < 0) return null
    val axis = Track.straight(into)
    val h = minOf(minOf(heightAt(x, y), heightAt(x + 1, y)), minOf(heightAt(x + 1, y + 1), heightAt(x, y + 1)))
    if (!headFree(company, x, y, axis)) return null
    val dx = Track.dx(into)
    val dy = Track.dy(into)
    for (k in 1..Spans.LONGEST[Spans.TUNNEL] + 1) {
        val tx = x + dx * k
        val ty = y + dy * k
        if (!inside(tx, ty)) return null
        val lo = minOf(minOf(heightAt(tx, ty), heightAt(tx + 1, ty)), minOf(heightAt(tx + 1, ty + 1), heightAt(tx, ty + 1)))
        val hi = maxOf(maxOf(heightAt(tx, ty), heightAt(tx + 1, ty)), maxOf(heightAt(tx + 1, ty + 1), heightAt(tx, ty + 1)))
        // The far portal: a slope going back down to the same height, the same way round.
        if (lo == h && hi == h + 1 && k > 1) {
            if (rises(tx, ty) != Track.opposite(into) || !headFree(company, tx, ty, axis)) return null
            return Span(-1, company.id, true, Spans.TUNNEL, x, y, tx, ty, h)
        }
        if (lo <= h) return null
        val i = index(tx, ty)
        if (deck.containsKey(i) || spanHeads.containsKey(i)) return null
    }
    return null
}

/** The edge tile ([x], [y]) rises towards, a step, if it's a plain slope; -1 if not. */
private fun World.rises(x: Int, y: Int): Int {
    val n = heightAt(x, y)
    val e = heightAt(x + 1, y)
    val s = heightAt(x + 1, y + 1)
    val w = heightAt(x, y + 1)
    return when {
        n == w && e == s && e == n + 1 -> Track.XP
        n == w && e == s && n == e + 1 -> Track.XM
        n == e && w == s && w == n + 1 -> Track.YP
        n == e && w == s && n == w + 1 -> Track.YM
        else -> -1
    }
}

/** What a tunnel from portal ([x], [y]) would cost, or -1. */
fun World.tunnelCost(company: Company, x: Int, y: Int): Long {
    val s = tunnelFrom(company, x, y) ?: return -1
    return s.length * price(Spans.COST[Spans.TUNNEL]) + 2 * price(Prices.TRACK)
}

internal fun World.buildTunnel(company: Company, c: Command.Tunnel) {
    val s = tunnelFrom(company, c.x, c.y) ?: return
    val cost = tunnelCost(company, c.x, c.y)
    if (company.money < cost) return
    spend(company, Books.CONSTRUCTION, cost)
    open(company, Span(spans.size, company.id, true, Spans.TUNNEL, s.x0, s.y0, s.x1, s.y1, s.height))
}

/** Puts span [s] on the map: track on its heads, and its own track over or under the tiles between. */
private fun World.open(company: Company, s: Span) {
    spans += s
    val dx = (s.x1 - s.x0).coerceIn(-1, 1)
    val dy = (s.y1 - s.y0).coerceIn(-1, 1)
    for ((x, y) in listOf(s.x0 to s.y0, s.x1 to s.y1)) {
        val i = index(x, y)
        fellTrees(company, i)
        track[i] = s.axis.toByte()
        owner[i] = company.id.toByte()
        spanHeads[i] = s.id
        touched(x, y)
    }
    for (k in 1..s.length) {
        val x = s.x0 + dx * k
        val y = s.y0 + dy * k
        deck[index(x, y)] = s.id
        touched(x, y)
    }
    networkChanged()
}

/** What span [s] cost to build, before inflation: each tile between the heads, and the track on both heads. */
internal fun spanBuilt(s: Span): Long = s.length.toLong() * Spans.COST[s.style] + 2 * Prices.TRACK

/** Whether a train is on span [s], a head or the track between. */
internal fun World.spanBlocked(s: Span): Boolean {
    val dx = (s.x1 - s.x0).coerceIn(-1, 1)
    val dy = (s.y1 - s.y0).coerceIn(-1, 1)
    for (k in 0..s.length + 1) {
        val i = index(s.x0 + dx * k, s.y0 + dy * k)
        if ((tileTrains[i] ?: 0) > 0 || (tileTrains[-i - 1] ?: 0) > 0) return true
    }
    return false
}

/** Takes span [s] away, heads and all, unless a train is on it. */
internal fun World.removeSpan(s: Span): Boolean {
    if (spanBlocked(s)) return false
    val dx = (s.x1 - s.x0).coerceIn(-1, 1)
    val dy = (s.y1 - s.y0).coerceIn(-1, 1)
    val tiles = (0..s.length + 1).map { index(s.x0 + dx * it, s.y0 + dy * it) }
    for ((k, i) in tiles.withIndex()) {
        if (k == 0 || k == tiles.size - 1) {
            spanHeads.remove(i)
            track[i] = 0
            wires[i] = 0
            owner[i] = -1
            for (p in Track.PIECES) signals.remove(pieceKey(i, p))
        } else deck.remove(i)
        touched(i % width, i / width)
    }
    spans[s.id] = null
    networkChanged()
    return true
}

/** Whether step [s]'s piece has wires over it. */
fun World.wired(s: Int): Boolean = spanOf(s)?.wired ?: (wires[stepTile(s)].toInt() and stepPiece(s) != 0)

/** Whether depot [d]'s track has wires over it, for electric engines to come and go. */
fun World.depotWired(d: Depot): Boolean = wires[d.tile].toInt() and Track.straight(d.edge) != 0

/** The year wires can first be hung. */
const val WIRES_FROM = 1895

/** Hangs or takes down wires along [c]'s line, on the company's track. */
internal fun World.wire(company: Company, c: Command.Wire) {
    if (!c.remove && Calendar.year(startYear, day) < WIRES_FROM) return
    val line = Track.line(this, c.sx, c.sy, c.ex, c.ey, c.first) ?: return
    for (entry in line) {
        val i = entry / 64
        val piece = entry % 64
        if (owner[i].toInt() != company.id || track[i].toInt() and piece == 0) continue
        val has = wires[i].toInt()
        if (c.remove == (has and piece == 0)) continue
        if (!c.remove) {
            if (company.money < price(Prices.WIRE)) return
            spend(company, Books.CONSTRUCTION, price(Prices.WIRE))
        }
        wires[i] = (if (c.remove) has and piece.inv() else has or piece).toByte()
        // A span from this head is wired with it.
        spanHeads[i]?.let { id -> spans[id]?.let { s -> if (s.wired == c.remove) { if (!c.remove) spend(company, Books.CONSTRUCTION, price(Prices.WIRE) * s.length); s.wired = !c.remove } } }
        touched(i % width, i / width)
    }
    networkChanged()
}
