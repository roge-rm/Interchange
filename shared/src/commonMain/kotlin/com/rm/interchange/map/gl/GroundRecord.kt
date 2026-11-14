package com.rm.interchange.map.gl

import kotlin.math.roundToInt

/**
 * A piece of ground as [com.rm.interchange.map.ChunkBaker] lays it down for the GPU map: a tile's top, or the earth
 * face under the map's front edge, in [STRIDE] ints. The GPU takes them as they are, as the first pass's instances
 * ([GROUND_VS]), so the layout here and the shader's must agree:
 * - 0: view x, view y << 16
 * - 1: corner heights top, right << 8, bottom << 16, left << 24, in steps
 * - 2: kind, the kinds spreading in across the up, right, down and left sides from bit 4 (4 bits each, [NONE] for
 *   none), [type] << 20, weather << 22, smooth light << 23, foot shade << 24, canal banks << 25 (by side)
 * - 3: road bits as the baker passes them (16 bits), how burnt in 255ths << 16
 * - 4: airport paving, as the baker packs it
 * - 5: the light of each half's plane, first | second << 16, in 256ths
 * - 6 to 9: ridges and hollows at each corner (top, right, bottom, left), as float bits
 * - 10 to 13: the light at each corner, as float bits
 * A sprite in the same pass has [SPRITE] as its type.
 */
internal object GroundRecord {
    const val STRIDE = 14
    const val NONE = 15

    const val TILE = 0
    const val FACE_RIGHT = 1
    const val FACE_LEFT = 2
    const val SPRITE = 3

    fun ground(
        vx: Int, vy: Int, t: Int, r: Int, b: Int, l: Int, kind: Int, spreads: IntArray, weather: Boolean, smooth: Boolean, foot: Boolean,
        banks: Int, road: Int, charred: Float, aero: Int, f1: Int, f2: Int, reliefs: FloatArray, lights: FloatArray,
    ): IntArray {
        val out = IntArray(STRIDE)
        out[0] = vx or (vy shl 16)
        out[1] = heights(t, r, b, l)
        var w = kind and 15
        for (e in 0 until 4) w = w or ((if (spreads[e] < 0) NONE else spreads[e] and 15) shl (4 + 4 * e))
        w = w or (TILE shl 20)
        if (weather) w = w or (1 shl 22)
        if (smooth) w = w or (1 shl 23)
        if (foot) w = w or (1 shl 24)
        w = w or ((banks and 15) shl 25)
        out[2] = w
        out[3] = (road and 0xffff) or ((charred * 255f).roundToInt().coerceIn(0, 255) shl 16)
        out[4] = aero
        out[5] = (f1 and 0xffff) or (f2 shl 16)
        for (k in 0 until 4) {
            out[6 + k] = reliefs[k].toRawBits()
            out[10 + k] = lights[k].toRawBits()
        }
        return out
    }

    /** The earth face down the right ([FACE_RIGHT]) or left front side of tile ([vx], [vy]), whose corners are [r], [b], [l] high. */
    fun face(vx: Int, vy: Int, type: Int, r: Int, b: Int, l: Int): IntArray {
        val out = IntArray(STRIDE)
        out[0] = vx or (vy shl 16)
        out[1] = heights(0, r, b, l)
        out[2] = type shl 20
        return out
    }

    private fun heights(t: Int, r: Int, b: Int, l: Int) = (t and 255) or ((r and 255) shl 8) or ((b and 255) shl 16) or ((l and 255) shl 24)

