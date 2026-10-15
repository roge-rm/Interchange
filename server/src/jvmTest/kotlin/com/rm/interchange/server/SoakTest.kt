package com.rm.interchange.server

import com.rm.interchange.net.Message
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Cargo
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Plows
import com.rm.interchange.sim.Prices
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.Surface
import com.rm.interchange.sim.Town
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.World
import com.rm.interchange.sim.human
import com.rm.interchange.sim.maxLoan
import com.rm.interchange.sim.price
import com.rm.interchange.sim.roadConnected
import com.rm.interchange.sim.roadEdges
import com.rm.interchange.sim.straightRoad
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.time.LocalTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A long run against a server already running, as INTERCHANGE_SOAK=host:port says. Two players join as two
 * companies and keep building roads, stops and garages and buying buses with orders, a third shares the first
 * one's company for a while, and the second leaves and comes back by client id. Fails on any resync, a lost
 * connection, or the two copies differing at the same frame at the end. Skipped without it.
 *
 *     INTERCHANGE_SOAK=127.0.0.1:45688 INTERCHANGE_SOAK_MINUTES=120 ./gradlew :server:jvmTest --tests '*SoakTest*' --rerun
 *
 * INTERCHANGE_SOAK_PASSWORD is the game's password. INTERCHANGE_SOAK_CONTROL, the server's control socket if it
 * can be reached, sets speed 8 for the run, pauses for the comparison and puts speed and pause back after.
 * The minute lines and the summary go to stdout and build/soak.log.
 */
class SoakTest {
    private val logFile = File("build/soak.log")

    private fun log(line: String) {
        val stamped = "${LocalTime.now().withNano(0)} $line"
        println(stamped)
        logFile.appendText(stamped + "\n")
    }

