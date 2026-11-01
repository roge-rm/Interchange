package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Aircraft taxiing between runway, stands and hangar: round trips, sharing the runway and stands, saves. */
class TaxiTest {
    /**
     * Flat land a step up with a lake from x = 20 to 29, y = 50 to 59, and two airports of [kind]: along x at (5, 5)
     * and along y at (40, 20), or seaplane bases either side of the lake.
     */
    private fun pair(kind: Int, weather: Boolean = false): World {
        val w = World(NewGame(64, 64, 1, 1965, Region.MIXED, sea = 0, hills = 0, trees = 0, towns = 0, industries = 0, weather = weather, inflation = false, collisions = false))
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 20..30 && y in 50..60) 0 else 1
        w.landShaped()
        for (i in w.trees.indices) w.trees[i] = 0
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        if (kind == Airports.SEAPLANE) {
            w.apply(Command.Airport(0, 19, 55, Airports.SEAPLANE, 0))
            w.apply(Command.Airport(0, 30, 52, Airports.SEAPLANE, 0))
        } else {
            w.apply(Command.Airport(0, 5, 5, kind, Track.X))
            w.apply(Command.Airport(0, 40, 20, kind, Track.Y))
        }
        assertEquals(2, w.airports.size)
        return w
    }

    private val planes = mapOf(
        Airports.STRIP to "beaver", Airports.SEAPLANE to "beaver", Airports.REGIONAL to "dc3",
        Airports.CITY to "viscount", Airports.INTERNATIONAL to "dc8",
    )

    private fun World.buy(key: String, airport: Int = 0, orders: IntArray = intArrayOf(0, 1)): Aircraft {
        val model = roster.engines.first { it.key == "base:$key" }.id
        apply(Command.BuyCraft(0, airports[airport]!!.depot, model))
        val id = aircraft.size - 1
        apply(Command.CraftOrder(0, true, id, Command.TrainOrder.ORDERS, orders.map { airports[it]!!.station }.toIntArray()))
        apply(Command.CraftOrder(0, true, id, Command.TrainOrder.START_STOP))
        val v = aircraft[id]!!
        v.seasonalGear = false
        if (v.info.floats) v.gear = if (airports[airport]!!.kind == Airports.SEAPLANE) Gear.FLOATS else Gear.WHEELS
        return v
    }

    /** A step with the wind from [from] at 20 km/h and clear skies, or none with weather off. */
    private fun World.stepIn(from: Int?) {
        if (from != null) {
            weather.wind = 20
            weather.windFrom = from
            for (a in airports) if (a != null) airSkies[a.id] = 0
        }
        step()
    }

    /** At most one aircraft on each runway, no stand held twice, and nothing parked off its stand. */
    private fun World.checkGround() {
        for (a in airports) {
            if (a == null) continue
            val all = aircraft.filterNotNull().filter { !it.inDepot && it.at == a.id }
            val on = all.filter { onRunway(it, a) }
            assertTrue(on.size <= 1, "two on the runway at airport ${a.id}: ${on.map { "${it.id}:${it.state}:${it.route}/${it.node}" }}")
            val stands = all.filter { it.stand >= 0 && it.wreckedFor == 0 }.map { it.stand }
            assertEquals(stands.size, stands.toSet().size, "a stand held twice at airport ${a.id}: ${all.map { "${it.id}:${it.state}:${it.stand}" }}")
            for (v in all) if (v.state == Flight.GATE) {
                val p = standPoint(a, v.stand)
                assertTrue(v.stand >= 0 && v.x == p[0] && v.y == p[1], "aircraft ${v.id} parked off a stand at airport ${a.id}")
            }
            val down = all.filter { it.heli && it.altitude == 0 && it.wreckedFor == 0 }.map { it.x to it.y }
            assertEquals(down.size, down.toSet().size, "two helicopters on one spot at airport ${a.id}")
        }
    }

    /** Steps [ticks], checking the ground; the places each aircraft called at, and the runway ends each took off from. */
    private fun World.run(ticks: Int, wind: Int?, crafts: List<Aircraft>): Pair<Map<Int, List<Int>>, Set<Pair<Int, Int>>> {
        val calls = HashMap<Int, ArrayList<Int>>()
        val ends = HashSet<Pair<Int, Int>>()
        repeat(ticks) {
            val waiting = crafts.associate { it.id to it.waiting }
            val routes = crafts.associate { it.id to it.route }
            stepIn(wind)
            checkGround()
            for (v in crafts) {
                if (v.state == Flight.GATE && v.waiting > waiting[v.id]!! && v.holding == 0) calls.getOrPut(v.id) { ArrayList() } += v.at
                val r = routes[v.id]!!
                // A take-off from an exit partway counts as from the end it goes the way of.
                val to = Taxiways.to(r)
                val end = if (to >= Taxiways.END0) to else if (r >= Taxiways.FAR_WAY) Taxiways.END1 else Taxiways.END0
                if (v.state == Flight.TAKEOFF && r >= 0) ends += v.at to end
            }
        }
        return calls to ends
    }

    @Test
    fun everyKindTaxisRoundTripsFromBothEnds() {
        for ((kind, key) in planes) {
            val ends = HashSet<Pair<Int, Int>>()
            // From the north-west, then the south-east: each runway used both ways.
            for (wind in intArrayOf(1, 5)) {
                val w = pair(kind, weather = true)
                val v = w.buy(key)
                val (calls, took) = w.run(9000, wind, listOf(v))
                val at = calls[v.id].orEmpty()
                assertTrue(at.toSet().size == 2 && at.size >= 3, "$key at kind $kind, wind $wind called at $at")
                ends += took
            }
            for (a in 0..1) for (e in intArrayOf(Taxiways.END0, Taxiways.END1)) assertTrue(a to e in ends, "kind $kind: took off from $ends")
        }
    }

    @Test
    fun severalShareOneAirportWithoutDeadlock() {
        for (kind in intArrayOf(Airports.STRIP, Airports.REGIONAL, Airports.CITY, Airports.INTERNATIONAL, Airports.SEAPLANE)) {
            val w = pair(kind)
            val key = planes.getValue(kind)
            val crafts = listOf(w.buy(key), w.buy(key, 1, intArrayOf(1, 0)), w.buy(key))
            // More than the stands at a strip or seaplane base: one waits in the hangar or the air.
            if (kind == Airports.STRIP || kind == Airports.SEAPLANE) crafts.forEach { it.service = false }
            val (calls, _) = w.run(64 * 150, null, crafts)
            for (v in crafts) assertTrue(calls[v.id].orEmpty().size >= 4, "kind $kind: ${v.id} called at ${calls[v.id]}")
        }
    }

    @Test
    fun outOfTheHangarToAStandAndBackIn() {
        for (kind in intArrayOf(Airports.STRIP, Airports.REGIONAL, Airports.CITY, Airports.INTERNATIONAL, Airports.SEAPLANE)) {
            val w = pair(kind)
            val a = w.airports[0]!!
            val v = w.buy(planes.getValue(kind))
            w.step()
            assertFalse(v.inDepot)
            assertEquals(Flight.TAXI_IN, v.state)
            assertEquals(Taxiways.DOOR, v.route / 16)
            // It comes out at the door, in the hangar's tile.
            assertEquals(w.depots[a.depot]!!.tile, w.index(v.x / SHIP_UNIT, v.y / SHIP_UNIT), "kind $kind")
            var t = 0
            while (v.state != Flight.GATE && t++ < 500) w.step()
            assertEquals(Flight.GATE, v.state)
            val p = w.standPoint(a, v.stand)
            assertEquals(p[0] to p[1], v.x to v.y)
            // Sent home from its stand: to the door and in.
            v.waiting = 0
            w.apply(Command.CraftOrder(0, true, v.id, Command.TrainOrder.TO_DEPOT))
            t = 0
            var taxied = false
            while (!v.inDepot && t++ < 2000) { w.step(); if (v.state == Flight.TAXI_IN && v.route % 16 == Taxiways.DOOR) taxied = true }
            assertTrue(v.inDepot && taxied, "kind $kind: in ${v.inDepot}, taxied $taxied")
            assertEquals(-1, v.stand)
        }
    }

    @Test
    fun anAirportWithAircraftTaxiingStays() {
        val w = pair(Airports.CITY)
        val v = w.buy("viscount")
        w.step()
        assertTrue(v.taxiing)
        assertTrue(w.airportBusy(w.airports[0]!!))
    }

    @Test
    fun aWreckOnTheRunwayHoldsTheOthersUntilCleared() {
        val w = pair(Airports.REGIONAL)
        val other = w.buy("dc3")
        // The second at its stand wanting to go, a wreck set down on the runway beside it.
        while (other.inDepot || other.state != Flight.GATE) w.step()
        w.apply(Command.BuyCraft(0, w.airports[0]!!.depot, other.model))
        val v = w.aircraft.last()!!
        v.inDepot = false
        v.at = other.at
        v.wreckedFor = 200
        v.state = Flight.LANDING
        w.runway(w.airports[other.at]!!).let { r -> v.x = (r[0] + r[2]) / 2; v.y = (r[1] + r[3]) / 2 }
        repeat(150) { w.step(); w.checkGround() }
        assertTrue(other.state != Flight.TAKEOFF && other.state != Flight.CRUISE, "left past a wreck")
        var t = 0
        while (other.state != Flight.CRUISE && t++ < 2000) { w.step(); w.checkGround() }
        assertEquals(Flight.CRUISE, other.state)
        assertTrue(w.aircraft[v.id] == null)
    }

    @Test
    fun savedMidTaxiTheyCarryOnTheSame() {
        val w = pair(Airports.INTERNATIONAL)
        w.buy("dc8")
        w.buy("dc8", 1, intArrayOf(1, 0))
        w.buy("dc8")
        var saved = 0
        repeat(9000) { t ->
            w.step()
            if (t % 97 == 0 && w.aircraft.any { it!!.taxiing }) {
                val copy = Save.read(Save.write(w))
                assertEquals(w.checksum(), copy.checksum())
                for (v in w.aircraft) {
                    val c = copy.aircraft[v!!.id]!!
                    assertEquals(listOf(v.route, v.node, v.stand, v.state), listOf(c.route, c.node, c.stand, c.state))
                }
                repeat(300) { w.step(); copy.step() }
                assertEquals(w.checksum(), copy.checksum())
                saved++
            }
        }
        assertTrue(saved > 5, "saved $saved times mid-taxi")
    }

    @Test
    fun twoWorldsTaxiTheSame() {
        fun world(): World {
            val w = pair(Airports.CITY, weather = true)
            w.buy("viscount")
            w.buy("viscount", 1, intArrayOf(1, 0))
            w.buy("dc3")
            w.buy("bell_47")
            return w
        }
        val a = world()
        val b = world()
        repeat(10) {
            repeat(1000) { a.step(); b.step() }
            assertEquals(a.checksum(), b.checksum())
        }
    }

    /** Puts airport [a]'s hangar on the tile an old save had it, the airport's first. */
    private fun World.hangarToOldSpot(a: Airport) {
        val d = depots[a.depot]!!
        val old = index(a.x, a.y)
        depotTiles.remove(d.tile)
        depots[d.id] = Depot(d.id, d.owner, old, d.edge, d.kind).also { it.built = d.built }
        depotTiles[old] = d.id
    }

    @Test
    fun aVersion52SaveParksOnStandsAndHangarsMove() {
        val w = pair(Airports.REGIONAL)
        val crafts = listOf(w.buy("dc3"), w.buy("dc3"))
        var t = 0
        while (crafts.any { it.state != Flight.GATE } && t++ < 3000) w.step()
        val tiles = w.airports.map { w.depots[it!!.depot]!!.tile }
        for (a in w.airports) w.hangarToOldSpot(a!!)
        for (a in w.airports) assertTrue(w.depots[a!!.depot]!!.tile != tiles[a.id], "hangar not moved for the test")
        val old = Save.read(Save.write(w, 52))
        for (a in old.airports) assertEquals(tiles[a!!.id], old.depots[a.depot]!!.tile)
        for (v in crafts) {
            val c = old.aircraft[v.id]!!
            if (c.inDepot || c.state != Flight.GATE) continue
            val a = old.airports[c.at]!!
            assertTrue(c.stand >= 0)
            val p = old.standPoint(a, c.stand)
            assertEquals(p[0] to p[1], c.x to c.y)
        }
        var calls = 0
        repeat(6000) {
            val waiting = crafts.map { old.aircraft[it.id]!!.waiting }
            old.step()
            for ((k, v) in crafts.withIndex()) { val c = old.aircraft[v.id]!!; if (c.state == Flight.GATE && c.waiting > waiting[k]) calls++ }
        }
        assertTrue(calls >= 4, "$calls calls after loading")
    }

    @Test
    fun aVersion52SaveWithMoreAtGatesThanStandsKeepsHomesAndTheyFly() {
        val w = pair(Airports.STRIP)
        val at = w.airports[0]!!
        val stands = Taxiways.STANDS[at.kind].size / 4
        // Based at the other airport, all sitting at gates of the first as an old save had them.
        val crafts = List(stands + 2) { w.buy("beaver", 1, intArrayOf(1, 0)) }
        val home = w.airports[1]!!.depot
        for (v in crafts) {
            v.inDepot = false
            v.state = Flight.GATE
            v.at = at.id
            v.stand = 0
            v.service = false
            val p = w.standPoint(at, 0)
            v.x = p[0]; v.y = p[1]
        }
        val loaded = Save.read(Save.write(w, 52))
        val got = crafts.map { loaded.aircraft[it.id]!! }
        for (v in got) assertEquals(home, v.depot)
        assertTrue(got.any { it.inDepot }, "none waiting in the hangar")
        val flew = HashSet<Int>()
        repeat(64 * 100) {
            loaded.step()
            for (v in got) {
                if (v.state == Flight.CRUISE) flew += v.id
                assertEquals(home, v.depot)
            }
        }
        assertEquals(crafts.map { it.id }.toSet(), flew, "some never flew")
    }

    @Test
    fun helicoptersLandOnTheirOwnStands() {
        val w = pair(Airports.REGIONAL)
        val crafts = listOf(w.buy("bell_47"), w.buy("bell_47"), w.buy("bell_47", 1, intArrayOf(1, 0)))
        crafts.forEach { it.service = false }
        val (calls, _) = w.run(64 * 100, null, crafts)
        for (v in crafts) assertTrue(calls[v.id].orEmpty().size >= 2, "${v.id} called at ${calls[v.id]}")
    }

    /**
     * Every route on a strip, regional, city or international airport keeps to what the map draws: the runway's middle
     * line (8 across), or on a city or international airport the taxiway's line down row 1 (24 across). Only the first
     * and last legs leave them, onto a stand or into the hangar, and a runway end's or exit's leg between the two lines.
     */
    @Test
    fun routesKeepToTheDrawnLines() {
        for (kind in listOf(Airports.STRIP, Airports.REGIONAL, Airports.CITY, Airports.INTERNATIONAL)) {
            val taxiway = kind == Airports.CITY || kind == Airports.INTERNATIONAL
            val places = (0 until Taxiways.STANDS[kind].size / 4) + (Taxiways.EXIT0 until Taxiways.EXIT0 + Taxiways.EXITS[kind].size) +
                listOf(Taxiways.DOOR, Taxiways.END0, Taxiways.END1)
            for (p in places) {
                val sp = Taxiways.spur(kind, p)
                assertEquals(if (taxiway) 24 else 8, sp[1], "kind $kind place $p joins off the line")
                for (i in 1 until sp.size / 2 - 1) assertTrue(sp[i * 2 + 1] == 8 || sp[i * 2 + 1] == 24, "kind $kind place $p point $i")
                if (p >= Taxiways.END0 || p in Taxiways.EXIT0 until Taxiways.DOOR) assertEquals(8, sp[sp.size - 1], "kind $kind place $p ends off the runway")
            }
        }
    }
}
