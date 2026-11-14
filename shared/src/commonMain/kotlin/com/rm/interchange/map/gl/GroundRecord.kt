package com.rm.interchange.map.gl

import com.rm.interchange.map.Atlas
import kotlin.math.roundToInt

/**
 * A piece of ground as [com.rm.interchange.map.TileRecorder] lays it down for the GPU map: a tile's top, or the earth
 * face under the map's front edge, in [STRIDE] ints. The GPU takes them as they are, as the first pass's instances
 * ([GROUND_VS]), so the layout here and the shader's must agree:
 * - 0: view x, view y << 16
 * - 1: corner heights top, right << 8, bottom << 16, left << 24, in steps
 * - 2: kind, the kinds spreading in across the up, right, down and left sides from bit 4 (4 bits each, [NONE] for
 *   none), [type] << 20, weather << 22, smooth light << 23, foot shade << 24, canal banks << 25 (by side)
 * - 3: road bits as the tile recorder passes them (16 bits), how burnt in 255ths << 16
 * - 4: airport paving, as the tile recorder packs it
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
    val layers = Atlas.RAG + 1
    var size = 1
    while (size * size * layers < ground.size) size *= 2
    require(size * size * layers == ground.size) { "ground sheet of ${ground.size}" }
    return size
}

/**
 * Region [r]'s sprites as the GPU buffer holds them, in ints: the first pass as [GroundRecord]s ([GroundRecord.STRIDE]
 * ints each, a sprite there with its cell in 0, snow tile in 1 and anchor x and y in 4 and 5), then the other passes'
 * sprites in [SPRITE_INTS] ints each: anchor x, anchor y, cell, snow tile, as [SpriteRecorder] has them. The shaders
 * place each sprite at the level drawn from its cell's [cellRects], so the buffer is the same at every level.
 * Written into [into] if it's big enough, so it can be used again; [packedInts] says how much of it is used.
 */
internal fun packRegion(r: RegionSprites, into: IntArray? = null): IntArray {
    val p0 = r.firstPass
    val size = packedInts(r)
    val out = if (into != null && into.size >= size) into else IntArray(size)
    val d = r.data
    var k = 0
    for (i in 0 until r.count) {
        val o = i * SpriteRecorder.STRIDE
        if (d[o] == SpriteRecorder.GROUND) {
            r.ground.copyInto(out, k, d[o + 1] * GroundRecord.STRIDE, (d[o + 1] + 1) * GroundRecord.STRIDE)
            k += GroundRecord.STRIDE
            continue
        }
        if (i < p0) {
            if (out === into) out.fill(0, k, k + GroundRecord.STRIDE)
            out[k] = d[o + 8]
            out[k + 1] = d[o + 11]
            out[k + 2] = GroundRecord.SPRITE shl 20
            out[k + 4] = d[o + 9]
            out[k + 5] = d[o + 10]
            k += GroundRecord.STRIDE
        } else {
            out[k] = d[o + 9]
            out[k + 1] = d[o + 10]
            out[k + 2] = d[o + 8]
            out[k + 3] = d[o + 11]
            k += SPRITE_INTS
        }
    }
    return out
}

/** How many ints [packRegion] writes for [r]. */
internal fun packedInts(r: RegionSprites) = r.firstPass * GroundRecord.STRIDE + (r.count - r.firstPass) * SPRITE_INTS

/** Ints a sprite of the second and third passes takes in a region's buffer. */
internal const val SPRITE_INTS = 4

/** Ints a cell takes in [cellRects], two RGBA texels of the shaders' cell table. */
internal const val CELL_INTS = 8

/** Cells a row of a cell table holds, 1024 texels across, as GLES 3.0 only promises textures 2048 wide. */
internal const val CELLS_ACROSS = 512

/** Rows of a cell table each level of [sheet] takes. */
internal fun cellRows(sheet: Int) = (cellCount(sheet) + CELLS_ACROSS - 1) / CELLS_ACROSS

/** How many cells [sheet] ([SpriteRecorder.STATIC] or [SpriteRecorder.SHADOW]) has. */
internal fun cellCount(sheet: Int) = (if (sheet == SpriteRecorder.SHADOW) Atlas.SHADOW_RECTS.size else Atlas.STATIC_RECTS.size) / 6

/**
 * Every cell of [sheet] at [level] as the sprite shaders read it, [CELL_INTS] ints each: sheet x, row in its page and
 * layer by [pages], width, height, then where its top left lies from the anchor rounded down to this level, x and y,
 * and a 0. These are the sums [TileRecorder]'s blitOn and shadeOn work out at that level.
 */
internal fun cellRects(sheet: Int, level: Int, pages: AtlasPages): IntArray {
    val n = cellCount(sheet)
    val out = IntArray(n * CELL_INTS)
    val shrink = 1 shl level
    for (cell in 0 until n) {
        val r = cell * 6
        val o = cell * CELL_INTS
        val sy: Int
        if (sheet == SpriteRecorder.SHADOW) {
            val q = Atlas.SHADOW_RECTS
            out[o] = q[r] / shrink
            sy = q[r + 1] / shrink
            out[o + 3] = q[r + 2] / shrink
            out[o + 4] = q[r + 3] / shrink
            out[o + 5] = q[r + 4] / shrink
            out[o + 6] = q[r + 5] / shrink
        } else {
            val lr = (cell * Atlas.LEVELS + level) * 6
            val q = Atlas.STATIC_LEVEL_RECTS
            out[o] = q[lr]
            sy = q[lr + 1]
            out[o + 3] = q[lr + 2]
            out[o + 4] = q[lr + 3]
            out[o + 5] = Atlas.STATIC_RECTS[r + 4] / shrink + q[lr + 4]
            out[o + 6] = Atlas.STATIC_RECTS[r + 5] / shrink + q[lr + 5]
        }
        out[o + 1] = pages.rowIn(sy)
        out[o + 2] = pages.layerOf(sy)
    }
    return out
}

/** Where a sprite of cell [o] in [rects] anchored at ([ax], [ay]) in level 0 pixels has its top left at [level], as the shaders work it out. */
internal fun placeAt(rects: IntArray, o: Int, ax: Int, ay: Int, level: Int): Pair<Int, Int> =
    ((ax shr level) + rects[o + 5]) to ((ay shr level) + rects[o + 6])
