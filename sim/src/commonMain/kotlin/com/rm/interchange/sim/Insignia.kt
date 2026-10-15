package com.rm.interchange.sim

/**
 * A company's insignia: [SIZE] by [SIZE] pixels, row by row, each one of [COLOURS] palette entries, two to a byte
 * (the first in the low four bits). It's drawn in the company's colour, so it follows the colour.
 */
object Insignia {
    const val SIZE = 16
    const val PIXELS = SIZE * SIZE
    const val BYTES = PIXELS / 2

    /** Palette entries: see-through, the company's colour, a lighter and a darker shade of it, then fixed ones. */
    const val CLEAR = 0
    const val COLOUR = 1
    const val LIGHT = 2
    const val DARK = 3
    const val WHITE = 4
    const val BLACK = 5
    const val GREY = 6
    const val GOLD = 7
    const val COLOURS = 8

    /** A letter for each palette entry, for drawing insignia as text. */
    const val LETTERS = ".cldwkgy"

    /** The insignia to pick from, and that new companies start with. */
    val presets: List<ByteArray> by lazy { INSIGNIA_DRAWINGS.map { parse(it) } }

    /** Pixel [i] of [packed]. */
    fun pixel(packed: ByteArray, i: Int): Int = (packed[i shr 1].toInt() shr ((i and 1) * 4)) and 15

    fun unpack(packed: ByteArray): IntArray = IntArray(PIXELS) { pixel(packed, it) }

    fun pack(pixels: IntArray): ByteArray {
        require(pixels.size == PIXELS)
        return ByteArray(BYTES) { ((pixels[it * 2] and 7) or ((pixels[it * 2 + 1] and 7) shl 4)).toByte() }
    }

    /** Whether [packed] is an insignia: the right size, every pixel in the palette. */
    fun valid(packed: ByteArray): Boolean = packed.size == BYTES && packed.all { it.toInt() and 0x88 == 0 }

    /** [rows] of [LETTERS] as an insignia. */
    fun parse(rows: List<String>): ByteArray {
        require(rows.size == SIZE && rows.all { it.length == SIZE }) { "an insignia is $SIZE rows of $SIZE" }
        return pack(IntArray(PIXELS) { i -> LETTERS.indexOf(rows[i / SIZE][i % SIZE]).also { require(it >= 0) } })
    }
}

/**
 * Gives [c] a preset insignia, the first no other company still going has, looking from one picked by the map's
 * seed and the company's id, so every player's copy picks the same.
 */
internal fun World.giveInsignia(c: Company) {
    val presets = Insignia.presets
    val start = (settings.seed + c.id * 7L).mod(presets.size.toLong()).toInt()
    for (k in presets.indices) {
        val p = presets[(start + k) % presets.size]
        if (companies.none { it.id != c.id && !it.gone && it.insignia.contentEquals(p) }) {
            c.insignia = p.copyOf()
            return
        }
    }
    c.insignia = presets[start].copyOf()
}

/** Sets [company]'s insignia to [pixels], if they are one. */
internal fun World.setInsignia(company: Company, pixels: ByteArray) {
    if (Insignia.valid(pixels)) company.insignia = pixels.copyOf()
}
