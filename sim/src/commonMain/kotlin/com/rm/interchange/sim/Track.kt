package com.rm.interchange.sim

/**
 * Track pieces. A tile holds any mix of six: straight along x or y, or one of
 * four short diagonals cutting off a corner. A tile's corners are N at (0, 0),
 * E at (1, 0), S at (1, 1) and W at (0, 1), and each piece runs between the
 * middles of two of its edges.
 */
object Track {
    const val X = 1
    const val Y = 2
    const val N = 4
    const val E = 8
    const val S = 16
    const val W = 32
    const val ALL = 63

    val PIECES = intArrayOf(X, Y, N, E, S, W)

    // Edges: the low and high x sides, the low and high y sides.
    const val XM = 0
    const val XP = 1
    const val YM = 2
    const val YP = 3

    /** The two edges [piece] joins, as a pair packed low and high. */
    fun edges(piece: Int): Int = when (piece) {
        X -> XM or (XP shl 2)
        Y -> YM or (YP shl 2)
        N -> YM or (XM shl 2)
        E -> YM or (XP shl 2)
        S -> XP or (YP shl 2)
        W -> XM or (YP shl 2)
        else -> error("Not a piece: $piece")
    }

    fun has(piece: Int, edge: Int): Boolean {
        val e = edges(piece)
        return e and 3 == edge || e shr 2 == edge
    }

    fun pieceOf(a: Int, b: Int): Int = PIECES.first { has(it, a) && has(it, b) }

    fun opposite(edge: Int) = edge xor 1

    /** The other edge [piece] joins, leaving it having come in at [edge]. */
    fun other(piece: Int, edge: Int): Int {
        val e = edges(piece)
        return if (e and 3 == edge) e shr 2 else e and 3
    }

    /** 0 to 5, in the order of [PIECES]. */
    fun index(piece: Int) = when (piece) { X -> 0; Y -> 1; N -> 2; E -> 3; S -> 4; else -> 5 }

    /** How long a piece is, in sixteenths of a tile: a diagonal cuts a corner and is shorter. */
    fun length(piece: Int) = if (piece == X || piece == Y) 16 else 11

    /** The straight piece running out through [edge]. */
    fun straight(edge: Int) = if (edge == XM || edge == XP) X else Y

    /** Whether two pieces on one tile meet or cross. Only the parallel diagonals keep apart. */
    fun touch(a: Int, b: Int) = !((a or b) == (N or S) || (a or b) == (E or W))

    /** The tile next to ([x], [y]) through [edge], as x and y steps. */
    fun dx(edge: Int) = when (edge) { XM -> -1; XP -> 1; else -> 0 }
    fun dy(edge: Int) = when (edge) { YM -> -1; YP -> 1; else -> 0 }

    /** Where an edge's middle is in the tile, x and y in quarters of a tile. */
    fun edgeX4(edge: Int) = when (edge) { XM -> 0; XP -> 4; else -> 2 }
    fun edgeY4(edge: Int) = when (edge) { YM -> 0; YP -> 4; else -> 2 }

    /**
     * The tiles and pieces a line of track takes from ([sx], [sy]) to ([ex], [ey]),
     * starting with [first]. [X] and [Y] lay straight along a row or a column;
     * a diagonal piece starts a zigzag of diagonals towards the end, which has to
     * be on a true diagonal from the start. Each entry is a tile index times 64
     * plus its piece. Null if the line doesn't make sense.
     */
    fun line(world: World, sx: Int, sy: Int, ex: Int, ey: Int, first: Int): IntArray? {
        if (!world.inside(sx, sy) || !world.inside(ex, ey)) return null
        val dx = ex - sx
        val dy = ey - sy
        val out = ArrayList<Int>()
        when (first) {
            X -> {
                if (dy != 0) return null
                val step = if (dx >= 0) 1 else -1
                var x = sx
                while (true) {
                    out += world.index(x, sy) * 64 + X
                    if (x == ex) break
                    x += step
                }
            }
            Y -> {
                if (dx != 0) return null
                val step = if (dy >= 0) 1 else -1
                var y = sy
                while (true) {
                    out += world.index(sx, y) * 64 + Y
                    if (y == ey) break
                    y += step
                }
            }
            N, E, S, W -> {
                if (dx == 0 && dy == 0) return intArrayOf(world.index(sx, sy) * 64 + first)
                if (kotlin.math.abs(dx) != kotlin.math.abs(dy)) return null
                val fx = if (dx > 0) XP else XM
                val fy = if (dy > 0) YP else YM
                // The first piece has to lead one way along the diagonal and come in from the other side.
                val leadsX = has(first, fx) && has(first, opposite(fy))
                val leadsY = has(first, fy) && has(first, opposite(fx))
                if (!leadsX && !leadsY) return null
                var x = sx
                var y = sy
                var piece = first
                repeat(2 * kotlin.math.abs(dx) + 1) {
                    if (!world.inside(x, y)) return null
                    out += world.index(x, y) * 64 + piece
                    val exit = if (has(piece, fx)) fx else fy
                    if (exit == fx) x += if (dx > 0) 1 else -1 else y += if (dy > 0) 1 else -1
                    val entry = opposite(exit)
                    piece = pieceOf(entry, if (exit == fx) fy else fx)
                }
            }
            else -> return null
        }
        return out.toIntArray()
    }
}
