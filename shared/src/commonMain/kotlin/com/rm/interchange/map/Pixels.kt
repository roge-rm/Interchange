package com.rm.interchange.map

import androidx.compose.ui.graphics.ImageBitmap

/** A bitmap of ARGB [pixels], [width] by [height], for a picture made by hand. */
internal expect fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap

/** How much memory the app may hold on the heap, in bytes. */
internal expect fun heapBytes(): Long

/** Whether [a] and [b] are the same size with the same pixels. */
internal expect fun sameImage(a: ImageBitmap, b: ImageBitmap): Boolean
