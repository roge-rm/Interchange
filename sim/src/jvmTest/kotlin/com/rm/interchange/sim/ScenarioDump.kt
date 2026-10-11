package com.rm.interchange.sim

import java.io.File
import kotlin.test.Test

/**
 * Writes a small game to try on a phone: three towns along one line, two
 * trains, a hundred days in. Only runs with INTERCHANGE_SCENARIO_OUT set to
 * a folder; the save and its notes go there as save_1.ichg and save_1.info.
 */
class ScenarioDump {
    @Test
    fun threeTownsTwoTrains() {
        val out = System.getenv("INTERCHANGE_SCENARIO_OUT") ?: return
        val w = World(96, 64, 1, startYear = 1925)
        for (i in w.corners.indices) w.corners[i] = 3
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
        repeat(Calendar.TICKS_PER_DAY * 100) { w.step() }
        File(out, "save_1.ichg").writeBytes(Save.write(w))
        File(out, "save_1.info").writeText("day=${w.day}\nstartYear=1925\nmoney=${w.companies[0].money}\nwidth=96\nheight=64\nregion=0\n")
        println(w.stations.map { s -> s!!.name + " " + s.rating.toList() } + w.towns.map { it.approval[0] } + w.trains.map { it!!.profitThisYear })
    }
}
