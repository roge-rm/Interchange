package com.rm.interchange.map

import androidx.compose.ui.graphics.ImageBitmap

/** A bitmap of ARGB [pixels], [width] by [height], for a chunk baked by hand. */
internal expect fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap
