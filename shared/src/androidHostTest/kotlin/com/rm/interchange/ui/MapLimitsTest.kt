package com.rm.interchange.ui

import com.rm.interchange.platform.DeviceMemory
import com.rm.interchange.platform.MapLimits
import com.rm.interchange.sim.NewGame
import kotlin.test.Test
import kotlin.test.assertEquals

class MapLimitsTest {
    private val sizes = NewGame.SIZES.toList()

    private fun memory(heap: Int, total: Int) = DeviceMemory(heap, heap / 2, heap, total)

    @Test
    fun unknownMemoryAllowsEverySize() {
        assertEquals(sizes, MapLimits.sides(sizes, 4096, null))
    }

    @Test
    fun smallHeapStopsAt1024() {
        assertEquals(1024L * 1024, MapLimits.mostTiles(memory(192, 2048)))
        assertEquals(sizes.filter { it <= 1024 }, MapLimits.sides(sizes, 1024, memory(192, 2048)))
        // A narrow map can be longer, as it's the tiles that count.
        assertEquals(sizes.filter { it <= 4096 }, MapLimits.sides(sizes, 256, memory(192, 2048)))
    }

    @Test
    fun eachStepNeedsBothHeapAndRam() {
        for ((i, s) in MapLimits.STEPS.withIndex()) {
            assertEquals(s.tiles, MapLimits.mostTiles(memory(s.heap, s.total)))
            if (i == 0) continue
            val below = MapLimits.STEPS[i - 1].tiles
            assertEquals(below, MapLimits.mostTiles(memory(s.heap - 1, s.total)))
            assertEquals(below, MapLimits.mostTiles(memory(s.heap, s.total - 1)))
        }
    }

    @Test
    fun theSmallestSizeIsAlwaysOffered() {
        assertEquals(listOf(64), MapLimits.sides(listOf(64, 128), 1 shl 20, memory(0, 0)))
    }
}
