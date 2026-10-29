package com.rm.interchange.map

import com.rm.interchange.sim.World
import com.rm.interchange.sim.mud
import kotlin.math.min

/**
 * The weather as the map shows it: a copy of each tile's snow, ice and mud, so every chunk baked from the same copy
 * agrees with its neighbours, however far the weather's moved on since. [versions] is the world's weather versions
 * summed when it was made, to tell when it's out of date. [blocks] counts, for each of the world's blocks, the copies
 * in which a tile in it changed; a chunk needs baking again only where the sum over its blocks has moved.
 */
internal class WeatherCopy(val id: Int, val versions: Long, val snow: ByteArray, val ice: ByteArray, val mud: ByteArray, val blocks: IntArray = IntArray(0)) {
    companion object {
        /** Copies [world]'s weather now, off the main thread, counting the blocks changed since [last]. */
        fun of(world: World, id: Int, versions: Long, last: WeatherCopy?): WeatherCopy {
            val n = world.width * world.height
            val snow = world.weather.snow.copyOf()
            val ice = world.weather.ice.copyOf()
            val mud = ByteArray(n) { world.mud(it).toByte() }
            val across = world.width / World.BLOCK
            val rows = world.height / World.BLOCK
            val blocks = IntArray(across * rows)
            if (last != null && last.blocks.size == blocks.size) {
                last.blocks.copyInto(blocks)
                val changed = BooleanArray(blocks.size)
                for (i in 0 until n) {
                    if (snow[i] != last.snow[i] || ice[i] != last.ice[i] || mud[i] != last.mud[i]) {
                        changed[min(rows - 1, i / world.width / World.BLOCK) * across + min(across - 1, i % world.width / World.BLOCK)] = true
                    }
                }
                for (b in blocks.indices) if (changed[b]) blocks[b]++
            }
            return WeatherCopy(id, versions, snow, ice, mud, blocks)
        }
    }
}
