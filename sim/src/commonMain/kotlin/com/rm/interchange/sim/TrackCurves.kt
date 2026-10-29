package com.rm.interchange.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/*
 * The line each track piece is drawn along (docs/CURVES_PLAN.md). Drawing only: nothing here is sim state, and the
 * results are pure functions of the track masks and the flags round a tile. A port of tools/blender/curves.py, back
 * mode; src/jvmTest/resources/curves_reference.txt is written from that script and TrackCurvesTest checks these
 * numbers against it.
 *
 * Points are in tile terms, x and y from 0 to 1 over the tile.
 */

/** A point on a curve and the direction it runs there, as a unit vector. */
class CurvePose(val x: Double, val y: Double, val dx: Double, val dy: Double) {
    /** The nearest of 16 headings, from x towards y. */
    val heading16: Int get() = TrackCurves.heading16(dx, dy)
}

/**
 * One curve on a tile: a cubic from edge [from] to edge [to] with control points [ctrl] (x, y of 4 points).
 * [sim] is the sim piece it stands for. A straight tile on an eased span holds a second curve for the branch, with
 * [via] the diagonal it eases into; otherwise [via] is 0. [span] is the eased span it belongs to, or null for a plain
 * shape, and [kind] that span's [TrackCurves.BACK] or [TrackCurves.CORNER], or [TrackCurves.PLAIN].
 */
class TileCurve(
    val sim: Int,
    val via: Int,
    val from: Int,
    val to: Int,
    val ctrl: DoubleArray,
    val kind: Int,
    val span: String?,
    val looks: String,
) {
    /** Distance along the curve at 64 even steps of t, from 0 to its length. */
    private val table = DoubleArray(TABLE + 1).also { tab ->
        var px = ctrl[0]
        var py = ctrl[1]
        for (i in 1..TABLE) {
            val t = i.toDouble() / TABLE
            val x = coord(t, 0)
            val y = coord(t, 1)
            tab[i] = tab[i - 1] + hypot(x - px, y - py)
            px = x
            py = y
        }
    }
    val length: Double get() = table[TABLE]

    /** Kept for the map's drawing (the corners of its overhead wire), filled on the draw thread. */
    var drawCache: DoubleArray? = null

    private fun coord(t: Double, k: Int): Double {
        val s = 1 - t
        return s * s * s * ctrl[k] + 3 * s * s * t * ctrl[2 + k] + 3 * s * t * t * ctrl[4 + k] + t * t * t * ctrl[6 + k]
    }

    private fun slope(t: Double, k: Int): Double {
        val s = 1 - t
        return 3 * s * s * (ctrl[2 + k] - ctrl[k]) + 6 * s * t * (ctrl[4 + k] - ctrl[2 + k]) + 3 * t * t * (ctrl[6 + k] - ctrl[4 + k])
    }

    private fun tOf(s: Double): Double {
        val d = s.coerceIn(0.0, length)
        for (i in 0 until TABLE) {
            if (table[i + 1] >= d) return (i + (d - table[i]) / maxOf(1e-12, table[i + 1] - table[i])) / TABLE
        }
        return 1.0
    }

    /** The point and direction at [t], 0 to 1 along the curve. */
    fun atT(t: Double): CurvePose {
        val dx = slope(t, 0)
        val dy = slope(t, 1)
        val n = hypot(dx, dy)
        return CurvePose(coord(t, 0), coord(t, 1), dx / n, dy / n)
    }

    /** The point and direction [dist] tiles along the curve from its [from] edge, clamped to its length. */
    fun at(dist: Double): CurvePose = atT(tOf(dist))

    /** As [at], measured from [edge], which is [from] or [to]; the direction is the way away from [edge]. */
    fun fromEdge(edge: Int, dist: Double): CurvePose {
        if (edge == from) return at(dist)
        val p = at(length - dist)
        return CurvePose(p.x, p.y, -p.dx, -p.dy)
    }

    /** As [fromEdge], written into [out] as x, y, dx, dy without making a pose; for drawing every frame. */
    fun poseInto(edge: Int, dist: Double, out: DoubleArray) {
        val t = tOf(if (edge == from) dist else length - dist)
        val dx = slope(t, 0)
        val dy = slope(t, 1)
        val n = hypot(dx, dy)
        val sign = if (edge == from) 1 else -1
        out[0] = coord(t, 0)
        out[1] = coord(t, 1)
        out[2] = sign * dx / n
        out[3] = sign * dy / n
    }

    companion object {
        const val TABLE = 64
    }
}

