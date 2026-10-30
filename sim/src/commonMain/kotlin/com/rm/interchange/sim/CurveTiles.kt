package com.rm.interchange.sim

/**
 * Which tiles draw their track on curves, the one rule the baked track art, the cars and the lines drawn over the map
 * (wires, signals, reserved track) share. Drawing only.
 *
 * A tile is drawn curved where its track lies level, on the land or on a foundation, it isn't a bridge or tunnel end
 * or deck, a level crossing, a platform or a depot, and the static sheet has every sprite it draws in all four turns
 * of the view, so turning the view never swaps it between curved and straight art. Elsewhere its pieces run straight
 * edge to edge with the old art.
 *
 * [world] gives the world when asked, so a cache holding this needn't keep a dropped world. [maskOf] gives the track
 * pieces on a tile in world terms, the world's own unless a plan is being looked at. Not thread safe: one per thread.
 *
 * Only the curves in the view's own turn are kept ([keys]); [curved] works out the other turns on small capped caches,
 * or with [keepTurns] keeps every turn's, for the map's bakers, which look at every tile at every level.
 */
class CurveTiles private constructor(private val world: () -> World, private val maskOf: (Int, Int) -> Int, private val keepTurns: Boolean) {
    constructor(world: () -> World, maskOf: (Int, Int) -> Int) : this(world, maskOf, false)
    constructor(world: () -> World) : this(world, { x, y -> maskAt(world(), x, y) }, false)
    constructor(keepTurns: Boolean, world: () -> World) : this(world, { x, y -> maskAt(world(), x, y) }, keepTurns)

    /** The world's curves, in world terms. */
    val curves = TrackCurves(maskOf) { x, y -> TrackCurves.flagsOf(world(), x, y) }

    /** The same, turned as a view at each quarter turn has it, made when asked; tile (u, v) of turn t is [worldOf] it. */
    private val turned = arrayOfNulls<TrackCurves>(4).also { it[0] = curves }
    private val masks: Array<(Int, Int) -> Int> = Array(4) { t -> { u: Int, v: Int -> viewMask(t, u, v) } }

    private fun turnedCurves(t: Int) = TrackCurves(masks[t]) { u, v -> TrackCurves.flagsOf(world(), wx(t, u, v), wy(t, u, v)) }

    /** The curves other turns work out the keys of [curved] from, emptied past [PROBE_KEEP] tiles. */
    private val probes = arrayOfNulls<TrackCurves>(4)

    /** Whether tile ([x], [y]) draws its track on curves. */
    fun curved(x: Int, y: Int): Boolean {
        val w = world()
        if (!levelRail(w, x, y, if (w.inside(x, y)) maskOf(x, y) else 0)) return false
        val known = CurveKeys.known ?: return true
        for (t in 0..3) {
            val probe = if (t == 0) curves else if (keepTurns) turnedOf(t) else (probes[t] ?: turnedCurves(t).also { probes[t] = it })
            val keys = CurveKeys.at(probe, masks[t], ux(t, x, y), uy(t, x, y))
            if (t != 0 && !keepTurns && probe.size > PROBE_KEEP) probe.invalidateAll()
            if (keys.isEmpty()) return false
            for (k in keys) if (k !in known) return false
        }
        return true
    }

    /** The sprite keys tile ([x], [y]) draws with the view at quarter turn [turn], as [CurveKeys.at] gives them. */
    fun keys(x: Int, y: Int, turn: Int): List<String> = CurveKeys.at(turnedOf(turn), masks[turn], ux(turn, x, y), uy(turn, x, y))

    private fun turnedOf(t: Int) = turned[t] ?: turnedCurves(t).also { turned[t] = it }

    /** Forgets the curves that read any tile from ([x0], [y0]) to ([x1], [y1]) inclusive. */
    fun invalidate(x0: Int, y0: Int, x1: Int, y1: Int) {
        for (t in 0..3) {
            val ua = ux(t, x0, y0)
            val ub = ux(t, x1, y1)
            val va = uy(t, x0, y0)
            val vb = uy(t, x1, y1)
            turned[t]?.invalidate(minOf(ua, ub), minOf(va, vb), maxOf(ua, ub), maxOf(va, vb))
            if (t != 0) probes[t]?.invalidate(minOf(ua, ub), minOf(va, vb), maxOf(ua, ub), maxOf(va, vb))
        }
    }

