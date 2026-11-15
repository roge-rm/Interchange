package com.rm.interchange.sim

import com.rm.interchange.sim.Track.E
import com.rm.interchange.sim.Track.N
import com.rm.interchange.sim.Track.S
import com.rm.interchange.sim.Track.W
import com.rm.interchange.sim.Track.X
import com.rm.interchange.sim.Track.XM
import com.rm.interchange.sim.Track.XP
import com.rm.interchange.sim.Track.Y
import com.rm.interchange.sim.Track.YM
import com.rm.interchange.sim.Track.YP
import java.io.File
import kotlin.test.Test

/**
 * Builds the station layouts of OpenTTD's railway station manual on flat maps and runs trains through them: ladder
 * throats, a three-way split, a terminus, drive-thru, waiting bays, a double entrance on presignals, a bag station and
 * a bridge over a throat. The ring back round has a signal every few tiles, so it holds more trains than the station;
 * ladderSparse leaves them out. Prints, for each, what the game refused to build, which platforms trains called at, where
 * they stood held and for how long, depot visits and any train that stood still too long, then a PASS or FAIL line
 * against what station routing has to do for it. Run with
 * -Pinterchange.layouts=all (or a comma list of names); the table also goes to sim/build/layouts.txt. Skipped without it.
 */
class StationLayoutRun {
    private val out = StringBuilder()
    private fun say(s: String) { println(s); out.appendLine(s) }

    private val scenarios = linkedMapOf(
        "ladder" to { ladder(presignals = false) },
        "ladderSparse" to { ladder(presignals = false, dense = false) },
        "ladderPre" to { ladder(presignals = true) },
        "split" to ::split,
        "terminus" to ::terminus,
        "drivethru" to ::driveThru,
        "bays" to ::bays,
        "double" to ::doubleEntrance,
        "bag" to ::bag,
        "bridge" to ::bridge,
    )

    @Test
    fun run() {
        val asked = System.getProperty("interchange.layouts") ?: return
        val names = asked.split(',').map { it.trim() }
        for ((name, f) in scenarios) {
            if ("all" !in names && "true" !in names && name !in names) continue
            try { f() } catch (e: Throwable) { say("$name: EXCEPTION $e"); e.printStackTrace() }
        }
        File("build/layouts.txt").writeText(out.toString())
    }

    private inner class Lab(val name: String, width: Int = 64, height: Int = 48) {
        val w = World(NewGame(width, height, 1, 1900, towns = 0, industries = 0, weather = false, collisions = true))
        val problems = ArrayList<String>()
        val axis = HashMap<Int, Int>()
        val engine = w.roster.engines.first { it.key == "base:american" }.id
        val wagon = w.roster.engines.first { it.key == "base:coach_wood" }.id

        init {
            for (i in w.corners.indices) w.corners[i] = 3
            w.companies[0].money = 1_000_000_000
        }

        fun rail(sx: Int, sy: Int, ex: Int, ey: Int, p: Int) {
            w.apply(Command.Rail(0, sx, sy, ex, ey, p, remove = false))
            val line = Track.line(w, sx, sy, ex, ey, p) ?: run { problems += "line ($sx,$sy)-($ex,$ey) of $p makes no sense"; return }
            for (e in line) if (w.track[e / 64].toInt() and (e % 64) == 0) problems += "piece ${e % 64} refused at (${e / 64 % w.width},${e / 64 / w.width}) holding ${w.track[e / 64]}"
        }

        fun piece(x: Int, y: Int, p: Int) = rail(x, y, x, y, p)

        fun sig(x: Int, y: Int, p: Int, facing: Int, kind: Int, oneWay: Boolean = false) {
            repeat(if (oneWay) 2 else 1) { w.apply(Command.Signal(0, x, y, p, facing, kind)) }
            val s = w.signalAt(w.index(x, y), p)
            val want = if (!oneWay) kind else if (kind == Signals.PATH) Signals.ONE_WAY_PATH else Signals.ONE_WAY
            if (s < 0 || Signals.kind(s) != want) problems += "signal $kind on ($x,$y) piece $p refused: track ${w.trackAt(x, y)}, fits ${w.signalFits(x, y, p)}"
        }

        fun station(x0: Int, y0: Int, x1: Int, y1: Int, ax: Int): Int {
            w.apply(Command.Station(0, x0, y0, x1, y1, ax))
            val id = w.stationTiles[w.index(x0, y0)] ?: run { problems += "station at ($x0,$y0) refused"; return -1 }
            for (y in y0..y1) for (x in x0..x1) if (w.stationTiles[w.index(x, y)] != id) problems += "($x,$y) not a platform of station $id"
            axis[id] = ax
            return id
        }

        fun waypoint(x: Int, y: Int): Int {
            w.apply(Command.Waypoint(0, x, y))
            return w.stationTiles[w.index(x, y)] ?: run { problems += "waypoint at ($x,$y) refused"; -1 }
        }

        fun depot(x: Int, y: Int, edge: Int): Int {
            w.apply(Command.Depot(0, x, y, edge))
            return w.depotTiles[w.index(x, y)] ?: run { problems += "depot at ($x,$y) refused"; -1 }
        }

        fun train(depot: Int, vararg orders: Int): Train {
            w.apply(Command.BuyTrain(0, depot, engine, wagon, 2))
            val id = w.trains.indexOfLast { it != null }
            w.apply(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, orders))
            w.apply(Command.TrainOrder(0, id, Command.TrainOrder.START_STOP))
            return w.trains[id]!!
        }

