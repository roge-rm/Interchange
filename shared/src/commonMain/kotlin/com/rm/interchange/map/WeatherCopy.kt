package com.rm.interchange.map

import com.rm.interchange.sim.World
import com.rm.interchange.sim.mud

/**
 * The weather as the map shows it: a copy of each tile's snow, ice and mud, so every region recorded from the same
 * copy agrees with its neighbours, however far the weather's moved on since. [id] goes up by one each copy.
 * [baked] is the id of the first copy with the same snow and ice as this one, what [TileRecorder] draws into sprites,
 * so tiles recorded with copies of the same [baked] agree.
 */
internal class WeatherCopy(val id: Int, val snow: ByteArray, val ice: ByteArray, val mud: ByteArray, val baked: Int = id) {
    companion object {
        /** Copies [world]'s weather now, off the main thread, [last] the copy before it if any. */
        fun of(world: World, id: Int, last: WeatherCopy? = null): WeatherCopy {
            val n = world.width * world.height
            val snow = world.weather.snow.copyOf()
            val ice = world.weather.ice.copyOf()
            val baked = if (last != null && last.snow.contentEquals(snow) && last.ice.contentEquals(ice)) last.baked else id
            return WeatherCopy(id, snow, ice, ByteArray(n) { world.mud(it).toByte() }, baked)
        }
    }
}
