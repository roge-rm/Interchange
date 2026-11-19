package com.rm.interchange.map.gl

import com.rm.interchange.map.View
import com.rm.interchange.map.ViewAt
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/** A label image waiting to go up onto the label atlas: its place there and its pixels, ARGB unpremultiplied. */
internal class LabelUpload(val x: Int, val y: Int, val w: Int, val h: Int, val pixels: IntArray)

/**
 * Where label images (town names, station signs, payments) sit on one [side] square texture, keyed by what they show.
 * Images are packed in shelves and kept till the page is full; then it starts again with only what's asked for after.
 * Main thread, but for [take] and [lose], which the GL thread calls.
 */
internal class LabelAtlas(val side: Int = SIDE) {
    private val rects = HashMap<String, IntArray>()
    private var shelfX = 0
    private var shelfY = 0
    private var shelfH = 0
    private val lock = ReentrantLock()
    private var waiting = ArrayList<LabelUpload>()
    @Volatile
    private var lost = false

    /** Set when the page started again while placing; whatever was placed before then this frame is gone. */
    var overflowed = false
        private set

    /** While set, a full page turns new images away rather than starting again. */
    private var keep = false

    /** How many images are on the page. */
    val size get() = rects.size

    /** Whether there are images for the GL thread to take. */
    val hasUploads get() = lock.withLock { waiting.isNotEmpty() }

    /** Called at the start of each frame of labels. Clears the page if the GL thread lost it. */
    fun begin() {
        overflowed = false
        keep = false
        if (lost) {
            lost = false
            clear()
        }
    }

    /**
     * The atlas rect (x, y, w, h) of the image for [key], [w] by [h], drawn by [draw] into ARGB pixels if it isn't
     * there yet; null if it doesn't fit even on an empty page.
     */
    fun get(key: String, w: Int, h: Int, draw: () -> IntArray): IntArray? {
        rects[key]?.let { return it }
        if (w <= 0 || h <= 0 || w + GAP > side || h + GAP > side) return null
        if (shelfX + w + GAP > side) {
            shelfX = 0
            shelfY += shelfH
            shelfH = 0
        }
        if (shelfY + h + GAP > side) {
            if (keep) return null
            clear()
            overflowed = true
        }
        val r = intArrayOf(shelfX, shelfY, w, h)
        shelfX += w + GAP
        shelfH = maxOf(shelfH, h + GAP)
        rects[key] = r
        val pixels = draw()
        lock.withLock { waiting += LabelUpload(r[0], r[1], w, h, pixels) }
        return r
    }

    /** Places the frame's labels again after [overflowed], keeping what's placed from here on. */
    fun again() {
        overflowed = false
        keep = true
    }

    private fun clear() {
        rects.clear()
        shelfX = 0
        shelfY = 0
        shelfH = 0
        lock.withLock { waiting = ArrayList() }
    }

    /** From the GL thread: the images placed since it last asked, in order. */
    fun take(): List<LabelUpload> = lock.withLock {
        if (waiting.isEmpty()) return emptyList()
        val t = waiting
        waiting = ArrayList()
        t
    }

    /** From the GL thread: the page is gone with its context, so every image goes up again. */
    fun lose() {
        lost = true
    }

    companion object {
        /** The page's side in pixels: 16 MB, room for a few hundred names at a phone's density. */
        const val SIDE = 2048
        /** Clear pixels between images, so filtering never reaches a neighbour. */
        const val GAP = 2
    }
}

/**
 * The labels drawn over the map in one frame, [FLOATS] each in [data]: world anchor x and y, ground height there,
 * how far above it the label's bottom sits in half tile widths, its atlas rect (x, y, w, h), alpha and [kind].
 */
internal class LabelBatch(val data: FloatArray, val count: Int, val mapWidth: Int, val mapHeight: Int) {
    fun sameAs(o: LabelBatch?): Boolean {
        if (o == null || o.count != count || o.mapWidth != mapWidth || o.mapHeight != mapHeight) return false
        for (i in 0 until count * FLOATS) if (data[i] != o.data[i]) return false
        return true
    }

    private var view: View? = null

