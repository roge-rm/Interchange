package com.rm.interchange.map.gl

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import com.rm.interchange.map.Atlas
import com.rm.interchange.map.Pen
import com.rm.interchange.map.Zoom
import kotlin.concurrent.Volatile
import kotlin.math.floor
import kotlin.math.round

/*
 * What moves, for the GPU map: everything drawMoving draws, as a list of instances made on the main thread each frame
 * in its drawing order and drawn over the static map. Vehicles and lit track are cut away where a building in front
 * stands, through the depth buffer: the
 * buildings' columns go in first at their tile's view depth, and a cut item only shows where no building nearer than
 * its own tile stands.
 */

/**
 * Kinds of live instance: a sprite from the live pages, a line, a filled convex shape, a building's column for
 * cutting, and a piece of a sprite cut on the CPU, untinted, its whole sprite's page rect in place of the tint so it
 * samples no further in than the whole sprite does.
 */
internal const val LIVE_SPRITE = 0
internal const val LIVE_LINE = 1
internal const val LIVE_SHAPE = 2
internal const val LIVE_COVER = 3
internal const val LIVE_PIECE = 4

/**
 * Floats an instance takes: kind, page, depth, shadow; then four of where (a sprite's screen rect, a line's ends, a
 * shape's first two corners); four more (a sprite's page rect in texels, a line's half width and 1 for a round cap,
 * a shape's last two corners); then the colour, premultiplied, or a sprite's tint.
 */
internal const val LIVE_FLOATS = 16

/** Sprite sources: the signals sheet, else a vehicle drawing's model. */
internal const val SIGNALS = -1

/** How a sprite is drawn: as it is, as an aircraft's shadow or burnt. */
internal const val LIVE_PLAIN = 0
internal const val LIVE_SHADOW = 1
internal const val LIVE_WRECK = 2

/** The side of a page of live sprites at [level], in texels: smaller at the far levels, whose sprites are small. */
internal fun livePageSide(level: Int) = when {
    level <= 1 -> 2048
    level <= 3 -> 1024
    else -> 512
}

/**
 * The depth buffer value for view depth [d] (x + y) on a map whose view depths run to [span] less 32: nearer is
 * greater. Items that aren't cut go at 1. The 16 bit buffer tells a half tile apart while span is under 32768, maps
 * up to some 16000 tiles a side.
 */
internal fun liveDepth(d: Float, span: Float) = ((d + 16f) / span).coerceIn(0f, 0.999f)

/** The depth a cut item at view point ([vx], [vy]) tests against: its tile's, half a step on, so buildings on nearer tiles hide it. */
internal fun cutDepth(vx: Float, vy: Float, span: Float) = liveDepth(floor(vx) + floor(vy) + 0.5f, span)

private val NOWHERE: (Float, Float) -> Boolean = { _, _ -> false }

/** The depth span for a view [viewWidth] by [viewHeight] tiles. */
internal fun depthSpan(viewWidth: Int, viewHeight: Int) = (viewWidth + viewHeight + 32).toFloat()

/**
 * Places sprites on a level's pages a row at a time, each row as tall as its tallest, a new page when one's full.
 * Sprites have a clear texel round them.
 */
internal class Shelf(val side: Int) {
    private var page = 0
    private var x = 0
    private var y = 0
    private var tall = 0

    /** Pages used so far. */
    val pages get() = if (x == 0 && y == 0) page else page + 1

    /** Puts a sprite [w] by [h] at [at] in [out] as page, x and y; false if it's bigger than a page. */
    fun place(w: Int, h: Int, out: IntArray, at: Int): Boolean {
        val pw = w + GUTTER
        val ph = h + GUTTER
        if (pw > side || ph > side) return false
        if (x + pw > side) {
            y += tall
            x = 0
            tall = 0
        }
        if (y + ph > side) {
            page++
            x = 0
            y = 0
            tall = 0
        }
        out[at] = page
        out[at + 1] = x
        out[at + 2] = y
        x += pw
        tall = maxOf(tall, ph)
        return true
    }

    private companion object {
        const val GUTTER = 1
    }
}

/**
 * A sheet's sprites to copy onto the GPU: [cells] holds for each its place on the sheet (x, y), its size, then its
 * page and place there, [LiveAtlas.CELL] ints a sprite, page -1 for none.
 */
internal class LiveUpload(val generation: Int, val source: Int, val level: Int, val image: ImageBitmap, val cells: IntArray)

