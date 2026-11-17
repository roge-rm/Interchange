package com.rm.interchange.platform

import androidx.compose.runtime.Composable

/**
 * What memory the device gives the game, in MB: [heap] is what this app may use now (the large heap, as the app
 * asks for it), [normalHeap] and [largeHeap] what the system offers apps, and [total] the device's RAM.
 */
data class DeviceMemory(val heap: Int, val normalHeap: Int, val largeHeap: Int, val total: Int)

/** This device's memory, or null where it can't be read, which allows every map size. */
@Composable
expect fun deviceMemory(): DeviceMemory?

/**
 * Which map sizes a new game may have on this device, by its memory. A map with more tiles than a row's [Step.tiles]
 * needs the next row's heap and RAM; the thresholds are all in [STEPS].
 */
object MapLimits {
    /** Up to [tiles] tiles needs a heap of [heap] MB and [total] MB of RAM or more. */
    class Step(val tiles: Long, val heap: Int, val total: Int)

    // The World holds about 35 bytes a tile (582 MB at 4096 on the host) and the map's weather and wear copies about
    // 9 more, so a map needs about 44 bytes a tile and room to work. Set from the Fire's numbers in B11b.
    val STEPS = listOf(
        Step(1024L * 1024, 0, 0),
        Step(2048L * 2048, 320, 1536),
        Step(4096L * 4096, 1024, 3072),
    )

    /** The most tiles a map may have with [memory], or every size when it's not known. */
    fun mostTiles(memory: DeviceMemory?): Long {
        if (memory == null) return STEPS.last().tiles
        var most = STEPS.first().tiles
        for (s in STEPS) if (memory.heap >= s.heap && memory.total >= s.total) most = s.tiles
        return most
    }

    /** The sides from [sizes] a map can have next to [other], with [memory]. */
    fun sides(sizes: List<Int>, other: Int, memory: DeviceMemory?): List<Int> {
        val most = mostTiles(memory)
        return sizes.filter { it.toLong() * other <= most }.ifEmpty { listOf(sizes.first()) }
    }
}
