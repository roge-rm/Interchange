package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalTest {
    private fun World.days(n: Int) = repeat(dayTicks * n) { step() }

    /** Steps on to the first of the next year. */
    private fun World.toNewYear() {
        val year = Calendar.year(startYear, day)
        while (Calendar.year(startYear, day) == year) step()
        repeat(dayTicks) { step() }
    }

    /** Two towns 40 tiles apart, each with a station by it, a train of coaches between them and passengers always waiting at both. */
    private fun line(): World {
        val w = World(NewGame(96, 64, 1, 1860, Region.MIXED, towns = 0, industries = 0, weather = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 10_000_000
        w.towns += Town(0, "Ayton", 7, 14, 3)
        w.towns += Town(1, "Beeburg", 47, 14, 3)
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 50, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 6, 10, 9, 10, Track.X))
        w.apply(Command.Station(0, 46, 10, 49, 10, Track.X))
        val engine = w.roster.engines.first { it.key == "base:american" }
        val coach = w.roster.engines.first { it.key == "base:coach_wood" }
        w.apply(Command.BuyTrain(0, 0, engine.id, coach.id, 4))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        return w
    }

    private fun World.runWithPassengers(days: Int) = repeat(dayTicks * days) {
        for (s in stations) if (s != null && s.waiting(Cargo.PASSENGERS) < 100) addWaiting(s, Cargo.PASSENGERS, if (s.id == 0) 1 else 0, s.id, 50, tick, 0)
        step()
    }

    @Test
    fun aConnectionADeliveryAndATownAreMetByCarryingPassengers() {
        val w = line()
        w.goals += Goal(Goal.CONNECT, 50, 1870, town = 0, town2 = 1)
        w.goals += Goal(Goal.DELIVER, 50, 1870, cargo = Cargo.PASSENGERS, town = 1, from = 1860)
        w.goals += Goal(Goal.TOWN_POP, 100, 1870, town = 1)
        // Counted from a later year, a delivery goal doesn't count yet.
        w.goals += Goal(Goal.DELIVER, 50, 1870, cargo = Cargo.PASSENGERS, town = 1, from = 1861)
        w.towns[1].population = 150
        assertEquals(w.towns[0].id, w.stationTown(w.stations[0]!!)?.id)
        assertEquals(w.towns[1].id, w.stationTown(w.stations[1]!!)?.id)
        w.runWithPassengers(70)
        for (g in w.goals.take(3)) {
            assertEquals(Goal.MET, g.status, "kind ${g.kind}")
            assertEquals(0, g.by)
            assertEquals(1860, g.doneYear)
        }
        assertEquals(Goal.OPEN, w.goals[3].status)
        assertEquals(0L, w.goals[3].count[0])
        assertEquals(3, w.chronicle.count { it.kind == News.GOAL_MET })
        // The towns' tallies: passengers delivered, this month and last.
        assertTrue(w.towns.sumOf { it.delivered[Cargo.PASSENGERS] + it.deliveredLastMonth[Cargo.PASSENGERS] } > 0)
    }

    @Test
    fun aTownDeliveredToByNobodyIsNotMetByGrowingAlone() {
        val w = line()
        w.goals += Goal(Goal.TOWN_POP, 100, 1870, town = 0)
        w.towns[0].population = 150
        w.days(40)
        assertEquals(Goal.OPEN, w.goals[0].status)
    }

    @Test
    fun aCompanyValueIsMetAtAMonthsEndAndAYearsProfitAtTheYearsEnd() {
        val w = World(64, 64, 1)
        w.companies[0].money = 10_000_000
        w.goals += Goal(Goal.COMPANY_VALUE, 5_000_000, 1851)
        w.goals += Goal(Goal.YEAR_PROFIT, 100_000, 1850)
        w.companies[0].thisYear[Books.INCOME] = 200_000
        w.days(40)
        assertEquals(Goal.MET, w.goals[0].status)
        assertEquals(0, w.goals[0].by)
        assertEquals(Goal.OPEN, w.goals[1].status)
        assertEquals(200_000L, w.goalProgress(w.goals[1], 0))
        w.toNewYear()
        assertEquals(Goal.MET, w.goals[1].status)
        assertEquals(11, w.goals[1].doneMonth)
    }

    @Test
    fun everyKindFailsWhenItsYearEndsUnmet() {
        val w = line()
        w.goals += Goal(Goal.TOWN_POP, 100_000, 1860, town = 0)
        w.goals += Goal(Goal.DELIVER, 100_000, 1860, cargo = Cargo.PASSENGERS, town = 1, from = 1860)
        w.goals += Goal(Goal.CONNECT, 100_000, 1860, town = 0, town2 = 1)
        w.goals += Goal(Goal.COMPANY_VALUE, 1_000_000_000, 1860)
        w.goals += Goal(Goal.YEAR_PROFIT, 1_000_000_000, 1860)
        w.goals += Goal(Goal.CONNECT, 100_000, 1861, town = 0, town2 = 1)
        w.runWithPassengers(200)
        assertTrue(w.goals.all { it.status == Goal.OPEN })
        val carried = w.goals[5].count[0]
        assertTrue(carried > 0)
        w.toNewYear()
        for (g in w.goals.take(5)) {
            assertEquals(Goal.FAILED, g.status, "kind ${g.kind}")
            assertEquals(1860, g.doneYear)
        }
        assertEquals(5, w.chronicle.count { it.kind == News.GOAL_FAILED })
        // The later connection is still open and counts again from the new year.
        assertEquals(Goal.OPEN, w.goals[5].status)
        assertTrue(w.goals[5].count[0] < carried)
    }

    @Test
    fun aRivalNeverMeetsAGoal() {
        val w = World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 3, rivals = 1))
        val rival = w.rivals.first().company
        w.companies[rival].money = 1_000_000_000
        w.goals += Goal(Goal.COMPANY_VALUE, 100_000_000, 1940)
        w.days(40)
        assertEquals(Goal.OPEN, w.goals[0].status)
    }

    private fun supplied(growth: TownGrowth) = World(NewGame(256, 256, 5, 1900, Region.MIXED, townGrowth = growth))

    @Test
    fun suppliedTownsGrowOnlyWhenSupplied() {
        val w = supplied(TownGrowth.SUPPLIED)
        val before = w.towns.map { it.houses }
        // One town gets all it needs, every day; the rest get nothing.
        val fed = w.towns.first()
        repeat(365) {
            fed.servedLastMonth = 100_000
            fed.mailLastMonth = 10_000
            fed.deliveredLastMonth.fill(10_000)
            w.days(1)
        }
        assertTrue(fed.houses > before[0], "${fed.name} didn't grow")
        for (t in w.towns.drop(1)) assertEquals(before[t.id], t.houses, "${t.name} grew unsupplied")
        // Growing normally, they all grow.
        val n = supplied(TownGrowth.NORMAL)
        val was = n.towns.sumOf { it.houses }
        n.days(365)
        assertTrue(n.towns.sumOf { it.houses } > was)
    }

    @Test
    fun aBigTownNeedsEverythingInWinter() {
        val w = World(64, 64, 1)
        val t = Town(0, "Big", 30, 30, 3).also { it.population = 5000 }
        w.towns += t
        w.days(32)
        // It's February, so last month was January.
        val needs = w.townNeeds(t)
        assertEquals(listOf(Cargo.PASSENGERS, Cargo.MAIL, Cargo.FOOD, Cargo.GOODS, Cargo.FUEL, Cargo.COAL), needs.map { it.cargo })
        assertTrue(needs.last().heating)
        assertEquals(500, needs.first().needed)
        assertTrue(!w.townSupplied(t))
        t.servedLastMonth = 1000; t.mailLastMonth = 100
        t.deliveredLastMonth[Cargo.FOOD] = 150; t.deliveredLastMonth[Cargo.GOODS] = 100; t.deliveredLastMonth[Cargo.FUEL] = 50; t.deliveredLastMonth[Cargo.COAL] = 50
        assertTrue(w.townSupplied(t))
        // A small one needs only passengers.
        assertEquals(listOf(Cargo.PASSENGERS), w.townNeeds(Town(1, "Small", 10, 10, 3).also { it.population = 300 }).map { it.cargo })
    }

    private fun made(goals: GoalSet, seed: Long = 3) = World(NewGame(256, 256, seed, 1930, Region.MIXED, goals = goals))

    private fun same(a: Goal, b: Goal) = listOf(a.kind, a.amount, a.deadline, a.cargo, a.town, a.town2, a.industry, a.from) ==
        listOf(b.kind, b.amount, b.deadline, b.cargo, b.town, b.town2, b.industry, b.from)

    @Test
    fun goalsAreMadeTheSameFromTheSameSeedAndLeaveTheRestAlone() {
        val none = made(GoalSet.NONE)
        assertTrue(none.goals.isEmpty())
        for ((set, kinds) in listOf(
            GoalSet.TOWNS to listOf(Goal.TOWN_POP, Goal.TOWN_POP, Goal.TOWN_POP),
            GoalSet.DELIVERIES to listOf(Goal.DELIVER, Goal.DELIVER, Goal.DELIVER),
            GoalSet.CONNECTIONS to listOf(Goal.CONNECT, Goal.CONNECT, Goal.CONNECT),
            GoalSet.MIXED to listOf(Goal.TOWN_POP, Goal.DELIVER, Goal.CONNECT, Goal.COMPANY_VALUE),
        )) {
            val a = made(set)
            val b = made(set)
            assertEquals(kinds, a.goals.map { it.kind }, "$set")
            assertTrue(a.goals.indices.all { same(a.goals[it], b.goals[it]) }, "$set differs")
            assertTrue(a.goals.all { it.deadline in 1940..1950 && it.amount > 0 && it.status == Goal.OPEN })
            // Made by their own dice, so the world is as it would be without them.
            assertEquals(none.rng.state, a.rng.state)
            assertEquals(none.towns.map { it.population }, a.towns.map { it.population })
            for (g in a.goals) when (g.kind) {
                Goal.TOWN_POP -> assertTrue(g.amount >= a.towns[g.town].population * 2L - 25)
                Goal.CONNECT -> assertTrue(a.towns[g.town].let { t -> a.towns[g.town2].let { u -> chebyshev(t.x, t.y, u.x, u.y) in 20..80 } })
                Goal.DELIVER -> assertTrue(g.industry >= 0 && a.roster.industries[a.industries[g.industry].kind].takes(g.cargo) || g.town >= 0)
            }
        }
        assertTrue(made(GoalSet.TOWNS).goals.map { it.town }.toSet().size == 3, "three different towns")
    }

    @Test
    fun goalsAndTalliesSurviveSavingAndLoading() {
        val a = World(NewGame(160, 160, 12, 1930, Region.MIXED, towns = 10, industries = 6, goals = GoalSet.MIXED, townGrowth = TownGrowth.SUPPLIED))
        a.days(40)
        a.goals[0].count[0] = 123
        a.towns[0].delivered[Cargo.FOOD] = 7
        val b = Save.read(Save.write(a))
        assertEquals(a.settings, b.settings)
        assertEquals(a.goals.size, b.goals.size)
        assertTrue(a.goals.indices.all { same(a.goals[it], b.goals[it]) && a.goals[it].count.contentEquals(b.goals[it].count) })
        assertEquals(7, b.towns[0].delivered[Cargo.FOOD])
        assertEquals(a.checksum(), b.checksum())
        a.days(60)
        b.days(60)
        assertEquals(a.checksum(), b.checksum())
    }

    /** A coal mine and two steel mills, the mills both in reach of the station at the line's end. */
    private fun mills(): World {
        val w = World(96, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        for ((key, x, y) in listOf(Triple("base:coal_mine", 10, 12), Triple("base:steel_mill", 80, 12), Triple("base:steel_mill", 80, 6))) {
            val kind = w.roster.industries.first { it.key == key }
            val ind = Industry(w.industries.size, kind.id, x, y, 0, -1)
            w.industries += ind
            for (ty in y until y + kind.along(0)) for (tx in x until x + kind.across(0)) w.industryOf[w.index(tx, ty)] = (ind.id + 1).toShort()
        }
        w.apply(Command.Station(0, 79, 10, 82, 10, Track.X))
        w.catchmentDirty = true
        return w
    }

    @Test
    fun aDeliveryGoalCountsForTheIndustryItsForWhenTwoTakeTheCargo() {
        val w = mills()
        val s = w.stations[0]!!
        assertEquals(1, w.takerAt(s, Cargo.COAL)?.id)
        w.goals += Goal(Goal.DELIVER, 100, 1910, cargo = Cargo.COAL, industry = 2, from = 1900)
        assertEquals(2, w.takerAt(s, Cargo.COAL)?.id)
        w.delivered(0, Cargo.COAL, 30, s, s, w.takerAt(s, Cargo.COAL))
        assertEquals(30L, w.goals[0].count[0])
    }

    @Test
    fun aDeliveryGoalFailsAtOnceWhenItsIndustryCloses() {
        val w = mills()
        w.goals += Goal(Goal.DELIVER, 100, 1910, cargo = Cargo.COAL, industry = 2, from = 1900)
        w.goals += Goal(Goal.DELIVER, 100, 1910, cargo = Cargo.COAL, industry = 1, from = 1900)
        w.closeIndustry(w.industries[2])
        assertEquals(Goal.FAILED, w.goals[0].status)
        assertEquals(Goal.OPEN, w.goals[1].status)
        assertEquals(1, w.chronicle.count { it.kind == News.GOAL_FAILED })
    }

    @Test
    fun aCompanyStartingAgainLosesItsGoalCounts() {
        val w = line()
        w.goals += Goal(Goal.TOWN_POP, 100_000, 1870, town = 0)
        w.goals += Goal(Goal.CONNECT, 100_000, 1870, town = 0, town2 = 1)
        for (g in w.goals) { g.count[0] = 50; g.count[1] = 70 }
        w.restart(w.companies[0])
        for (g in w.goals) { assertEquals(0L, g.count[0]); assertEquals(70L, g.count[1]) }
    }
}