    @Test
    fun twoPlayersStayInStepForALongRun() {
        val soak = System.getenv("INTERCHANGE_SOAK") ?: return
        val host = soak.substringBeforeLast(':')
        val port = soak.substringAfterLast(':').toInt()
        val minutes = System.getenv("INTERCHANGE_SOAK_MINUTES")?.toDouble() ?: 120.0
        val password = System.getenv("INTERCHANGE_SOAK_PASSWORD") ?: ""
        logFile.parentFile?.mkdirs()
        logFile.writeText("")

        val control = System.getenv("INTERCHANGE_SOAK_CONTROL")?.let { Control(File(it)) }
        val before = control?.ask("status")
        if (control != null && before == null) log("Control socket ${control.socket} can't be reached, carrying on without it")
        val controlled = before != null
        if (controlled) {
            control!!.ask("speed", "8")
            control.ask("resume")
        }

        // Client ids differ each run, so a run never takes back a company from an earlier one.
        val run = Random.nextInt(1 shl 24).toString(16)
        val a = Client("A", host, port, password, "soak-a-$run", ::log)
        val b = Client("B", host, port, password, "soak-b-$run", ::log)
        val c = Client("C", host, port, password, "soak-c-$run", ::log)
        val problems = ArrayList<String>()
        var heapMost = 0L
        val start = System.nanoTime()
        val end = start + (minutes * 60e9).toLong()
        try {
            a.join(Message.NEW_COMPANY, 1)
            b.join(Message.NEW_COMPANY, 2)
            log("Joined ${a.remote.server} at $soak: A plays company ${a.company}, B company ${b.company}, for $minutes minutes" +
                if (controlled) ", speed 8" else "")
            val (startDay, startFrame) = a.read { it.world.day to it.frame }
            var nextMinute = start + 60_000_000_000L
            var minute = 0
            var coopIn = false
            var coopOut = false
            var bBack = false
            while (System.nanoTime() < end && problems.isEmpty()) {
                Thread.sleep(1000)
                val through = (System.nanoTime() - start).toDouble() / (end - start)
                if (!coopIn && through >= 0.3) {
                    coopIn = true
                    c.join(a.company, 3)
                    log("C joined A's company ${a.company}, playing ${c.company}")
                    if (c.company != a.company) problems += "C was given company ${c.company}, not A's ${a.company}"
                }
                if (coopIn && !coopOut && through >= 0.5) {
                    coopOut = true
                    c.leave()
                    log("C left")
                }
                if (!bBack && through >= 0.6) {
                    bBack = true
                    val was = b.company
                    b.leave()
                    log("B left")
                    Thread.sleep(5000)
                    b.join(was, 2)
                    log("B came back as company ${b.company}")
                    if (b.company != was) problems += "B came back as company ${b.company}, not $was"
                }
                for (k in listOf(a, b, c)) problems += k.problems()
                val heap = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                heapMost = maxOf(heapMost, heap)
                if (System.nanoTime() >= nextMinute) {
                    nextMinute += 60_000_000_000L
                    minute++
                    log("Minute $minute: " + a.read { g -> describe(g, listOf(a, b, c)) } + ", heap ${heap shr 20} MB")
                }
            }

            // Stop building, let the last checks settle, then compare the two copies at the same frame.
            for (k in listOf(a, b, c)) k.stopBot()
            Thread.sleep(3000)
            if (controlled) control!!.ask("pause")
            Thread.sleep(2000)
            val (endDay, endFrame) = a.read { it.world.day to it.frame }
            val sums = compare(a, b)
            Thread.sleep(3000)
            for (k in listOf(a, b, c)) problems += k.problems()
            val same = sums.size == 2 && sums["A"] == sums["B"]
            if (!same) problems += "checksums differ at the end: $sums"

            val year = { day: Int -> a.read { Calendar.year(it.world.startYear, day) } }
            log("Summary: ${"%.1f".format((System.nanoTime() - start) / 60e9)} minutes, game days $startDay to $endDay " +
                "(${endDay - startDay} days, ${year(startDay)} to ${year(endDay)}), frames $startFrame to $endFrame")
            for (k in listOf(a, b, c)) log("  ${k.label}: ${k.stats}, resyncs ${k.resyncs.get()}")
            log("  ${a.read { g -> describe(g, listOf(a, b, c)) }}, heap at most ${heapMost shr 20} MB")
            log("  Checksums at the same frame: $sums, ${if (same) "the same" else "DIFFERENT"}; the server's checksum for that frame " +
                if (a.resyncs.get() + b.resyncs.get() == 0) "matched both" else "did not match")
            if (problems.isNotEmpty()) log("Problems: $problems")
        } finally {
            for (k in listOf(a, b, c)) k.leave()
            if (controlled) {
                Regex("\"speed\":(\\d+)").find(before!!)?.let { control!!.ask("speed", it.groupValues[1]) }
                control!!.ask(if (before.contains("\"paused\":true")) "pause" else "resume")
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("; "))
    }

    /**
     * Each client's checksum just after the same frame, one the server also sends its checksum with, so a client that
     * differs from the server goes out of step there too.
     */
    private fun compare(a: Client, b: Client): Map<String, Long> {
        val at = maxOf(a.read { it.frame }, b.read { it.frame }) + 64
        val target = (at / Game.CHECK_FRAMES + 1) * Game.CHECK_FRAMES
        val sums = ConcurrentHashMap<String, Long>()
        for (k in listOf(a, b)) k.runner.onGameThread { g ->
            g.afterFrame = { f ->
                if (f.frame == target + 1) {
                    sums[k.label] = f.world.checksum() or 1
                    f.afterFrame = null
                }
            }
        }
        val until = System.nanoTime() + 60_000_000_000L
        while (sums.size < 2 && System.nanoTime() < until) Thread.sleep(100)
        log("Frame $target: $sums")
        return sums
    }

    /** The date, frames, and each player's company's money, loan and buses. */
    private fun describe(g: Game, clients: List<Client>): String {
        val w = g.world
        val date = "%d-%02d-%02d".format(Calendar.year(w.startYear, w.day), Calendar.month(w.startYear, w.day) + 1,
            Calendar.dayOfMonth(w.startYear, w.day))
        val companies = w.companies.filter { w.human(it.id) && !it.gone }.joinToString(", ") { co ->
            val buses = w.roadVehicles.count { it != null && it.owner == co.id }
            "${co.id} ${co.name}: \$${co.money} owing \$${co.loan}, $buses buses"
        }
        val resyncs = clients.joinToString(" ") { "${it.label} ${it.resyncs.get()}" }
        return "$date day ${w.day}, frame ${g.frame}, resyncs $resyncs; $companies"
    }
}

/** The dedicated server's control socket, one line in and one out; null back if it can't be reached. */
private class Control(val socket: File) {
    fun ask(vararg args: String): String? = try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { c ->
            c.connect(UnixDomainSocketAddress.of(socket.toPath()))
            val writer = Channels.newWriter(c, Charsets.UTF_8)
            writer.write(args.joinToString("\t") + "\n")
            writer.flush()
            BufferedReader(Channels.newReader(c, Charsets.UTF_8)).readLine()
        }
    } catch (e: IOException) {
        null
    }
}

