package com.rm.interchange.map

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

internal actual fun heapBytes(): Long = Runtime.getRuntime().maxMemory()

internal actual fun sameImage(a: ImageBitmap, b: ImageBitmap): Boolean = a.asAndroidBitmap().sameAs(b.asAndroidBitmap())
