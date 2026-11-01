package com.rm.interchange.sim

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Aircraft flying smooth turns: headings in 256ths, a turn rate, runway ends by the wind, lining up, holding. */
class AirFlightTest {
    /**
     * Flat land a step up, a regional airport at (5, 5) along x and another at (40, 40) along y, and a lake from x = 20
     * to 29, y = 50 to 59. With [weather], the wind is whatever the test sets before each step.
     */
    private fun airports(weather: Boolean = false, year: Int = 1965): World {
        val w = World(NewGame(64, 64, 1, year, Region.MIXED, sea = 0, hills = 0, trees = 0, towns = 0, industries = 0, weather = weather, inflation = false, collisions = false))
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 20..30 && y in 50..60) 0 else 1
        w.landShaped()
        for (i in w.trees.indices) w.trees[i] = 0
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        w.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
        w.apply(Command.Airport(0, 40, 40, Airports.REGIONAL, Track.Y))
        return w
    }

    private fun World.buy(key: String, airport: Int = 0, orders: IntArray = intArrayOf(0, 1)): Aircraft {
        val model = roster.engines.first { it.key == "base:$key" }.id
        val before = aircraft.size
        apply(Command.BuyCraft(0, airports[airport]!!.depot, model))
        assertEquals(before + 1, aircraft.size, "bought a $key")
        val id = aircraft.size - 1
        apply(Command.CraftOrder(0, true, id, Command.TrainOrder.ORDERS, orders.map { airports[it]!!.station }.toIntArray()))
        apply(Command.CraftOrder(0, true, id, Command.TrainOrder.START_STOP))
        // Bush planes stay on wheels: skis in January would find no snow to land on.
        val v = aircraft[id]!!
        if (v.info.floats) { v.seasonalGear = false; v.gear = Gear.WHEELS }
        return v
    }

    /** A step with the wind from [from] (eighths, as [Weather.windFrom]) at 20 km/h and clear skies. */
    private fun World.stepIn(from: Int?) {
        if (from != null) {
            weather.wind = 20
            weather.windFrom = from
            for (a in airports) if (a != null) airSkies[a.id] = 0
        }
        step()
    }

    @Test
    fun sinesAndBearingsGoRound() {
        assertEquals(TRIG_ONE, sin256(64))
        assertEquals(TRIG_ONE, cos256(0))
        assertEquals(-TRIG_ONE, cos256(128))
        assertEquals(0, sin256(128))
        for (h in 0 until 256) {
            assertEquals(h, bearing256(cos256(h) * 10, sin256(h) * 10), "heading $h")
            assertTrue(abs(sin256(h).toLong() * sin256(h) + cos256(h).toLong() * cos256(h) - TRIG_ONE.toLong() * TRIG_ONE) < 2L * TRIG_ONE * 2)
        }
        assertEquals(0, bearing256(5, 0))
        assertEquals(64, bearing256(0, 5))
        assertEquals(128, bearing256(-5, 0))
        assertEquals(192, bearing256(0, -5))
        assertEquals(32, bearing256(7, 7))
    }

    /** Each tick's turn of every aircraft, checked against its turn rate, while it's away from the gate. */
    private fun watchTurns(w: World, ticks: Int, wind: Int? = null, each: (Aircraft) -> Unit = {}) {
        val was = HashMap<Int, Pair<Int, Int>>()
        repeat(ticks) {
            for (v in w.aircraft) if (v != null) was[v.id] = v.state to v.heading
            w.stepIn(wind)
            for (v in w.aircraft) {
                if (v == null || v.inDepot) continue
                val (state, heading) = was[v.id] ?: continue
                each(v)
                if (state == Flight.GATE || v.state == Flight.GATE) continue
                val turn = abs(((v.heading - heading + 128) and 255) - 128)
                assertTrue(turn <= turnRate(v), "${v.info.key} turned $turn in a tick in state ${v.state}, rate ${turnRate(v)}")
            }
        }
    }

    @Test
    fun aircraftTurnNoFasterThanTheirRate() {
        val w = airports()
        w.apply(Command.Airport(0, 10, 30, Airports.STRIP, Track.Y))
        w.apply(Command.Airport(0, 48, 12, Airports.STRIP, Track.X))
        val dc3 = w.buy("dc3")
        val beaver = w.buy("beaver", 2, intArrayOf(2, 3))
        val heli = w.buy("bell_47", 0, intArrayOf(0, 1))
        val flew = HashSet<Int>()
        watchTurns(w, 12_000) { v -> if (v.state == Flight.HOLD || v.state == Flight.LANDING) flew += v.id }
        for (v in listOf(dc3, beaver, heli)) assertTrue(v.id in flew, "${v.info.key} never flew")
        // Airliners turn round two tiles, small planes one.
        assertEquals(2 * SHIP_UNIT, turnRadius(dc3))
        assertEquals(SHIP_UNIT, turnRadius(beaver))
    }

    @Test
    fun theyTakeOffAndLandIntoTheWindFromEitherEnd() {
        // From the north-west: west along x at the first airport, north along y at the second; south-east the other way.
        for ((from, expect) in listOf(1 to intArrayOf(128, 192), 5 to intArrayOf(0, 64))) {
            val w = airports(weather = true)
            val v = w.buy("dc3")
            val offs = ArrayList<Pair<Int, Int>>()
            val downs = ArrayList<Pair<Int, Int>>()
            var state = v.state
            var altitude = v.altitude
            var calls = 0
            repeat(12_000) {
                val waiting = v.waiting
                w.stepIn(from)
                if (state == Flight.TAXI_OUT && v.state == Flight.TAKEOFF) offs += v.at to v.heading
                if (state == Flight.LANDING && altitude > 0 && v.state == Flight.LANDING && v.altitude == 0) downs += v.at to v.heading
                // Rolling out after touchdown, straight down the runway.
                if (v.state == Flight.LANDING && v.altitude == 0) assertEquals(expect[v.at], v.heading)
                if (v.state == Flight.GATE && v.waiting > waiting) calls++
                state = v.state
                altitude = v.altitude
            }
            assertTrue(calls >= 4, "wind $from: $calls calls")
            for (a in 0..1) {
                assertTrue(offs.count { it.first == a } >= 2 && downs.count { it.first == a } >= 2, "wind $from: took off $offs, landed $downs")
            }
            for ((a, h) in offs + downs) assertEquals(expect[a], h, "wind $from at airport $a: took off $offs, landed $downs")
        }
    }

    @Test
    fun withoutWindTheyLandFromTheNearerEnd() {
        val w = airports()
        val v = w.buy("dc3")
        val downs = HashSet<Pair<Int, Int>>()
        var altitude = 0
        repeat(12_000) {
            w.step()
            if (v.state == Flight.LANDING && altitude > 0 && v.altitude == 0) downs += v.at to v.heading
            altitude = v.altitude
        }
        // The first airport is up and left of the second: it lands at the second heading south (64), and back at the
        // first heading west (128), from the ends facing where it came from.
        assertEquals(setOf(1 to 64, 0 to 128), downs)
    }

    @Test
    fun theyLineUpFromAnywhere() {
        val w = airports()
        val v = w.buy("dc3")
        // Let it take off once, then set it down around the second airport, every way, never due a service.
        v.service = false
        while (v.state != Flight.CRUISE) w.step()
        val b = w.airports[1]!!
        val cx = (b.x * SHIP_UNIT + SHIP_UNIT / 2)
        val cy = (b.y * SHIP_UNIT + 2 * SHIP_UNIT)
        var worst = 0
        for (k in 0 until 16) for (h in intArrayOf(0, 64, 128, 192, 32, 160)) {
            v.state = Flight.CRUISE
            v.order = 1
            v.at = 0
            v.x = cx + cos256(k * 16) * 12 / TRIG_ONE * SHIP_UNIT
            v.y = cy + sin256(k * 16) * 12 / TRIG_ONE * SHIP_UNIT
            v.heading = h
            v.speed = v.maxSpeed
            v.altitude = 11 * 16
            v.holding = 0
            var t = 0
            while (!(v.state == Flight.LANDING && v.altitude == 0) && t < 2000) { w.step(); t++ }
            assertTrue(t < 2000, "from ring $k heading $h it never landed: state ${v.state} at ${v.x / SHIP_UNIT},${v.y / SHIP_UNIT}")
            worst = maxOf(worst, t)
            while (v.state != Flight.GATE) w.step()
            v.waiting = 0
        }
        println("lining up from 12 tiles out took at most $worst ticks")
        assertTrue(worst < 400, "worst $worst ticks")
    }

    @Test
    fun aHoldIsASteadyCircle() {
        val w = airports(weather = true)
        val v = w.buy("dc3")
        val b = w.airports[1]!!
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        var t = 0
        while (t < 20_000 && xs.size < 180) {
            // Fog at the second airport, without landing aids.
            for (a in w.airports) if (a != null) w.airSkies[a.id] = if (a === b) 1 else 0
            w.weather.wind = 20
            w.weather.windFrom = 1
            val heading = v.heading
            val holding = v.state == Flight.HOLD
            w.step()
            t++
            if (holding && v.state == Flight.HOLD) {
                val turn = (v.heading - heading) and 255
                assertTrue(turn in 1..turnRate(v), "turned $turn")
                xs += v.x
                ys += v.y
            }
        }
        assertTrue(xs.size >= 180, "held ${xs.size} ticks")
        // Once round at least, about two tiles across each way.
        val across = (xs.max() - xs.min()) to (ys.max() - ys.min())
        for (d in listOf(across.first, across.second)) assertTrue(d in 3 * SHIP_UNIT..5 * SHIP_UNIT, "circle $across")
    }

    @Test
    fun everyKindMakesARoundTrip() {
        val w = airports()
        w.apply(Command.Airport(0, 10, 30, Airports.STRIP, Track.Y))
        w.apply(Command.Airport(0, 48, 12, Airports.STRIP, Track.X))
        // On the lake's west shore, its water out to the east, and on the east shore, out to the west.
        w.apply(Command.Airport(0, 19, 55, Airports.SEAPLANE, 0))
        w.apply(Command.Airport(0, 30, 52, Airports.SEAPLANE, 0))
        assertEquals(6, w.airports.size)
        val crafts = listOf(
            w.buy("dc3", 0, intArrayOf(0, 1)), w.buy("dc3", 1, intArrayOf(1, 0)), w.buy("beaver", 2, intArrayOf(2, 3)),
            w.buy("bell_47", 1, intArrayOf(1, 2)), w.buy("otter", 4, intArrayOf(4, 5)),
        )
        val sea = crafts.last()
        sea.seasonalGear = false
        sea.gear = Gear.FLOATS
        val calls = HashMap<Int, ArrayList<Int>>()
        watchTurns(w, 15_000)
        val waiting = HashMap<Int, Int>()
        repeat(15_000) {
            for (v in crafts) waiting[v.id] = v.waiting
            w.step()
            for (v in crafts) if (v.state == Flight.GATE && v.waiting > waiting[v.id]!! && v.holding == 0) calls.getOrPut(v.id) { ArrayList() } += v.at
        }
        for (v in crafts) {
            val at = calls[v.id].orEmpty()
            assertTrue(at.toSet().size == 2 && at.size >= 3, "${v.info.key} called at $at")
        }
    }

    @Test
    fun twoWorldsFlyTheSame() {
        fun world(): World {
            val w = airports(weather = true)
            w.apply(Command.Airport(0, 10, 30, Airports.STRIP, Track.Y))
            w.buy("dc3")
            w.buy("dc3", 1, intArrayOf(1, 0))
            w.buy("beaver", 2, intArrayOf(2, 0))
            w.buy("bell_47", 1, intArrayOf(1, 2))
            return w
        }
        val a = world()
        val b = world()
        repeat(8) {
            repeat(1000) { a.step(); b.step() }
            assertEquals(a.checksum(), b.checksum())
        }
        assertEquals(a.aircraft.map { it?.heading }, b.aircraft.map { it?.heading })
    }

    @Test
    fun aircraftSaveMidTurnAndCarryOn() {
        val w = airports()
        w.buy("dc3")
        w.buy("dc3", 1, intArrayOf(1, 0))
        w.buy("bell_47", 1, intArrayOf(1, 0))
        var saved = 0
        repeat(6000) { t ->
            w.step()
            if (t % 300 == 0) {
                val copy = Save.read(Save.write(w))
                assertEquals(w.checksum(), copy.checksum())
                for (v in w.aircraft) {
                    val c = copy.aircraft[v!!.id]!!
                    assertEquals(v.heading, c.heading)
                    assertEquals(v.landing, c.landing)
                    if (v.heading % 32 != 0) saved++
                }
                repeat(200) { w.step(); copy.step() }
                assertEquals(w.checksum(), copy.checksum())
            }
        }
        assertTrue(saved > 0, "never saved one between eighths")
    }

    @Test
    fun aVersion52SaveLoadsWithEighthsTurnedTo256ths() {
        val w = airports()
        val v = w.buy("dc3")
        // Somewhere along its first trip, in the air.
        var t = 0
        while ((v.state != Flight.CRUISE || v.heading % 32 == 0) && t++ < 5000) w.step()
        val old = Save.read(Save.write(w, 52))
        val c = old.aircraft[v.id]!!
        assertEquals(((v.heading + 16) shr 5 and 7) * 32, c.heading)
        assertEquals(Flight.CRUISE, c.state)
        // And it carries on, round its trip.
        var calls = 0
        repeat(10_000) {
            val waiting = c.waiting
            old.step()
            if (c.state == Flight.GATE && c.waiting > waiting) calls++
        }
        assertTrue(calls >= 2, "$calls calls after loading")
    }

    /**
     * How far along its runway, in hundredths, [key] first leaves the ground taking off from airport 0 of a world with
     * airport kinds [from] at (5, 5) and [to] at (30, 30), both along x, the tick it does, and how far along it started its roll.
     */
    private fun liftOff(key: String, from: Int, to: Int, year: Int): Triple<Int, Int, Int> {
        val w = World(NewGame(64, 64, 1, year, Region.MIXED, sea = 0, hills = 0, trees = 0, towns = 0, industries = 0, weather = false, inflation = false, collisions = false))
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = 1
        w.landShaped()
        for (i in w.trees.indices) w.trees[i] = 0
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        w.apply(Command.Airport(0, 5, 5, from, Track.X))
        w.apply(Command.Airport(0, 30, 30, to, Track.X))
        assertEquals(2, w.airports.count { it != null }, "built both airports")
        val v = w.buy(key)
        v.service = false
        var t = 0
        var rollFrom = -1
        while (!(v.state == Flight.TAKEOFF && v.altitude > 0) && t < 5000) {
            w.step()
            t++
            if (v.state == Flight.TAKEOFF && rollFrom < 0) rollFrom = v.x
        }
        assertTrue(t < 5000, "$key never took off")
        val r = w.runway(w.airports[0]!!)
        val total = abs(r[2] - r[0]) + abs(r[3] - r[1])
        val start = if (cos256(v.heading) > 0) r[0] else r[2]
        return Triple(abs(v.x - start) * 100 / total, t, abs(rollFrom - start) * 100 / total)
    }

    @Test
    fun smallPlanesLiftOffSoonerThanAirliners() {
        val beaver = liftOff("beaver", Airports.STRIP, Airports.STRIP, 1955)
        val dc3 = liftOff("dc3", Airports.REGIONAL, Airports.REGIONAL, 1955)
        val jet = liftOff("b737_200", Airports.CITY, Airports.CITY, 1975)
        val dc8 = liftOff("dc8", Airports.INTERNATIONAL, Airports.INTERNATIONAL, 1965)
        println("lift-off along the runway: beaver $beaver, dc3 $dc3, b737_200 $jet, dc8 $dc8 (hundredths, tick)")
        assertTrue(beaver.first - beaver.third < 50, "a bush plane is off the ground in under half a strip, at ${beaver.first} from ${beaver.third}")
        for ((name, at) in listOf("dc3" to dc3, "b737_200" to jet, "dc8" to dc8)) {
            assertTrue(at.first > beaver.first, "$name lifts off later than a bush plane, at ${at.first}")
            assertTrue(at.first in 60..100, "$name lifts off most of the way down its runway, at ${at.first}")
        }
        assertEquals(beaver, liftOff("beaver", Airports.STRIP, Airports.STRIP, 1955), "the same each run")
        assertEquals(jet, liftOff("b737_200", Airports.CITY, Airports.CITY, 1975), "the same each run")
    }

    /**
     * Takes [key] off from airport 0 of [w] on [gear] with the wind from [wind] (or none), watching the runway: where its taxi out
     * ended, where it started rolling and where it lifted off, along its runway in 16ths of a tile.
     */
    private fun takeOffRun(w: World, key: String, wind: Int?, gear: Int = Gear.WHEELS): IntArray {
        val v = w.buy(key)
        v.gear = gear
        v.service = false
        val a = w.airports[0]!!
        var route = -1
        var from = -1
        var t = 0
        while (!(v.state == Flight.TAKEOFF && v.altitude > 0) && t < 6000) {
            if (v.state == Flight.TAXI_OUT) route = v.route
            w.stepIn(wind)
            t++
            if (v.state == Flight.TAKEOFF && from < 0) {
                from = alongRunway(w, a, v)
                // Nothing else is on the runway while it rolls.
                assertTrue(w.aircraft.none { it != null && it !== v && onRunway(it, a) }, "another on the runway")
            }
        }
        assertTrue(t < 6000, "$key never took off")
        return intArrayOf(route, from, alongRunway(w, a, v))
    }

    /** How far along [a]'s runway [v] is from the end it rolls from, in 16ths. */
    private fun alongRunway(w: World, a: Airport, v: Aircraft): Int {
        val r = w.runway(a)
        val start = if (cos256(v.heading) + sin256(v.heading) > 0) intArrayOf(r[0], r[1]) else intArrayOf(r[2], r[3])
        return (abs(v.x - start[0]) + abs(v.y - start[1])) * 16 / SHIP_UNIT
    }

    /** A strip at (5, 5) along x and another at (30, 30), or seaplane bases either side of the lake. */
    private fun strips(kind: Int, weather: Boolean, year: Int = 1955): World {
        val w = World(NewGame(64, 64, 1, year, Region.MIXED, sea = 0, hills = 0, trees = 0, towns = 0, industries = 0, weather = weather, inflation = false, collisions = false))
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
            w.apply(Command.Airport(0, 30, 30, kind, Track.X))
        }
        assertEquals(2, w.airports.count { it != null }, "built both airports")
        return w
    }

    @Test
    fun aSmallPlaneTakesOffFromAnExitPartway() {
        // Calm, east towards the other strip; then from the north-west, west down the runway the other way.
        for (wind in listOf(null, 1)) {
            val w = strips(Airports.STRIP, weather = wind != null)
            val (route, from, up) = takeOffRun(w, "beaver", wind)
            val to = Taxiways.to(route)
            assertTrue(to in Taxiways.EXIT0 until Taxiways.DOOR, "wind $wind: lined up at place $to")
            assertEquals(wind != null, route >= Taxiways.FAR_WAY, "wind $wind: the way it takes off")
            assertTrue(from > 1, "wind $wind: started its roll partway, at $from")
            assertTrue(up > from && up < 64, "wind $wind: lifted off at $up, before the end, from $from")
        }
    }

    @Test
    fun aSmallPlaneWaitsForTheRunwayBeforeLiningUpAtAnExit() {
        val w = strips(Airports.REGIONAL, weather = false, year = 1965)
        val a = w.airports[0]!!
        val liner = w.buy("dc3")
        val small = w.buy("beaver")
        liner.service = false
        small.service = false
        var held = 0
        var rolled = false
        for (t in 0 until 6000) {
            w.step()
            val busy = w.aircraft.filter { it != null && onRunway(it, a) }
            assertTrue(busy.size <= 1, "tick $t: ${busy.size} on the runway together")
            if (small.state == Flight.TAXI_OUT && small.at == a.id && small.route >= 0 && Taxiways.to(small.route) in Taxiways.EXIT0 until Taxiways.DOOR) {
                if (small.speed == 0 && w.runwayBusy(a, small)) held++
            }
            if (small.state == Flight.TAKEOFF) rolled = true
        }
        assertTrue(rolled, "the small plane took off")
        assertTrue(held > 0, "the small plane held short while the runway was in use")
    }

    @Test
    fun airlinersStillUseTheRunwayEnd() {
        for ((key, kind) in listOf("dc3" to Airports.REGIONAL, "dc8" to Airports.INTERNATIONAL)) {
            val w = strips(kind, weather = false, year = 1965)
            val (route, from, _) = takeOffRun(w, key, null)
            assertTrue(Taxiways.to(route) >= Taxiways.END0, "$key lined up at place ${Taxiways.to(route)}")
            assertTrue(from <= 16, "$key started its roll at $from")
        }
    }

    @Test
    fun aShortRunLeavesASmallPlaneTheEnd() {
        // A seaplane base's exits leave less than three times a float plane's run on the water.
        val w = strips(Airports.SEAPLANE, weather = false)
        val (route, _, _) = takeOffRun(w, "beaver", null, Gear.FLOATS)
        assertTrue(Taxiways.to(route) >= Taxiways.END0, "lined up at place ${Taxiways.to(route)}")
        // Wherever it picks an exit, the run ahead of it is at least three rolls.
        for (kind in intArrayOf(Airports.STRIP, Airports.REGIONAL, Airports.CITY, Airports.INTERNATIONAL, Airports.SEAPLANE)) {
            val s = strips(kind, weather = false, year = 1965)
            val a = s.airports[0]!!
            val v = s.buy("beaver")
            if (kind == Airports.SEAPLANE) v.gear = Gear.FLOATS
            val r = s.runway(a)
            val len = (abs(r[2] - r[0]) + abs(r[3] - r[1])) / (SHIP_UNIT / 16)
            for (stand in 0 until Taxiways.STANDS[kind].size / 4) for (end in intArrayOf(Taxiways.END0, Taxiways.END1)) {
                val at = s.lineUpAt(v, a, stand, end)
                if (at >= Taxiways.END0) { assertEquals(end, at); continue }
                val e = Taxiways.spur(kind, at)
                val on = e[e.size - 2]
                val run = if (end == Taxiways.END0) len - on else on
                assertTrue(run.toLong() * (SHIP_UNIT / 16) >= takeOffRoll(v, a).toLong() * 3, "kind $kind stand $stand end $end: exit $at leaves $run")
            }
        }
    }
}
