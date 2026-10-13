package com.rm.interchange.map

import com.rm.interchange.sim.World
import com.rm.interchange.sim.mud

/**
 * The weather as the map shows it: a copy of each tile's snow, ice and mud, so every chunk baked from the same copy
 * agrees with its neighbours, however far the weather's moved on since. [versions] is the world's weather versions
 * summed when it was made, to tell when it's out of date.
 */
internal class WeatherCopy(val id: Int, val versions: Long, val snow: ByteArray, val ice: ByteArray, val mud: ByteArray) {
    companion object {
        /** Copies [world]'s weather now, off the main thread. */
        fun of(world: World, id: Int, versions: Long) = WeatherCopy(
            id, versions,
            world.weather.snow.copyOf(),
            world.weather.ice.copyOf(),
            ByteArray(world.width * world.height) { world.mud(it).toByte() },
        )
    }
}