        /**
         * The return half of a loop: the top row y = 3 run west from [xR] to [xL] with station B and the depot on it,
         * the right column run north from [yB], and their corners, all with one-way path signals. With [withC] a
         * second station C at the west end of the top row. Gives B, C (or -1), the depot and a waypoint on the top row.
         */
        fun ring(xL: Int, xR: Int, yB: Int, withC: Boolean = false, dense: Boolean = true): IntArray {
            rail(xL + 1, 3, xR - 1, 3, X)
            piece(xL, 3, S); piece(xR, 3, W); piece(xR, yB, N)
            rail(xR, 4, xR, yB - 1, Y)
            val d = depot(xR - 4, 1, YP)
            piece(xR - 4, 2, Y); piece(xR - 4, 3, N)
            val b = station(xR - 14, 3, xR - 10, 3, X)
            val c = if (withC) station(xL + 4, 3, xL + 8, 3, X) else -1
            sig(xR - 4, 2, Y, YM, Signals.PATH)
            for (x in intArrayOf(xR - 7, xR - 16, xL + 2) + if (withC) intArrayOf(xL + 10) else IntArray(0)) sig(x, 3, X, XP, Signals.PATH, true)
            sig(xR, 6, Y, YP, Signals.PATH, true)
            val wp = waypoint(xR - 20, 3)
            // More blocks round the ring, every 5 tiles where the track takes one, so it holds more trains than the station.
            if (dense) for (x in xL + 7..xR - 19 step 5) if (w.signalFits(x, 3, X) && w.signalAt(w.index(x, 3), X) < 0) sig(x, 3, X, XP, Signals.PATH, true)
            if (dense) for (y in 4 until yB step 3) if (y != 6) sig(xR, y, Y, YP, Signals.PATH, true)
            return intArrayOf(b, c, d, wp)
        }

        /** The left column, run south from the top row to the bottom row at [yB]. */
        fun leftColumn(xL: Int, yB: Int) {
            rail(xL, 4, xL, yB - 1, Y)
            piece(xL, yB, E)
            sig(xL, 6, Y, YM, Signals.PATH, true)
        }

        /** One-way path signals every 4 tiles along row [y] from [x0] to [x1], for eastbound trains. */
        fun blocks(y: Int, x0: Int, x1: Int) {
            for (x in x0..x1 step 4) sig(x, y, X, XM, Signals.PATH, true)
        }

        /** The diagonals of a ladder fanning out from row [rows].first eastbound, one row further each tile, starting at [a]. */
        fun entryLadder(a: Int, rows: IntRange) {
            val top = rows.first
            piece(a, top, W)
            for (y in top + 1..rows.last) {
                piece(a + y - top - 1, y, E)
                if (y != rows.last) piece(a + y - top, y, W)
            }
        }

        /** The mirror of [entryLadder]: rows merging eastbound into row [rows].first, ending at [b]. */
        fun exitLadder(b: Int, rows: IntRange) {
            val top = rows.first
            piece(b, top, S)
            for (y in top + 1..rows.last) {
                piece(b - (y - top - 1), y, N)
                if (y != rows.last) piece(b - (y - top), y, S)
            }
        }

