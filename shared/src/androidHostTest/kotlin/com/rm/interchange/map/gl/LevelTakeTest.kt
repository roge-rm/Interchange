package com.rm.interchange.map.gl

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.rm.interchange.map.Atlas
import com.rm.interchange.map.LevelSheets
import com.rm.interchange.map.Zoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LevelTakeTest {
    private object Blank : ImageBitmap {
        override val width = 1
        override val height = 1
        override val colorSpace = ColorSpaces.Srgb
        override val hasAlpha = true
        override val config = ImageBitmapConfig.Argb8888
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) {}
        override fun prepareToDraw() {}
    }

    private fun sheets() = LevelSheets(IntArray(4), IntArray(4), 2, ByteArray(4), 2, Blank)

    /** Stands in for [com.rm.interchange.map.Sheets]: each level unpacked, till it's forgotten, which unpacks it again. */
    private class Source(val unpacked: Array<LevelSheets?>) {
        val forgot = ArrayList<Int>()
        fun get(l: Int) = unpacked[l]
        fun forget(l: Int) {
            forgot += l
            unpacked[l] = null
        }
    }

    @Test
    fun aSecondSceneUnpacksSheetsTheFirstLetGoOf() {
        val first = Zoom.SHARPEST
        val source = Source(Array(Atlas.LEVELS) { sheets() })
        // The first scene takes every level, then lets go of the pixels once they're on the GPU.
        val taken = takeLevels(arrayOfNulls(Atlas.LEVELS), first, true, source::get, source::forget)
        assertNotNull(taken)
        for (l in Zoom.SHARPEST until Atlas.LEVELS) taken[l]!!.release()
        // Its own levels stay as they are.
        assertNull(takeLevels(taken, first, true, source::get, source::forget))
        assertTrue(source.forgot.isEmpty())
        // A new scene doesn't take them, it has them unpacked again.
        val second = takeLevels(arrayOfNulls(Atlas.LEVELS), first, false, source::get, source::forget)
        assertNull(second)
        assertEquals(listOf(first), source.forgot)
        val fresh = sheets()
        source.unpacked[first] = fresh
        val got = takeLevels(arrayOfNulls(Atlas.LEVELS), first, false, source::get, source::forget)
        assertNotNull(got)
        assertSame(fresh, got[first])
    }

    @Test
    fun aDroppedLevelIsNotAskedForAgain() {
        val first = Zoom.SHARPEST
        val source = Source(Array(Atlas.LEVELS) { sheets() })
        val skip = BooleanArray(Atlas.LEVELS)
        skip[Atlas.LEVELS - 1] = true
        val taken = takeLevels(arrayOfNulls(Atlas.LEVELS), first, true, source::get, source::forget, skip)
        assertNotNull(taken)
        assertNull(taken[Atlas.LEVELS - 1])
        assertNotNull(taken[first])
        assertNull(takeLevels(taken, first, true, source::get, source::forget, skip))
    }
}