/** Where the live sprites have the images they're cut from, and their rects there (x, y, w, h, dx, dy a sprite) in that level's pixels. */
internal interface LiveSource {
    /** The image, null till it's unpacked; it's only unpacked when [ask] is set. */
    fun image(source: Int, level: Int, ask: Boolean): ImageBitmap?
    fun rects(source: Int, level: Int): IntArray?
    /** Whether there's room under the memory budget to unpack another. */
    fun room(): Boolean
}

/**
 * Where each sheet's sprites go on the GPU's live pages, a set of pages a level, filled as sheets first show. Main
 * thread only, but for [uploads], which the GL thread reads and answers through [uploaded].
 */
internal class LiveAtlas {
    /** A sheet's sprites: [LiveAtlas.CELL] ints each, page, x and y on it, width, height and the offset from where it stands. */
    class Placed(val cells: IntArray) {
        var onGpu = false
    }

    /** Which GPU context the pages are for, so answers for the last lot are let go. Read by the GL thread. */
    @Volatile
    var generation = 0
        private set
    private var shelves = Array(Atlas.LEVELS) { Shelf(livePageSide(it)) }
    private val placed = HashMap<Int, Placed>()

    /** Sheets placed and waiting to go up, for the GL thread. A new list each time it changes. */
    @Volatile
    var uploads: List<LiveUpload> = emptyList()
        private set
    private val waiting = HashMap<LiveUpload, Placed>()

    /** Set once every sheet on screen has been up at once; the cover over the map only waits for that the first time. */
    var ready = false

    fun get(source: Int, level: Int): Placed? = placed[key(source, level)]

    /** Places [source]'s sprites at [level], cut from [image] by [rects], and lines them up for the GPU. */
    fun place(source: Int, level: Int, image: ImageBitmap, rects: IntArray): Placed {
        val n = rects.size / 6
        val cells = IntArray(n * CELL) { if (it % CELL == 0) -1 else 0 }
        val up = IntArray(n * UP)
        // Tallest first, so the rows waste less.
        val order = (0 until n).sortedByDescending { rects[it * 6 + 3] }
        val shelf = shelves[level]
        for (c in order) {
            val w = rects[c * 6 + 2]
            val h = rects[c * 6 + 3]
            up[c * UP + 4] = -1
            if (w <= 0 || h <= 0 || !shelf.place(w, h, cells, c * CELL)) continue
            cells[c * CELL + 3] = w
            cells[c * CELL + 4] = h
            cells[c * CELL + 5] = rects[c * 6 + 4]
            cells[c * CELL + 6] = rects[c * 6 + 5]
            up[c * UP] = rects[c * 6]
            up[c * UP + 1] = rects[c * 6 + 1]
            up[c * UP + 2] = w
            up[c * UP + 3] = h
            up[c * UP + 4] = cells[c * CELL]
            up[c * UP + 5] = cells[c * CELL + 1]
            up[c * UP + 6] = cells[c * CELL + 2]
        }
        val p = Placed(cells)
        placed[key(source, level)] = p
        val u = LiveUpload(generation, source, level, image, up)
        waiting[u] = p
        uploads = uploads + u
        return p
    }

    /** From the GL thread, on the main thread: [done] are on the GPU. */
    fun uploaded(done: List<LiveUpload>) {
        var left = uploads
        for (u in done) {
            if (u.generation != generation) continue
            waiting.remove(u)?.onGpu = true
            left = left - u
        }
        uploads = left
    }

    /** The GPU has lost its pages and is now context [context]: everything is placed again as it shows. */
    fun reset(context: Int = generation + 1) {
        generation = context
        shelves = Array(Atlas.LEVELS) { Shelf(livePageSide(it)) }
        placed.clear()
        waiting.clear()
        uploads = emptyList()
        ready = false
    }

    /** Pages used at [level]. */
    fun pages(level: Int) = shelves[level].pages

    private fun key(source: Int, level: Int) = (source + 1) * Atlas.LEVELS + level

    companion object {
        const val CELL = 7
        /** Ints a sprite takes in a [LiveUpload]: sheet x, y, width, height, then page, x and y. */
        const val UP = 7
    }
}

/**
 * One frame's live instances for level [level], placed for the atlas's [generation]: [covers] building columns, then
 * [count] instances to draw, [LIVE_FLOATS] each, from the start of [data]. Numbered in [seq] as made, so the main
 * thread knows when the GL thread's done with one and can fill it again.
 */
