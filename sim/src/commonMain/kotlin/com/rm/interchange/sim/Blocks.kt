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
    for (t in trains) t?.route = null
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
    blocksDirty = false
    tileTrains.clear()
    blockTrains.clear()
    for (t in trains) {
        if (t == null) continue
        t.onTiles = IntArray(0)
        t.onBlocks = IntArray(0)
        countOn(t)
    }
}

/** Whether the block [piece] of [tile] is in has a train in it, for drawing its signals. */
fun World.blockHasTrain(tile: Int, piece: Int): Boolean {
    val b = blocks[pieceKey(tile, piece)] ?: return false
    return (blockTrains[b] ?: 0) > 0
}
