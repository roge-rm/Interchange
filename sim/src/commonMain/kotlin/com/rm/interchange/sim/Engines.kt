package com.rm.interchange.sim

/**
 * What can be bought to run on the track, and when: engines and the wagons
 * they haul, after those used in Canada, with our own railway's names and
 * colours. Speeds in km/h, power in horsepower, weights in tonnes, money in
 * dollars, running costs a year. A steam engine brings its tender with it.
 */
class Engine(
    val id: Int,
    val name: String,
    /** Its type, as railwaymen called it: a wheel arrangement, or what a car is for. */
    val type: String,
    /** True for something that pulls, false for a wagon or a tender. */
    val pulls: Boolean,
    val from: Int,
    val until: Int,
    val speed: Int = 0,
    val power: Int = 0,
    val weight: Int,
    val cost: Long,
    val running: Long,
    /** What a wagon carries, and how much. */
    val carries: String = "",
    val capacity: Int = 0,
    /** What more than one is called, for wagons. */
    val plural: String = name + "s",
    /** Which drawing it has, a [VehicleArt] number. */
    val sprite: Int,
    /** The tender it brings, an engine id, or -1. */
    val tender: Int = -1,
    /** A tender, bought only with its engine. */
    val isTender: Boolean = false,
) {
    /** Its length, in sixteenths of a tile. */
    val length get() = VehicleArt.LENGTHS[sprite]
}

object Engines {
    private fun steam(id: Int, name: String, type: String, from: Int, until: Int, speed: Int, power: Int, weight: Int, cost: Long, running: Long, sprite: Int, tenderWeight: Int) = listOf(
        Engine(id, name, type, pulls = true, from = from, until = until, speed = speed, power = power, weight = weight, cost = cost, running = running, sprite = sprite, tender = id + 1),
        Engine(id + 1, "$name tender", "Tender", pulls = false, from = from, until = until, weight = tenderWeight, cost = cost / 6, running = 0, sprite = sprite + 1, isTender = true),
    )

    val ALL: List<Engine> = listOf(
        steam(0, "Dorchester", "0-4-0", 1836, 1866, 30, 120, 7, 6_000, 900, VehicleArt.DORCHESTER, 4),
        steam(2, "American", "4-4-0", 1853, 1915, 90, 600, 35, 14_000, 1_900, VehicleArt.AMERICAN, 20),
        steam(4, "Ten-Wheeler", "4-6-0", 1880, 1935, 100, 1_000, 55, 22_000, 2_600, VehicleArt.TEN_WHEELER, 35),
        steam(6, "Consolidation", "2-8-0", 1885, 1945, 72, 1_400, 70, 26_000, 3_000, VehicleArt.CONSOLIDATION, 45),
        steam(8, "Pacific", "4-6-2", 1906, 1960, 125, 2_200, 95, 42_000, 4_200, VehicleArt.PACIFIC, 70),
        steam(10, "Mikado", "2-8-2", 1912, 1960, 85, 2_600, 110, 45_000, 4_500, VehicleArt.MIKADO, 75),
        steam(12, "Hudson", "4-6-4", 1930, 1960, 150, 3_200, 150, 70_000, 6_000, VehicleArt.HUDSON, 110),
        steam(14, "Northern", "4-8-4", 1927, 1960, 135, 3_800, 170, 78_000, 6_600, VehicleArt.NORTHERN, 120),
        steam(16, "Selkirk", "2-10-4", 1929, 1958, 105, 4_600, 200, 85_000, 7_000, VehicleArt.SELKIRK, 125),
        listOf(
            Engine(18, "Wooden coach", "Coach", pulls = false, from = 1836, until = 1926, weight = 18, cost = 3_000, running = 350, carries = "passengers", capacity = 40, plural = "Wooden coaches", sprite = VehicleArt.COACH_WOOD),
            Engine(19, "Steel coach", "Coach", pulls = false, from = 1910, until = 1980, weight = 40, cost = 9_000, running = 700, carries = "passengers", capacity = 64, plural = "Steel coaches", sprite = VehicleArt.COACH_STEEL),
            Engine(20, "Wooden baggage car", "Baggage car", pulls = false, from = 1850, until = 1926, weight = 16, cost = 2_600, running = 300, carries = "mail", capacity = 20, sprite = VehicleArt.BAGGAGE_WOOD),
            Engine(21, "Steel baggage car", "Baggage car", pulls = false, from = 1912, until = 1985, weight = 35, cost = 7_500, running = 600, carries = "mail", capacity = 30, sprite = VehicleArt.BAGGAGE_STEEL),
            Engine(22, "Wooden boxcar", "Boxcar", pulls = false, from = 1850, until = 1936, weight = 10, cost = 1_800, running = 220, carries = "goods", capacity = 20, sprite = VehicleArt.BOXCAR_WOOD),
            Engine(23, "Steel boxcar", "Boxcar", pulls = false, from = 1925, until = 2010, weight = 22, cost = 5_000, running = 400, carries = "goods", capacity = 40, sprite = VehicleArt.BOXCAR_STEEL),
            Engine(24, "Flatcar", "Flatcar", pulls = false, from = 1836, until = 2050, weight = 9, cost = 1_400, running = 180, carries = "timber", capacity = 20, sprite = VehicleArt.FLATCAR),
            Engine(25, "Gondola", "Gondola", pulls = false, from = 1870, until = 2050, weight = 12, cost = 1_900, running = 220, carries = "steel", capacity = 30, sprite = VehicleArt.GONDOLA),
            Engine(26, "Hopper", "Hopper", pulls = false, from = 1900, until = 2050, weight = 14, cost = 2_400, running = 240, carries = "coal", capacity = 50, sprite = VehicleArt.HOPPER),
            Engine(27, "Tank car", "Tank car", pulls = false, from = 1880, until = 2050, weight = 15, cost = 2_600, running = 260, carries = "oil", capacity = 40, sprite = VehicleArt.TANK_CAR),
            Engine(28, "Stock car", "Stock car", pulls = false, from = 1870, until = 1985, weight = 11, cost = 1_900, running = 220, carries = "livestock", capacity = 20, sprite = VehicleArt.STOCK_CAR),
            Engine(29, "Caboose", "Caboose", pulls = false, from = 1870, until = 1990, weight = 12, cost = 2_200, running = 250, sprite = VehicleArt.CABOOSE),
        ),
    ).flatten()

    /** What can be bought in [year]: engines if [pulls], else wagons. */
    fun available(year: Int, pulls: Boolean) = ALL.filter { it.pulls == pulls && !it.isTender && year >= it.from && year < it.until }
}
