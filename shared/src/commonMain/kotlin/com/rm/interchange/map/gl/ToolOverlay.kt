package com.rm.interchange.map.gl

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.rm.interchange.map.View
import com.rm.interchange.map.ViewAt
import com.rm.interchange.map.sameImage
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where a tool overlay image was drawn: the view [at] on a [width] by [height] screen, the image reaching [marginX] and
 * [marginY] pixels past each side of it.
 */
internal class OverlayPlace(val at: ViewAt, val width: Int, val height: Int, val marginX: Int, val marginY: Int) {
    fun sameAs(o: OverlayPlace?) =
        o != null && o.at == at && o.width == width && o.height == height && o.marginX == marginX && o.marginY == marginY

    /**
     * Where the image goes on a [w] by [h] screen at [frame], into [out] as left, top, right and bottom in screen
     * pixels: moved and scaled from where it was drawn. False at another turn, where it can't be moved into place.
     */
    fun mapTo(frame: ViewAt, w: Int, h: Int, out: FloatArray): Boolean {
        if (frame.turn != at.turn) return false
        // View point 0, 0 on the ground, where it was drawn and where it is now; the rest scales round it.
        val drawnX = originX(at, width)
        val drawnY = originY(at, height)
        val k = frame.halfWidth / at.halfWidth
        var nowX = originX(frame, w)
        var nowY = originY(frame, h)
        if (k == 1f) {
            // Whole pixels, so each texel lands on one pixel.
            nowX = drawnX + (nowX - drawnX).roundToInt()
            nowY = drawnY + (nowY - drawnY).roundToInt()
        }
        out[0] = nowX + k * (-marginX - drawnX)
        out[1] = nowY + k * (-marginY - drawnY)
        out[2] = nowX + k * (width + marginX - drawnX)
        out[3] = nowY + k * (height + marginY - drawnY)
        return true
    }

    /**
     * Whether the image needs drawing again for a [w] by [h] screen at [frame], whose zoom has held for [zoomHeld]
     * milliseconds: at another turn or screen size, panned more than half its margin, as further on the screen's edge
     * could run past it, or at another zoom once that's held for [ZOOM_HELD_MILLIS] or the image would be scaled past
     * twice or half its size. Till then the GL thread scales it.
     */
    fun staleFor(frame: ViewAt, w: Int, h: Int, zoomHeld: Long = Long.MAX_VALUE): Boolean {
        if (frame.turn != at.turn || w != width || h != height) return true
        if (frame.halfWidth != at.halfWidth) {
            val k = frame.halfWidth / at.halfWidth
            return k > 2f || k < 0.5f || zoomHeld >= ZOOM_HELD_MILLIS
        }
        val dx = originX(frame, w) - originX(at, width)
        val dy = originY(frame, h) - originY(at, height)
        return abs(dx) > marginX / 2f || abs(dy) > marginY / 2f
    }

    companion object {
        /** How long a new zoom holds still before the image is drawn again at it. */
        const val ZOOM_HELD_MILLIS = 150L

        private val view = ThreadLocal.withInitial { View(1, 1) }

        private fun originX(at: ViewAt, w: Int): Float = view.get().let { it.moveTo(at); it.screenX(0f, 0f, w.toFloat()) }

        private fun originY(at: ViewAt, h: Int): Float = view.get().let { it.moveTo(at); it.screenY(0f, 0f, 0f, h.toFloat()) }
    }
}

/** Clears [image] and draws [block] into it. */
private fun paintInto(image: ImageBitmap, density: Density, direction: LayoutDirection, block: DrawScope.() -> Unit) {
    val size = Size(image.width.toFloat(), image.height.toFloat())
    CanvasDrawScope().draw(density, direction, Canvas(image), size) {
        drawRect(Color.Transparent, blendMode = BlendMode.Src)
        block()
    }
}

/** A tool overlay image handed to the GL thread, [id] going up with each, premultiplied as the platform keeps it. */
internal class OverlayShot(val id: Long, val place: OverlayPlace, val image: ImageBitmap)

/**
 * The tool overlay (build plans and the lit selection) drawn into an image at the view it's drawn at, for the GL
 * thread to draw moved and scaled to each frame's view. Drawn again only when the overlay or zoom or turn changes or a
 * pan nears its edge, and handed over only when the image differs. Main thread, but for [take], [taken] and [lose],
 * which the GL thread calls.
 */
