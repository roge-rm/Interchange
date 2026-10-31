package com.rm.interchange.sim

import java.io.File
import kotlin.test.Test

/**
 * Writes a saved game to watch ships and aircraft turn, turns_showcase (.ichg with its .info) as turns, into the folder
 * -Pinterchange.turnShowcase names. Skipped without it. 1965 in July on flat land: a lake with an island, two docks and a buoy
 * so the ships' path turns through 45 and 90 degrees and they turn about at the docks, and two regional airports a short way
 * apart, with three aircraft taxiing, taking off, turning and landing between them. Weather and collisions are off.
 */
class TurnShowcase {
    @Test
    fun write() {
        val out = System.getProperty("interchange.turnShowcase")?.let { File(it) } ?: return
        out.mkdirs()
        val w = World(NewGame(64, 64, 5, 1965, Region.MIXED, sea = 0, hills = 0, trees = 0, towns = 0, industries = 0, weather = false, inflation = false, collisions = false))
        // Land a step up, a lake from x = 10 to 26 and y = 6 to 22 with an island in it.
        for (cy in 0..w.height) for (cx in 0..w.width) {
            val lake = cx in 10..27 && cy in 6..23 && !(cx in 16..20 && cy in 10..13)
            w.corners[w.corner(cx, cy)] = if (lake) 0 else 1
        }
        w.landShaped()
        for (i in w.trees.indices) w.trees[i] = 0
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        w.apply(Command.Dock(0, 9, 9))
        w.apply(Command.Dock(0, 27, 19))
        w.apply(Command.Shipyard(0, 9, 14))
        w.apply(Command.Buoy(0, 18, 17))
        w.apply(Command.Buoy(0, 22, 8))
        w.apply(Command.Airport(0, 6, 30, Airports.REGIONAL, Track.X))
        w.apply(Command.Airport(0, 22, 30, Airports.REGIONAL, Track.X))
        while (Calendar.month(1965, w.day) < 6) w.step()
        val buoyA = w.buoys.getValue(w.index(18, 17))
        val buoyB = w.buoys.getValue(w.index(22, 8))
        fun ship(orders: IntArray) {
            w.apply(Command.BuyCraft(0, 0, w.roster.engines.first { it.key == "base:steam_laker" }.id))
            val id = w.ships.size - 1
            w.apply(Command.CraftOrder(0, false, id, Command.TrainOrder.ORDERS, orders))
            w.apply(Command.CraftOrder(0, false, id, Command.TrainOrder.START_STOP))
        }
        fun craft(orders: IntArray) {
            w.apply(Command.BuyCraft(0, w.airports[0]!!.depot, w.roster.engines.first { it.key == "base:dc3" }.id))
            val id = w.aircraft.size - 1
            w.apply(Command.CraftOrder(0, true, id, Command.TrainOrder.ORDERS, orders))
            w.apply(Command.CraftOrder(0, true, id, Command.TrainOrder.START_STOP))
        }
        val air = w.airports.filterNotNull().map { it.station }
        val trace = System.getProperty("interchange.turnTrace") != null
        repeat(Integer.getInteger("interchange.turnTicks", 9000)) { t ->
            // One more of each every 500 ticks, up to three.
            if (t % 500 == 0 && t < 1500) {
                ship(intArrayOf(0, buoyA, 1, buoyB))
                craft(intArrayOf(air[0], air[1]))
            }
            w.step()
            if (trace && t % 250 == 0) println("t=$t ships ${w.ships.filterNotNull().map { "${it.x / 4096},${it.y / 4096}h${it.heading}" }} aircraft ${w.aircraft.filterNotNull().map { "${it.x / 4096},${it.y / 4096}${it.state}" }}")
        }
        val g = w.settings
        File(out, "turns.ichg").writeBytes(Save.write(w))
        File(out, "turns.info").writeText("day=${w.day}\nstartYear=${g.startYear}\nmoney=${w.companies[0].money}\nwidth=${g.width}\nheight=${g.height}\nregion=${g.region.ordinal}\n")
        println("ships ${w.ships.size} aircraft ${w.aircraft.size} airports ${w.airports.filterNotNull().map { it.x to it.y }} stations ${w.stations.map { it?.name }}")
    }
}
