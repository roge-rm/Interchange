package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SaveTest {
    /** A line with two stations, a depot and a running train, from the train tests. */
    private fun busy(): World {
        val w = World(64, 64, 1)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        w.apply(Command.Signal(0, 20, 10, Track.X, Track.XM))
        w.apply(Command.BuyTrain(0, 0, 0, 18, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        repeat(700) { w.step() }
        return w
    }

    @Test
    fun aLoadedWorldCarriesOnExactlyAsTheSavedOne() {
        val a = busy()
        val b = Save.read(Save.write(a))
        assertEquals(a.checksum(), b.checksum())
        // Both run on, a command mid-way, and stay the same.
        repeat(3_000) { i ->
            if (i == 1_000) for (w in listOf(a, b)) w.apply(Command.Rail(0, 20, 11, 30, 11, Track.X, remove = false))
            a.step()
            b.step()
        }
        assertEquals(a.checksum(), b.checksum())
    }

    @Test
    fun aGeneratedMapSurvivesSavingAndLoading() {
        val a = World(NewGame(width = 128, height = 64, seed = 7, region = Region.SHIELD, hills = 2))
        val b = Save.read(Save.write(a))
        assertContentEquals(a.corners, b.corners)
        assertContentEquals(a.trees, b.trees)
        assertEquals(a.settings, b.settings)
    }

    @Test
    fun commandsReadBackAsWritten() {
        val commands = listOf(
            Command.Terraform(0, 3, -4, true),
            Command.Rail(0, 1, 2, 30, 2, Track.X, remove = true),
            Command.Station(0, 5, 5, 8, 6, Track.Y),
            Command.Depot(0, 9, 9, Track.YP),
            Command.Signal(0, 4, 4, Track.N, Track.YM),
            Command.Clear(0, 1, 1),
            Command.BuyTrain(0, 2, 4, 22, 9),
        )
        for (c in commands) assertEquals(c, Codec.decode(Codec.encode(c)))
        val orders = Codec.decode(Codec.encode(Command.TrainOrder(0, 3, Command.TrainOrder.ORDERS, intArrayOf(2, 0, 5)))) as Command.TrainOrder
        assertContentEquals(intArrayOf(2, 0, 5), orders.stations)
    }

    @Test
    fun aRecordedGamePlaysBackTheSame() {
        // The commands of a game, each with the tick it ran on, recorded as bytes and played into a fresh world.
        val log = ByteWriter()
        val a = World(64, 64, 3)
        val script = mapOf(5L to Command.Terraform(0, 10, 10, true), 40L to Command.Rail(0, 4, 4, 20, 4, Track.X, remove = false), 90L to Command.Terraform(0, 30, 30, false))
        repeat(200) {
            script[a.tick]?.let { c -> log.long(a.tick); Codec.write(log, c); a.apply(c) }
            a.step()
        }
        val b = World(64, 64, 3)
        val r = ByteReader(log.toByteArray())
        var next = if (r.done) Long.MAX_VALUE else r.long()
        repeat(200) {
            while (next == b.tick) { b.apply(Codec.read(r)); next = if (r.done) Long.MAX_VALUE else r.long() }
            b.step()
        }
        assertEquals(a.checksum(), b.checksum())
    }

    @Test
    fun damagedSavesAreTurnedAwayPlainly() {
        assertFailsWith<BadData> { Save.read("not a save".encodeToByteArray()) }
        val good = Save.write(World(64, 64, 1))
        assertFailsWith<BadData> { Save.read(good.copyOf(good.size / 2)) }
    }
}