    /**
     * Puts the labels on a [w] by [h] screen at [at], into [out] as [QUAD] floats each: left, top, width and height in
     * whole screen pixels, atlas x, y, width and height, and alpha. Town names always go; a sign that would cover a
     * name or an earlier sign is left out; payments go over anything. Returns how many went, off screen ones left out.
     */
    fun place(at: ViewAt, w: Int, h: Int, out: FloatArray, taken: FloatArray = FloatArray(count * 4)): Int {
        val v = view ?: View(mapWidth, mapHeight).also { view = it }
        v.moveTo(at)
        var n = 0
        var t = 0
        for (i in 0 until count) {
            val o = i * FLOATS
            val x = data[o]
            val y = data[o + 1]
            val vx = v.toViewX(x, y)
            val vy = v.toViewY(x, y)
            val sx = v.screenX(vx, vy, w.toFloat())
            val sy = v.screenY(vx, vy, data[o + 2], h.toFloat()) - v.halfWidth * data[o + 3]
            val lw = data[o + 6]
            val lh = data[o + 7]
            val left = (sx - lw / 2).roundToInt().toFloat()
            val top = (sy - lh).roundToInt().toFloat()
            val kind = data[o + 9].toInt()
            if (kind == SIGN) {
                var hit = false
                for (k in 0 until t) {
                    val q = k * 4
                    if (left < taken[q + 2] && taken[q] < left + lw && top < taken[q + 3] && taken[q + 1] < top + lh) {
                        hit = true
                        break
                    }
                }
                if (hit) continue
            }
            if (kind != PAYMENT) {
                taken[t * 4] = left
                taken[t * 4 + 1] = top
                taken[t * 4 + 2] = left + lw
                taken[t * 4 + 3] = top + lh
                t++
            }
            if (left >= w || top >= h || left + lw <= 0f || top + lh <= 0f) continue
            val q = n * QUAD
            out[q] = left
            out[q + 1] = top
            out[q + 2] = lw
            out[q + 3] = lh
            out[q + 4] = data[o + 4]
            out[q + 5] = data[o + 5]
            out[q + 6] = lw
            out[q + 7] = lh
            out[q + 8] = data[o + 8]
            n++
        }
        return n
    }

    companion object {
        const val FLOATS = 10
        const val QUAD = 9
        const val TOWN = 0
        const val SIGN = 1
        const val PAYMENT = 2
    }
}

/**
 * Takes the labels for a frame, between [GlScene.show] and [GlScene.finish], which hands them to the GL thread with
 * the frame. Main thread.
 */
class MapLabels internal constructor(internal val atlas: LabelAtlas, private val mapWidth: Int, private val mapHeight: Int) {
    private var data = FloatArray(64 * LabelBatch.FLOATS)
    private var count = 0

    internal fun reset() {
        count = 0
        atlas.begin()
    }

    /** Whether the atlas started its page again while this frame's labels were placed, so they go again. */
    internal val overflowed get() = atlas.overflowed

    /** Starts the frame's labels again after [overflowed]; a full page then leaves out the labels that don't fit. */
    internal fun restart() {
        count = 0
        atlas.again()
    }

    /**
     * Adds a label anchored at world point ([x], [y]) at ground height [ground], its bottom [lift] half tile widths
     * above it, drawn from atlas rect [rect] at [alpha]. [kind] is [LabelBatch.TOWN], SIGN or PAYMENT.
     */
    internal fun add(x: Float, y: Float, ground: Float, lift: Float, rect: IntArray, alpha: Float, kind: Int) {
        if ((count + 1) * LabelBatch.FLOATS > data.size) data = data.copyOf(data.size * 2)
        val o = count * LabelBatch.FLOATS
        data[o] = x
        data[o + 1] = y
        data[o + 2] = ground
        data[o + 3] = lift
        data[o + 4] = rect[0].toFloat()
        data[o + 5] = rect[1].toFloat()
        data[o + 6] = rect[2].toFloat()
        data[o + 7] = rect[3].toFloat()
        data[o + 8] = alpha
        data[o + 9] = kind.toFloat()
        count++
    }

    /** The frame's labels, or [last] if they're the same, so a still map hands nothing new. */
    internal fun batch(last: LabelBatch?): LabelBatch? {
        if (count == 0 && last == null) return null
        val b = LabelBatch(data.copyOf(count * LabelBatch.FLOATS), count, mapWidth, mapHeight)
        return if (b.sameAs(last)) last else b
    }
}
