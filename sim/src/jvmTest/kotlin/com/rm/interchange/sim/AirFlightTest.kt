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
}