/** What a player's commands came to: ones that took, ones that didn't, and ones sent to fail and whether they did. */
private class Stats {
    val sent = AtomicInteger()
    val worked = AtomicInteger()
    val failed = AtomicInteger()
    val refused = AtomicInteger()
    val wentThrough = AtomicInteger()
    val errors = AtomicInteger()
    val failedKinds = ConcurrentHashMap<String, AtomicInteger>()
    val throughKinds = ConcurrentHashMap<String, AtomicInteger>()

    override fun toString() = "sent ${sent.get()}, took ${worked.get()}, didn't take ${failed.get()} ${counts(failedKinds)}, " +
        "meant to fail and refused ${refused.get()}, meant to fail but took ${wentThrough.get()} ${counts(throughKinds)}" +
        if (errors.get() > 0) ", ${errors.get()} errors" else ""

    private fun counts(m: Map<String, AtomicInteger>) = m.entries.sortedBy { it.key }.joinToString(" ", "(", ")") { "${it.key} ${it.value.get()}" }
}

/** A player on its own connection and copy of the game, with a [Bot] building while it's in. */
private class Client(
    val label: String, val host: String, val port: Int, val password: String, val clientId: String,
    val log: (String) -> Unit,
) {
    lateinit var remote: Remote
    lateinit var runner: GameThread
    val resyncs = AtomicInteger()
    val stats = Stats()
    @Volatile private var bot: Bot? = null
    @Volatile private var leaving = true
    private var reported = 0
    private var joins = 0

    val company get() = remote.company

    fun join(join: Int, seed: Long) {
        remote = Remote.join(host, port, Message.Hello(0, clientId, "Soak $label", password, join))
        runner = GameThread({ Save.read(remote.welcome!!.save) }, remote)
        runner.start()
        leaving = false
        runner.onGameThread { g ->
            g.log = { why ->
                resyncs.incrementAndGet()
                log("$label out of step at frame ${g.frame}: $why")
            }
        }
        bot = Bot(this, Random(seed * 7919 + joins++)).also { it.start() }
    }

    fun stopBot() {
        bot?.stop()
        bot = null
    }

    fun leave() {
        if (leaving) return
        leaving = true
        stopBot()
        remote.leave()
        runner.stop()
    }

    /** Anything gone wrong since last asked: a lost connection or going out of step. */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        if (!leaving) remote.lost?.let { out += "$label lost its connection: $it" }
        val r = resyncs.get()
        if (r > reported) {
            reported = r
            out += "$label went out of step"
        }
        return out
    }

    /** [f] of the game, read on the game thread. */
    fun <T> read(f: (Game) -> T): T {
        val done = CompletableFuture<T>()
        runner.onGameThread { g -> done.complete(f(g)) }
        return done.get(30, TimeUnit.SECONDS)
    }
}

/**
 * Plays as a person would, a few commands a second: borrows, lays roads between its home town and those nearby,
 * puts stops and garages in them, buys buses and gives them orders, sends old ones home and sells them, and now
 * and then tries something that should be refused. Each command's effect is looked for a few seconds later.
 * Everything but the pacing runs on the game thread.
 */
private class Bot(private val client: Client, private val rng: Random) {
    private class Check(val kind: String, val until: Long, val meantToFail: Boolean, val took: (World) -> Boolean)

    private val checks = ArrayList<Check>()
    private var home = -1
    @Volatile private var running = true
    private val stats get() = client.stats

    private val thread = Thread({
        while (running) {
            try {
                Thread.sleep(250L + rng.nextInt(300))
            } catch (_: InterruptedException) {
                break
            }
            if (running) client.runner.onGameThread { g ->
                try {
                    if (running) step(g)
                } catch (e: Exception) {
                    stats.errors.incrementAndGet()
                    client.log("${client.label} bot error: $e")
                }
            }
        }
    }, "soak-bot-${client.label}").apply { isDaemon = true }

    fun start() = thread.start()

    fun stop() {
        running = false
        thread.interrupt()
    }

    private fun send(command: Command, kind: String, meantToFail: Boolean = false, took: (World) -> Boolean) {
        client.runner.request(command)
        stats.sent.incrementAndGet()
        checks += Check(kind, System.nanoTime() + CHECK_NANOS, meantToFail, took)
    }

