package com.rm.interchange.map.gl

import com.rm.interchange.map.ViewAt

/**
 * The view what's drawn over the GPU map in Compose draws at: the view of the last frame the GL thread drew, so town
 * names, overlays and the rest move with the ground under them, and stand with it while a pan is held or a frame is
 * slow. The GL thread calls [drew], the Compose draw [take].
 */
internal class LayerView {
    private val lock = Any()
    private var drawn: ViewAt? = null
    private var used: ViewAt? = null

    /** From the GL thread, for a frame drawn at [at]: whether the layers were drawn at another view and should draw again. */
    fun drew(at: ViewAt): Boolean {
        synchronized(lock) {
            drawn = at
            return at != used
        }
    }

    /** From the Compose draw: the view to draw the layers at, null (the view as it is) till the GL thread draws a frame. */
    fun take(): ViewAt? = synchronized(lock) { drawn.also { used = it } }
}
