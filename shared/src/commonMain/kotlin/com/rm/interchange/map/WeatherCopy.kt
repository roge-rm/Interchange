package com.rm.interchange.map

import com.rm.interchange.sim.World
import com.rm.interchange.sim.mud

/**
 * The weather as the map shows it: a copy of each tile's snow, ice and mud, so every region recorded from the same
 * copy agrees with its neighbours, however far the weather's moved on since. [id] goes up by one each copy.
 */
internal class WeatherCopy(val id: Int, val snow: ByteArray, val ice: ByteArray, val mud: ByteArray) {
    companion object {
        /** Copies [world]'s weather now, off the main thread. */
        fun of(world: World, id: Int): WeatherCopy {
            val n = world.width * world.height
            return WeatherCopy(id, world.weather.snow.copyOf(), world.weather.ice.copyOf(), ByteArray(n) { world.mud(it).toByte() })
        }
    }
}
