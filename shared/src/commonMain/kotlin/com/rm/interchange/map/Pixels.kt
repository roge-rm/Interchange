package com.rm.interchange.map

import androidx.compose.ui.graphics.ImageBitmap

/** A bitmap of ARGB [pixels], [width] by [height], for a chunk baked by hand. */
internal expect fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap

/** A baked chunk's picture: rows [top] to [top] + [height] of [pixels], kept where drawing it is quickest. When measuring, [split] gets the two copies' times in nanoseconds. */
internal expect fun chunkBitmapOf(pixels: IntArray, width: Int, top: Int, height: Int, split: LongArray? = null): ImageBitmap

/** How much memory the app may hold on the heap, in bytes. */
internal expect fun heapBytes(): Long
