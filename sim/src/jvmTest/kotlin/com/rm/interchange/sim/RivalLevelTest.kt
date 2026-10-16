package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertTrue

class RivalLevelTest {
    private fun game(level: Int) = World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 3, rivals = 2, rivalLevel = level))

    private fun World.days(n: Int) = repeat(Calendar.TICKS_PER_DAY * n) { step() }

    private fun World.statues() = towns.sumOf { t -> rivals.count { t.statue[it.company] >= 0 } }

    @Test
    fun easyRivalsPayTownsNothing() {
        val w = game(RivalLevel.EASY)
        w.days(365 * 2)
        assertTrue(w.statues() == 0)
        assertTrue(w.towns.none { it.exclusive >= 0 || it.roadWorks > 0 || it.funded > 0 })
    }

    @Test
    fun normalRivalsPutUpStatuesAtHome() {
        val w = game(RivalLevel.NORMAL)
        // Rich enough for one in their first year, and making money as if they had a year behind them.
        for (r in w.rivals) {
            w.companies[r.company].money = 250_000
            w.companies[r.company].lastYear[Books.INCOME] = 2_000_000
        }
        w.days(365)
        assertTrue(w.statues() > 0, "no statues")
        // But nothing aimed at the player.
        assertTrue(w.towns.none { it.exclusive >= 0 || it.roadWorks > 0 })
    }

    @Test
    fun easyRivalsKeepClearOfThePlayersTowns() {
        val w = game(RivalLevel.EASY)
        // A stop of the player's in every town, as if it served them all.
        w.companies[0].money = 10_000_000
        for (t in w.towns) {
            val stop = (0 until w.width * w.height).firstOrNull { i ->
                w.townOf[i].toInt() == t.id && w.straightRoad(i) != 0 && w.isFlat(i % w.width, i / w.width) && w.track[i].toInt() == 0
            } ?: continue
            w.apply(Command.RoadStop(0, stop % w.width, stop / w.width))
        }
        w.days(365 * 2)
        assertTrue(w.rivals.all { r -> r.routes.none { !it.freight } }, "an easy rival ran buses in the player's towns")
    }
}
