package com.rm.interchange.sim

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HyperloopTest {
    private fun id(w: World, key: String) = w.roster.engines.first { it.key == key }.id

    /** A flat 128 map from [year], with plenty of money. */
    private fun flat(year: Int = 2065, future: Boolean = true): World {
        val w = World(NewGame(128, 128, 1, year, towns = 0, industries = 0, weather = false, handling = false, eraCosts = false, future = future))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 1_000_000_000
        return w
    }

    /**
     * A tube line along y = 10 with portals at x 10 and 110, a depot behind the first and two Vela pods running. Between
     * x 49 and 70 it's bored under a road, a rail line and a hill.
     */
    private fun line(w: World = flat()): World {
        w.apply(Command.Road(0, 50, 5, 50, 15, Surface.PAVED, remove = false))
        w.apply(Command.Rail(0, 55, 5, 55, 15, Track.Y, remove = false))
        for (y in 6..14) for (x in 60..63) w.corners[y * (w.width + 1) + x] = 6
        w.apply(Command.Depot(0, 2, 10, Track.XP, tube = true))
        w.apply(Command.Rail(0, 3, 10, 49, 10, Track.X, remove = false, tube = true))
        w.apply(Command.Rail(0, 70, 10, 120, 10, Track.X, remove = false, tube = true))
        w.apply(Command.Bridge(0, 49, 10, 70, 10, Spans.TUNNEL, tube = true))
        w.apply(Command.Station(0, 10, 10, 14, 10, Track.X, tube = true))
        w.apply(Command.Station(0, 106, 10, 110, 10, Track.X, tube = true))
        val vela = id(w, "base:vela")
        w.apply(Command.BuyTrain(0, 0, vela, id(w, "base:aurora_coach"), 0))
        w.apply(Command.EditTrain(0, 0, listOf(TrainCar(vela), TrainCar(vela))))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        return w
    }

    @Test
    fun anOldSaveWithGuidewayLoadsAndPlaysAsBefore() {
        val w = flat(2045)
        w.apply(Command.Depot(0, 2, 10, Track.XP, maglev = true))
        w.apply(Command.Rail(0, 3, 10, 120, 10, Track.X, remove = false, maglev = true))
        w.apply(Command.Station(0, 10, 10, 14, 10, Track.X, maglev = true))
        w.apply(Command.Station(0, 106, 10, 110, 10, Track.X, maglev = true))
        w.apply(Command.BuyTrain(0, 0, id(w, "base:aurora"), id(w, "base:aurora_coach"), 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        repeat(2_000) { w.step() }
        val old = Save.read(Save.write(w, version = 73))
        val now = Save.read(Save.write(w))
        assertTrue(old.tube.none { it })
        assertContentEquals(w.guideway, old.guideway)
        assertEquals(w.checksum(), old.checksum())
        assertEquals(w.checksum(), now.checksum())
        repeat(3_000) { w.step(); old.step(); now.step() }
        assertEquals(w.checksum(), old.checksum())
        assertEquals(w.checksum(), now.checksum())
        // No tube before 2060.
        old.apply(Command.Rail(0, 3, 30, 20, 30, Track.X, remove = false, tube = true))
        assertTrue(old.tube.none { it })
    }

    @Test
    fun aTubeLineIsBuiltRunAndLifted() {
        val w = line()
        assertTrue((3..49).all { w.tube[w.index(it, 10)] && w.trackAt(it, 10) == Track.X })
        assertTrue(w.tube[w.index(2, 10)] && w.tube[w.index(110, 10)])
        // The bore runs under the road and the rail, which stay as they were.
        assertEquals(1, w.spans.size)
        assertTrue(w.spans[0]!!.tunnel && w.bore(w.spans[0]!!))
        assertEquals(Track.Y, w.trackAt(55, 10))
        assertFalse(w.tube[w.index(55, 10)])
        assertTrue(w.road[w.index(50, 10)].toInt() != 0)
        assertEquals(1, w.trains.size)
        val t = w.trains[0]!!
        assertTrue(t.hyperloop)
        assertEquals(2, t.formation.size)
        var fastest = 0
        var arrived = false
        repeat(20_000) {
            w.step()
            fastest = maxOf(fastest, t.speed)
            if (t.waiting > 0 && !t.inDepot && w.stationTiles[stepTile(t.steps.last())] == 1) arrived = true
        }
        assertTrue(arrived, "never got there")
        // Well past the maglev's 600 km/h.
        assertTrue(fastest * 2700 / 4096 / 16 > 700, "${fastest * 2700 / 4096 / 16} km/h")
        // Lifted by the tube tool only, where no pod is.
        w.apply(Command.Rail(0, 80, 10, 90, 10, Track.X, remove = true))
        w.apply(Command.Rail(0, 80, 10, 90, 10, Track.X, remove = true, maglev = true))
        assertEquals(Track.X, w.trackAt(85, 10))
        w.apply(Command.Rail(0, 80, 10, 90, 10, Track.X, remove = true, tube = true))
        assertTrue((80..90).all { w.trackAt(it, 10) == 0 && !w.tube[w.index(it, 10)] || w.trainOnTile(w.index(it, 10)) })
    }

    @Test
    fun kindsNeverJoin() {
        val w = flat()
        w.apply(Command.Rail(0, 3, 20, 20, 20, Track.X, remove = false, tube = true))
        // Rail and guideway laid on into the tube's end aren't laid where they would lead into it, nor over it.
        w.apply(Command.Rail(0, 21, 20, 30, 20, Track.X, remove = false))
        assertEquals(0, w.trackAt(21, 20))
        w.apply(Command.Rail(0, 21, 20, 30, 20, Track.X, remove = false, maglev = true))
        assertEquals(0, w.trackAt(21, 20))
        assertEquals(Track.X, w.trackAt(22, 20))
        w.apply(Command.Rail(0, 10, 15, 10, 25, Track.Y, remove = false, maglev = true))
        assertEquals(Track.X, w.trackAt(10, 20))
        assertTrue(w.tube[w.index(10, 20)] && !w.guideway[w.index(10, 20)])
        // Nor tube into rail or guideway.
        w.apply(Command.Rail(0, 40, 30, 50, 30, Track.X, remove = false))
        w.apply(Command.Rail(0, 30, 30, 39, 30, Track.X, remove = false, tube = true))
        assertEquals(0, w.trackAt(39, 30))
        w.apply(Command.Rail(0, 40, 35, 50, 35, Track.X, remove = false, maglev = true))
        w.apply(Command.Rail(0, 30, 35, 39, 35, Track.X, remove = false, tube = true))
        assertEquals(0, w.trackAt(39, 35))
        // From the tube's end there's no way on.
        assertEquals(0, w.waysOn(step(w.index(20, 20), Track.index(Track.X), Track.XM)).size)
        // Pods are bought only in a tube depot, and only pods there.
        w.apply(Command.Depot(0, 2, 20, Track.XP, tube = true))
        w.apply(Command.Depot(0, 51, 30, Track.XM))
        w.apply(Command.Depot(0, 51, 35, Track.XM, maglev = true))
        assertEquals(TrackKind.TUBE, w.depotKind(w.depots[0]!!))
        assertEquals(TrackKind.RAIL, w.depotKind(w.depots[1]!!))
        assertEquals(TrackKind.GUIDEWAY, w.depotKind(w.depots[2]!!))
        val vela = id(w, "base:vela")
        val coach = id(w, "base:aurora_coach")
        w.apply(Command.BuyTrain(0, 1, vela, coach, 0))
        w.apply(Command.BuyTrain(0, 2, vela, coach, 0))
        w.apply(Command.BuyTrain(0, 0, id(w, "base:aurora"), coach, 3))
        w.apply(Command.BuyTrain(0, 0, vela, coach, 2))
        assertEquals(0, w.trains.size)
        w.apply(Command.BuyTrain(0, 0, vela, coach, 0))
        assertEquals(1, w.trains.size)
        assertTrue(w.trains[0]!!.hyperloop)
        assertFalse(validTrainFormation(w.roster, listOf(TrainCar(vela), TrainCar(coach))))
        assertFalse(validTrainFormation(w.roster, listOf(TrainCar(vela), TrainCar(id(w, "base:coach_streamline")))))
        // No wires on tube, and no level crossing either way.
        w.apply(Command.Wire(0, 3, 20, 20, 20, Track.X, remove = false))
        assertTrue((3..20).all { w.wires[w.index(it, 20)].toInt() == 0 })
        w.apply(Command.Road(0, 5, 60, 30, 60, Surface.PAVED, remove = false))
        w.apply(Command.Rail(0, 10, 55, 10, 65, Track.Y, remove = false, tube = true))
        assertEquals(0, w.trackAt(10, 60))
        w.apply(Command.Road(0, 12, 15, 12, 25, Surface.PAVED, remove = false))
        assertEquals(0, w.road[w.index(12, 20)].toInt())
        // Rivals take no pods.
        assertTrue(w.roster.engines.filter { it.hyperloop }.all { it.future })
    }

    @Test
    fun podsCarryOnlyPassengersAndMail() {
        val w = flat()
        for (key in listOf("base:vela", "base:photon")) {
            val e = w.roster[id(w, key)]
            assertEquals(Traction.HYPERLOOP, e.traction)
            assertContentEquals(intArrayOf(Cargo.PASSENGERS, Cargo.MAIL), e.cargoes)
        }
        val manifest = """{ "format": 4, "id": "x", "version": 1, "name": { "en": "X" } }"""
        val coal = Packs.unzip(zip(mapOf("manifest.json" to manifest, "vehicles.json" to """{ "vehicles": [ { "id": "c", "replaces": "base:vela", "carries": ["passengers", "coal"] } ] }""")))
        assertEquals(listOf("base:vela: hyperloop pods carry only passengers and mail"), assertFailsWith<BadPack> { Roster.build(listOf(Packs.BASE, coal)) }.problems)
    }

    @Test
    fun tubeCostsByYear() {
        val c = Command.Rail(0, 10, 10, 19, 10, Track.X, remove = false, tube = true)
        // Not before its year, and not with future transport off.
        assertEquals(0, flat(2059).railCost(c).second)
        assertEquals(0, flat(2065, future = false).railCost(c).second)
        assertFalse(flat(2065, future = false).onSale(flat(2065, future = false).roster[id(flat(), "base:vela")]))
        val w = flat()
        assertEquals(10 * Hyperloop.TRACK to 10, w.railCost(c))
        assertEquals(10 * Maglev.TRACK to 10, w.railCost(c.copy(tube = false, maglev = true)))
        w.apply(c)
        w.apply(Command.Rail(0, 10, 12, 19, 12, Track.X, remove = false))
        // Kept up as six tiles of rail each.
        repeat(Calendar.TICKS_PER_DAY * 2) { w.step() }
        assertEquals(10 * Hyperloop.UPKEEP_TILES + 10, w.companies[0].trackTiles)
        // Trains don't wear it.
        w.wearBy(w.index(12, 10), 50_000_000L)
        assertEquals(255, w.condition[w.index(12, 10)].toInt() and 255)
        // A bore costs its tiles and its heads; a concrete span four times rail's; no trestle.
        val bore = Command.Bridge(0, 30, 40, 51, 40, Spans.TUNNEL, tube = true)
        assertEquals(20 * Hyperloop.BORE + 2 * Hyperloop.TRACK, w.bridgeCost(w.companies[0], bore))
        assertEquals(-1L, flat(2059).bridgeCost(flat(2059).companies[0], bore))
        assertEquals(-1L, w.bridgeCost(w.companies[0], bore.copy(x1 = 30 + Hyperloop.BORE_LONGEST + 2)))
        assertEquals(-1L, w.bridgeCost(w.companies[0], Command.Bridge(0, 30, 50, 36, 50, Spans.TRESTLE, tube = true)))
        assertEquals(-1L, w.bridgeCost(w.companies[0], Command.Bridge(0, 30, 50, 36, 50, Spans.TUNNEL)))
        assertEquals(EraCosts.ELECTRIC, EraCosts.fuelOf(w.roster[id(w, "base:vela")]))
    }

    @Test
    fun aBoreNeverCrossesADip() {
        val w = flat()
        val c = w.companies[0]
        val bore = Command.Bridge(0, 30, 40, 40, 40, Spans.TUNNEL, tube = true)
        assertEquals(Refusal.NONE, w.bridgeRefusal(c, bore))
        // Higher ground between the heads is fine.
        for (y in 40..41) w.corners[y * (w.width + 1) + 35] = 5
        assertEquals(Refusal.NONE, w.bridgeRefusal(c, bore))
        // A dip is refused.
        w.corners[40 * (w.width + 1) + 36] = 2
        assertEquals(Refusal.NOT_LEVEL, w.bridgeRefusal(c, bore))
    }

    @Test
    fun podBendsAndBraking() {
        val steps = intArrayOf(step(0, Track.index(Track.X), Track.XM), step(1, Track.index(Track.X), Track.XM), step(2, Track.index(Track.W), Track.XM))
        assertEquals(Hyperloop.GENTLE_KMH, bendCaps(steps, hyperloop = true).max())
        // Braking reach from 1,000 km/h at twice a train's braking, in tiles: well under a hundred.
        val v = 1000L * 4096 * 16 / 2700
        val tiles = v * v / (2L * BRAKE * Hyperloop.BRAKE_TIMES) / 16 / (16 * FIX)
        assertTrue(tiles in 40..70, "$tiles")
    }

    @Test
    fun save74KeepsStep() {
        val w = line()
        repeat(3_000) { w.step() }
        val again = Save.read(Save.write(w))
        assertTrue((3..49).all { again.tube[again.index(it, 10)] })
        assertEquals(w.checksum(), again.checksum())
        repeat(3_000) { w.step(); again.step() }
        assertEquals(w.checksum(), again.checksum())
        assertEquals(TrackKind.TUBE, again.depotKind(again.depots[0]!!))
        assertTrue(again.bore(again.spans[0]!!))
        // Tube is in the checksum.
        again.tube[again.index(30, 10)] = false
        assertFalse(w.checksum() == again.checksum())
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
