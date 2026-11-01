package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Player trains on a loop stay on the map, or in their depot's list, whatever happens to them. */
class LoopTest {
    private fun world(collisions: Boolean): World {
        val w = World(NewGame(48, 32, 1, 1900, towns = 0, industries = 0, weather = false, collisions = collisions))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        return w
    }

    /**
     * A loop from x = 10 to 30 and y = 10 to 20 with cut corners, stations on its top (x = 14 to 16) and bottom
     * (x = 24 to 26), and a depot at (7, 15) joined to its west side both ways. With [signals], one-way path signals
     * all round so trains go clockwise.
     */
    private fun loop(collisions: Boolean, signals: Boolean): World {
        val w = world(collisions)
        w.apply(Command.Rail(0, 11, 10, 29, 10, Track.X, remove = false))
        w.apply(Command.Rail(0, 11, 20, 29, 20, Track.X, remove = false))
        w.apply(Command.Rail(0, 10, 11, 10, 19, Track.Y, remove = false))
        w.apply(Command.Rail(0, 30, 11, 30, 19, Track.Y, remove = false))
        w.apply(Command.Rail(0, 10, 10, 10, 10, Track.S, remove = false))
        w.apply(Command.Rail(0, 30, 10, 30, 10, Track.W, remove = false))
        w.apply(Command.Rail(0, 30, 20, 30, 20, Track.N, remove = false))
        w.apply(Command.Rail(0, 10, 20, 10, 20, Track.E, remove = false))
        w.apply(Command.Depot(0, 7, 15, Track.XP))
        w.apply(Command.Rail(0, 8, 15, 9, 15, Track.X, remove = false))
        w.apply(Command.Rail(0, 10, 15, 10, 15, Track.N, remove = false))
        w.apply(Command.Rail(0, 10, 15, 10, 15, Track.W, remove = false))
        w.apply(Command.Station(0, 14, 10, 16, 10, Track.X))
        w.apply(Command.Station(0, 24, 20, 26, 20, Track.X))
        if (signals) {
            // A path signal goes one-way on a second tap, as in the game.
            fun oneWay(x: Int, y: Int, piece: Int, facing: Int) = repeat(2) { w.apply(Command.Signal(0, x, y, piece, facing, Signals.PATH)) }
            for (x in intArrayOf(12, 20, 28)) oneWay(x, 10, Track.X, Track.XM)
            for (y in intArrayOf(12, 17)) oneWay(30, y, Track.Y, Track.YM)
            for (x in intArrayOf(28, 20, 12)) oneWay(x, 20, Track.X, Track.XP)
            for (y in intArrayOf(18, 12)) oneWay(10, y, Track.Y, Track.YP)
            assertEquals(10, w.signals.values.count { Signals.kind(it) == Signals.ONE_WAY_PATH })
            // Out of the depot onto the loop through a path signal, so a train setting out holds its way in.
            w.apply(Command.Signal(0, 8, 15, Track.X, Track.XM, Signals.PATH))
        }
        return w
    }