    private fun viewMask(t: Int, u: Int, v: Int): Int {
        val m = maskOf(wx(t, u, v), wy(t, u, v))
        if (t == 0 || m == 0) return m
        var out = 0
        for (p in Track.PIECES) if (m and p != 0) {
            val e = Track.edges(p)
            out = out or Track.pieceOf(viewEdge(t, e and 3), viewEdge(t, e shr 2))
        }
        return out
    }

    companion object {
        /** What keeps a tile on the old straight art whatever its shape. */
        const val OLD_ART = TrackCurves.BRIDGE or TrackCurves.TUNNEL or TrackCurves.CROSSING or TrackCurves.DEPOT or TrackCurves.PLATFORM

        /** How far a tile's curved art reads: its keys read the curves of the tile next door, which read two further. */
        const val REACH = 3

        /** Tiles a probe keeps, enough for the neighbours along a chunk's rows to be shared. */
        private const val PROBE_KEEP = 512

        /**
         * Whether tile ([x], [y]) with track [pieces] on it would lie level and could be drawn curved, before asking for
         * its sprites.
         */
        fun levelRail(world: World, x: Int, y: Int, pieces: Int = if (world.inside(x, y)) world.trackAt(x, y) else 0): Boolean {
            if (!world.inside(x, y)) return false
            if (pieces == 0 || TrackCurves.flagsOf(world, x, y) and OLD_ART != 0) return false
            val h = world.surfaceHeight(x, y, pieces, CORNER_N)
            return world.surfaceHeight(x, y, pieces, CORNER_E) == h && world.surfaceHeight(x, y, pieces, CORNER_S) == h &&
                world.surfaceHeight(x, y, pieces, CORNER_W) == h
        }

        private fun maskAt(world: World, x: Int, y: Int) = if (world.inside(x, y)) world.track[world.index(x, y)].toInt() and 63 else 0

        // A view at quarter turn t sees world tile (x, y) as (u, v), the same offsets View.worldX and worldY give.
        private fun wx(t: Int, u: Int, v: Int) = when (t) { 0 -> u; 1 -> v; 2 -> -u; else -> -v }
        private fun wy(t: Int, u: Int, v: Int) = when (t) { 0 -> v; 1 -> -u; 2 -> -v; else -> u }
        private fun ux(t: Int, x: Int, y: Int) = when (t) { 0 -> x; 1 -> -y; 2 -> -x; else -> y }
        private fun uy(t: Int, x: Int, y: Int) = when (t) { 0 -> y; 1 -> x; 2 -> -y; else -> -x }

        /** World [edge] as a view at quarter turn [t] sees it. */
        fun viewEdge(t: Int, edge: Int): Int = when (t) {
            0 -> edge
            1 -> when (edge) { Track.XM -> Track.YM; Track.XP -> Track.YP; Track.YM -> Track.XP; else -> Track.XM }
            2 -> Track.opposite(edge)
            else -> when (edge) { Track.YM -> Track.XM; Track.YP -> Track.XP; Track.XM -> Track.YP; else -> Track.YM }
        }
    }
}

/**
 * Watches a world's blocks for changes, for the drawing caches. It counts the bumps to [World.chunkVersions] it has
 * taken in against [World.touches], so a bump not yet seen when the count moved is picked up on a later call.
 */
class BlockWatch(world: World) {
    private val versions = world.chunkVersions.copyOf()
    private var seen = versions.sum()

    /** Calls [changed] with the tile range of each block that changed since the last call. */
    fun forChanged(world: World, changed: (Int, Int, Int, Int) -> Unit) {
        if (world.touches == seen) return
        val across = world.width / World.BLOCK
        for (b in versions.indices) {
            val v = world.chunkVersions[b]
            if (v == versions[b]) continue
            seen += v - versions[b]
            versions[b] = v
            val x0 = b % across * World.BLOCK
            val y0 = b / across * World.BLOCK
            changed(x0, y0, x0 + World.BLOCK - 1, y0 + World.BLOCK - 1)
        }
    }
}