        fun dump(x0: Int, y0: Int, x1: Int, y1: Int) {
            say("$name map, x $x0..$x1, y $y0..$y1 (track byte in hex, PP platform, DD depot, * signal)")
            for (y in y0..y1) {
                val sb = StringBuilder("  %2d ".format(y))
                for (x in x0..x1) {
                    val i = w.index(x, y)
                    val t = w.track[i].toInt() and 63
                    val signalled = Track.PIECES.any { w.signalAt(i, it) >= 0 }
                    sb.append(when {
                        w.depotTiles.containsKey(i) -> "DD"
                        w.stationTiles.containsKey(i) -> "PP"
                        t == 0 -> " ."
                        signalled -> "*" + "%x".format(t and 15).takeLast(1)
                        else -> "%02x".format(t)
                    })
                }
                say(sb.toString())
            }
        }

        /** What stops [t], from the sim's own look ahead: the free distance and the reason. */
        fun walk(t: Train): String {
            val m = Class.forName("com.rm.interchange.sim.TrainsKt").declaredMethods.first { it.name == "walkAhead" }
            m.isAccessible = true
            return m.invoke(null, w, t, BRAKE, null).toString()
        }

        fun front(t: Train) = t.steps.lastOrNull()?.let { stepTile(it) } ?: -1
        fun xy(i: Int) = "(${i % w.width},${i / w.width})"

