package com.rm.interchange.map

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StaticSnowTest {
    /** The per-pixel restore the strip one replaced. */
    private fun restoreOneByOne(pixels: IntArray, snow: ByteArray) {
        for (i in pixels.indices) {
            val up = (snow[i / 4].toInt() ushr ((i % 4) * 2)) and 3
            pixels[i] = (pixels[i] and -4) or up
        }
    }

    @Test
    fun stripsMatchOneByOne() {
        val random = Random(7)
        for (size in listOf(0, 1, 3, 4, 5, 63, 1000, 4099, 65537)) for (parts in listOf(1, 3, 8)) {
            val pixels = IntArray(size) { random.nextInt() }
            val snow = ByteArray((size + 3) / 4).also { random.nextBytes(it) }
            val want = pixels.copyOf().also { restoreOneByOne(it, snow) }
            val got = pixels.copyOf()
            val ranges = strips(size, parts)
            assertEquals(size, ranges.sumOf { it.last + 1 - it.first }, "strips cover $size once")
            for (r in ranges) restoreStaticSnow(got, snow, r.first, r.last + 1)
            assertContentEquals(want, got, "size $size in $parts")
            val whole = pixels.copyOf().also { restoreStaticSnow(it, snow) }
            assertContentEquals(want, whole, "size $size whole")
        }
    }

    @Test
    fun shadeCopyMatchesOneByOne() {
        val random = Random(11)
        val w = 37
        val h = 50
        val image = IntArray(w * h) { random.nextInt() }
        val want = ByteArray(w * h) { image[it].toByte() }
        val got = ByteArray(w * h)
        val rowsAtOnce = 6
        for (rows in strips(h, 3)) for (y in rows step rowsAtOnce) {
            val n = minOf(rowsAtOnce, rows.last + 1 - y)
            val strip = image.copyOfRange(y * w, (y + n) * w) + IntArray(w)
            copyShade(strip, w * n, got, y * w)
        }
        assertContentEquals(want, got)
    }
}