internal class LiveBatch(data: FloatArray) {
    var data = data
        private set
    var level = 0
        private set
    var generation = 0
        private set
    var covers = 0
        private set
    var count = 0
        private set
    var seq = 0L
        private set

    fun fill(level: Int, generation: Int, coverData: FloatArray, covers: Int, items: FloatArray, count: Int, seq: Long) {
        val size = (covers + count) * LIVE_FLOATS
        if (data.size < size) data = FloatArray(size + size / 2)
        coverData.copyInto(data, 0, 0, covers * LIVE_FLOATS)
        items.copyInto(data, covers * LIVE_FLOATS, 0, count * LIVE_FLOATS)
        this.level = level
        this.generation = generation
        this.covers = covers
        this.count = count
        this.seq = seq
    }

    /** Whether this holds the same as [coverData] and [items] would. */
    fun holds(level: Int, generation: Int, coverData: FloatArray, covers: Int, items: FloatArray, count: Int): Boolean {
        if (level != this.level || generation != this.generation || covers != this.covers || count != this.count) return false
        val c = covers * LIVE_FLOATS
        for (i in 0 until c) if (data[i] != coverData[i]) return false
        for (i in 0 until count * LIVE_FLOATS) if (data[c + i] != items[i]) return false
        return true
    }
}

/**
 * Makes a frame's [LiveBatch] on the main thread, as drawMoving lays its items down, in screen pixels. Shapes go in
 * as they come, uncut unless [z] is set; sprites are placed from the pages, and their sheets asked for at every level
 * the first time each shows, so a zoom never waits.
 */
internal class LiveBuilder(private val atlas: LiveAtlas, private val source: LiveSource) : Pen {
    private var data = FloatArray(LIVE_FLOATS * 256)
    private var n = 0
    private var coverData = FloatArray(LIVE_FLOATS * 64)
    private var covers = 0
    private val coverTiles = HashSet<Int>()
    private var level = -1
    private var span = 8192f
    private var scale = 1f
    private var halfHeight = 1f
    /** For each source, the frame it was last made sure of, so each is looked at once a frame. */
    private var seen = IntArray(64)
    private var frame = 0

    /** The depth shapes go in at: 1 for uncut. */
    var z = 1f

    /** Whether a sheet showing this frame isn't on the GPU yet at some level. */
    var missing = false
        private set

    /** Whether this frame has begun since the last [reset]. */
    val begun get() = level >= 0

    fun reset() {
        level = -1
        n = 0
        covers = 0
        z = 1f
        inGarage = NOWHERE
        missing = false
        coverTiles.clear()
        hiddenCount = 0
        frame++
    }

    /** Starts a frame at [level], whose half tile width is [hw], drawn [scale] times its size, with the map's depth [span]. */
    fun begin(level: Int, hw: Float, scale: Float, span: Float = 8192f) {
        reset()
        this.level = level
        this.span = span
        this.scale = scale
        halfHeight = hw / 2
        need(SIGNALS)
    }

    /** Batches handed out before, to fill again once the GL thread's past them, but for the last. */
    private val spare = ArrayList<LiveBatch>()
    private var seq = 0L
    private var last: LiveBatch? = null

    /**
     * This frame's batch: [shown], the one the GL thread was last given, if it holds the same, else a new one or a
     * spare filled again. A spare is only filled once [taken], the last batch the GL thread copied out, is at least
     * as new, and never the one shown, which a new context reads again.
     */
    fun batch(shown: LiveBatch? = null, taken: Long = Long.MAX_VALUE): LiveBatch? {
        if (!begun) return null
        val gen = atlas.generation
        if (shown != null && shown.holds(level, gen, coverData, covers, data, n)) return shown
        last?.let { spare += it }
        last = null
        var out: LiveBatch? = null
        for (i in spare.indices) {
            val b = spare[i]
            if (b.seq <= taken && b !== shown) {
                out = b
                spare.removeAt(i)
                break
            }
        }
        // Bounded: the GL thread copies a batch a frame, so few wait.
        if (out == null && spare.size > SPARE) spare.removeAt(0)
        val b = out ?: LiveBatch(FloatArray((covers + n) * LIVE_FLOATS))
        b.fill(level, gen, coverData, covers, data, n, ++seq)
        last = b
        return b
    }

