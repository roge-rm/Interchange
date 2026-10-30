package com.rm.interchange.map

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * A map chunk's picture from rows [top] on of [pixels], [width] wide. Made a hardware bitmap, so it goes up to the
 * GPU once, here on the baking thread, and stays there; an ordinary one is uploaded again whenever the chunks on
 * screen outgrow the GPU's texture cache, which stalls every frame.
 */
internal actual fun chunkBitmapOf(pixels: IntArray, width: Int, top: Int, height: Int, split: LongArray?): ImageBitmap {
    val t0 = if (Probe.ON) Probe.nanos() else 0L
    val soft = Bitmap.createBitmap(pixels, top * width, width, width, height, Bitmap.Config.ARGB_8888)
    val t1 = if (Probe.ON) Probe.nanos() else 0L
    val hard = soft.copy(Bitmap.Config.HARDWARE, false)
    if (Probe.ON) { split!![0] = t1 - t0; split[1] = Probe.nanos() - t1 }
    if (hard == null) return soft.asImageBitmap()
    soft.recycle()
    return hard.asImageBitmap()
}

internal actual fun heapBytes(): Long = Runtime.getRuntime().maxMemory()