    private fun buy(w: World, orders: IntArray?): Train {
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:american" }.id, w.roster.engines.first { it.key == "base:coach_wood" }.id, 2))
        val id = w.trains.size - 1
        if (orders != null) w.apply(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, orders))
        w.apply(Command.TrainOrder(0, id, Command.TrainOrder.START_STOP))
        return w.trains[id]!!
    }

    /** Car keys the map would draw this tick, each train's id times 256 plus the car. */
    private fun World.drawn(): Set<Int> {
        val v = view().vehicles
        return (0 until v.count).map { v.key(it) / 256 }.toSet()
    }

    /**
     * Checks every train in [ids] each work day for [days]: it's still in the world, and either in its depot or on
     * track that's there with some of it drawn, or a wreck named in crash news. One just setting out can be out of
     * sight in its depot's door for a day. Gives how many times each moved on to its next order.
     */
    private fun watch(w: World, ids: List<Int>, days: Int): IntArray {
        val hidden = IntArray(ids.size)
        val calls = IntArray(ids.size)
        val order = IntArray(ids.size) { w.trains[ids[it]]?.order ?: 0 }
        repeat(days) { day ->
            repeat(Calendar.TICKS_PER_DAY) { w.step() }
            val drawn = w.drawn()
            val crashed = w.crashes.list.flatMap { c -> c.vehicles.map { it.id } }.toSet()
            for ((k, id) in ids.withIndex()) {
                val t = w.trains.getOrNull(id)
                if (t == null) {
                    assertTrue(id in crashed, "train $id gone on day $day with no crash news")
                    continue
                }
                if (t.order != order[k]) calls[k]++
                order[k] = t.order
                if (t.inDepot) {
                    hidden[k] = 0
                    continue
                }
                for (s in t.steps) assertTrue(w.track[stepTile(s)].toInt() and stepPiece(s) != 0 || stepLevel(s) == 1, "train $id off the track on day $day")
                hidden[k] = if (id in drawn) 0 else hidden[k] + 1
                assertTrue(hidden[k] <= 1, "train $id out of its depot but not drawn on day $day: steps ${t.steps.map { "(${stepTile(it) % w.width},${stepTile(it) / w.width}) ${stepPiece(it)}" }} nose ${t.nose} speed ${t.speed} running ${t.running} target ${t.target} broken ${t.brokenFor} toDepot ${t.toDepot}")
            }
        }
        return calls
    }

    @Test
    fun aTrainStartedWithNoOrdersWaitsInItsDepot() {
        val w = loop(collisions = true, signals = true)
        val t = buy(w, null)
        repeat(Calendar.TICKS_PER_DAY * 5) { w.step() }
        // Set going, it stays in its depot's list till it has somewhere to go.
        assertTrue(t.inDepot && t.running)
        assertTrue(w.view().trains.any { it.id == t.id && it.inDepot && it.depot == 0 })
        // Given orders, it sets out.
        w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.ORDERS, intArrayOf(Order.withWait(0, 2), Order.withWait(1, 2))))
        watch(w, listOf(t.id), 20)
        assertTrue(!t.inDepot)
    }

    @Test
    fun aTrainLeftAtItsDoorGoesBackIn() {
        val w = loop(collisions = true, signals = true)
        val t = buy(w, intArrayOf(Order.withWait(0, 2)))
        w.step()
        assertTrue(!t.inDepot)
        // Its orders taken away with its engine still inside: back in, so it's listed and the door's clear.
        w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.ORDERS, IntArray(0)))
        repeat(Calendar.TICKS_PER_DAY) { w.step() }
        assertTrue(t.inDepot && t.running)
        assertTrue(!w.trainOnTile(w.index(7, 15)))
    }

    /** A train out of the depot with its engine on the lead and its tail still inside. */
    private fun halfOut(w: World): Train {
        val t = buy(w, intArrayOf(Order.withWait(0, 2), Order.withWait(1, 2)))
        var ticks = 0
        while (t.inDepot || (t.behind(t.carMiddle(0)) shr 32) < 0) { w.step(); assertTrue(ticks++ < 200) }
        assertTrue((t.behind(t.length) shr 32) < 0)
        return t
    }

    @Test
    fun aTrainSentToItsDepotAndStoppedInTheDoorGoesBackIn() {
        val w = loop(collisions = true, signals = true)
        val t = buy(w, intArrayOf(Order.withWait(0, 2)))
        w.step()
        w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.TO_DEPOT))
        assertTrue(t.toDepot)
        w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.START_STOP))
        repeat(Calendar.TICKS_PER_DAY) { w.step() }
        // Its trip to the depot is over, so it can be sent again.
        assertTrue(t.inDepot && !t.running && !t.toDepot && !t.servicing)
    }

    @Test
    fun aTrainBrokenDownInTheDoorGoesBackIn() {
        val w = loop(collisions = true, signals = true)
        val t = buy(w, intArrayOf(Order.withWait(0, 2)))
        w.step()
        t.brokenFor = Calendar.TICKS_PER_DAY * 2
        var ticks = 0
        // Back in once it's rolled to a stand, the breakdown over; it sets out again after.
        while (!t.inDepot) { w.step(); assertTrue(ticks++ < 32, "still out") }
        assertTrue(t.running && t.brokenFor == 0)
    }

    @Test
    fun aTrainHalfOutOfTheDoorWithNoOrdersGoesBackIn() {
        val w = loop(collisions = true, signals = true)
        val t = halfOut(w)
        w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.ORDERS, IntArray(0)))
        repeat(Calendar.TICKS_PER_DAY) { w.step() }
        assertTrue(t.inDepot && t.running)
        assertTrue(!w.trainOnTile(w.index(7, 15)))
    }

    /** Sends a train out on the loop back to its depot and steps till it's nearly stood wholly in the shed, about to go in. */
    private fun intoShed(w: World): Train {
        val t = buy(w, intArrayOf(Order.withWait(0, 2), Order.withWait(1, 2)))
        repeat(Calendar.TICKS_PER_DAY * 3) { w.step() }
        assertTrue(!t.inDepot)
        w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.TO_DEPOT))
        val depot = w.index(7, 15)
        var ticks = 0
        while (true) {
            assertTrue(!t.inDepot && ticks++ < Calendar.TICKS_PER_DAY * 120, "never got to the shed: in ${t.inDepot} ticks $ticks at ${t.steps.lastOrNull()?.let { "(${stepTile(it) % w.width},${stepTile(it) / w.width})" }} nose ${t.nose} speed ${t.speed} toDepot ${t.toDepot} held ${t.held} waiting ${t.waiting} lost ${t.lostUntil > w.tick}")
            val last = t.steps.last()
            if (stepTile(last) == depot && t.nose + t.speed / 16 >= stepLength(last) + t.length - 64) return t
            w.step()
        }
    }

    @Test
    fun aTrainStoppedWhollyInTheShedGoesIn() {
        val w = loop(collisions = true, signals = true)
        val t = intoShed(w)
        w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.START_STOP))
        assertTrue(!t.running && !t.inDepot)
        repeat(Calendar.TICKS_PER_DAY / 2) { w.step() }
        assertTrue(t.inDepot && !t.running && !t.toDepot)
    }

    @Test
    fun aTrainBrokenDownWhollyInTheShedGoesIn() {
        val w = loop(collisions = true, signals = true)
        val t = intoShed(w)
        t.brokenFor = Calendar.TICKS_PER_DAY * 2
        repeat(Calendar.TICKS_PER_DAY / 2) { w.step() }
        assertTrue(t.inDepot && t.brokenFor == 0 && !t.toDepot)
    }

    @Test
    fun trainsOnASignalledLoopStayOnTheMap() {
        for (collisions in listOf(true, false)) {
            val w = loop(collisions, signals = true)
            val orders = intArrayOf(Order.withWait(0, 2), Order.withWait(1, 2))
            val ids = (0 until 3).map { buy(w, orders).id }
            val calls = watch(w, ids, 365 * 3)
            // Unlocked they make 48 to 53 calls each.
            assertTrue(calls.all { it >= 40 }, "too few calls: ${calls.toList()}, collisions $collisions")
            assertEquals(0, w.crashes.list.size, "a crash on a signalled loop, collisions $collisions: ${w.crashes.list.map { c -> "${c.vehicles.map { it.id }} at (${c.tile % w.width},${c.tile / w.width})" }}")
            assertTrue(ids.all { w.trains[it] != null })
        }
    }

    @Test
    fun aTrainWaitsInItsDepotWhileOneComesInAlongTheLead() {
        val w = loop(collisions = true, signals = true)
        val a = buy(w, intArrayOf(Order.withWait(0, 2), Order.withWait(1, 2)))
        repeat(Calendar.TICKS_PER_DAY * 3) { w.step() }
        w.apply(Command.TrainOrder(0, a.id, Command.TrainOrder.TO_DEPOT))
        // A second train waits in the depot, stopped, till the first is on the lead coming in.
        val b = buy(w, intArrayOf(Order.withWait(0, 2), Order.withWait(1, 2)))
        w.apply(Command.TrainOrder(0, b.id, Command.TrainOrder.START_STOP))
        val lead = setOf(w.index(8, 15), w.index(9, 15))
        var ticks = 0
        assertTrue(a.toDepot)
        while (!(stepTile(a.steps.last()) in lead && stepEntry(a.steps.last()) == Track.XP)) { w.step(); assertTrue(a.toDepot && ticks++ < Calendar.TICKS_PER_DAY * 120, "never came to the lead") }
        w.apply(Command.TrainOrder(0, b.id, Command.TrainOrder.START_STOP))
        // It stays in till the first is in, then sets out.
        ticks = 0
        while (!a.inDepot) {
            w.step()
            assertTrue(b.inDepot, "set out with a train coming in: a ${a.steps.map { "(${stepTile(it) % w.width},${stepTile(it) / w.width}) ${stepPiece(it)} e${stepEntry(it)}" }} nose ${a.nose} b ${b.steps.map { "(${stepTile(it) % w.width},${stepTile(it) / w.width}) ${stepPiece(it)}" }} ticks $ticks reserved ${w.reserved.map { "(${(it.key / 8) % w.width},${(it.key / 8) / w.width}) ${it.key % 8} ${it.value}" }}")
            assertTrue(ticks++ < Calendar.TICKS_PER_DAY * 10, "the first never got in")
        }
        repeat(Calendar.TICKS_PER_DAY * 20) { w.step() }
        assertTrue(!b.inDepot && !b.stuck)
        assertEquals(0, w.crashes.list.size)
    }

    @Test
    fun aTrainWaitsInItsDepotWhileOneGoesByOnPlainTrack() {
        for (collisions in listOf(true, false)) {
            val w = loop(collisions, signals = false)
            val orders = intArrayOf(Order.withWait(0, 2), Order.withWait(1, 2))
            val a = buy(w, orders)
            // Two more, stopped in the depot.
            val waiting = (0 until 2).map { buy(w, orders).also { t -> w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.START_STOP)) } }
            val junction = w.index(10, 15)
            var ticks = 0
            // Once round to its first call, then till its nose comes onto the junction by the depot.
            while (a.order == 0) { w.step(); assertTrue(ticks++ < Calendar.TICKS_PER_DAY * 60, "never called") }
            while (stepTile(a.steps.last()) != junction) { w.step(); assertTrue(ticks++ < Calendar.TICKS_PER_DAY * 120, "never came by the depot") }
            for (t in waiting) w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.START_STOP))
            // They stay in while it goes over the junction, then set out, one behind the other.
            while (a.steps.any { stepTile(it) == junction }) {
                w.step()
                assertTrue(waiting.all { it.inDepot }, "set out in front of a train going by, collisions $collisions")
            }
            ticks = 0
            while (waiting.any { it.inDepot }) { w.step(); assertTrue(ticks++ < Calendar.TICKS_PER_DAY * 30, "never set out, collisions $collisions") }
            assertEquals(0, w.crashes.list.size, "a crash setting out, collisions $collisions")
        }
    }

    @Test
    fun trainsOnALoopWithoutSignalsAreOnlyGoneByACrash() {
        for (collisions in listOf(true, false)) {
            val w = loop(collisions, signals = false)
            val ids = listOf(intArrayOf(Order.withWait(0, 2), Order.withWait(1, 2)), intArrayOf(Order.withWait(1, 2), Order.withWait(0, 2)), null)
                .map { buy(w, it).id }
            watch(w, ids, 365 * 2)
            if (!collisions) assertTrue(ids.all { w.trains[it] != null })
        }
    }
}
