package com.rm.interchange.map

/**
 * Restore four two-bit snow values per byte, the first pixel in the low bits, into [pixels] from [from] until [to].
 * [from] is a multiple of 4, so strips can run on different threads at once.
 */
internal fun restoreStaticSnow(pixels: IntArray, snow: ByteArray, from: Int = 0, to: Int = pixels.size) {
    require(snow.size == (pixels.size + 3) / 4) { "Static snow mask does not match its colour sheet" }
    require(from and 3 == 0 && from <= to && to <= pixels.size)
    // A byte at a time, four pixels each, as this runs interpreted on a fresh install.
    var i = from
    var b = from shr 2
    val whole = to and 3.inv()
    while (i < whole) {
        val s = snow[b].toInt()
        pixels[i] = (pixels[i] and -4) or (s and 3)
        pixels[i + 1] = (pixels[i + 1] and -4) or ((s shr 2) and 3)
        pixels[i + 2] = (pixels[i + 2] and -4) or ((s shr 4) and 3)
        pixels[i + 3] = (pixels[i + 3] and -4) or ((s shr 6) and 3)
        i += 4
        b++
    }
    if (i < to) {
        val s = snow[b].toInt()
        var shift = 0
        while (i < to) {
            pixels[i] = (pixels[i] and -4) or ((s shr shift) and 3)
            shift += 2
            i++
        }
    }
}

/** Copy [count] grey pixels from [strip] into [shadows] at [at], one channel's byte each. */
internal fun copyShade(strip: IntArray, count: Int, shadows: ByteArray, at: Int) {
    var o = at
    var i = 0
    while (i < count) {
        shadows[o] = strip[i].toByte()
        o++
        i++
    }
}

/** [size] split into about [parts] ranges, each start a multiple of 4. */
internal fun strips(size: Int, parts: Int): List<IntRange> {
    val step = ((size + parts - 1) / parts + 3) and 3.inv()
    if (step == 0) return emptyList()
    return (0 until size step step).map { it until minOf(it + step, size) }
}
