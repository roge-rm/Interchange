package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps the money in balance: a year of full vehicles running between two stations, with no weather, must earn
 * comfortably more than they cost to run, trains most of all.
 */
class FareTest {
    private fun run(weather: Boolean, year: Int, key: String, gap: Int): Pair<Long, Long> {
        val w = World(NewGame(96, 64, 1, year, Region.MIXED, towns = 0, industries = 0, weather = weather))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 10_000_000
        // Two towns' worth of people: a big block of houses round each stop.
        val y = 30
        val x0 = 10
        val x1 = x0 + gap
        w.apply(Command.Road(0, x0, y, x1, y, Surface.PAVED.coerceAtMost(Surface.entries(year).last()), remove = false))
        w.apply(Command.Road(0, x0, y, x0, y + 2, Surface.DIRT, remove = false))
        w.apply(Command.Garage(0, x0, y + 3, Track.YM))
        w.apply(Command.RoadStop(0, x0 + 2, y))
        w.apply(Command.RoadStop(0, x1 - 2, y))
        val model = w.roster.engines.first { it.key == "base:$key" }
        repeat(3) {
            w.apply(Command.BuyRoadVehicle(0, 0, model.id))
            val id = w.roadVehicles.size - 1
            w.apply(Command.RoadOrder(0, id, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
            w.apply(Command.RoadOrder(0, id, Command.TrainOrder.START_STOP))
        }
        // Plenty waiting at each end, always.
        repeat(Calendar.TICKS_PER_DAY * 365) {
            for (s in w.stations) if (s != null && s.waiting(Cargo.PASSENGERS) < 200) w.addWaiting(s, Cargo.PASSENGERS, if (s.id == 0) 1 else 0, s.id, 100, w.tick, 0)
            w.step()
        }
        val c = w.companies[0]
        return (c.thisYear[Books.INCOME] + c.lastYear[Books.INCOME]) to -(c.thisYear[Books.RUNNING] + c.lastYear[Books.RUNNING])
    }

    private fun train(year: Int, engine: String, wagon: String, wagons: Int, gap: Int): Pair<Long, Long> {
        val w = World(NewGame(96, 64, 1, year, Region.MIXED, towns = 0, industries = 0, weather = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 10 + gap + 4, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 6, 10, 9, 10, Track.X))
        w.apply(Command.Station(0, 6 + gap, 10, 9 + gap, 10, Track.X))
        val e = w.roster.engines.first { it.key == "base:$engine" }.id
        val wg = w.roster.engines.first { it.key == "base:$wagon" }.id
        w.apply(Command.BuyTrain(0, 0, e, wg, wagons))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        repeat(Calendar.TICKS_PER_DAY * 365) {
            for (s in w.stations) if (s != null && s.waiting(Cargo.PASSENGERS) < 400) w.addWaiting(s, Cargo.PASSENGERS, if (s.id == 0) 1 else 0, s.id, 200, w.tick, 0)
            w.step()
        }
        val c = w.companies[0]
        return (c.thisYear[Books.INCOME] + c.lastYear[Books.INCOME]) to -(c.thisYear[Books.RUNNING] + c.lastYear[Books.RUNNING])
    }

    private fun pays(what: String, r: Pair<Long, Long>, times: Int) =
        assertTrue(r.first * 10 > r.second * times, "$what earns ${r.first} against ${r.second} to run")

    @Test
    fun everyKindOfVehiclePaysItsWay() {
        pays("an 1860 train", train(1860, "american", "coach_wood", 4, 40), 30)
        pays("a 1960 train", train(1960, "gp9", "coach_steel", 6, 60), 30)
        pays("a motor bus", run(false, 1930, "motor_bus", 25), 15)
        pays("a New Look bus", run(false, 1965, "new_look", 25), 20)
        pays("a horse omnibus", run(false, 1850, "horse_omnibus", 15), 15)
        pays("a stagecoach", run(false, 1850, "stagecoach", 25), 15)
    }
}
