package com.rm.interchange.sim

/** The most places [stationsJoined] looks at, over all its searches together. */
private const val JOIN_LOOK = 200_000

/**
 * Whether two of [company]'s stations are joined: platforms by the company's own track the way a train can run,
 * turning only in its depots and at its platforms, or road stops by any road a road vehicle can drive.
 *
 * Each station is searched from in turn, sharing one set of places seen. A place an earlier search reached without
 * finding a second station can't lead to one either, as the way back is the same track or road the other way round.
 * One-way roads and signals aren't followed for that, so it can be wrong for them.
 */
fun World.stationsJoined(company: Int): Boolean {
    val mine = stations.filterNotNull().filter { it.owner == company && !it.waypoint }
    if (mine.size < 2) return false
    val seen = PlaceSet(JOIN_LOOK)
    val queue = IntArray(JOIN_LOOK)

    // Rail places are steps, road places road steps made negative, so both go in one set.
    fun ownRail(tile: Int, level: Int): Boolean =
        if (level == 1) deck[tile]?.let { spans.getOrNull(it)?.owner } == company
        else owns(company, tile) && depotAt(tile).let { it == null || it.owner == company && it.kind == Depots.RAIL }
    fun turnsAt(tile: Int, level: Int): Boolean =
        level == 0 && (depotAt(tile)?.owner == company || stationAt(tile)?.let { it.owner == company && !it.waypoint } == true)

    for (from in mine) {
        var size = 0
        fun railGoal(s: Int) = stepLevel(s) == 0 && stationAt(stepTile(s))?.let { it.id != from.id && it.owner == company && !it.waypoint } == true
        fun roadGoal(s: Int) = roadStops[roadStepTile(s)]?.let { it != from.id && stations.getOrNull(it)?.owner == company } == true
        // False once the search has looked at all it may.
        fun visit(key: Int): Boolean {
            if (!seen.add(key)) return true
            if (seen.size >= JOIN_LOOK) return false
            queue[size++] = key
            return true
        }
        for (tile in from.tiles) for (entry in 0 until 4) for (s in waysIn(tile, entry)) if (!visit(s)) return false
        for (tile in from.stops) for (entry in 0 until 4) for (s in roadWays(tile, entry)) if (!visit(-s - 1)) return false
        var head = 0
        while (head < size) {
            val key = queue[head++]
            if (key >= 0) {
                val tile = stepTile(key)
                val level = stepLevel(key)
                if (turnsAt(tile, level) && !visit(step(tile, (key shr 2) and 7, stepExit(key), level))) return false
                // Into a depot is only ever turning round there.
                if (level == 0 && depotTiles.containsKey(tile)) continue
                for (n in waysOn(key)) {
                    if (!ownRail(stepTile(n), stepLevel(n))) continue
                    if (railGoal(n)) return true
                    if (!visit(n)) return false
                }
            } else {
                for (n in roadNext(-key - 1)) {
                    if (depotTiles.containsKey(roadStepTile(n))) continue
                    if (roadGoal(n)) return true
                    if (!visit(-n - 1)) return false
                }
            }
        }
    }
    return false
}

/** A set of ints that never holds more than [most], kept in one array with open addressing. */
private class PlaceSet(most: Int) {
    private val keys: IntArray
    private val used: BooleanArray
    private val mask: Int
    var size = 0
        private set

    init {
        var n = 16
        while (n < most * 2) n = n shl 1
        keys = IntArray(n)
        used = BooleanArray(n)
        mask = n - 1
    }

    /** Adds [k], and whether it wasn't there before. */
    fun add(k: Int): Boolean {
        var i = (k * -0x61c88647) ushr 7 and mask
        while (used[i]) {
            if (keys[i] == k) return false
            i = (i + 1) and mask
        }
        used[i] = true
        keys[i] = k
        size++
        return true
    }
}
