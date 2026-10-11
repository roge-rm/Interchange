package com.rm.interchange.sim

/** An image as ARGB ints, row by row, with colour kept apart from alpha. */
class Picture(val width: Int, val height: Int, val argb: IntArray = IntArray(width * height))

/** Reads and writes PNG, which the platform does: Android's Bitmap, or ImageIO on a computer. */
interface Pictures {
    fun decode(png: ByteArray): Picture
    fun encode(picture: Picture): ByteArray
}

/**
 * A vehicle's sprites ready to draw: a sheet for each zoom level, sharpest
 * first and each half the last, and six numbers a sprite at the sharpest
 * level: x, y, width and height on the sheet, then where its top left sits
 * from the anchor, the top corner of the tile the vehicle's middle stands on.
 */
class PackedSprites(val levels: List<Picture>, val rects: IntArray)

/**
 * Turns a pack's sprite sheet into [PackedSprites]: four by four cells of
 * [Packs.CELL] pixels, cut apart, trimmed to what's drawn, packed onto one
 * sheet and halved for each level. Cells are the eight headings, then the four
 * straight headings climbing and going down a slope, at 8 + heading / 2 * 2 + 0 or 1.
 */
object PackSprites {
    const val LEVELS = 6
    /** Trimmed edges sit on this grid, so every level halves exactly. */
    private const val ALIGN = 1 shl (LEVELS - 1)
    const val SIZE = Packs.CELL * Packs.GRID
    /** Fainter than this doesn't count when trimming, so a soft shadow's last wisps don't stretch the box. */
    private const val FAINT = 12
    private const val SHEET_WIDTH = 1024
    private const val ANCHOR_X = Packs.STAND_X
    private const val ANCHOR_Y = Packs.STAND_Y - Packs.CELL / 4

    /** What's wrong with a sprite sheet, if anything. */
    fun problems(p: Picture): List<String> {
        if (p.width != SIZE || p.height != SIZE) return listOf("should be $SIZE by $SIZE pixels, not ${p.width} by ${p.height}")
        val out = ArrayList<String>()
        for (c in 0 until 16) {
            val box = box(p, c)
            if (box == null) { out += "cell ${c + 1} is empty"; continue }
            val (l, t, r, b) = box
            if (l == 0 || t == 0 || r == Packs.CELL || b == Packs.CELL) out += "cell ${c + 1} is cut off at its edge"
        }
        return out
    }

    fun pack(p: Picture): PackedSprites {
        require(p.width == SIZE && p.height == SIZE) { "a sprite sheet should be $SIZE by $SIZE" }
        // Each cell's box, out to the grid around the anchor.
        val boxes = (0 until 16).map { c ->
            val (l, t, r, b) = box(p, c) ?: intArrayOf(ANCHOR_X, ANCHOR_Y, ANCHOR_X, ANCHOR_Y)
            intArrayOf(down(l - ANCHOR_X) + ANCHOR_X, down(t - ANCHOR_Y) + ANCHOR_Y, up(r - ANCHOR_X) + ANCHOR_X, up(b - ANCHOR_Y) + ANCHOR_Y)
        }
        // Shelves, tallest first.
        val order = (0 until 16).sortedByDescending { boxes[it][3] - boxes[it][1] }
        val pos = Array(16) { IntArray(2) }
        var x = 0
        var y = 0
        var shelf = 0
        for (c in order) {
            val w = boxes[c][2] - boxes[c][0]
            val h = boxes[c][3] - boxes[c][1]
            if (x + w > SHEET_WIDTH) { x = 0; y += shelf; shelf = 0 }
            pos[c][0] = x
            pos[c][1] = y
            x += w
            shelf = maxOf(shelf, h)
        }
        val sheet = Picture(SHEET_WIDTH, maxOf(ALIGN, up(y + shelf)))
        val rects = IntArray(16 * 6)
        for (c in 0 until 16) {
            val (l, t, r, b) = boxes[c]
            val cx = c % Packs.GRID * Packs.CELL
            val cy = c / Packs.GRID * Packs.CELL
            for (yy in t until b) for (xx in l until r) {
                // The box can reach past the cell's edge to meet the grid; past it is clear.
                if (xx < 0 || yy < 0 || xx >= Packs.CELL || yy >= Packs.CELL) continue
                val v = p.argb[(cy + yy) * SIZE + cx + xx]
                sheet.argb[(pos[c][1] + yy - t) * SHEET_WIDTH + pos[c][0] + xx - l] = v
            }
            rects[c * 6] = pos[c][0]; rects[c * 6 + 1] = pos[c][1]; rects[c * 6 + 2] = r - l; rects[c * 6 + 3] = b - t
            rects[c * 6 + 4] = l - ANCHOR_X; rects[c * 6 + 5] = t - ANCHOR_Y
        }
        val levels = ArrayList<Picture>()
        levels += sheet
        repeat(LEVELS - 1) { levels += half(levels.last()) }
        return PackedSprites(levels, rects)
    }

    /** Where cell [c] has more than faint pixels, as left, top, right and bottom in the cell, or null if none. */
    private fun box(p: Picture, c: Int): IntArray? {
        val cx = c % Packs.GRID * Packs.CELL
        val cy = c / Packs.GRID * Packs.CELL
        var l = Packs.CELL
        var t = Packs.CELL
        var r = 0
        var b = 0
        for (y in 0 until Packs.CELL) for (x in 0 until Packs.CELL) {
            if ((p.argb[(cy + y) * SIZE + cx + x] ushr 24) > FAINT) {
                if (x < l) l = x
                if (x >= r) r = x + 1
                if (y < t) t = y
                if (y >= b) b = y + 1
            }
        }
        return if (r == 0) null else intArrayOf(l, t, r, b)
    }

    /** Half the size, each pixel the average of four, colour weighted by alpha so edges don't pick up the clear black round them. */
    fun half(p: Picture): Picture {
        val out = Picture(p.width / 2, p.height / 2)
        for (y in 0 until out.height) for (x in 0 until out.width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (k in 0 until 4) {
                val v = p.argb[(y * 2 + k / 2) * p.width + x * 2 + k % 2]
                val va = v ushr 24
                a += va
                r += (v shr 16 and 255) * va
                g += (v shr 8 and 255) * va
                b += (v and 255) * va
            }
            out.argb[y * out.width + x] = if (a == 0) 0 else ((a + 2) / 4 shl 24) or (r / a shl 16) or (g / a shl 8) or (b / a)
        }
        return out
    }

    private fun down(v: Int) = v.floorDiv(ALIGN) * ALIGN
    private fun up(v: Int) = -(-v).floorDiv(ALIGN) * ALIGN
}
