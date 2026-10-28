package com.rm.interchange.sim

import java.io.File
import kotlin.test.Test

/**
 * Writes a saved game of trains running on curved track (docs/CURVES_PLAN.md, C4), curves_trains (.ichg with its .info),
 * into the folder -Pinterchange.curveTrains names. Skipped without it. Flat bare land in 1962: one train over a 90
 * degree turn at (21, 10), and one off a turnout eased back at (14, 34), down a diagonal line and eased into a straight
 * at (17, 38).
 */
class CurveTrainShowcase {
    @Test
    fun write() {
        val out = System.getProperty("interchange.curveTrains")?.let { File(it) } ?: return
        out.mkdirs()
        val w = World(NewGame(64, 64, 5, 1962, Region.MIXED, sea = 0, hills = 0, trees = 0, towns = 0, industries = 0, collisions = false))
        for (cy in 0..w.height) for (cx in 0..w.width) w.corners[w.corner(cx, cy)] = 3
        w.landShaped()
        for (i in w.trees.indices) w.trees[i] = 0
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        fun rail(x0: Int, y0: Int, x1: Int, y1: Int, piece: Int) = w.apply(Command.Rail(0, x0, y0, x1, y1, piece, remove = false))
        fun one(x: Int, y: Int, a: Int, b: Int) = rail(x, y, x, y, Track.pieceOf(a, b))
        // The turn.
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        rail(3, 10, 20, 10, Track.X)
        one(21, 10, Track.XM, Track.YP)
        rail(21, 11, 21, 24, Track.Y)
        w.apply(Command.Station(0, 5, 10, 8, 10, Track.X))
        w.apply(Command.Station(0, 21, 20, 21, 23, Track.Y))
        // The turnout, the diagonal and the ease.
        w.apply(Command.Depot(0, 2, 34, Track.XP))
        rail(3, 34, 40, 34, Track.X)
        one(14, 34, Track.XM, Track.YP)
        for (k in 0..2) {
            one(14 + k, 35 + k, Track.YM, Track.XP)
            one(15 + k, 35 + k, Track.XM, Track.YP)
        }
        rail(17, 38, 17, 50, Track.Y)
        w.apply(Command.Station(0, 5, 34, 8, 34, Track.X))
        w.apply(Command.Station(0, 17, 45, 17, 48, Track.Y))
        w.apply(Command.Station(0, 36, 34, 39, 34, Track.X))
        val year = 1962
        val engine = w.roster.available(year, true).filter { it.kind == VehicleKind.ENGINE && it.traction == Traction.DIESEL }.maxBy { it.speed }
        val wagon = w.roster.available(year, false).first { it.carries.any { c -> c.lowercase().startsWith("passenger") } }
        for ((depot, stops) in listOf(0 to intArrayOf(0, 1), 1 to intArrayOf(2, 3))) {
            val id = w.trains.size
            w.apply(Command.BuyTrain(0, depot, engine.id, wagon.id, 4))
            w.apply(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, stops))
            w.apply(Command.TrainOrder(0, id, Command.TrainOrder.START_STOP))
        }
        w.networkChanged()
        // On into July, for bare ground, with the trains on their way.
        while (Calendar.month(1962, w.day) < 6) w.step()
        val g = w.settings
        File(out, "curves_trains.ichg").writeBytes(Save.write(w, Save.VERSION))
        File(out, "curves_trains.info").writeText("day=${w.day}\nstartYear=${g.startYear}\nmoney=${w.companies[0].money}\nwidth=${g.width}\nheight=${g.height}\nregion=${g.region.ordinal}\n")
        val notes = StringBuilder("engine ${engine.key} wagon ${wagon.key}\n")
        for (t in w.trains) if (t != null) notes.append("train ${t.id} at ${t.steps.lastOrNull()?.let { stepTile(it) % w.width to stepTile(it) / w.width }} running ${t.running}\n")
        File(out, "curves_trains.txt").writeText(notes.toString())
    }
}
