package com.rm.interchange.sim

/*
 * Autonomous trucks and drones, with [NewGame.future] on.
 *
 * An autonomous truck is a road vehicle marked `autonomous` in its pack, from about [AUTONOMOUS_FROM]. It has no crew,
 * so its pack running cost leaves out the crew's share; on a highway it follows another autonomous vehicle in a
 * convoy, [CONVOY_GAP] behind instead of [GAP], so a highway carries more of them.
 *
 * A drone is an aircraft with runway `drone`, from about [DRONE_FROM]. It takes off and lands straight up and down as a
 * helicopter does, only at a pad ([Airports.PAD]), a one-tile airport that joins a station beside it as an airport
 * does. Drones fly short hops and carry only [Drones.CARGOES]. A drone on its pad stays there while the wind is at
 * [Drones.WIND] or more or snow is falling on the pad; one in the air lands at its pad and waits.
 */

/** The years autonomous trucks and drones are first on sale in the base pack, and pads can be built. */
const val AUTONOMOUS_FROM = 2035
const val DRONE_FROM = 2030

/** How far an autonomous truck keeps behind another in a convoy on a highway, against [GAP]. */
const val CONVOY_GAP = FIX / 2

object Drones {
    /** Wind at the middle of the map, in km/h, that grounds drones. */
    const val WIND = 35
    /** What a drone may carry, by cargo id. */
    val CARGOES = listOf("mail", "goods", "food")
    /** The most a drone carries. */
    const val MOST_LOAD = 6

    /** Why drones are grounded: not, wind, or snow. */
    const val FLYING = 0
    const val GROUNDED_WIND = 1
    const val GROUNDED_SNOW = 2
}

/** A drone, which lands only at pads. */
val Engine.drone get() = kind == VehicleKind.AIRCRAFT && runway == Runways.DRONE

/** Whether pads can be built now: future transport on and [DRONE_FROM] come. */
fun World.padsOpen() = settings.future && Calendar.year(startYear, day) >= DRONE_FROM

/** Whether drones are grounded at airport [a] now, a [Drones] reason. */
fun World.droneGrounded(a: Airport): Int {
    if (!settings.weather) return Drones.FLYING
    if (weather.wind >= Drones.WIND) return Drones.GROUNDED_WIND
    if (skyAt(a.x, a.y).snowing) return Drones.GROUNDED_SNOW
    return Drones.FLYING
}

/**
 * Whether drones will be grounded on tile ([x], [y]) on each of the next [days] days, tomorrow first, as a [Drones]
 * reason from the forecast: the wind expected and snow expected to fall. Empty with the weather off.
 */
fun World.droneOutlook(x: Int, y: Int, days: Int = 5): IntArray {
    if (!settings.weather || !inside(x, y) || days <= 0) return IntArray(0)
    val o = outlook(days)
    val f = forecastTile(o, x, y)
    return IntArray(days) { d ->
        when {
            o.wind[d] >= Drones.WIND -> Drones.GROUNDED_WIND
            f[d].snowfall > 0 -> Drones.GROUNDED_SNOW
            else -> Drones.FLYING
        }
    }
}

/** Whether [v] and [o] run in a convoy on [tile]: both autonomous, on a highway. */
internal fun World.convoy(v: RoadVehicle, o: RoadVehicle, tile: Int) =
    v.info.autonomous && o.info.autonomous && surfaceAt(tile) == Surface.HIGHWAY

/** Whether [e] belongs to future transport by autonomy: an autonomous truck or a drone. */
internal val Engine.autonomyFuture get() = autonomous || drone

/** Pads in the checksum, only with future transport on and some pad built, so older games keep theirs. */
internal fun World.autonomyChecksum(mix: (Long) -> Unit) {
    if (!settings.future) return
    var any = false
    for (a in airports) if (a != null && a.kind == Airports.PAD) {
        if (!any) mix(940L)
        any = true
        mix(a.id.toLong() * 43)
    }
}
