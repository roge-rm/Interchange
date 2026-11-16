package com.rm.interchange.map

/**
 * The static sheet's cells as the map reads them: the base pack's from [Atlas], then four cells (one for each view
 * edge a building can face) for each sprite sheet a game's pack houses and industry parts draw with, on rows added
 * below the base sheet. Set for a game by [Sheets.useBuildings], before its map is drawn.
 */
object StaticCells {
    /** Cells the base sheet has. */
    val BASE = Atlas.STATIC_RECTS.size / 6

    /** Cells the GPU's table has room for, a whole number of rows with at least 64 to spare. */
    val LIMIT = (BASE + 64 + 511) / 512 * 512

    /** Cells a game's packs can add, four a sprite sheet. */
    val ROOM get() = (LIMIT - BASE) / 4 * 4

    /** [Atlas.STATIC_RECTS] and [Atlas.STATIC_LEVEL_RECTS] with the cells a game's buildings add. */
    var rects: IntArray = Atlas.STATIC_RECTS
        private set
    var levelRects: IntArray = Atlas.STATIC_LEVEL_RECTS
        private set

    /** How many cells there are now. */
    val count get() = rects.size / 6

    internal fun set(extra: BuildingArt?) {
        if (extra == null) {
            levelRects = Atlas.STATIC_LEVEL_RECTS
            rects = Atlas.STATIC_RECTS
        } else {
            levelRects = Atlas.STATIC_LEVEL_RECTS + extra.levelRects()
            rects = Atlas.STATIC_RECTS + extra.rects()
        }
        Cover.forgetColumns()
    }
}

/**
 * The drawings of a game's pack houses and industry parts: for each place in [sprites] (its [Roster] sprite number),
 * its packed rects at level 0 as [com.rm.interchange.ui.InstalledPacks.rects] has them, or null if it's missing, and
 * [sheet] for its packed sheet at a level. A sprite's cells are BASE + its place in [sprites] * 4 + the view edge, XM, XP, YM or YP.
 * A missing drawing is a plain box.
 */
class BuildingArt(
    val sprites: List<Int>,
    private val keys: List<String>,
    private val packed: List<IntArray?>,
    val sheet: (index: Int, level: Int) -> ByteArray?,
) {
    /** Whether [other] draws the same, so the sheets needn't be made again. */
    fun same(other: BuildingArt?) = other != null && sprites == other.sprites && keys == other.keys &&
        packed.indices.all { packed[it].contentEquals(other.packed[it]) }

    private val shown = minOf(sprites.size, StaticCells.ROOM / 4)

    /** Cell [i]'s box at level 0: x and y on its sheet, width, height, and where its top left sits from its anchor. */
    private fun box(i: Int): IntArray {
        val r = packed[i / 4]
        return if (r == null) FALLBACK else r.copyOfRange(i % 4 * 6, i % 4 * 6 + 6)
    }

    internal fun rects(): IntArray = IntArray(shown * 4 * 6).also { out ->
        for (i in 0 until shown * 4) {
            val b = box(i)
            out[i * 6 + 2] = b[2]
            out[i * 6 + 3] = b[3]
            out[i * 6 + 4] = b[4]
            out[i * 6 + 5] = b[5]
        }
    }

    private val places = arrayOfNulls<IntArray>(Atlas.LEVELS)

    /** Where each cell goes on level [level]'s static sheet, x and y: in rows below what the base sheet fills, then the row's end. */
    private fun place(level: Int): IntArray = places[level] ?: run {
        var width = 0
        var bottom = 0
        for (c in 0 until StaticCells.BASE) {
            val o = (c * Atlas.LEVELS + level) * 6
            if (Atlas.STATIC_LEVEL_RECTS[o + 2] <= 0) continue
            width = maxOf(width, Atlas.STATIC_LEVEL_RECTS[o] + Atlas.STATIC_LEVEL_RECTS[o + 2])
            bottom = maxOf(bottom, Atlas.STATIC_LEVEL_RECTS[o + 1] + Atlas.STATIC_LEVEL_RECTS[o + 3])
        }
        val out = IntArray(shown * 4 * 2 + 1)
        var x = 0
        var y = bottom
        var row = 0
        for (i in 0 until shown * 4) {
            val w = box(i)[2] shr level
            val h = box(i)[3] shr level
            if (x + w + 1 > width) { x = 0; y += row + 1; row = 0 }
            out[i * 2] = x
            out[i * 2 + 1] = y
            x += w + 1
            row = maxOf(row, h)
        }
        out[shown * 4 * 2] = y + row + 1
        out.also { places[level] = it }
    }

    internal fun levelRects(): IntArray = IntArray(shown * 4 * Atlas.LEVELS * 6).also { out ->
        for (i in 0 until shown * 4) for (l in 0 until Atlas.LEVELS) {
            val o = (i * Atlas.LEVELS + l) * 6
            val b = box(i)
            val p = place(l)
            out[o] = p[i * 2]
            out[o + 1] = p[i * 2 + 1]
            out[o + 2] = b[2] shr l
            out[o + 3] = b[3] shr l
        }
    }

    /** The rows level [level]'s static sheet needs, if its own are [rows]. */
    fun rowsAt(level: Int, rows: Int) = if (shown == 0) rows else maxOf(rows, place(level)[shown * 4 * 2])

    /**
     * Draws the cells into [pixels], [width] across, at [level]. [read] gives a packed sheet's pixels and width, null
     * if it can't be read. Snow marks (the low bits of blue) are cleared: nothing here faces the sky as far as the game knows.
     */
    suspend fun paint(level: Int, pixels: IntArray, width: Int, read: suspend (ByteArray) -> Pair<IntArray, Int>?) {
        val p = place(level)
        for (s in 0 until shown) {
            val png = if (packed[s] == null) null else sheet(s, level)
            val image = png?.let { read(it) }
            for (f in 0 until 4) {
                val i = s * 4 + f
                val b = box(i)
                val w = b[2] shr level
                val h = b[3] shr level
                if (w <= 0 || h <= 0) continue
                for (y in 0 until h) for (x in 0 until w) {
                    val v = if (image == null) boxPixel(x, y, w, h) else {
                        val sx = (b[0] shr level) + x
                        val sy = (b[1] shr level) + y
                        if (sx >= image.second || sy * image.second + sx >= image.first.size) 0 else image.first[sy * image.second + sx] and 3.inv()
                    }
                    pixels[(p[i * 2 + 1] + y) * width + p[i * 2] + x] = v
                }
            }
        }
    }

    private companion object {
        /** A box 192 by 128 standing on its tile's middle, for a drawing that's missing. */
        val FALLBACK = intArrayOf(0, 0, 192, 128, -96, 0)

        fun boxPixel(x: Int, y: Int, w: Int, h: Int): Int {
            val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1
            val shade = if (edge) 0x40 else if (y < h / 3) 0xb0 else if (x < w / 2) 0x80 else 0x98
            return (0xff shl 24) or (shade shl 16) or (shade shl 8) or shade and 3.inv()
        }
    }
}
