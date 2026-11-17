package com.rm.interchange.sim

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutonomyTest {
    private fun model(w: World, key: String) = w.roster.engines.first { it.key == "base:$key" }

    /** A flat 64 map from [year], with plenty of money. */
    private fun flat(year: Int = 2045, future: Boolean = true, weather: Boolean = false): World {
        val w = World(NewGame(64, 64, 1, year, towns = 0, industries = 0, weather = weather, handling = false, eraCosts = false, future = future))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 100_000_000
        return w
    }

    /** A highway along y = 20 from x 5 to 40, a garage off it at x 5 and stops at x 8 and 38. */
    private fun highway(w: World = flat(), surface: Int = Surface.HIGHWAY): World {
        w.apply(Command.Road(0, 5, 20, 40, 20, surface, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, surface, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        w.apply(Command.RoadStop(0, 8, 20))
        w.apply(Command.RoadStop(0, 38, 20))
        return w
    }

    /** Pads at (8, 21) beside the first stop, and at (20, 30) and (8 + [far], 21) alone. */
    private fun pads(w: World, far: Int = 40): World {
        w.apply(Command.Airport(0, 8, 21, Airports.PAD, Track.X))
        w.apply(Command.Airport(0, 20, 30, Airports.PAD, Track.X))
        if (8 + far < w.width) w.apply(Command.Airport(0, 8 + far, 21, Airports.PAD, Track.X))
        return w
    }

    private fun World.buyDrone(key: String, pad: Int, orders: IntArray): Aircraft? {
        val before = aircraft.size
        apply(Command.BuyCraft(0, airports[pad]!!.depot, model(this, key).id))
        if (aircraft.size == before) return null
        val id = aircraft.size - 1
        apply(Command.CraftOrder(0, true, id, Command.TrainOrder.ORDERS, orders))
        apply(Command.CraftOrder(0, true, id, Command.TrainOrder.START_STOP))
        return aircraft[id]
    }

    /** The stations [v] calls at over [ticks]. */
    private fun calls(w: World, v: Aircraft, ticks: Int, each: () -> Unit = {}): List<Int> {
        val called = ArrayList<Int>()
        repeat(ticks) {
            each()
            val before = v.waiting
            w.step()
            if (v.state == Flight.GATE && v.waiting > before) called += w.airports[v.at]!!.station
        }
        return called
    }

    @Test
    fun anOldSaveLoadsAndPlaysAsBefore() {
        // A v74 game with a tube line and a bus on a highway plays the same read back as 74 or 75.
        val w = World(NewGame(128, 128, 1, 2065, towns = 0, industries = 0, weather = false, handling = false, eraCosts = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 1_000_000_000
        highway(w)
        w.apply(Command.BuyRoadVehicle(0, 0, model(w, "aero_tractor").id))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.START_STOP))
        w.apply(Command.Depot(0, 2, 60, Track.XP, tube = true))
        w.apply(Command.Rail(0, 3, 60, 120, 60, Track.X, remove = false, tube = true))
        w.apply(Command.Station(0, 10, 60, 14, 60, Track.X, tube = true))
        w.apply(Command.Station(0, 106, 60, 110, 60, Track.X, tube = true))
        val vela = model(w, "vela").id
        w.apply(Command.BuyTrain(0, 1, vela, model(w, "aurora_coach").id, 0))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(2, 3)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        repeat(2_000) { w.step() }
        val old = Save.read(Save.write(w, version = 74))
        val now = Save.read(Save.write(w))
        assertEquals(w.checksum(), old.checksum())
        assertEquals(w.checksum(), now.checksum())
        repeat(3_000) { w.step(); old.step(); now.step() }
        assertEquals(w.checksum(), old.checksum())
        assertEquals(w.checksum(), now.checksum())
        assertTrue(old.tube.any { it })
    }

    @Test
    fun autonomousTrucksRunWithoutTheCrewShare() {
        val w = flat()
        // Each against its crewed peer: the same load, about 40% less to run.
        for ((auto, crewed) in listOf("auto_tractor" to "aero_tractor", "auto_dump" to "quad_dump", "auto_tanker" to "tanker_train")) {
            val a = model(w, auto)
            val c = model(w, crewed)
            assertTrue(a.autonomous && !c.autonomous)
            assertEquals(c.capacity, a.capacity)
            assertEquals(c.cargoes.toList(), a.cargoes.toList())
            assertTrue(w.running(a) * 100 in w.running(c) * 55..w.running(c) * 62, "$auto ${w.running(a)} against ${w.running(c)}")
        }
        assertTrue(model(w, "convoy_tractor").autonomous)
        // Rivals take an autonomous truck over a crewed one that carries as much as fast; not with future off.
        assertEquals("base:auto_tractor", w.bestModel(Cargo.GOODS)!!.key)
        assertEquals("base:aero_tractor", flat(future = false).bestModel(Cargo.GOODS)!!.key)
        // Only road vehicles are autonomous.
        val manifest = """{ "format": 1, "id": "x", "version": 1, "name": { "en": "X" } }"""
        val bad = Packs.unzip(zip(mapOf("manifest.json" to manifest, "vehicles.json" to """{ "vehicles": [ { "id": "c", "replaces": "base:beaver", "autonomous": true } ] }""")))
        assertTrue(assertFailsWith<BadPack> { Roster.build(listOf(Packs.BASE, bad)) }.problems.any { "autonomous" in it })
    }

    /** Two trucks of [key] on the highway's tile at x 20, the second [behind] behind the first, and how far the second may go. */
    private fun followRoom(key: String, surface: Int): Int {
        val w = highway(surface = surface)
        repeat(2) { w.apply(Command.BuyRoadVehicle(0, 0, model(w, key).id)) }
        val a = w.roadVehicles[0]!!
        val b = w.roadVehicles[1]!!
        val tile = w.index(20, 20)
        for (v in listOf(a, b)) {
            v.inDepot = false
            v.at = roadStep(tile, Track.XM, Track.XP)
        }
        a.along = 15 * FIX
        b.along = 2 * FIX
        val on = hashMapOf(tile to arrayListOf(a, b))
        return w.roadFreeAhead(b, on)
    }

    @Test
    fun autonomousTrucksFollowCloserOnHighways() {
        val crewed = followRoom("aero_tractor", Surface.HIGHWAY)
        val convoy = followRoom("auto_tractor", Surface.HIGHWAY)
        assertEquals(crewed + GAP - CONVOY_GAP, convoy)
        // Not on a paved road.
        assertEquals(followRoom("aero_tractor", Surface.PAVED), followRoom("auto_tractor", Surface.PAVED))
        // A truck just into the next tile holds a crewed follower to the end of its own; one in a convoy goes on.
        assertEquals(8 * FIX, nextTileRoom("aero_tractor"))
        assertTrue(nextTileRoom("auto_tractor") > 8 * FIX)
    }

    /** How far the second of two trucks of [key] may go, halfway along the tile at x 20, with the first just into the next. */
    private fun nextTileRoom(key: String): Int {
        val w = highway()
        repeat(2) { w.apply(Command.BuyRoadVehicle(0, 0, model(w, key).id)) }
        val a = w.roadVehicles[0]!!
        val b = w.roadVehicles[1]!!
        val t0 = w.index(20, 20)
        val t1 = w.index(21, 20)
        for (v in listOf(a, b)) v.inDepot = false
        a.at = roadStep(t1, Track.XM, Track.XP)
        a.along = a.info.length * FIX + CONVOY_GAP
        b.at = roadStep(t0, Track.XM, Track.XP)
        b.along = 8 * FIX
        return w.roadFreeAhead(b, hashMapOf(t0 to arrayListOf(b), t1 to arrayListOf(a)))
    }

    @Test
    fun aPadIsBuiltJoinedAndServedByADrone() {
        val w = pads(highway())
        assertEquals(3, w.airports.size)
        val pad = w.airports[0]!!
        assertEquals(Airports.PAD, pad.kind)
        // Joined to the road stop's station beside it, one tile.
        assertEquals(w.roadStops[w.index(8, 20)], pad.station)
        assertEquals(1, w.stations[pad.station]!!.aero.size)
        assertEquals(w.index(8, 21), w.depots[pad.depot]!!.tile)
        // A drone flies between the pads and calls at both.
        val v = w.buyDrone("mail_drone", 0, intArrayOf(pad.station, w.airports[1]!!.station))!!
        assertTrue(v.heli)
        val called = calls(w, v, 20_000)
        assertTrue(called.size >= 4, "called at $called")
        assertEquals(listOf(pad.station, w.airports[1]!!.station), called.take(2))
        // Saves and plays the same.
        val again = Save.read(Save.write(w))
        assertEquals(w.checksum(), again.checksum())
        repeat(2_000) { w.step(); again.step() }
        assertEquals(w.checksum(), again.checksum())
        // Pads are in the checksum.
        assertEquals(3, again.airports.count { it?.kind == Airports.PAD })
    }

    @Test
    fun dronesKeepToPadsTheirRangeAndSmallCargo() {
        val w = pads(highway(flat(2055)))
        w.apply(Command.Airport(0, 30, 40, Airports.HELIPORT, Track.X))
        val heliport = w.airports.last()!!
        val drone = model(w, "mail_drone")
        val pad = w.airports[0]!!
        // Drones only at pads, nothing else at pads.
        assertTrue(pad.serves(drone))
        assertFalse(heliport.serves(drone))
        assertFalse(pad.serves(model(w, "jetranger")))
        assertNull(w.buyDrone("mail_drone", heliport.id, intArrayOf(heliport.station)))
        // Ranges in tiles of 750 m: 20, 16 and 40.
        assertEquals(listOf(15, 12, 30), listOf("mail_drone", "freight_drone", "tiltrotor_drone").map { model(w, it).range })
        // Mail, goods and food only, a few at a time.
        for (key in listOf("mail_drone", "freight_drone", "tiltrotor_drone")) {
            val e = model(w, key)
            assertTrue(e.drone && e.capacity in 1..Drones.MOST_LOAD)
            assertTrue(e.cargoes.all { it == Cargo.MAIL || it == Cargo.GOODS || it == Cargo.FOOD })
        }
        val manifest = """{ "format": 1, "id": "x", "version": 1, "name": { "en": "X" } }"""
        val coal = Packs.unzip(zip(mapOf("manifest.json" to manifest, "vehicles.json" to """{ "vehicles": [ { "id": "c", "replaces": "base:mail_drone", "carries": ["coal"] } ] }""")))
        assertEquals(listOf("base:mail_drone: drones carry only mail, goods, food"), assertFailsWith<BadPack> { Roster.build(listOf(Packs.BASE, coal)) }.problems)
        // The far pad, 40 tiles (30 km) off, is past a mail drone's 15 km: it stays home.
        val far = w.airports[2]!!
        val v = w.buyDrone("mail_drone", 0, intArrayOf(pad.station, far.station))!!
        var flew = false
        repeat(6_000) { w.step(); if (v.flying) flew = true }
        assertFalse(flew)
        // A tilt-rotor reaches it.
        val t = w.buyDrone("tiltrotor_drone", 0, intArrayOf(pad.station, far.station))!!
        assertTrue(far.station in calls(w, t, 20_000))
    }

    @Test
    fun dronesAreGroundedInWindAndSnow() {
        val w = pads(highway(flat(weather = true)))
        val pad = w.airports[0]!!
        val other = w.airports[1]!!
        val v = w.buyDrone("mail_drone", 0, intArrayOf(pad.station, other.station))!!
        fun calm() { w.weather.wind = 0; w.weather.fronts.clear(); w.weather.anomaly = 0 }
        // Wind: stays on the pad.
        var flew = false
        repeat(3_000) { w.weather.wind = Drones.WIND + 5; w.weather.fronts.clear(); w.step(); if (v.flying) flew = true }
        assertFalse(flew)
        assertEquals(Drones.GROUNDED_WIND, w.droneGrounded(pad))
        assertEquals(Drones.GROUNDED_WIND, w.view(0).aircraft.first { it.id == v.id }.grounded)
        // Snow on the pad: still on it.
        fun snow() {
            w.weather.wind = 0
            w.weather.anomaly = -400
            w.weather.fronts.clear()
            w.weather.fronts += Front(Front.LOW, 8 * 256, 21 * 256, 0, 0, 30, 1_000, 0, 10, false)
        }
        repeat(3_000) { snow(); w.step(); if (v.flying) flew = true }
        assertFalse(flew)
        assertEquals(Drones.GROUNDED_SNOW, w.droneGrounded(pad))
        // Calm and dry: off it goes.
        assertTrue(other.station in calls(w, v, 20_000) { calm() })
        assertEquals(Drones.FLYING, w.droneGrounded(pad))
        // One in the air lands at its pad whatever the weather.
        w.weather.wind = 60
        assertNull(w.cantLand(v, other))
        // The forecast gives the days ahead.
        calm()
        assertTrue(w.droneOutlook(8, 21).all { it == Drones.FLYING })
        w.weather.fronts += Front(Front.STORM, 32 * 256, 32 * 256, 0, 0, 60, 0, 0, 10, false)
        assertEquals(Drones.GROUNDED_WIND, w.droneOutlook(8, 21)[0])
        assertTrue(flat(weather = false).droneOutlook(8, 21).isEmpty())
    }

    @Test
    fun costsByYearAndFutureOff() {
        // No pads before 2030 or with future off.
        for (w in listOf(flat(2029), flat(future = false))) {
            assertEquals(Refusal.NOT_YET, w.airportRefusal(w.companies[0], Command.Airport(0, 20, 30, Airports.PAD, Track.X)))
            assertFalse(Airports.PAD in Airports.entries(Calendar.year(w.startYear, w.day), w.settings.future))
        }
        val w = flat()
        assertEquals(Refusal.NONE, w.airportRefusal(w.companies[0], Command.Airport(0, 20, 30, Airports.PAD, Track.X)))
        assertTrue(Airports.PAD in Airports.entries(2045, future = true))
        assertFalse(Airports.PAD in Airports.entries(2045))
        val money = w.companies[0].money
        w.apply(Command.Airport(0, 20, 30, Airports.PAD, Track.X))
        assertEquals(w.build(Airports.COST[Airports.PAD], Mix.AIRPORT, w.index(20, 30)), money - w.companies[0].money)
        assertEquals(1_500L, Airports.COST[Airports.PAD])
        // Trucks and drones not on sale with future off, nor bought.
        val off = highway(flat(future = false))
        for (key in listOf("auto_tractor", "mail_drone")) assertFalse(off.onSale(model(off, key)))
        off.apply(Command.BuyRoadVehicle(0, 0, model(off, "auto_tractor").id))
        assertEquals(0, off.roadVehicles.size)
        assertTrue(w.onSale(model(w, "auto_tractor")) && w.onSale(model(w, "mail_drone")))
        // By year.
        assertFalse(flat(2034).onSale(model(w, "auto_tractor")))
        assertFalse(flat(2054).onSale(model(w, "convoy_tractor")))
        assertFalse(flat(2029).onSale(model(w, "mail_drone")))
        // Drones pay the electric price.
        assertEquals(EraCosts.ELECTRIC, EraCosts.fuelOf(model(w, "mail_drone")))
    }

    @Test
    fun save75KeepsStep() {
        val w = pads(highway())
        repeat(3) { w.apply(Command.BuyRoadVehicle(0, 0, model(w, "auto_tractor").id)) }
        for (id in 0 until 3) {
            w.apply(Command.RoadOrder(0, id, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
            w.apply(Command.RoadOrder(0, id, Command.TrainOrder.START_STOP))
        }
        w.buyDrone("freight_drone", 0, intArrayOf(w.airports[0]!!.station, w.airports[1]!!.station))
        repeat(3_000) { w.step() }
        assertEquals(75, Save.VERSION)
        val again = Save.read(Save.write(w))
        assertEquals(w.checksum(), again.checksum())
        repeat(3_000) { w.step(); again.step() }
        assertEquals(w.checksum(), again.checksum())
        assertTrue(again.roadVehicles.all { it!!.info.autonomous })
    }

    private fun zip(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, v) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(v.encodeToByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