/**
 * Curves for the tiles of a map. [maskAt] gives a tile's [Track] pieces (0 off the map) and [flagsAt] its [TrackCurves]
 * flag bits. Answers are cached by tile; call [invalidate] when a tile's track or flags change.
 */
class TrackCurves(private val maskAt: (Int, Int) -> Int, private val flagsAt: (Int, Int) -> Int) {
    private val cache = HashMap<Long, List<TileCurve>>()

    /** Everything drawn on tile ([x], [y]): its plain shapes and the parts of eased spans, in piece order. */
    fun curvesAt(x: Int, y: Int): List<TileCurve> = cache.getOrPut(key(x, y)) {
        val masks = HashMap<Long, Int>()
        val flags = HashMap<Long, Int>()
        for (dx in -2..2) for (dy in -2..2) {
            val k = key(x + dx, y + dy)
            masks[k] = maskAt(x + dx, y + dy)
            flags[k] = flagsAt(x + dx, y + dy)
        }
        drawn(masks, flags, key(x, y))
    }

    /** The curve of [piece] on tile ([x], [y]) itself; null if the tile has none. For a straight beside an eased
     *  turnout this is the through line; [branchAt] gives the branch. */
    fun curveOf(x: Int, y: Int, piece: Int): TileCurve? = curvesAt(x, y).firstOrNull { it.sim == piece && it.via == 0 }

    /** The curve on a straight's tile that eases into diagonal [via], or null if it doesn't. */
    fun branchAt(x: Int, y: Int, piece: Int, via: Int): TileCurve? =
        curvesAt(x, y).firstOrNull { it.sim == piece && it.via == via }

    /** Forgets the tiles that read tile ([x], [y]): those within two of it. */
    fun invalidate(x: Int, y: Int) {
        for (dx in -2..2) for (dy in -2..2) cache.remove(key(x + dx, y + dy))
    }

    /** Forgets the tiles that read any tile from ([x0], [y0]) to ([x1], [y1]) inclusive. */
    fun invalidate(x0: Int, y0: Int, x1: Int, y1: Int) {
        for (x in x0 - 2..x1 + 2) for (y in y0 - 2..y1 + 2) cache.remove(key(x, y))
    }

    fun invalidateAll() = cache.clear()

    /** How many tiles' curves are kept. */
    val size get() = cache.size

