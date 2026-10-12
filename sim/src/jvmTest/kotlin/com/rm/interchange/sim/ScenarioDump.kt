package com.rm.interchange.sim

import java.io.File
import kotlin.test.Test

/**
 * Writes a small game to try on a phone: three towns along one line by a
 * lake on the Shield, two trains, INTERCHANGE_SCENARIO_DAYS days in (a
 * hundred if not set). Only runs with INTERCHANGE_SCENARIO_OUT set to a
 * folder; the save and its notes go there as save_1.ichg and save_1.info.
 */
class ScenarioDump {
    @Test
    fun threeTownsTwoTrains() {
        val out = System.getenv("INTERCHANGE_SCENARIO_OUT") ?: return
        val days = System.getenv("INTERCHANGE_SCENARIO_DAYS")?.toIntOrNull() ?: 100
        val w = World(NewGame(96, 64, 1, 1925, Region.SHIELD, towns = 0, industries = 0))
        // Flat land at height 3 with a lake along the south, maples along the line.
        for (y in 0..64) for (x in 0..96) w.corners[w.corner(x, y)] = if (y >= 46) 0 else if (y == 45) 1 else if (y == 44) 2 else 3
        for (x in 3..92 step 3) w.trees[w.index(x, 28)] = Trees.pack(Trees.MAPLE, 2).toByte()
        w.shoreChanged()
        w.companies[0].money = 300_000
        for ((n, x) in listOf(10, 47, 84).withIndex()) {
            val t = Town(n, listOf("Elmbrook", "Saint-Lin", "Port Murray")[n], x, 33, 3, french = n == 1)
            w.towns += t
            val c = w.index(x, 33)
            w.road[c] = (15 or PAVED).toByte()
            w.townOf[c] = n.toShort()
            for ((dx, dy, bit) in listOf(Triple(-1, 0, 2), Triple(1, 0, 1), Triple(0, -1, 8), Triple(0, 1, 4))) {
                val i = w.index(x + dx, 33 + dy)
                w.road[i] = (bit or PAVED).toByte()
                w.townOf[i] = n.toShort()
            }
        }
        w.apply(Command.Depot(0, 2, 30, Track.XP))
        w.apply(Command.Depot(0, 93, 30, Track.XM))
        w.apply(Command.Rail(0, 3, 30, 92, 30, Track.X, remove = false))
        w.apply(Command.Station(0, 9, 30, 12, 30, Track.X))
        w.apply(Command.Station(0, 46, 30, 49, 30, Track.X))
        w.apply(Command.Station(0, 83, 30, 86, 30, Track.X))
        for (t in w.towns) repeat(300) { w.growTown(t, 1925) }
        w.townsChanged = true
        w.catchmentDirty = true
        for ((k, orders) in listOf(intArrayOf(0, 1), intArrayOf(1, 2)).withIndex()) {
            w.apply(Command.BuyTrain(0, k, 4, 19, 3))
            w.apply(Command.TrainOrder(0, k, Command.TrainOrder.ORDERS, orders))
            w.apply(Command.TrainOrder(0, k, Command.TrainOrder.START_STOP))
        }
        repeat(Calendar.TICKS_PER_DAY * days) { w.step() }
        File(out, "save_1.ichg").writeBytes(Save.write(w))
        File(out, "save_1.info").writeText("day=${w.day}\nstartYear=1925\nmoney=${w.companies[0].money}\nwidth=96\nheight=64\nregion=2\n")
        println(w.stations.map { s -> s!!.name + " " + s.rating.toList() } + w.towns.map { it.approval[0] } + w.trains.map { it!!.profitThisYear })
    }