    private fun settle(w: World) {
        val now = System.nanoTime()
        checks.removeAll { c ->
            when {
                c.took(w) -> {
                    if (c.meantToFail) {
                        stats.wentThrough.incrementAndGet()
                        stats.throughKinds.getOrPut(c.kind) { AtomicInteger() }.incrementAndGet()
                    } else stats.worked.incrementAndGet()
                    true
                }
                now > c.until -> {
                    if (c.meantToFail) stats.refused.incrementAndGet()
                    else {
                        stats.failed.incrementAndGet()
                        stats.failedKinds.getOrPut(c.kind) { AtomicInteger() }.incrementAndGet()
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun step(g: Game) {
        val w = g.world
        settle(w)
        val me = client.company
        val co = w.companies.getOrNull(me)?.takeIf { !it.gone } ?: return
        if (home < 0) home = pickHome(w) ?: return
        // Short of money, borrow first, as a player would.
        if (co.money < w.price(Prices.DEPOT) * 3 && co.loan < w.maxLoan()) {
            val loan = co.loan
            return send(Command.Loan(me, true), "borrow") { it.companies[me].loan > loan }
        }
        repeat(4) {
            val did = when (rng.nextInt(100)) {
                in 0..17 -> road(w, me)
                in 18..34 -> stop(w, me)
                in 35..44 -> garage(w, me)
                in 45..59 -> buy(w, me)
                in 60..79 -> orders(w, me)
                in 80..85 -> sendHome(w, me)
                in 86..87 -> repay(w, me)
                in 88..98 -> wrong(w, me)
                else -> rename(w, me)
            }
            if (did) return
        }
    }

    private fun pickHome(w: World): Int? {
        val big = w.towns.filter { it.population >= 150 }.ifEmpty { w.towns }
        return big.randomOrNull(rng)?.id
    }

    /** The home town or one of the five nearest it. */
    private fun someTown(w: World): Town {
        val h = w.towns[home]
        val near = w.towns.filter { it.id != h.id }.sortedBy { maxOf(kotlin.math.abs(it.x - h.x), kotlin.math.abs(it.y - h.y)) }.take(5)
        return if (near.isEmpty() || rng.nextInt(3) == 0) h else near.random(rng)
    }

    private fun repay(w: World, me: Int): Boolean {
        val co = w.companies[me]
        if (co.loan <= 0 || co.money < w.price(Prices.LOAN_STEP) * 3) return false
        val loan = co.loan
        send(Command.Loan(me, false), "repay") { it.companies[me].loan < loan }
        return true
    }

    /** A road from near the home town's middle to near another's, along x then y. */
    private fun road(w: World, me: Int): Boolean {
        val h = w.towns[home]
        val t = someTown(w)
        if (t.id == h.id) return false
        val sx = h.x + rng.nextInt(-2, 3)
        val sy = h.y + rng.nextInt(-2, 3)
        val ex = t.x + rng.nextInt(-2, 3)
        val ey = t.y + rng.nextInt(-2, 3)
        if (!w.inside(sx, sy) || !w.inside(ex, ey)) return false
        // Only tiles a road could go on: not houses, industries, water or stations.
        val tiles = (line(sx, sy, ex, sy) + line(ex, sy, ex, ey)).filter { (x, y) ->
            val i = w.index(x, y)
            w.house[i].toInt() == 0 && w.industryOf[i].toInt() == 0 && !w.isWater(x, y) && !w.stationTiles.containsKey(i) && !w.depotTiles.containsKey(i)
        }
        fun laid(x: World) = tiles.count { (x0, y0) -> x.roadEdges(x.index(x0, y0)) != 0 }
        val before = laid(w)
        if (before == tiles.size) return false
        val surface = Surface.entries(Calendar.year(w.startYear, w.day)).first()
        send(Command.Road(me, sx, sy, ex, sy, surface, remove = false), "road") { laid(it) > before }
        send(Command.Road(me, ex, sy, ex, ey, surface, remove = false), "road") { laid(it) > before }
        return true
    }

    private fun line(x0: Int, y0: Int, x1: Int, y1: Int): List<Pair<Int, Int>> {
        val n = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0))
        val dx = (x1 - x0).coerceIn(-1, 1)
        val dy = (y1 - y0).coerceIn(-1, 1)
        return (0..n).map { k -> (x0 + dx * k) to (y0 + dy * k) }
    }

    private fun myStops(w: World, me: Int) = w.roadStops.entries.filter { w.stations.getOrNull(it.value)?.owner == me }

    /** A drive-through stop on straight, flat road in a town, away from this company's other stops. */
    private fun stop(w: World, me: Int): Boolean {
        val t = someTown(w)
        val mine = myStops(w, me).map { it.key }
        val sites = ArrayList<Int>()
        for (y in t.y - TOWN_REACH..t.y + TOWN_REACH) for (x in t.x - TOWN_REACH..t.x + TOWN_REACH) {
            if (!w.inside(x, y)) continue
            val i = w.index(x, y)
            if (w.straightRoad(i) == 0 || w.track[i].toInt() != 0 || w.roadStops.containsKey(i) || !w.isFlat(x, y)) continue
            val owner = w.roadOwner[i].toInt()
            if (owner >= 0 && owner != me) continue
            if (mine.any { maxOf(kotlin.math.abs(it % w.width - x), kotlin.math.abs(it / w.width - y)) < 3 }) continue
            sites += i
        }
        val i = sites.randomOrNull(rng) ?: return false
        send(Command.RoadStop(me, i % w.width, i / w.width), "stop") { x -> x.roadStops[i]?.let { x.stations.getOrNull(it)?.owner } == me }
        return true
    }

    /** A garage by one of this company's stops, if it has fewer than one for every two stops. */
    private fun garage(w: World, me: Int): Boolean {
        val stops = myStops(w, me)
        if (stops.isEmpty() || w.depots.count { it != null && it.owner == me && it.road } > stops.size / 2) return false
        val s = stops.random(rng).key
        val sx = s % w.width
        val sy = s / w.width
        val sites = ArrayList<Pair<Int, Int>>()
        for (y in sy - 3..sy + 3) for (x in sx - 3..sx + 3) {
            if (!w.inside(x, y) || !w.isFlat(x, y) || w.isWater(x, y)) continue
            val i = w.index(x, y)
            if (w.track[i].toInt() != 0 || w.road[i].toInt() != 0 || w.stationTiles.containsKey(i) || w.depotTiles.containsKey(i) ||
                w.house[i].toInt() != 0 || w.industryOf[i].toInt() != 0
            ) continue
            for (e in 0 until 4) {
                val nx = x + Track.dx(e)
                val ny = y + Track.dy(e)
                if (!w.inside(nx, ny)) continue
                val n = w.index(nx, ny)
                if (w.roadEdges(n) == 0 || w.track[n].toInt() != 0 || w.roadStops.containsKey(n) || w.depotTiles.containsKey(n)) continue
                if (w.roadConnected(n, s)) sites += i to e
            }
        }
        val (i, e) = sites.randomOrNull(rng) ?: return false
        send(Command.Garage(me, i % w.width, i / w.width, e), "garage") { x -> x.depotTiles[i]?.let { x.depots.getOrNull(it)?.owner } == me }
        return true
    }

    /** A bus in one of this company's garages that's on road to one of its stops, up to [MOST_BUSES]. */
    private fun buy(w: World, me: Int): Boolean {
        val count = w.roadVehicles.count { it != null && it.owner == me }
        if (count >= MOST_BUSES) return false
        val stops = myStops(w, me).map { it.key }
        val depot = w.depots.filterNotNull().filter { it.owner == me && it.road && stops.any { s -> w.roadConnected(it.tile, s) } }
            .randomOrNull(rng) ?: return false
        val year = Calendar.year(w.startYear, w.day)
        val model = w.roster.availableRoad(year).filter { !it.tram && it.plow == Plows.NONE && Cargo.PASSENGERS in it.cargoes && it.capacity > 0 }
            .maxByOrNull { it.capacity.toLong() * it.speed } ?: return false
        send(Command.BuyRoadVehicle(me, depot.id, model.id, Cargo.PASSENGERS), "buy") { x ->
            x.roadVehicles.count { it != null && it.owner == me } > count
        }
        return true
    }

    /** Orders between two of this company's stations the bus can reach from its garage, then starts it. */
    private fun orders(w: World, me: Int): Boolean {
        val v = w.roadVehicles.filterNotNull().filter { it.owner == me && it.orders.isEmpty() }.randomOrNull(rng) ?: return false
        val from = w.depots.getOrNull(v.depot)?.tile ?: return false
        val stations = myStops(w, me).filter { w.roadConnected(from, it.key) }.map { it.value }.distinct()
        if (stations.size < 2) return false
        val (a, b) = stations.shuffled(rng).take(2)
        val id = v.id
        send(Command.RoadOrder(me, id, Command.TrainOrder.ORDERS, intArrayOf(a, b)), "orders") { x ->
            x.roadVehicles.getOrNull(id)?.orders?.map { it and 0xffff } == listOf(a, b)
        }
        send(Command.RoadOrder(me, id, Command.TrainOrder.START_STOP), "start") { x ->
            x.roadVehicles.getOrNull(id)?.let { it.running || !it.inDepot } ?: false
        }
        return true
    }

    /** Sells a bus that's come home, or sends one home if there are plenty. */
    private fun sendHome(w: World, me: Int): Boolean {
        val mine = w.roadVehicles.filterNotNull().filter { it.owner == me }
        mine.firstOrNull { it.inDepot && !it.running && it.orders.isNotEmpty() }?.let { v ->
            val id = v.id
            send(Command.RoadOrder(me, id, Command.TrainOrder.SELL), "sell") { x -> x.roadVehicles.getOrNull(id) == null }
            return true
        }
        if (mine.size < 8) return false
        val v = mine.filter { it.running && !it.inDepot && !it.toDepot }.randomOrNull(rng) ?: return false
        val id = v.id
        send(Command.RoadOrder(me, id, Command.TrainOrder.TO_DEPOT), "home") { x -> x.roadVehicles.getOrNull(id)?.let { it.toDepot || it.inDepot } ?: true }
        return true
    }

    /** Something that should be refused: building on or clearing another company's things, or renaming it. */
    private fun wrong(w: World, me: Int): Boolean {
        val others = w.companies.filter { it.id != me && !it.gone }
        when (rng.nextInt(4)) {
            0 -> {
                val stop = w.roadStops.entries.filter { (w.stations.getOrNull(it.value)?.owner ?: me) != me }.randomOrNull(rng) ?: return false
                val i = stop.key
                send(Command.Clear(me, i % w.width, i / w.width), "clear theirs", meantToFail = true) { x -> !x.roadStops.containsKey(i) }
            }
            1 -> {
                // A sample of the map, so big maps aren't searched whole.
                val sites = List(4000) { rng.nextInt(w.width * w.height) }.filter { i ->
                    val owner = w.roadOwner[i].toInt()
                    owner >= 0 && owner != me && w.straightRoad(i) != 0 && w.track[i].toInt() == 0 && !w.roadStops.containsKey(i) &&
                        w.isFlat(i % w.width, i / w.width)
                }
                val i = sites.randomOrNull(rng) ?: return false
                send(Command.RoadStop(me, i % w.width, i / w.width), "stop on theirs", meantToFail = true) { x -> x.roadStops.containsKey(i) }
            }
            2 -> {
                val depot = w.depots.filterNotNull().filter { it.owner != me && it.road }.randomOrNull(rng) ?: return false
                val model = w.roster.availableRoad(Calendar.year(w.startYear, w.day)).firstOrNull { !it.tram } ?: return false
                val from = w.roadVehicles.size
                send(Command.BuyRoadVehicle(me, depot.id, model.id), "buy in theirs", meantToFail = true) { x ->
                    (from until x.roadVehicles.size).any { x.roadVehicles[it]?.let { v -> v.owner == me && v.depot == depot.id } ?: false }
                }
            }
            else -> {
                // The server drops a command for a company that isn't the player's.
                val other = others.randomOrNull(rng) ?: return false
                val name = "Taken Over ${rng.nextInt(1000)}"
                send(Command.RenameCompany(other.id, name), "rename theirs", meantToFail = true) { x -> x.companies[other.id].name == name }
            }
        }
        return true
    }

    private fun rename(w: World, me: Int): Boolean {
        val name = "Soak ${client.label} Lines ${rng.nextInt(1000)}"
        send(Command.RenameCompany(me, name), "rename") { x -> x.companies[me].name == name }
        if (rng.nextInt(3) == 0) client.remote.say("${client.label} here, day ${w.day}")
        return true
    }

    companion object {
        /** How long a command has to show its effect, sent to the server and back. */
        const val CHECK_NANOS = 6_000_000_000L
        const val TOWN_REACH = 4
        const val MOST_BUSES = 40
    }
}
