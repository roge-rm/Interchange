package com.rm.interchange.sim

/*
 * Signal blocks: the stretches of track between signals. Pieces on one tile
 * that meet or cross are in the same block; track carries a block on from tile
 * to tile until a signal stands at the join. A block signal shows red while
 * another train is in the block ahead of it.
 *
 * Worked out afresh whenever track or signals change, then every train is
 * counted again on the blocks it's on.
 */

/** Marks the blocks and every route for working out again, after track, a station, a depot or a signal changes. */
internal fun World.networkChanged() {
    blocksDirty = true
    catchmentDirty = true
    linksDirty = true
    for (t in trains) if (t != null) {
        t.route = null
        releaseAhead(t)
    }
}

/** Marks the blocks for working out again without touching routes, as when a world has just been loaded. */
internal fun World.networkChangedKeepingRoutes() {
    blocksDirty = true
    catchmentDirty = true
    linksDirty = true
}

internal fun World.workOutBlocks() {
    blocks.clear()
    var next = 0
    val queue = ArrayDeque<Int>()
    for (tile in track.indices) {
        val pieces = track[tile].toInt()
        if (pieces == 0) continue
        for (p in Track.PIECES) {
            if (pieces and p == 0 || blocks.containsKey(pieceKey(tile, p))) continue
            val id = next++
            blocks[pieceKey(tile, p)] = id
            queue.addLast(pieceKey(tile, p))
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                val t = key / 8
                val piece = Track.PIECES[key % 8]
                val here = track[t].toInt()
                fun join(k: Int) {
                    if (!blocks.containsKey(k)) {
                        blocks[k] = id
                        queue.addLast(k)
                    }
                }
                for (q in Track.PIECES) if (q != piece && here and q != 0 && Track.touch(piece, q)) join(pieceKey(t, q))
                val e = Track.edges(piece)
                for (edge in intArrayOf(e and 3, e shr 2)) {
                    val mine = signalAt(t, piece)
                    if (mine >= 0 && Signals.facing(mine) == edge) continue
                    val x = t % width + Track.dx(edge)
                    val y = t / width + Track.dy(edge)
                    if (!inside(x, y)) continue
                    val n = index(x, y)
                    for (w in waysIn(n, Track.opposite(edge))) {
                        val q = stepPiece(w)
                        val theirs = signalAt(n, q)
                        if (theirs >= 0 && Signals.facing(theirs) == Track.opposite(edge)) continue
                        join(pieceKey(n, q))
                    }
                }
            }
        }
    }
    // Each signal is a way out of the block before it into the block past it, and a two-way one the other way too.
    exits.clear()
    for ((key, s) in signals) {
        val tile = key / 8
        val here = blocks[key] ?: continue
        val f = Signals.facing(s)
        val x = tile % width + Track.dx(f)
        val y = tile / width + Track.dy(f)
        if (!inside(x, y)) continue
        val n = index(x, y)
        for (w in waysIn(n, Track.opposite(f))) {
            val there = blocks[pieceKey(n, stepPiece(w))] ?: continue
            exits.getOrPut(there) { ArrayList() } += intArrayOf(Signals.kind(s), here)
            if (!Signals.oneWay(s)) exits.getOrPut(here) { ArrayList() } += intArrayOf(Signals.kind(s), there)
        }
    }
    blocksDirty = false
    tileTrains.clear()
    pieceTrains.clear()
    blockTrains.clear()
    for (t in trains) {
        if (t == null) continue
        t.onTiles = IntArray(0)
        t.onPieces = IntArray(0)
        t.onBlocks = IntArray(0)
        countOn(t)
    }
}

/**
 * Whether a signal [s] on [piece] of [tile] shows red, for drawing: a path signal unless a train holds
 * the way through it, any other while a train is in its block.
 */
fun World.signalRed(tile: Int, piece: Int, s: Int): Boolean =
    if (Signals.path(s)) reserved[pieceKey(tile, piece)] == null else blockHasTrain(tile, piece)

/** Whether the block [piece] of [tile] is in has a train in it, for drawing its signals. */
fun World.blockHasTrain(tile: Int, piece: Int): Boolean {
    val b = blocks[pieceKey(tile, piece)] ?: return false
    return (blockTrains[b] ?: 0) > 0
}