        /**
         * Runs [days] days and says which platform of each station each train called at, the tiles trains stood held on
         * (days in all), depot visits, the longest any train stood still away from a platform and its depot, crashes,
         * and, for [watch], how often a train's front came onto each of those tiles.
         */
        fun run(trains: List<Train>, days: Int, watch: List<Int> = emptyList(), groups: Map<String, List<Train>> = emptyMap()): Outcome {
            for (p in problems) say("$name: REFUSED $p")
            val calls = HashMap<Int, java.util.TreeMap<String, Int>>()
            val callsBy = HashMap<Int, Int>()
            val held = HashMap<Int, Int>()
            val depotIn = HashMap<Int, Int>()
            val watched = HashMap<Int, Int>()
            val still = HashMap<Int, Int>()
            val where = HashMap<Int, String>()
            val visits = HashMap<Int, MutableSet<Int>>()
            var worst = 0
            var worstAt = ""
            var stuckNow = 0
            val wasWaiting = HashMap<Int, Boolean>()
            val wasIn = HashMap<Int, Boolean>()
            val lastFront = HashMap<Int, Int>()
            var mostCalling = 0
            val ticks = days * Calendar.TICKS_PER_DAY
            for (tick in 0 until ticks) {
                w.step()
                mostCalling = maxOf(mostCalling, trains.count { w.trains.getOrNull(it.id) === it && it.waiting > 0 && !it.inDepot && w.stationTiles.containsKey(front(it)) })
                for (t in trains) {
                    if (w.trains.getOrNull(t.id) !== t) continue
                    val f = front(t)
                    val waiting = t.waiting > 0 && !t.inDepot
                    if (waiting && wasWaiting[t.id] != true) {
                        val st = w.stationTiles[f]
                        if (st != null) {
                            val label = if (axis[st] == Y) "x${f % w.width}" else "y${f / w.width}"
                            calls.getOrPut(st) { java.util.TreeMap() }.merge(label, 1, Int::plus)
                            callsBy.merge(t.id, 1, Int::plus)
                        }
                    }
                    wasWaiting[t.id] = waiting
                    if (t.inDepot && wasIn[t.id] == false) depotIn.merge(t.depot, 1, Int::plus)
                    wasIn[t.id] = t.inDepot
                    if (!t.inDepot && t.held && t.speed == 0) held.merge(f, 1, Int::plus)
                    if (f >= 0 && f in watch && lastFront[t.id] != f) watched.merge(f, 1, Int::plus)
                    if (f >= 0) w.stationTiles[f]?.let { visits.getOrPut(t.id) { HashSet() } += it }
                    lastFront[t.id] = f
                    val here = "${t.steps.lastOrNull()}:${t.nose}"
                    if (waiting || t.inDepot || where[t.id] != here) still[t.id] = 0 else still.merge(t.id, 1, Int::plus)
                    where[t.id] = here
                    val s = still[t.id] ?: 0
                    if (s > worst) { worst = s; worstAt = "train ${t.id} at ${xy(f)}" }
                }
            }
            stuckNow = trains.count { (still[it.id] ?: 0) > 30 * Calendar.TICKS_PER_DAY }
            for (t in trains) if ((still[t.id] ?: 0) > 30 * Calendar.TICKS_PER_DAY) say("$name: standing train ${t.id} front ${xy(front(t))} held ${t.held} target ${t.target} route ${t.route?.size} speed ${t.speed} turnRound ${t.turnRound} stuck ${t.stuck} aside ${t.aside} routeAt ${t.routeAt} route ${t.route?.joinToString(" ") { xy(stepTile(it)) }} ahead ${walk(t)} broken ${t.brokenFor} running ${t.running} waiting ${t.waiting} headOn ${t.headOn} holds ${w.reserved.entries.filter { it.value == t.id }.map { xy(it.key / 8) }.distinct()}")
            // A train standing at a red signal with a route but not held never plans again; drop its route and see if that frees things.
            val unheld = trains.filter { (still[it.id] ?: 0) > 30 * Calendar.TICKS_PER_DAY && !it.held && it.route != null }
            val unheldFired = unheld.isNotEmpty()
            val crashes = w.crashes.list.size
            if (unheld.isNotEmpty()) {
                for (t in unheld) t.route = null
                val before = trains.map { "${it.steps.lastOrNull()}:${it.nose}" }
                repeat(10 * Calendar.TICKS_PER_DAY) { w.step() }
                val moved = trains.indices.count { "${trains[it].steps.lastOrNull()}:${trains[it].nose}" != before[it] }
                say("$name: routes of ${unheld.map { it.id }} dropped by hand: 10 days on, ${unheld.joinToString { "train ${it.id} at ${xy(front(it))} held ${it.held} ahead ${walk(it)} new route ${it.route?.joinToString(" ") { r -> xy(stepTile(r)) }} plan ${w.planRoute(it)?.let { p -> "behind ${p.behind} " + p.steps.joinToString(" ") { r -> xy(stepTile(r)) } }} lostUntil-tick ${it.lostUntil - w.tick} order ${it.order} orders ${it.orders}" }}, $moved/${trains.size} trains moved")
            }
            for ((st, m) in calls.toSortedMap()) say("$name: station $st calls by platform $m")
            say("$name: calls per train ${trains.map { callsBy[it.id] ?: 0 }}; most calling at once $mostCalling")
            say("$name: held days by tile ${held.entries.sortedByDescending { it.value }.take(8).joinToString { "${xy(it.key)} %.1f".format(it.value.toDouble() / Calendar.TICKS_PER_DAY) }}")
            say("$name: depot entries by depot $depotIn")
            if (watch.isNotEmpty()) say("$name: fronts onto ${watch.joinToString { "${xy(it)} ${watched[it] ?: 0}" }}")
            for ((g, ts) in groups) say("$name: $g visited stations ${ts.map { visits[it.id]?.sorted() ?: emptyList() }}")
            say("$name: longest standing still ${"%.1f".format(worst.toDouble() / Calendar.TICKS_PER_DAY)} days ($worstAt); still over 30 days at the end: $stuckNow; crashes ${w.crashes.list.size}; trains left ${trains.count { w.trains.getOrNull(it.id) === it }}/${trains.size}")
            return Outcome(calls.values.flatMap { it.entries }.associate { it.key to it.value }, callsBy.values.sum(), worst.toDouble() / Calendar.TICKS_PER_DAY, stuckNow, unheldFired, crashes, watch.associateWith { watched[it] ?: 0 }, mostCalling)
        }

        /** Says PASS or FAIL for the layout, with the checks that failed. */
        fun verdict(checks: List<Pair<String, Boolean>>) {
            val failed = checks.filter { !it.second }.map { it.first }
            say(if (failed.isEmpty()) "$name: PASS ${checks.joinToString { it.first }}" else "$name: FAIL ${failed.joinToString()}")
        }

