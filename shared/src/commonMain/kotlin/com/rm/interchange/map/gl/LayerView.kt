package com.rm.interchange.map.gl

import com.rm.interchange.map.ViewAt

/**
 * The view what's drawn over the GPU map in Compose draws at, so town names, overlays and the rest move with the ground
 * under them. Where each GL frame shows on the same display frame as the Compose frame that handed it over, timed or
 * held for it, while the GL thread keeps up that's the view of the frame last handed over. Otherwise, or when it falls
 * behind, as on a slow GPU, it's the view of the last frame it drew, so they stand with the ground while a frame is slow.
 * The Compose draw calls [handed] and [take], the GL thread [drew].
 */
internal class LayerView {
    private val lock = Any()
    private var drawn: ViewAt? = null
    private var drawnSeq = 0L
    private var used: ViewAt? = null
    // The last frame handed to the GL thread, and whether it had drawn the one before it by then.
    private var handedAt: ViewAt? = null
    private var keepingUp = false

    /**
     * From the Compose draw: frame [seq], at [at], has gone to the GL thread, [held] to show with this Compose frame.
     * It keeps up while it has drawn the frame [slack] before this one or later.
     */
    fun handed(seq: Long, at: ViewAt, held: Boolean, slack: Int = 1) {
        synchronized(lock) {
            handedAt = at
            keepingUp = held && drawnSeq >= seq - slack
        }
    }

    /** From the GL thread, for frame [seq] drawn at [at]: whether the layers were drawn at another view and should draw again. */
    fun drew(at: ViewAt, seq: Long = 0L): Boolean {
        synchronized(lock) {
            drawn = at
            drawnSeq = seq
            return at != used
        }
    }

    /** From the Compose draw: the view to draw the layers at, null (the view as it is) till the GL thread draws a frame. */
    fun take(): ViewAt? = synchronized(lock) {
        (if (keepingUp && drawn != null) handedAt else drawn).also { used = it }
    }
}
