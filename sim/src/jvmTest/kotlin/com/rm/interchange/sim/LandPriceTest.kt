package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals

/** [landAt] looking only at near towns prices every tile as going through every town does. */
class LandPriceTest {
    /** Land at tile [i] by every town, as it was priced before the towns near a tile were kept by square. */
    private fun World.landByEveryTown(i: Int): Int {
        val x = i % width
        val y = i / width
        var most = 0
        for (t in towns) {
            val d = maxOf(kotlin.math.abs(t.x - x), kotlin.math.abs(t.y - y))
            most = maxOf(most, t.population * 4 / (d + 4))
        }
        return 600 + minOf(1_400, most)
    }

    private fun same(w: World, what: String) {
        for (i in 0 until w.width * w.height) {
            val want = w.landByEveryTown(i)
            if (w.landAt(i) != want) assertEquals(want, w.landAt(i), "$what tile ${i % w.width},${i / w.width}")
        }
    }

    /** Every tile now, after each town's grown [growths] times in [year], and with one town far bigger than the rest. */
    private fun check(size: Int, seed: Long, start: Int, growths: Int, year: Int) {
        val w = World(NewGame(size, size, seed, start))
        val what = "$size seed $seed from $start"
        same(w, "$what, ${w.towns.size} towns, most ${w.towns.maxOf { it.population }}")
        for (t in w.towns) repeat(growths) { w.growTown(t, year) }
        same(w, "$what grown in $year, most ${w.towns.maxOf { it.population }}")
        w.towns[w.towns.size / 2].population = 30_000
        same(w, "$what with a city")
    }

    @Test
    fun smallMaps() {
        for (seed in 1L..4L) check(256, seed, 1850, 300, 1960)
        check(256, 5, 1990, 300, 2020)
    }

    @Test
    fun largeMaps() {
        check(1024, 1, 1850, 200, 1950)
        check(1024, 2, 1930, 200, 2000)
    }
}