    /** A valley crossed by a truss bridge, a ridge with a tunnel, and path signals into two platforms past a waypoint, under wires. */
    @Test
    fun bridgesTunnelsAndSignals() {
        val out = System.getenv("INTERCHANGE_SCENARIO_OUT") ?: return
        val w = World(NewGame(64, 64, 1, 1960, Region.MIXED, towns = 0, industries = 0, weather = false))
        for (y in 0..64) for (x in 0..64) {
            val valley = minOf(minOf(x - 19, 31 - x), minOf(y - 3, 17 - y)).coerceIn(0, 3)
            val ridge = minOf(minOf(x - 16, 34 - x), minOf(y - 23, 37 - y)).coerceIn(0, 4)
            w.corners[w.corner(x, y)] = (4 - valley + ridge).toByte()
        }
        for (i in 0 until w.width * w.height) { w.trees[i] = 0; w.ground[i] = Ground.MEADOW.toByte() }
        w.companies[0].money = 10_000_000
        fun line(y: Int, gapFrom: Int, gapTo: Int) {
            w.apply(Command.Depot(0, 2, y, Track.XP))
            w.apply(Command.Rail(0, 3, y, gapFrom, y, Track.X, remove = false))
            w.apply(Command.Rail(0, gapTo, y, 50, y, Track.X, remove = false))
            w.apply(Command.Station(0, 4, y, 6, y, Track.X))
            w.apply(Command.Station(0, 46, y, 49, y, Track.X))
        }
        line(10, 19, 30)
        w.apply(Command.Bridge(0, 19, 10, 30, 10, Spans.TRUSS))
        line(30, 16, 34)
        w.apply(Command.Tunnel(0, 16, 30))
        // Two platforms at the east end of y = 50, path signals in, a waypoint on the way and wires over it all.
        w.apply(Command.Depot(0, 2, 50, Track.XP))
        w.apply(Command.Rail(0, 3, 50, 45, 50, Track.X, remove = false))
        w.apply(Command.Rail(0, 36, 50, 36, 50, Track.W, remove = false))
        w.apply(Command.Rail(0, 36, 51, 36, 51, Track.E, remove = false))
        w.apply(Command.Rail(0, 37, 51, 45, 51, Track.X, remove = false))
        w.apply(Command.Station(0, 4, 50, 6, 50, Track.X))
        w.apply(Command.Station(0, 41, 50, 44, 51, Track.X))
        w.apply(Command.Signal(0, 30, 50, Track.X, Track.XM, Signals.PATH))
        w.apply(Command.Signal(0, 20, 50, Track.X, Track.XM, Signals.ENTRY))
        w.apply(Command.Signal(0, 25, 50, Track.X, Track.XM, Signals.EXIT))
        w.apply(Command.Waypoint(0, 15, 50))
        w.apply(Command.Wire(0, 3, 50, 45, 50, Track.X, remove = false))
        // A snow shed, a fence, worn track with a slow order, and a patch of burnt forest.
        w.apply(Command.SnowWorks(0, 40, 10, shed = true))
        w.apply(Command.SnowWorks(0, 12, 52, shed = false))
        w.apply(Command.SnowWorks(0, 13, 52, shed = false))
        for (x in 8..12) w.condition[w.index(x, 30)] = 60
        for (y in 40..44) for (x in 20..26) { w.fire[w.index(x, y)] = if (x == 23) 3 else BURNT.toByte(); w.trees[w.index(x, y)] = 0 }
        val american = w.roster.engines.first { it.key == "base:gp9" }.id
        val coach = w.roster.engines.first { it.key == "base:coach_streamline" }.id
        val ends = w.stations.withIndex().filter { (_, s) -> s != null && !s.waypoint }.map { it.index }
        val waypoint = w.stations.indexOfFirst { it?.waypoint == true }
        for ((k, depot) in listOf(0, 1, 2, 2).withIndex()) {
            w.apply(Command.BuyTrain(0, depot, american, coach, 4))
            val pair = ends.subList(minOf(depot, 2) * 2, minOf(depot, 2) * 2 + 2)
            val orders = if (depot == 2) intArrayOf(pair[0], waypoint, pair[1]) else intArrayOf(pair[0], pair[1])
            w.apply(Command.TrainOrder(0, k, Command.TrainOrder.ORDERS, orders))
            w.apply(Command.TrainOrder(0, k, Command.TrainOrder.START_STOP))
            repeat(200) { w.step() }
        }
        repeat(Calendar.TICKS_PER_DAY * 6) { w.step() }
        File(out, "save_2.ichg").writeBytes(Save.write(w))
        File(out, "save_2.info").writeText("day=${w.day}\nstartYear=1960\nmoney=${w.companies[0].money}\nwidth=64\nheight=64\nregion=0\n")
        println("spans ${w.spans.size} stations ${w.stations.size} " + w.trains.map { t -> stepTile(t!!.steps.lastOrNull() ?: 0) })
    }