internal class ToolOverlay(
    private val newImage: (Int, Int) -> ImageBitmap = { w, h -> ImageBitmap(w, h) },
    private val same: (ImageBitmap, ImageBitmap) -> Boolean = ::sameImage,
    private val paint: (ImageBitmap, Density, LayoutDirection, DrawScope.() -> Unit) -> Unit = ::paintInto,
) {
    private val lock = ReentrantLock()
    private var pending: OverlayShot? = null
    private var busy: ImageBitmap? = null
    private val pool = ArrayList<ImageBitmap>()
    private var nextId = 0L
    private var key: Any? = null
    private var zoomSeen = Float.NaN
    private var zoomSince = 0L

    /** The last image drawn, kept as it was while the GL thread may still take it. Set under [lock]. */
    @Volatile
    private var last: OverlayShot? = null

    /**
     * The shot for [draw] at [view] on a [w] by [h] screen at [now] milliseconds, drawn again only if [key] doesn't
     * equal the one drawn last or [OverlayPlace.staleFor] says so. Images the same as the last keep the last, so a
     * still map hands nothing new.
     */
    fun shot(
        density: Density, direction: LayoutDirection, key: Any, view: View, w: Int, h: Int, now: Long,
        draw: DrawScope.(originX: Float, originY: Float) -> Unit,
    ): OverlayShot? {
        val at = view.at()
        if (at.halfWidth != zoomSeen) {
            zoomSeen = at.halfWidth
            zoomSince = now
        }
        val was = last
        if (was != null && key == this.key && !was.place.staleFor(at, w, h, now - zoomSince)) return was
        this.key = key
        if (w <= 0 || h <= 0) return null
        val mx = marginFor(w)
        val my = marginFor(h)
        val bw = w + mx * 2
        val bh = h + my * 2
        val image = image(bw, bh)
        val ox = view.screenX(0f, 0f, bw.toFloat())
        val oy = view.screenY(0f, 0f, 0f, bh.toFloat())
        paint(image, density, direction) { draw(ox, oy) }
        val place = OverlayPlace(at, w, h, mx, my)
        if (was != null && place.sameAs(was.place) && same(image, was.image)) {
            lock.withLock { pool += image }
            return was
        }
        val s = OverlayShot(++nextId, place, image)
        lock.withLock {
            if (was != null) pool += was.image
            last = s
            pending = s
        }
        return s
    }

    /** A clear image [w] by [h] that's neither the last nor one the GL thread is reading. */
    private fun image(w: Int, h: Int): ImageBitmap = lock.withLock {
        pool.removeAll { it.width != w || it.height != h }
        val free = pool.firstOrNull { it !== last?.image && it !== busy && it !== pending?.image }
        if (free != null) {
            pool.remove(free)
            free
        } else null
    } ?: newImage(w, h)

    /** The last image drawn, for a held edit, which keeps it while the tool shows nothing. */
    fun held(): OverlayShot? = last

    /** Lets go of the images while no overlay shows. */
    fun idle() {
        key = null
        if (last == null) return
        lock.withLock {
            pool.clear()
            last = null
            pending = null
        }
    }

    /** From the GL thread: the newest image not yet taken, or null; it's read till [taken]. */
    fun take(): OverlayShot? = lock.withLock {
        val s = pending ?: return null
        pending = null
        busy = s.image
        s
    }

    /** From the GL thread: [s]'s image is on the GPU, so it can be drawn into again once it isn't the last. */
    fun taken(s: OverlayShot) {
        lock.withLock { if (busy === s.image) busy = null }
    }

    /** From the GL thread: the texture is gone with its context, so the last image goes up again. */
    fun lose() {
        lock.withLock { if (pending == null) pending = last }
    }

    companion object {
        /** The image reaches past each side of the screen by this share of it, so a pan moves it before drawing again. */
        const val MARGIN = 0.125f
        /** Its longest side, within any GPU's texture size. */
        const val MAX_SIDE = 4096

        fun marginFor(side: Int): Int = minOf((side * MARGIN).roundToInt(), ((MAX_SIDE - side) / 2).coerceAtLeast(0))
    }
}
