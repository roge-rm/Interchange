package com.rm.interchange.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildingArtTest {
    private fun rects(w: Int) = IntArray(96).also { for (c in 0 until 4) { it[c * 6 + 2] = w; it[c * 6 + 3] = w / 2; it[c * 6 + 4] = -w / 2; it[c * 6 + 5] = -w / 4 } }

    @Test
    fun cellsAreAddedAndRemoved() {
        val base = StaticCells.BASE
        val art = BuildingArt(listOf(3, 7), listOf("a/x", "b/y"), listOf(rects(128), null)) { _, _ -> null }
        StaticCells.set(art)
        try {
            assertEquals(base + 8, StaticCells.count)
            assertEquals(128, StaticCells.rects[base * 6 + 2])
            // The missing drawing is a box.
            assertEquals(192, StaticCells.rects[(base + 4) * 6 + 2])
            // Each level's cell lies under the base sheet's rows and inside its width, none on another.
            val bottom = (0 until base).maxOf { StaticCells.levelRects[(it * Atlas.LEVELS + 1) * 6 + 1] + StaticCells.levelRects[(it * Atlas.LEVELS + 1) * 6 + 3] }
            val boxes = (0 until 8).map { c -> StaticCells.levelRects.copyOfRange(((base + c) * Atlas.LEVELS + 1) * 6, ((base + c) * Atlas.LEVELS + 1) * 6 + 4) }
            for (b in boxes) assertTrue(b[1] >= bottom)
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                val a = boxes[i]; val b = boxes[j]
                assertTrue(a[0] + a[2] <= b[0] || b[0] + b[2] <= a[0] || a[1] + a[3] <= b[1] || b[1] + b[3] <= a[1], "cells $i and $j overlap")
            }
            assertTrue(art.rowsAt(1, 10) > bottom)
        } finally {
            StaticCells.set(null)
        }
        assertEquals(base, StaticCells.count)
    }
}