        /**
         * As before free platform choice or better: as many calls as [calls], none standing still longer than
         * [longest] days, nothing still standing at the end and no crashes.
         */
        fun noWorse(o: Outcome, calls: Int, longest: Double) = verdict(listOf(
            "calls ${o.calls} >= $calls" to (o.calls >= calls),
            "longest standing ${"%.1f".format(o.worstDays)} <= $longest days" to (o.worstDays <= longest),
            "none still standing (${o.stuckNow})" to (o.stuckNow == 0),
            "no crashes (${o.crashes})" to (o.crashes == 0),
        ))
    }

    /** What a run gave: calls by platform label, calls in all, the longest standing still in days and the rest. */
    private class Outcome(val byPlatform: Map<String, Int>, val calls: Int, val worstDays: Double, val stuckNow: Int, val unheld: Boolean, val crashes: Int, val watched: Map<Int, Int>, val mostCalling: Int)

    /** Six through platforms fed by a diagonal ladder off one eastbound line and merged out by another. */
    private fun ladder(presignals: Boolean, dense: Boolean = true) {
        val lab = Lab(if (presignals) "ladderPre" else if (dense) "ladder" else "ladderSparse")
        with(lab) {
            val (_, _, d, wp) = ring(4, 56, 10, dense = dense).toList()
            leftColumn(4, 10)
            rail(5, 10, 55, 10, X)
            entryLadder(12, 10..15)
            exitLadder(32, 10..15)
            for (y in 11..15) rail(12 + y - 10, y, 32 - (y - 10), y, X)
            val a = station(20, 10, 24, 15, X)
            say("$name: plain diagonal (12,11) E takes a signal: ${w.signalFits(12, 11, E)}; switch (13,11) X+W: ${w.signalFits(13, 11, X)}")
            if (presignals) {
                sig(8, 10, X, XM, Signals.ENTRY)
                for (y in 10..15) sig(19, y, X, XM, Signals.EXIT)
                for (y in 10..15) sig(25, y, X, XM, Signals.TWO_WAY, true)
                sig(40, 10, X, XM, Signals.TWO_WAY, true)
            } else {
                sig(8, 10, X, XM, Signals.PATH, true)
                for (y in 10..15) sig(25, y, X, XM, Signals.PATH, true)
                sig(40, 10, X, XM, Signals.PATH, true)
            }
            if (dense) blocks(10, 44, 52)
            dump(4, 1, 44, 16)
            val ts = (0 until 8).map { train(d, Order.withWait(a, 16), wp) }
            val o = run(ts, 200)
            when {
                presignals -> verdict(listOf(
                    "platforms used ${o.byPlatform.size} >= 4" to (o.byPlatform.size >= 4),
                    "nothing standing over 30 days (${"%.1f".format(o.worstDays)})" to (o.worstDays <= 30.0),
                ))
                // Trains take the nearest free platform, as in OpenTTD, so as many are used as are ever needed at once.
                dense -> verdict(listOf(
                    "platforms used ${o.byPlatform.size} >= most calling at once ${o.mostCalling}" to (o.byPlatform.size >= o.mostCalling),
                    "nothing standing over 30 days (${"%.1f".format(o.worstDays)})" to (o.worstDays <= 30.0),
                ))
                else -> verdict(listOf(
                    "none still standing over 30 days (${o.stuckNow})" to (o.stuckNow == 0),
                    "unheld branch never fired" to !o.unheld,
                ))
            }
        }
    }

    /** A Y+N+E tile taking trains from the north three ways, each to its own platform, merging out east. */
    private fun split() {
        with(Lab("split")) {
            val (_, _, d, wp) = ring(10, 56, 8).toList()
            rail(10, 4, 10, 9, Y)
            piece(10, 8, N); piece(10, 8, E)
            piece(9, 8, S); piece(9, 9, E); piece(10, 10, E)
            rail(11, 8, 55, 8, X)
            rail(10, 9, 31, 9, X)
            rail(11, 10, 30, 10, X)
            exitLadder(32, 8..10)
            val a = station(22, 8, 26, 10, X)
            sig(10, 5, Y, YM, Signals.PATH, true)
            for (y in 8..10) sig(27, y, X, XM, Signals.PATH, true)
            sig(40, 8, X, XM, Signals.PATH, true)
            blocks(8, 44, 52)
            say("$name: split (10,8) holds ${w.trackAt(10, 8)} (Y+N+E = ${Y or N or E}), crossing (10,9) holds ${w.trackAt(10, 9)}")
            dump(8, 1, 40, 11)
            val ts = (0 until 5).map { train(d, Order.withWait(a, 16), wp) }
            noWorse(run(ts, 200, watch = listOf(w.index(11, 8), w.index(11, 9), w.index(11, 10))), 11, 19.3)
        }
    }

    /**
     * A terminus of four dead-end platforms facing west: in along row 10 by a ladder, out by a second ladder to row 14,
     * which turns back round to row 10 past station B. An escape depot sits on a spur off the approach.
     */
    private fun terminus() {
        with(Lab("terminus")) {
            rail(5, 10, 27, 10, X)
            rail(5, 14, 17, 14, X)
            piece(4, 14, E); rail(4, 11, 4, 13, Y); piece(4, 10, S)
            entryLadder(14, 10..13)
            for (y in 11..13) rail(14 + y - 10, y, 27, y, X)
            for (y in 10..13) piece(21 - (y - 10), y, S)
            for (y in 11..14) piece(21 - (y - 11), y, N)
            val a = station(23, 10, 27, 13, X)
            station(8, 14, 12, 14, X)
            val wp = waypoint(16, 14)
            val d = depot(6, 16, YM)
            piece(6, 15, Y); piece(6, 14, W)
            val escape = depot(11, 8, YP)
            piece(11, 9, Y); piece(11, 10, N); piece(11, 10, E)
            sig(8, 10, X, XM, Signals.PATH, true)
            for (y in 10..13) sig(22, y, X, XP, Signals.PATH)
            sig(14, 14, X, XP, Signals.PATH, true)
            sig(6, 15, Y, YP, Signals.PATH)
            sig(11, 9, Y, YM, Signals.PATH)
            sig(4, 12, Y, YP, Signals.PATH, true)
            say("$name: escape depot $escape, spur tile (11,10) holds ${w.trackAt(11, 10)}")
            dump(3, 7, 28, 16)
            val ts = (0 until 6).map { train(d, Order.withWait(a, 12), wp) }
            noWorse(run(ts, 200), 11, 73.0)
            say("$name: the longest wait has its own goal in S5, with the escape depot")
        }
    }

    /** A main line with two side platforms: some trains stop, the rest run B to C past on the main. */
    private fun driveThru() {
        with(Lab("drivethru")) {
            val (b, c, d, wp) = ring(4, 56, 10, withC = true).toList()
            leftColumn(4, 10)
            rail(5, 10, 55, 10, X)
            entryLadder(14, 10..12)
            exitLadder(34, 10..12)
            for (y in 11..12) rail(14 + y - 10, y, 34 - (y - 10), y, X)
            val a = station(22, 11, 26, 12, X)
            sig(8, 10, X, XM, Signals.PATH, true)
            for (y in 10..12) sig(27, y, X, XM, Signals.PATH, true)
            sig(40, 10, X, XM, Signals.PATH, true)
            blocks(10, 44, 52)
            dump(4, 1, 44, 13)
            val stop = (0 until 3).map { train(d, Order.withWait(a, 16), wp) }
            val pass = (0 until 3).map { train(d, Order.withWait(b, 0), Order.withWait(c, 0)) }
            noWorse(run(stop + pass, 200, groups = mapOf("stopping" to stop, "non-stop" to pass)), 20, 16.0)
        }
    }

    /** Three holding tracks before a two-platform station. */
    private fun bays() {
        with(Lab("bays")) {
            val (_, _, d, wp) = ring(4, 56, 10).toList()
            leftColumn(4, 10)
            rail(5, 10, 55, 10, X)
            entryLadder(12, 10..12)
            exitLadder(26, 10..12)
            for (y in 11..12) rail(12 + y - 10, y, 26 - (y - 10), y, X)
            entryLadder(30, 10..11)
            exitLadder(44, 10..11)
            rail(31, 11, 43, 11, X)
            val a = station(34, 10, 38, 11, X)
            sig(8, 10, X, XM, Signals.PATH, true)
            for (y in 10..12) sig(21, y, X, XM, Signals.PATH, true)
            for (y in 10..11) sig(39, y, X, XM, Signals.PATH, true)
            sig(48, 10, X, XM, Signals.PATH, true)
            sig(52, 10, X, XM, Signals.PATH, true)
            dump(4, 1, 50, 13)
            val ts = (0 until 6).map { train(d, Order.withWait(a, 16), wp) }
            val o = run(ts, 200, watch = (10..12).map { w.index(20, it) })
            verdict(listOf(
                "fronts onto (20,11) ${o.watched[w.index(20, 11)]}" to ((o.watched[w.index(20, 11)] ?: 0) > 0),
                "fronts onto (20,12) ${o.watched[w.index(20, 12)]}" to ((o.watched[w.index(20, 12)] ?: 0) > 0),
            ))
        }
    }

    /** Two platform groups, each with its own entry and combo signal, behind one entry presignal. */
    private fun doubleEntrance() {
        with(Lab("double")) {
            val (_, _, d, wp) = ring(4, 56, 10).toList()
            leftColumn(4, 10)
            rail(5, 10, 55, 10, X)
            piece(12, 10, W); piece(12, 11, E); piece(13, 11, W); piece(13, 12, E); piece(14, 12, W); piece(14, 13, E)
            entryLadder(18, 10..11)
            entryLadder(18, 13..14)
            exitLadder(36, 10..14)
            rail(19, 11, 35, 11, X)
            rail(15, 13, 33, 13, X)
            rail(19, 14, 32, 14, X)
            // Built apart the groups come out as two stations, so one rect over both, with an unused platform on row 12, makes one.
            with(Lab("doubleApart")) { say("$name: groups built apart are separate stations: ${station(22, 10, 26, 11, X) != station(22, 13, 26, 14, X)}") }
            val a = station(22, 10, 26, 14, X)
            sig(8, 10, X, XM, Signals.ENTRY)
            sig(16, 10, X, XM, Signals.COMBO); sig(16, 13, X, XM, Signals.COMBO)
            for (y in intArrayOf(10, 11, 13, 14)) sig(21, y, X, XM, Signals.EXIT)
            for (y in intArrayOf(10, 11, 13, 14)) sig(27, y, X, XM, Signals.TWO_WAY, true)
            sig(40, 10, X, XM, Signals.TWO_WAY, true)
            blocks(10, 44, 52)
            dump(4, 1, 44, 15)
            val ts = (0 until 7).map { train(d, Order.withWait(a, 16), wp) }
            val o = run(ts, 200)
            val second = (o.byPlatform["y13"] ?: 0) + (o.byPlatform["y14"] ?: 0)
            verdict(listOf(
                "second group called at $second > 0" to (second > 0),
                "first group called at ${(o.byPlatform["y10"] ?: 0) + (o.byPlatform["y11"] ?: 0)} > 0" to ((o.byPlatform["y10"] ?: 0) + (o.byPlatform["y11"] ?: 0) > 0),
                "nothing standing over 30 days (${"%.1f".format(o.worstDays)})" to (o.worstDays <= 30.0),
            ))
        }
    }

    /** A dead-end platform on a stem off a through line, joined both ways by an X+W+S tile, so trains back out and go on east. */
    private fun bag() {
        with(Lab("bag")) {
            val (_, _, d, wp) = ring(4, 56, 10).toList()
            leftColumn(4, 10)
            rail(5, 10, 55, 10, X)
            piece(15, 10, W); piece(15, 10, S)
            rail(15, 11, 15, 16, Y)
            val a = station(15, 12, 15, 16, Y)
            sig(8, 10, X, XM, Signals.PATH, true)
            sig(15, 11, Y, YP, Signals.PATH)
            sig(20, 10, X, XM, Signals.PATH, true)
            blocks(10, 28, 52)
            say("$name: wye (15,10) holds ${w.trackAt(15, 10)} (X+W+S = ${X or W or S})")
            dump(4, 1, 24, 17)
            val ts = (0 until 3).map { train(d, Order.withWait(a, 6), wp) }
            noWorse(run(ts, 150, watch = listOf(w.index(14, 10), w.index(16, 10))), 5, 12.2)
        }
    }

    /** Whether a bridge can carry one line of a throat over another on flat land. */
    private fun bridge() {
        with(Lab("bridge")) {
            rail(5, 20, 14, 20, X)
            rail(26, 20, 35, 20, X)
            rail(20, 10, 20, 30, Y)
            val c = Command.Bridge(0, 14, 20, 26, 20, Spans.TRUSS)
            val cost = w.bridgeCost(w.companies[0], c)
            w.apply(c)
            say("$name: flat-land bridge over track: cost $cost, spans ${w.spans.count { it != null }}, deck tiles ${w.deck.size}")
            for (p in problems) say("$name: REFUSED $p")
            say("$name: no S1 criteria")
        }
    }
}