    /** Whether the building on view tile [tile] hasn't had its columns put in yet this frame; they go in once. */
    fun takeCover(tile: Int) = coverTiles.add(tile)

    /** A building's column, [l], [t], [r], [b] on screen, on a tile [depth] deep. */
    fun cover(l: Float, t: Float, r: Float, b: Float, depth: Int) {
        if ((covers + 1) * LIVE_FLOATS > coverData.size) coverData = coverData.copyOf(coverData.size * 2)
        val o = covers * LIVE_FLOATS
        val d = coverData
        d.fill(0f, o, o + LIVE_FLOATS)
        d[o] = LIVE_COVER.toFloat()
        d[o + 2] = liveDepth(depth.toFloat(), span)
        d[o + 4] = l; d[o + 5] = t; d[o + 6] = r; d[o + 7] = b
        covers++
    }

    /**
     * Sprite [cell] of [src] standing at screen point ([px], [py]), drawn as [mode] says, cut by the buildings in front
     * of view point ([cutX], [cutY]) unless cutX is below 0. Placed as if on a tile whose middle is where it stands.
     * Returns whether it went in, which it doesn't while its sheet isn't on the GPU.
     */
    fun sprite(src: Int, cell: Int, px: Float, py: Float, mode: Int = LIVE_PLAIN, cutX: Float = -1f, cutY: Float = -1f): Boolean {
        if (cell < 0) return false
        need(src)
        val p = atlas.get(src, level) ?: return false
        if (!p.onGpu || (cell + 1) * LiveAtlas.CELL > p.cells.size) return false
        val c = p.cells
        val o = cell * LiveAtlas.CELL
        val page = c[o]
        if (page < 0) return false
        val w = c[o + 3]
        val h = c[o + 4]
        val left = px + c[o + 5] * scale
        val top = py - halfHeight * scale + c[o + 6] * scale
        val l = left.toInt()
        val t = top.toInt()
        val x0 = c[o + 1].toFloat()
        val y0 = c[o + 2].toFloat()
        val wreck = mode == LIVE_WRECK
        val depth = if (cutX >= 0f) cut(cutX, cutY) else 1f
        val shadow = if (mode == LIVE_SHADOW) 1f else 0f
        val sl = l.toFloat()
        val st = t.toFloat()
        val sr = (left + w * scale).toInt().toFloat()
        val sb = (top + h * scale).toInt().toFloat()
        val cr = if (wreck) WRECK_R else 1f
        val cg = if (wreck) WRECK_G else 1f
        val cb = if (wreck) WRECK_B else 1f
        val ca = if (mode == LIVE_SHADOW) SHADOW_ALPHA else 1f
        // The page rect follows the screen rect across, for each piece left showing.
        val tx = w / (sr - sl).coerceAtLeast(1e-3f)
        val ty = h / (sb - st).coerceAtLeast(1e-3f)
        val plain = mode == LIVE_PLAIN
        showing(sl, st, sr, sb) { a, b, c, d ->
            val whole = a == sl && b == st && c == sr && d == sb
            if (whole || !plain) put(LIVE_SPRITE, page, depth, shadow, a, b, c, d, x0 + (a - sl) * tx, y0 + (b - st) * ty, x0 + (c - sl) * tx, y0 + (d - st) * ty, cr, cg, cb, ca)
            else put(LIVE_PIECE, page, depth, 0f, a, b, c, d, x0 + (a - sl) * tx, y0 + (b - st) * ty, x0 + (c - sl) * tx, y0 + (d - st) * ty, x0, y0, x0 + w, y0 + h)
        }
        return true
    }

    // Rects that hide what goes in till [clearHidden], as left, top, right, bottom on screen, and scratch for [showing].
    private var hidden = FloatArray(64)
    private var hiddenCount = 0
    private var xs = FloatArray(32)
    private var spans = FloatArray(32)
    private var runs = FloatArray(32)
    private var lastRuns = FloatArray(32)

    /**
     * Hides the rect [l], [t], [r], [b] on screen from the sprites and rects that go in till [clearHidden]. Snapped to
     * whole pixels, so the pieces left meet on pixel edges, where a shape's smoothing leaves no seam.
     */
    fun hide(l: Float, t: Float, r: Float, b: Float) {
        if ((hiddenCount + 1) * 4 > hidden.size) hidden = hidden.copyOf(hidden.size * 2)
        val o = hiddenCount * 4
        hidden[o] = round(l); hidden[o + 1] = round(t); hidden[o + 2] = round(r); hidden[o + 3] = round(b)
        if (hidden[o] >= hidden[o + 2] || hidden[o + 1] >= hidden[o + 3]) return
        hiddenCount++
    }

