package com.rm.interchange.sim

import java.io.File
import kotlin.test.Test

/**
 * Writes a saved game to watch ships and aircraft turn, turns_showcase (.ichg with its .info) as turns, into the folder
 * -Pinterchange.turnShowcase names. Skipped without it. 1965 in July on flat land: a lake with an island, two docks and a buoy
 * so the ships' path turns through 45 and 90 degrees and they turn about at the docks, and two regional airports a short way
 * apart, with three aircraft taxiing, taking off, turning and landing between them. Two each of strips, city and
 * international airports and seaplane bases have a plane going between them too. Weather and collisions are off.
 * With -Pinterchange.turnTrace, aircraft_paths.csv has every aircraft every tick and taxi_points.csv every airport's
 * taxi points.
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
        w.apply(Command.Airport(0, 24, 38, Airports.REGIONAL, Track.Y))
        w.apply(Command.Airport(0, 36, 4, Airports.STRIP, Track.X))
        w.apply(Command.Airport(0, 56, 10, Airports.STRIP, Track.Y))
        w.apply(Command.Airport(0, 2, 46, Airports.CITY, Track.X))
        w.apply(Command.Airport(0, 14, 50, Airports.CITY, Track.Y))
        w.apply(Command.Airport(0, 30, 26, Airports.INTERNATIONAL, Track.X))
        w.apply(Command.Airport(0, 50, 34, Airports.INTERNATIONAL, Track.Y))
        w.apply(Command.Airport(0, 9, 20, Airports.SEAPLANE, 0))
        w.apply(Command.Airport(0, 27, 16, Airports.SEAPLANE, 0))
        check(w.airports.size == 10) { "built ${w.airports.size} airports" }
        while (Calendar.month(1965, w.day) < 6) w.step()
        val buoyA = w.buoys.getValue(w.index(18, 17))
        val buoyB = w.buoys.getValue(w.index(22, 8))
        fun ship(orders: IntArray) {
            w.apply(Command.BuyCraft(0, 0, w.roster.engines.first { it.key == "base:steam_laker" }.id))
            val id = w.ships.size - 1
            w.apply(Command.CraftOrder(0, false, id, Command.TrainOrder.ORDERS, orders))
            w.apply(Command.CraftOrder(0, false, id, Command.TrainOrder.START_STOP))
        }
        fun craft(orders: IntArray, key: String = "dc3", home: Int = 0) {
            w.apply(Command.BuyCraft(0, w.airports[home]!!.depot, w.roster.engines.first { it.key == "base:$key" }.id))
            val id = w.aircraft.size - 1
            w.apply(Command.CraftOrder(0, true, id, Command.TrainOrder.ORDERS, orders))
            w.apply(Command.CraftOrder(0, true, id, Command.TrainOrder.START_STOP))
        }
        val air = w.airports.filterNotNull().map { it.station }
        val trace = System.getProperty("interchange.turnTrace") != null
        // With the trace, every aircraft every tick: tick, id, x and y in 4096ths, heading in 256ths, state, altitude.
        val paths = if (trace) StringBuilder("tick,id,x,y,heading,state,altitude\n") else null
        repeat(Integer.getInteger("interchange.turnTicks", 9000)) { t ->
            // One more of each every 500 ticks, up to three.
            if (t % 500 == 0 && t < 1500) {
                ship(intArrayOf(0, buoyA, 1, buoyB))
                craft(intArrayOf(air[0], air[1]))
            }
            if (t == 0) {
                craft(intArrayOf(air[2], air[3]), "beaver", 2)
                craft(intArrayOf(air[4], air[5]), "viscount", 4)
                craft(intArrayOf(air[6], air[7]), "dc8", 6)
                craft(intArrayOf(air[8], air[9]), "beaver", 8)
            }
            w.step()
            if (trace && t % 250 == 0) println("t=$t ships ${w.ships.filterNotNull().map { "${it.x / 4096},${it.y / 4096}h${it.heading}" }} aircraft ${w.aircraft.filterNotNull().map { "${it.x / 4096},${it.y / 4096}h${it.heading}s${it.state}" }}")
            if (paths != null) for (v in w.aircraft) if (v != null && !v.inDepot) paths.append("$t,${v.id},${v.x},${v.y},${v.heading},${v.state},${v.altitude}\n")
        }
        val g = w.settings
        File(out, "turns.ichg").writeBytes(Save.write(w))
        if (paths != null) {
            File(out, "aircraft_paths.csv").writeText(paths.toString())
            // Each airport's routes between every two places, point by point in 4096ths.
            val points = StringBuilder("airport,kind,x,y,w,h,route,i,px,py,runway\n")
            for (a in w.airports) {
                if (a == null) continue
                val stands = Taxiways.STANDS[a.kind].size / 4
                val places = (0 until stands) + listOf(Taxiways.DOOR, Taxiways.END0, Taxiways.END1)
                // Landings turn off at exits onto the way to a stand or the hangar.
                val exits = (Taxiways.EXIT0 until Taxiways.EXIT0 + Taxiways.EXITS[a.kind].size).toList()
                for (f in places + exits) for (to in places) {
                    if (f == to || a.kind == Airports.HELIPORT && f >= Taxiways.END0) continue
                    if (Taxiways.stand(f) && Taxiways.stand(to) || f in exits && to >= Taxiways.END0) continue
                    val pts = taxiRoute(a, f * 16 + to)
                    for (i in 0 until pts.size / 2) {
                        val p = taxiPoint(a, pts, i)
                        points.append("${a.id},${a.kind},${a.x},${a.y},${a.w},${a.h},${f * 16 + to},$i,${p[0]},${p[1]},${if (pts[i * 2 + 1] == 8) 1 else 0}\n")
                    }
                }
            }
            File(out, "taxi_points.csv").writeText(points.toString())
        }
        File(out, "turns.info").writeText("day=${w.day}\nstartYear=${g.startYear}\nmoney=${w.companies[0].money}\nwidth=${g.width}\nheight=${g.height}\nregion=${g.region.ordinal}\n")
        println("ships ${w.ships.size} aircraft ${w.aircraft.size} airports ${w.airports.filterNotNull().map { it.x to it.y }} stations ${w.stations.map { it?.name }}")
    }
}
