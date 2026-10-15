package com.rm.interchange.dedicated

import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region

/**
 * A new game's settings: from the INTERCHANGE_ env vars, where a value out of
 * range is left at its default, then from [chosen] (size, seed, year, region,
 * rivals, dayLength), where one is refused with [IllegalArgumentException].
 */
fun newGameSettings(env: Map<String, String>, chosen: Map<String, String> = emptyMap()): NewGame {
    for (key in chosen.keys) require(key in KEYS) { "no setting called \"$key\"" }
    fun pick(key: String, envKey: String, default: Int, ok: (Int) -> Boolean): Int {
        chosen[key]?.takeIf { it.isNotEmpty() }?.let { v ->
            return v.toIntOrNull()?.takeIf(ok) ?: throw IllegalArgumentException("$key can't be $v")
        }
        return env[envKey]?.toIntOrNull()?.takeIf(ok) ?: default
    }
    val size = pick("size", "INTERCHANGE_MAP_SIZE", 256) { it in NewGame.SIZES }
    val seed = chosen["seed"]?.takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: throw IllegalArgumentException("seed can't be $it") }
        ?: env["INTERCHANGE_SEED"]?.toLongOrNull() ?: System.currentTimeMillis()
    val region = chosen["region"]?.takeIf { it.isNotEmpty() }?.let { r ->
        region(r) ?: throw IllegalArgumentException("region is one of ${Region.entries.joinToString { it.name }}")
    } ?: env["INTERCHANGE_REGION"]?.let(::region) ?: Region.MIXED
    return NewGame(
        width = size, height = size, seed = seed,
        startYear = pick("year", "INTERCHANGE_START_YEAR", 1930) { it in YEARS },
        region = region,
        rivals = pick("rivals", "INTERCHANGE_RIVALS", 2) { it in 0..NewGame.MOST_RIVALS },
        dayLength = pick("dayLength", "INTERCHANGE_DAY_LENGTH", 10) { it in NewGame.DAY_LENGTHS },
    )
}

private fun region(name: String) = Region.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

private val KEYS = setOf("size", "seed", "year", "region", "rivals", "dayLength")

/** Start years a new game may have, as in the app. */
val YEARS = NewGame.FIRST_YEAR..NewGame.LAST_YEAR