    fun vx(g: IntArray, o: Int = 0) = g[o] and 0xffff
    fun vy(g: IntArray, o: Int = 0) = g[o] ushr 16
    /** Corner [c]'s height: 0 top, 1 right, 2 bottom, 3 left. */
    fun height(g: IntArray, c: Int, o: Int = 0) = (g[o + 1] ushr (8 * c)) and 255
    fun kind(g: IntArray, o: Int = 0) = g[o + 2] and 15
    /** The kind spreading in across side [e], or -1. */
    fun spread(g: IntArray, e: Int, o: Int = 0) = ((g[o + 2] ushr (4 + 4 * e)) and 15).let { if (it == NONE) -1 else it }
    fun type(g: IntArray, o: Int = 0) = (g[o + 2] ushr 20) and 3
    fun weather(g: IntArray, o: Int = 0) = (g[o + 2] ushr 22) and 1 != 0
    fun smooth(g: IntArray, o: Int = 0) = (g[o + 2] ushr 23) and 1 != 0
    fun foot(g: IntArray, o: Int = 0) = (g[o + 2] ushr 24) and 1 != 0
    fun banks(g: IntArray, o: Int = 0) = (g[o + 2] ushr 25) and 15
    fun road(g: IntArray, o: Int = 0) = g[o + 3] and 0xffff
    fun charred(g: IntArray, o: Int = 0) = ((g[o + 3] ushr 16) and 255) / 255f
    fun aero(g: IntArray, o: Int = 0) = g[o + 4]
    fun f1(g: IntArray, o: Int = 0) = g[o + 5] and 0xffff
    fun f2(g: IntArray, o: Int = 0) = g[o + 5] ushr 16
    fun relief(g: IntArray, c: Int, o: Int = 0) = Float.fromBits(g[o + 6 + c])
    fun light(g: IntArray, c: Int, o: Int = 0) = Float.fromBits(g[o + 10 + c])
}

/** The side of each square texture on a ground sheet: the ground kinds and the noise, [Atlas.RAG] + 1 of them. */
internal fun groundSize(ground: IntArray): Int {
    val layers = com.rm.interchange.map.Atlas.RAG + 1
    var size = 1
    while (size * size * layers < ground.size) size *= 2
    require(size * size * layers == ground.size) { "ground sheet of ${ground.size}" }
    return size
}

/**
 * Region [r]'s sprites as the GPU buffer holds them, in ints: the first pass as [GroundRecord]s ([GroundRecord.STRIDE]
 * ints each, a sprite there with its rect in 6 to 9 and its sheet x, row, layer and snow in 10 to 13, all as float
 * bits), then the other passes' sprites in six ints each, the rect as float bits and the sheet x, row, layer and
 * snow as shorts, two to an int in the order [littleEndian] says. Sheet rows and layers are from [static] and
 * [shadows]' pages.
 */
internal fun packRegion(r: RegionSprites, static: AtlasPages, shadows: AtlasPages, littleEndian: Boolean): IntArray {
    val p0 = r.firstPass
    val out = IntArray(p0 * GroundRecord.STRIDE + (r.count - p0) * SPRITE_INTS)
    val d = r.data
    fun shorts(a: Int, b: Int) = if (littleEndian) (a and 0xffff) or (b shl 16) else (b and 0xffff) or (a shl 16)
    var k = 0
    for (i in 0 until r.count) {
        val o = i * SpriteRecorder.STRIDE
        if (d[o] == SpriteRecorder.GROUND) {
            r.ground.copyInto(out, k, d[o + 1] * GroundRecord.STRIDE, (d[o + 1] + 1) * GroundRecord.STRIDE)
            k += GroundRecord.STRIDE
            continue
        }
        val pages = if (d[o] == SpriteRecorder.SHADOW) shadows else static
        val sheetY = d[o + 6]
        if (i < p0) {
            out[k + 2] = GroundRecord.SPRITE shl 20
            for (f in 0 until 4) out[k + 6 + f] = d[o + 1 + f].toFloat().toRawBits()
            out[k + 10] = d[o + 5].toFloat().toRawBits()
            out[k + 11] = pages.rowIn(sheetY).toFloat().toRawBits()
            out[k + 12] = pages.layerOf(sheetY).toFloat().toRawBits()
            out[k + 13] = d[o + 7].toFloat().toRawBits()
            k += GroundRecord.STRIDE
        } else {
            for (f in 0 until 4) out[k + f] = d[o + 1 + f].toFloat().toRawBits()
            out[k + 4] = shorts(d[o + 5], pages.rowIn(sheetY))
            out[k + 5] = shorts(pages.layerOf(sheetY), d[o + 7])
            k += SPRITE_INTS
        }
    }
    return out
}

/** Ints a sprite of the second and third passes takes in a region's buffer. */
internal const val SPRITE_INTS = 6
