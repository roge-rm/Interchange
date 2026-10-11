package com.rm.interchange.sim

/**
 * What can be bought to run on the track, and when. Speeds in km/h, weights
 * in tonnes, money in dollars, running costs a year.
 */
class Engine(
    val id: Int,
    val name: String,
    /** True for something that pulls, false for a wagon. */
    val pulls: Boolean,
    val from: Int,
    val until: Int,
    val speed: Int,
    val power: Int,
    val weight: Int,
    val cost: Long,
    val running: Long,
    /** What a wagon carries, and how much; nothing yet for an engine. */
    val carries: String = "",
    val capacity: Int = 0,
    /** What more than one is called, for wagons. */
    val plural: String = name + "s",
    /** Which drawing the game uses, from the atlas. */
    val sprite: Int,
)

object Engines {
    val ALL = listOf(
        Engine(0, "Comet", pulls = true, from = 1830, until = 1880, speed = 56, power = 300, weight = 22, cost = 9_000, running = 1_400, sprite = 0),
        Engine(1, "Ranger", pulls = true, from = 1858, until = 1910, speed = 88, power = 650, weight = 38, cost = 16_000, running = 2_100, sprite = 1),
        Engine(2, "Coach", pulls = false, from = 1830, until = 1920, speed = 0, power = 0, weight = 12, cost = 2_500, running = 300, carries = "passengers", capacity = 32, plural = "Coaches", sprite = 2),
        Engine(3, "Box van", pulls = false, from = 1830, until = 1930, speed = 0, power = 0, weight = 10, cost = 1_800, running = 220, carries = "goods", capacity = 16, sprite = 3),
        Engine(4, "Open wagon", pulls = false, from = 1830, until = 1940, speed = 0, power = 0, weight = 9, cost = 1_500, running = 200, carries = "coal", capacity = 20, sprite = 4),
    )

    /** What can be bought in [year]. */
    fun available(year: Int, pulls: Boolean) = ALL.filter { it.pulls == pulls && year >= it.from && year < it.until }
}
