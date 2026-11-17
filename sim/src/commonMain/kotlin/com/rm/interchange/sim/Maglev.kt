package com.rm.interchange.sim

/*
 * Maglev, with [NewGame.future] on, from [MAGLEV_FROM]. Guideway is track on a tile marked in [World.guideway]: the
 * same pieces, signals, junctions, platforms, depots, bridges and tunnels as rail, kept apart from it. A tile is all
 * rail or all guideway, and no piece of one leads into a piece of the other across an edge, so the two never join
 * and a train only runs on the kind its depot is. Guideway crosses no road on the level and takes no wires.
 *
 * Maglev trains are made of engines and coaches with traction maglev, bought only in a depot on guideway. They need
 * no wires and no grip, take bends faster than rail and aren't held to a bridge's or tunnel's speed. Trains don't
 * wear guideway and the weather doesn't heave it, so it's never on a slow order; it costs [Maglev.UPKEEP_TILES]
 * tiles of rail to keep up.
 */

/** The year guideway can first be built. */
const val MAGLEV_FROM = 2040

object Maglev {
    /** A piece of guideway, lifting one, a platform and a depot on it, in dollars before inflation. */
    const val TRACK = 2_000L
    const val TRACK_REMOVE = 100L
    const val PLATFORM = 2_400L
    const val DEPOT = 6_000L
    /** A bridge or tunnel for guideway costs this many times rail's for each tile between its heads. */
    const val SPAN_TIMES = 2
    /** What a tile of guideway counts as in its company's track upkeep, against 1 for a tile of rail. */
    const val UPKEEP_TILES = 3
    /** How fast a maglev takes a tight bend and a gentle one, in km/h. */
    const val TIGHT_KMH = 160
    const val GENTLE_KMH = 300
}

/** A maglev engine or coach, which runs only on guideway. */
val Engine.maglev get() = traction == Traction.MAGLEV

/** Whether guideway can be built now: future transport on and [MAGLEV_FROM] come. */
fun World.maglevOpen() = settings.future && Calendar.year(startYear, day) >= MAGLEV_FROM

/** Whether a train of [cars] is a maglev, by its first car; [validTrainFormation] keeps them all one kind. */
fun maglevCars(roster: Roster, cars: List<TrainCar>) = cars.isNotEmpty() && roster[cars.first().model].maglev

/** Whether depot [d] stands on guideway, so buys and takes maglev trains. */
fun World.maglevDepot(d: Depot) = guideway[d.tile]

/** Whether span [s] carries guideway, as its heads do. */
fun World.maglevSpan(s: Span) = guideway[index(s.x0, s.y0)]

/**
 * Whether pieces on tile [i] of kind [maglev] (guideway or rail) meet no piece of the other kind across [edges]
 * (bits by edge), with [trackOf] the track and [kindOf] the kind of each tile.
 */
internal fun World.kindMeets(i: Int, edges: Int, maglev: Boolean, trackOf: (Int) -> Int, kindOf: (Int) -> Boolean): Boolean {
    for (edge in 0 until 4) {
        if (edges and (1 shl edge) == 0) continue
        val x = i % width + Track.dx(edge)
        val y = i / width + Track.dy(edge)
        if (!inside(x, y)) continue
        val n = index(x, y)
        val theirs = trackOf(n)
        if (theirs == 0 || kindOf(n) == maglev) continue
        val back = Track.opposite(edge)
        if (Track.PIECES.any { theirs and it != 0 && Track.has(it, back) }) return false
    }
    return true
}

/** The edges [pieces] lead out through, as bits by edge. */
internal fun pieceEdges(pieces: Int): Int {
    var out = 0
    for (p in Track.PIECES) if (pieces and p != 0) {
        val e = Track.edges(p)
        out = out or (1 shl (e and 3)) or (1 shl (e shr 2))
    }
    return out
}

/** Guideway tiles in the checksum, and the setting, only with future transport on, so older games keep theirs. */
internal fun World.maglevChecksum(mix: (Long) -> Unit) {
    if (!settings.future) return
    mix(920L)
    for (i in guideway.indices) if (guideway[i] && track[i].toInt() != 0) mix(i.toLong() * 37)
}