    fun clearHidden() {
        hiddenCount = 0
    }

    /**
     * The parts of [l], [t], [r], [b] on screen the [hide] rects leave showing, into [piece] as rects: in strips
     * across between the sides of the hiding rects that reach into it, each strip's gaps down between them, side by
     * side strips with the same gaps going in as one.
     */
    private inline fun showing(l: Float, t: Float, r: Float, b: Float, piece: (Float, Float, Float, Float) -> Unit) {
        var near = 0
        for (k in 0 until hiddenCount) if (overlaps(k, l, t, r, b)) near++
        if (near == 0) {
            piece(l, t, r, b)
            return
        }
        if (xs.size < near * 2 + 2) xs = FloatArray(near * 4 + 4)
        if (spans.size < near * 2) spans = FloatArray(near * 4)
        if (runs.size < near * 2 + 2) {
            runs = FloatArray(near * 4 + 4)
            lastRuns = FloatArray(near * 4 + 4)
        }
        var n = 0
        xs[n++] = l
        xs[n++] = r
        for (k in 0 until hiddenCount) {
            if (!overlaps(k, l, t, r, b)) continue
            val hl = hidden[k * 4]
            val hr = hidden[k * 4 + 2]
            if (hl > l && hl < r) xs[n++] = hl
            if (hr > l && hr < r) xs[n++] = hr
        }
        xs.sort(0, n)
        var lastFrom = l
        var lastTo = l
        var lastCount = -1
        for (i in 0 until n - 1) {
            val xa = xs[i]
            val xb = xs[i + 1]
            if (xb <= xa) continue
            val mid = (xa + xb) / 2
            var m = 0
            for (k in 0 until hiddenCount) {
                if (!overlaps(k, l, t, r, b) || hidden[k * 4] > mid || hidden[k * 4 + 2] < mid) continue
                spans[m * 2] = maxOf(t, hidden[k * 4 + 1])
                spans[m * 2 + 1] = minOf(b, hidden[k * 4 + 3])
                m++
            }
            // By top, then down past each one, into runs of what shows.
            for (p in 1 until m) {
                val a0 = spans[p * 2]
                val a1 = spans[p * 2 + 1]
                var q = p - 1
                while (q >= 0 && spans[q * 2] > a0) {
                    spans[(q + 1) * 2] = spans[q * 2]
                    spans[(q + 1) * 2 + 1] = spans[q * 2 + 1]
                    q--
                }
                spans[(q + 1) * 2] = a0
                spans[(q + 1) * 2 + 1] = a1
            }
            var count = 0
            var y = t
            for (p in 0 until m) {
                if (spans[p * 2] > y) {
                    runs[count * 2] = y
                    runs[count * 2 + 1] = spans[p * 2]
                    count++
                }
                y = maxOf(y, spans[p * 2 + 1])
            }
            if (y < b) {
                runs[count * 2] = y
                runs[count * 2 + 1] = b
                count++
            }
            if (count == lastCount && sameRuns(count)) {
                lastTo = xb
                continue
            }
            for (p in 0 until lastCount) piece(lastFrom, lastRuns[p * 2], lastTo, lastRuns[p * 2 + 1])
            runs.copyInto(lastRuns, 0, 0, count * 2)
            lastCount = count
            lastFrom = xa
            lastTo = xb
        }
        for (p in 0 until lastCount) piece(lastFrom, lastRuns[p * 2], lastTo, lastRuns[p * 2 + 1])
    }

    private fun sameRuns(count: Int): Boolean {
        for (p in 0 until count * 2) if (runs[p] != lastRuns[p]) return false
        return true
    }

    private fun overlaps(k: Int, l: Float, t: Float, r: Float, b: Float): Boolean {
        val o = k * 4
        return hidden[o] < r && hidden[o + 2] > l && hidden[o + 1] < b && hidden[o + 3] > t
    }

    /** Whether a view point is on a garage's tile, for [cut]; set each frame after [begin]. */
    var inGarage: (Float, Float) -> Boolean = NOWHERE