    /** Roads: a highway with a bus and trucks, a level crossing, a streetcar line and a salt depot, in winter. */
    @Test
    fun roads() {
        val out = System.getenv("INTERCHANGE_SCENARIO_OUT") ?: return
        val w = World(NewGame(64, 64, 1, 1965, Region.MIXED, towns = 0, industries = 0))
        for (i in w.corners.indices) w.corners[i] = 3
        for (i in 0 until w.width * w.height) { w.trees[i] = 0; w.ground[i] = Ground.MEADOW.toByte() }
        w.shoreChanged()
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 6, 20, 50, 20, Surface.HIGHWAY, remove = false))
        w.apply(Command.Road(0, 6, 20, 6, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 6, 23, Track.YM))
        w.apply(Command.RoadStop(0, 10, 20))
        w.apply(Command.RoadStop(0, 46, 20))
        w.apply(Command.SaltDepot(0, 30, 21))
        w.apply(Command.Road(0, 20, 20, 20, 30, Surface.GRAVEL, remove = false))
        w.apply(Command.RoadOneWay(0, 20, 25))
        // Track across the highway on a level crossing, a train going back and forth.
        w.apply(Command.Rail(0, 35, 5, 35, 40, Track.Y, remove = false))
        w.apply(Command.Depot(0, 35, 41, Track.YM))
        w.apply(Command.Station(0, 35, 6, 35, 9, Track.Y))
        w.apply(Command.Station(0, 35, 36, 35, 39, Track.Y))
        val stations = w.stations.size
        w.apply(Command.BuyTrain(0, w.depots.size - 1, w.roster.engines.first { it.key == "base:gp9" }.id, w.roster.engines.first { it.key == "base:coach_streamline" }.id, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(stations - 2, stations - 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        // A streetcar line along y = 30.
        w.apply(Command.Road(0, 8, 30, 45, 30, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 8, 30, 45, 30, Surface.PAVED, remove = false, tram = true))
        w.apply(Command.Road(0, 8, 30, 8, 32, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 8, 30, 8, 32, Surface.PAVED, remove = false, tram = true))
        w.apply(Command.Garage(0, 8, 33, Track.YM))
        w.apply(Command.RoadStop(0, 12, 30))
        w.apply(Command.RoadStop(0, 42, 30))
        val garage = w.depots.size - 1
        fun buy(key: String, depot: Int, orders: IntArray) {
            w.apply(Command.BuyRoadVehicle(0, depot, w.roster.engines.first { it.key == "base:$key" }.id))
            val id = w.roadVehicles.size - 1
            w.apply(Command.RoadOrder(0, id, Command.TrainOrder.ORDERS, orders))
            w.apply(Command.RoadOrder(0, id, Command.TrainOrder.START_STOP))
            repeat(150) { w.step() }
        }
        val a = w.roadStops.getValue(w.index(10, 20))
        val b = w.roadStops.getValue(w.index(46, 20))
        for (key in listOf("new_look", "semi_van", "highway_coach", "tandem_dump")) buy(key, 0, intArrayOf(a, b))
        val c = w.roadStops.getValue(w.index(12, 30))
        val d = w.roadStops.getValue(w.index(42, 30))
        buy("pcc", garage, intArrayOf(c, d))
        repeat(Calendar.TICKS_PER_DAY * 4) { w.step() }
        File(out, "save_3.ichg").writeBytes(Save.write(w))
        File(out, "save_3.info").writeText("day=${w.day}\nstartYear=1965\nmoney=${w.companies[0].money}\nwidth=64\nheight=64\nregion=0\n")
        println("road vehicles " + w.roadVehicles.map { v -> "${roadStepTile(v!!.at) % 64},${roadStepTile(v.at) / 64} in${v.inDepot} r${v.route?.size}" })
    }
}