    companion object {
        const val PLAIN = 0
        const val BACK = 1
        const val CORNER = 2

        // Flag bits for [flagsAt]: anything on a tile that keeps a span off it.
        const val SLOPE = 1
        const val BRIDGE = 2
        const val TUNNEL = 4
        const val CROSSING = 8
        const val PLATFORM = 16
        const val DEPOT = 32
        const val SIGNAL = 64
        private const val BLOCKING = 127

        /** Shares of the way from each end to the corner of the two lines, straight end then diagonal end. */
        private val BACK_SHARE = doubleArrayOf(0.39, 0.99)
        /** A quarter circle's handles as a share of the way to the corner. */
        private const val CORNER_SHARE = 0.5523
        /** How far past 45 degrees the two hooks of a crossover meet. */
        private const val SWING_S = 24.0

        fun heading16(dx: Double, dy: Double): Int = (round(atan2(dy, dx) / (PI / 8)).toInt() % 16 + 16) % 16

        /** Curves over [world]'s track. Slope, bridge and tunnel heads and decks, level crossings, platforms,
         *  depots and signals keep a span off a tile. */
        fun of(world: World) = TrackCurves(
            { x, y -> if (world.inside(x, y)) world.track[world.index(x, y)].toInt() and 63 else 0 },
            { x, y -> flagsOf(world, x, y) },
        )

        fun flagsOf(world: World, x: Int, y: Int): Int {
            if (!world.inside(x, y)) return 0
            val i = world.index(x, y)
            var f = 0
            if (world.track[i].toInt() == 0 && world.deck[i] == null) return 0
            if (!world.isFlat(x, y)) f = f or SLOPE
            world.deck[i]?.let { f = f or if (world.spans[it]?.tunnel == true) TUNNEL else BRIDGE }
            world.spanHeads[i]?.let { f = f or if (world.spans[it]?.tunnel == true) TUNNEL else BRIDGE }
            if (world.crossing(i)) f = f or CROSSING
            if (world.stationTiles.containsKey(i)) f = f or PLATFORM
            if (world.depotTiles.containsKey(i)) f = f or DEPOT
            if (Track.PIECES.any { world.signals.containsKey(pieceKey(i, it)) || world.signals.containsKey(pieceKey(i, it, 1)) }) f = f or SIGNAL
            return f
        }

        // ---- The geometry, ported from curves.py -----------------------------------------------------------

        private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        private fun kx(k: Long) = (k shr 32).toInt()
        private fun ky(k: Long) = k.toInt()
        private fun step(k: Long, edge: Int) = key(kx(k) + Track.dx(edge), ky(k) + Track.dy(edge))

        private fun lowEdge(piece: Int) = Track.edges(piece) and 3
        private fun highEdge(piece: Int) = Track.edges(piece) shr 2
        private fun isStraight(piece: Int) = piece == Track.X || piece == Track.Y
        private fun midX(edge: Int) = Track.edgeX4(edge) / 4.0
        private fun midY(edge: Int) = Track.edgeY4(edge) / 4.0
        private fun far(piece: Int, edge: Int) = Track.other(piece, edge)

        /** A tile's pieces in [Track.PIECES] order. */
        private fun piecesOf(mask: Int) = Track.PIECES.filter { mask and it != 0 }

        private class V(val x: Double, val y: Double) {
            operator fun unaryMinus() = V(-x, -y)
        }

        /** Straight out of the tile through each edge. */
        private fun out(edge: Int) = V(Track.dx(edge).toDouble(), Track.dy(edge).toDouble())

        private fun own(piece: Int): V {
            val dx = midX(highEdge(piece)) - midX(lowEdge(piece))
            val dy = midY(highEdge(piece)) - midY(lowEdge(piece))
            val n = hypot(dx, dy)
            return V(dx / n, dy / n)
        }

        /** The piece's own direction where it leaves its tile through [edge]. */
        private fun outward(piece: Int, edge: Int): V = if (highEdge(piece) == edge) own(piece) else -own(piece)

        private fun turn(d: V, deg: Double): V {
            val a = deg * PI / 180
            return V(d.x * cos(a) - d.y * sin(a), d.x * sin(a) + d.y * cos(a))
        }

        /** The handle length of a piece's cubic by this end's look and the other's. */
        private fun handle(a: Char, b: Char): Double = when ("$a$b") {
            "TT" -> 0.2761
            "TD" -> 0.280
            "DT" -> 0.219
            "TS" -> 0.314
            "ST" -> 0.164
            else -> 0.2357
        }

        private class Layout(val masks: Map<Long, Int>) {
            fun pieces(t: Long): List<Int> = piecesOf(masks[t] ?: 0)
        }

        /** The diagonal across [edge] that carries on in [piece]'s line, as its tile and piece, or null. */
        private fun carriesOn(l: Layout, tile: Long, piece: Int, edge: Int): Pair<Long, Int>? {
            if (isStraight(piece)) return null
            val nt = step(tile, edge)
            val mine = outward(piece, edge)
            for (p in l.pieces(nt)) {
                if (!isStraight(p) && Track.has(p, Track.opposite(edge))) {
                    val theirs = outward(p, Track.opposite(edge))
                    if (abs(mine.x + theirs.x) < 1e-9 && abs(mine.y + theirs.y) < 1e-9) return nt to p
                }
            }
            return null
        }

        /** T or D at a diagonal's end, before S is worked out. */
        private fun baseLook(l: Layout, tile: Long, piece: Int, edge: Int): Char {
            if (carriesOn(l, tile, piece, edge) != null) return 'D'
            val nt = step(tile, edge)
            val here = l.pieces(tile).any { it != piece && Track.has(it, edge) }
            val there = l.pieces(nt).any { Track.has(it, Track.opposite(edge)) }
            return if (here || there) 'T' else 'D'
        }

        /** How [piece] meets [edge]: T, D or S, and its outward direction there. */
        private fun endLook(l: Layout, tile: Long, piece: Int, edge: Int): Pair<Char, V> {
            if (isStraight(piece)) return 'T' to out(edge)
            if (baseLook(l, tile, piece, edge) == 'T') return 'T' to out(edge)
            val on = carriesOn(l, tile, piece, edge) ?: return 'D' to outward(piece, edge)
            val (nt, other) = on
            if (!(baseLook(l, tile, piece, far(piece, edge)) == 'T' &&
                    baseLook(l, nt, other, far(other, Track.opposite(edge))) == 'T')) return 'D' to outward(piece, edge)
            val axis = out(far(piece, edge))
            val d = outward(piece, edge)
            val a = turn(d, SWING_S)
            val b = turn(d, -SWING_S)
            val da = abs(a.x * axis.x + a.y * axis.y)
            val db = abs(b.x * axis.x + b.y * axis.y)
            return 'S' to (if (da < db) a else b)
        }

        private class Shape(val looks: String, val ctrl: DoubleArray)

        private fun shapes(l: Layout): Map<Pair<Long, Int>, Shape> {
            val res = HashMap<Pair<Long, Int>, Shape>()
            for (tile in l.masks.keys) for (piece in l.pieces(tile)) {
                val ea = lowEdge(piece)
                val eb = highEdge(piece)
                val (la, oa) = endLook(l, tile, piece, ea)
                val (lb, ob) = endLook(l, tile, piece, eb)
                val da = -oa
                val ha = if (isStraight(piece)) 1.0 / 3 else handle(la, lb)
                val hb = if (isStraight(piece)) 1.0 / 3 else handle(lb, la)
                val ax = midX(ea)
                val ay = midY(ea)
                val bx = midX(eb)
                val by = midY(eb)
                val ctrl = doubleArrayOf(
                    ax, ay, ax + da.x * ha, ay + da.y * ha, bx - ob.x * hb, by - ob.y * hb, bx, by,
                )
                res[tile to piece] = Shape("$la$lb", ctrl)
            }
            return res
        }

        private class Part(val tile: Long, val sim: Int, val via: Int, val from: Int, val to: Int)

        /** An eased line: control points in world terms, its parts in the way it runs, the tile and edge it is split
         *  at, the edge its switch is on, whether it has switch points and which straights its parts replace. */
        private class Span(
            val kind: Int,
            val group: String,
            val ctrl: DoubleArray,
            val parts: List<Part>,
            val splitTile: Long,
            val splitEdge: Int,
            val edge: Int,
            val hide: List<Pair<Long, Int>>,
        ) {
            fun tiles() = parts.map { it.tile }
        }

        private fun world(tile: Long, edge: Int) = doubleArrayOf(kx(tile) + midX(edge), ky(tile) + midY(edge))

        private fun lerp(a: DoubleArray, b: DoubleArray, f: Double) =
            doubleArrayOf(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)

        private fun hooks(l: Layout): Map<Pair<Long, Int>, IntArray> {
            val out = LinkedHashMap<Pair<Long, Int>, IntArray>()
            for ((k, sh) in shapes(l)) {
                val piece = k.second
                if (!isStraight(piece) && sh.looks.toSet() == setOf('D', 'T')) {
                    val ea = lowEdge(piece)
                    val eb = highEdge(piece)
                    out[k] = if (sh.looks[0] == 'T') intArrayOf(ea, eb) else intArrayOf(eb, ea)
                }
            }
            return out
        }

        private fun label(piece: Int) = when (piece) { Track.X -> 'X'; Track.Y -> 'Y'; Track.N -> 'N'; Track.E -> 'E'; Track.S -> 'S'; else -> 'W' }

        /** Every span the pieces allow, before the clear rule. */
        private fun candidates(l: Layout): List<Span> {
            val hooks = hooks(l)
            val res = ArrayList<Span>()
            val seen = HashSet<String>()
            val order = hooks.keys.sortedWith(compareBy({ kx(it.first) }, { ky(it.first) }, { label(it.second) }))
            for (k in order) {
                val (t, p) = k
                val (e, f) = hooks.getValue(k).let { it[0] to it[1] }
                val n = step(t, e)
                val oe = Track.opposite(e)
                val r = l.pieces(n).firstOrNull { hooks[n to it]?.get(0) == oe }
                if (r != null) {
                    val ga = "${kx(t)},${ky(t)},$e"
                    val gb = "${kx(n)},${ky(n)},$oe"
                    val group = if (compareValuesBy(t to e, n to oe, { kx(it.first) }, { ky(it.first) }, { it.second }) <= 0) "$ga|$gb" else "$gb|$ga"
                    if (!seen.add(group)) continue
                    val g = hooks.getValue(n to r)[1]
                    val p0 = world(t, f)
                    val p3 = world(n, g)
                    val c = world(t, e)
                    val a = lerp(p0, c, CORNER_SHARE)
                    val b = lerp(p3, c, CORNER_SHARE)
                    res += Span(CORNER, group, doubleArrayOf(p0[0], p0[1], a[0], a[1], b[0], b[1], p3[0], p3[1]),
                        listOf(Part(t, p, 0, f, e), Part(n, r, 0, oe, g)), t, e, e, emptyList())
                    continue
                }
                val others = l.pieces(t).filter { it != p && Track.has(it, e) }
                val through = others.filter { isStraight(it) }
                val sp = l.pieces(n).firstOrNull { isStraight(it) && Track.has(it, oe) } ?: continue
                val g = far(sp, oe)
                val p0 = world(n, g)
                val c = world(t, e)
                val p3 = world(t, f)
                val a = lerp(p0, c, BACK_SHARE[0])
                val b = lerp(p3, c, BACK_SHARE[1])
                val hide = if (through.isEmpty()) listOf(n to sp) else emptyList()
                res += Span(BACK, "${kx(t)},${ky(t)},$e", doubleArrayOf(p0[0], p0[1], a[0], a[1], b[0], b[1], p3[0], p3[1]),
                    listOf(Part(n, sp, p, g, oe), Part(t, p, 0, e, f)), t, e, e, hide)
            }
            return res
        }

        /** The tile where a span's hook starts square to its edge, which may also hold the switch's other routes. */
        private fun switchTile(sp: Span) = sp.parts[1].tile

        /** The clear rule. */
        private fun tileClear(sp: Span, l: Layout, flags: Map<Long, Int>): Boolean {
            for (part in sp.parts) {
                if ((flags[part.tile] ?: 0) and BLOCKING != 0) return false
                val here = l.pieces(part.tile)
                if (sp.kind != CORNER && part.tile == switchTile(sp)) {
                    if (!here.all { Track.has(it, sp.edge) }) return false
                } else if (here != listOf(part.sim)) return false
            }
            return true
        }

        private fun easedSpans(l: Layout, flags: Map<Long, Int>): List<Span> {
            val found = candidates(l).filter { tileClear(it, l, flags) }
            val claims = HashMap<Long, MutableSet<String>>()
            for (sp in found) for (t in sp.tiles()) claims.getOrPut(t) { HashSet() }.add(sp.group)
            return found.filter { sp -> sp.tiles().all { claims.getValue(it).size == 1 } }
        }

        private fun cubicAt(c: DoubleArray, t: Double, k: Int): Double {
            val s = 1 - t
            return s * s * s * c[k] + 3 * s * s * t * c[2 + k] + 3 * s * t * t * c[4 + k] + t * t * t * c[6 + k]
        }

        /** A span as the curves of its parts, split where it crosses its middle edge. */
        private fun spanParts(sp: Span): List<TileCurve> {
            val axis = if (sp.splitEdge == Track.XM || sp.splitEdge == Track.XP) 0 else 1
            val value = world(sp.splitTile, sp.splitEdge)[axis]
            var lo = 0.0
            var hi = 1.0
            var flo = cubicAt(sp.ctrl, lo, axis) - value
            repeat(60) {
                val m = (lo + hi) / 2
                val fm = cubicAt(sp.ctrl, m, axis) - value
                if (fm * flo > 0) {
                    lo = m
                    flo = fm
                } else hi = m
            }
            val t = (lo + hi) / 2
            val c = sp.ctrl
            val p0 = doubleArrayOf(c[0], c[1])
            val p1 = doubleArrayOf(c[2], c[3])
            val p2 = doubleArrayOf(c[4], c[5])
            val p3 = doubleArrayOf(c[6], c[7])
            val a = lerp(p0, p1, t)
            val b = lerp(p1, p2, t)
            val cc = lerp(p2, p3, t)
            val d = lerp(a, b, t)
            val e = lerp(b, cc, t)
            val m = lerp(d, e, t)
            val halves = listOf(
                doubleArrayOf(p0[0], p0[1], a[0], a[1], d[0], d[1], m[0], m[1]),
                doubleArrayOf(m[0], m[1], e[0], e[1], cc[0], cc[1], p3[0], p3[1]),
            )
            return sp.parts.zip(halves).map { (part, half) ->
                val local = DoubleArray(8) { half[it] - if (it % 2 == 0) kx(part.tile) else ky(part.tile) }
                TileCurve(part.sim, part.via, part.from, part.to, local, sp.kind, sp.group, "EE")
            }
        }

        /** What tile [at] draws, from the 5 by 5 round it in [masks] and [flags]. */
        private fun drawn(masks: Map<Long, Int>, flags: Map<Long, Int>, at: Long): List<TileCurve> {
            val l = Layout(masks.filterValues { it != 0 })
            val base = shapes(l)
            val spans = easedSpans(l, flags)
            val covered = HashSet<Pair<Long, Int>>()
            val hidden = HashSet<Pair<Long, Int>>()
            val res = ArrayList<TileCurve>()
            for (sp in spans) {
                hidden.addAll(sp.hide)
                for ((part, curve) in sp.parts.zip(spanParts(sp))) {
                    if (part.via == 0) covered += part.tile to part.sim
                    if (part.tile == at) res += curve
                }
            }
            for ((k, sh) in base) {
                if (k.first != at || k in covered || k in hidden) continue
                val piece = k.second
                res += TileCurve(piece, 0, lowEdge(piece), highEdge(piece), sh.ctrl, PLAIN, null, sh.looks)
            }
            return res.sortedWith(compareBy({ Track.index(it.sim) }, { it.via }))
        }
    }
}