    /** The depth a cut item at view point ([vx], [vy]) goes at this frame: in a garage, half a tile behind its tile. */
    fun cut(vx: Float, vy: Float) =
        if (inGarage(vx, vy)) liveDepth(floor(vx) + floor(vy) - 0.5f, span) else cutDepth(vx, vy, span)

    /** Makes sure [src]'s sheets are placed at every level the map draws at, once a frame; notes any not on the GPU yet. */
    private fun need(src: Int) {
        val i = src + 1
        if (i >= seen.size) seen = seen.copyOf(maxOf(i + 1, seen.size * 2))
        if (seen[i] == frame) return
        seen[i] = frame
        for (l in Zoom.SHARPEST until Atlas.LEVELS) {
            val p = atlas.get(src, l)
            if (p != null) {
                if (!p.onGpu) missing = true
                continue
            }
            val rects = source.rects(src, l) ?: continue
            // Past the budget, only the level drawn now is unpacked; the rest wait till uploads let some go.
            val image = source.image(src, l, l == level || source.room())
            if (image == null) {
                missing = true
                continue
            }
            atlas.place(src, l, image, rects)
            missing = true
        }
    }

    override fun line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, cap: StrokeCap) =
        put(color, LIVE_LINE, x1, y1, x2, y2, width / 2, if (cap == StrokeCap.Round) 1f else 0f, 0f, 0f)

    override fun rect(color: Color, x: Float, y: Float, w: Float, h: Float) =
        showing(x, y, x + w, y + h) { l, t, r, b -> put(color, LIVE_SHAPE, l, t, r, t, r, b, l, b) }

    override fun rectEdge(color: Color, x: Float, y: Float, w: Float, h: Float, width: Float) {
        // Top and bottom run on over the corners, as a stroke's square joins do.
        val e = width / 2
        line(color, x - e, y, x + w + e, y, width)
        line(color, x - e, y + h, x + w + e, y + h, width)
        line(color, x, y, x, y + h, width)
        line(color, x + w, y, x + w, y + h, width)
    }

    override fun circle(color: Color, radius: Float, x: Float, y: Float) = put(color, LIVE_LINE, x, y, x, y, radius, 1f, 0f, 0f)

    override fun shape(color: Color, xy: FloatArray, n: Int, edge: Float) {
        if (edge > 0f) {
            for (k in 0 until n) {
                val j = (k + 1) % n
                line(color, xy[2 * k], xy[2 * k + 1], xy[2 * j], xy[2 * j + 1], edge, StrokeCap.Round)
            }
            return
        }
        val last = minOf(n, 4) - 1
        put(color, LIVE_SHAPE, xy[0], xy[1], xy[2], xy[3], xy[4], xy[5], xy[2 * last], xy[2 * last + 1])
    }

    private fun put(color: Color, kind: Int, a0: Float, a1: Float, a2: Float, a3: Float, b0: Float, b1: Float, b2: Float, b3: Float) {
        val a = color.alpha
        put(kind, 0, z, 0f, a0, a1, a2, a3, b0, b1, b2, b3, color.red * a, color.green * a, color.blue * a, a)
    }

    private fun put(kind: Int, page: Int, depth: Float, shadow: Float, a0: Float, a1: Float, a2: Float, a3: Float,
                    b0: Float, b1: Float, b2: Float, b3: Float, c0: Float, c1: Float, c2: Float, c3: Float) {
        if ((n + 1) * LIVE_FLOATS > data.size) data = data.copyOf(data.size * 2)
        val o = n * LIVE_FLOATS
        val d = data
        d[o] = kind.toFloat(); d[o + 1] = page.toFloat(); d[o + 2] = depth; d[o + 3] = shadow
        d[o + 4] = a0; d[o + 5] = a1; d[o + 6] = a2; d[o + 7] = a3
        d[o + 8] = b0; d[o + 9] = b1; d[o + 10] = b2; d[o + 11] = b3
        d[o + 12] = c0; d[o + 13] = c1; d[o + 14] = c2; d[o + 15] = c3
        n++
    }

    private companion object {
        /** A shadow is the sprite's shape in black at 0.3, a wreck its colours times 4A4038. */
        const val SHADOW_ALPHA = 0.3f
        const val WRECK_R = 0x4a / 255f
        const val WRECK_G = 0x40 / 255f
        const val WRECK_B = 0x38 / 255f
        /** Spare batches kept at most. */
        const val SPARE = 4
    }
}
