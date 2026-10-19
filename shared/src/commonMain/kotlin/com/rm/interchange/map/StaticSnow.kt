package com.rm.interchange.map

/** Restore four two-bit snow values per byte, the first pixel in the low bits. */
internal fun restoreStaticSnow(pixels: IntArray, snow: ByteArray) {
    require(snow.size == (pixels.size + 3) / 4) { "Static snow mask does not match its colour sheet" }
    for (i in pixels.indices) {
        val up = (snow[i / 4].toInt() ushr ((i % 4) * 2)) and 3
        pixels[i] = (pixels[i] and -4) or up
    }
}
