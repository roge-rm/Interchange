package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScenarioTest {
    /** An editor world of flat land at height 3, bare of trees, with the ground a coal mine wants. */
    private fun editor(): World {
        val w = World.newEditor(NewGame(64, 64, 1, 1900, towns = 0, industries = 0, weather = false))
        flatten(w)
        return w
    }

    private fun flatten(w: World) {
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        val wants = coalMine(w).ground.firstOrNull()
        w.ground.fill((if (wants == null) Ground.MEADOW else Ground.NAMES.indexOfFirst { it.lowercase() == wants }).toByte())
    }

    private fun coalMine(w: World) = w.roster.industries.first { it.key == "base:coal_mine" }

    @Test
    fun anEditorWorldStandsStillAndBuildsForNothing() {
        val w = editor()
        val money = w.companies[0].money
        repeat(1000) { w.step() }
        assertEquals(0L, w.tick)
        w.apply(Command.Rail(0, 5, 10, 30, 10, Track.X, remove = false))
        assertTrue(w.track[w.index(20, 10)].toInt() != 0)
        assertEquals(money, w.companies[0].money)
        assertTrue(w.companies[0].thisYear.all { it == 0L })
        // The bank's dealings are refused.
        w.apply(Command.Loan(0, true))
        assertEquals(0L, w.companies[0].loan)
    }

    @Test
    fun landIsRaisedLoweredAndLevelledByArea() {
        val w = editor()
        w.apply(Command.EditTerrain(0, 10, 10, 12, 12, Command.EditTerrain.RAISE))
        w.apply(Command.EditTerrain(0, 10, 10, 12, 12, Command.EditTerrain.RAISE))
        assertEquals(5, w.heightAt(11, 11))
        assertEquals(5, w.heightAt(13, 13))
        // The land round about follows a step at a time.
        assertEquals(4, w.heightAt(9, 11))
        assertEquals(3, w.heightAt(8, 11))
        w.apply(Command.EditTerrain(0, 10, 10, 12, 12, Command.EditTerrain.LOWER))
        assertEquals(4, w.heightAt(11, 11))
        w.apply(Command.EditTerrain(0, 30, 30, 40, 40, Command.EditTerrain.LEVEL, 0))
        assertTrue(w.isWater(35, 35))
        assertEquals(1, w.heightAt(29, 35))
        // Nothing moves where track would.
        w.apply(Command.Rail(0, 50, 20, 55, 20, Track.X, remove = false))
        w.apply(Command.EditTerrain(0, 48, 18, 52, 22, Command.EditTerrain.RAISE))
        assertEquals(3, w.heightAt(50, 20))
    }

    @Test
    fun townsArePlacedAndGrown() {
        val w = editor()
        w.apply(Command.PlaceTown(0, 20, 20, "Smithers", TownSize.MEDIUM))
        assertEquals(1, w.towns.size)
        val t = w.towns[0]
        assertEquals("Smithers", t.name)
        assertTrue(t.houses >= 10, "only ${t.houses} buildings")
        // Too close to another, it isn't placed; a blank name gets one made up.
        w.apply(Command.PlaceTown(0, 23, 22, "", TownSize.SMALL))
        assertEquals(1, w.towns.size)
        w.apply(Command.PlaceTown(0, 45, 45, "", TownSize.SMALL))
        assertEquals(2, w.towns.size)
        assertTrue(w.towns[1].name.isNotBlank())
        val before = t.houses
        w.apply(Command.GrowTown(0, 0, TownSize.LARGE))
        assertTrue(t.houses > before)
    }

    @Test
    fun industriesArePlacedAndRemoved() {
        val w = editor()
        w.apply(Command.PlaceIndustry(0, "base:coal_mine", 20, 20, 0))
        assertEquals(1, w.industries.size)
        assertEquals(1, w.industryOf[w.index(20, 20)].toInt())
        // Not on top of another, nor of a kind there isn't.
        w.apply(Command.PlaceIndustry(0, "base:coal_mine", 21, 21, 1))
        w.apply(Command.PlaceIndustry(0, "base:nothing", 40, 40, 0))
        assertEquals(1, w.industries.size)
        w.goals += Goal(Goal.DELIVER, 100, 1910, cargo = Cargo.COAL, industry = 0)
        w.apply(Command.RemoveIndustry(0, 0))
        assertTrue(w.industries[0].closed)
        assertEquals(0, w.industryOf[w.index(20, 20)].toInt())
        assertTrue(w.goals.isEmpty())
    }

    @Test
    fun treesArePlantedAndCleared() {
        val w = editor()
        w.ground.fill(Ground.FOREST.toByte())
        w.apply(Command.EditTrees(0, 5, 5, 9, 9, plant = true))
        for (y in 5..9) for (x in 5..9) assertEquals(3, Trees.count(w.trees[w.index(x, y)].toInt()))
        assertEquals(0, w.trees[w.index(10, 10)].toInt())
        w.apply(Command.EditTrees(0, 5, 5, 7, 7, plant = false))
        assertEquals(0, w.trees[w.index(6, 6)].toInt())
        assertEquals(3, Trees.count(w.trees[w.index(8, 8)].toInt()))
    }

    @Test
    fun goalsAndTheScenarioAreSet() {
        val w = editor()
        w.apply(Command.PlaceTown(0, 20, 20, "Hope", TownSize.SMALL))
        w.apply(Command.SetGoals(0, listOf(Goal(Goal.TOWN_POP, 500, 1920, town = 0), Goal(Goal.COMPANY_VALUE, 1_000_000, 1930))))
        assertEquals(2, w.goals.size)
        // A goal about a town that isn't there spoils the list.
        w.apply(Command.SetGoals(0, listOf(Goal(Goal.TOWN_POP, 500, 1920, town = 5))))
        assertEquals(2, w.goals.size)
        w.apply(Command.SetScenario(0, "  Up the valley ", "First.\n\nSecond.", 400_000, 25_000, Scenario.ANY, 1925, "ch1"))
        val s = assertNotNull(w.scenario)
        assertEquals("Up the valley", s.title)
        assertEquals(400_000L, s.money)
        assertEquals(20_000L, s.loan)
        assertEquals(Scenario.ANY, s.win)
        assertEquals("ch1", s.chapter)
        assertEquals(1925, w.startYear)
    }

    @Test
    fun editorCommandsAreRefusedInAGame() {
        val w = World(NewGame(64, 64, 1, 1900, towns = 0, industries = 0, weather = false))
        flatten(w)
        w.companies[0].money = 10_000_000
        val commands = listOf(
            Command.EditTerrain(0, 10, 10, 12, 12, Command.EditTerrain.RAISE),
            Command.PlaceTown(0, 20, 20, "Smithers", TownSize.MEDIUM),
            Command.GrowTown(0, 0, TownSize.SMALL),
            Command.PlaceIndustry(0, "base:coal_mine", 40, 40, 0),
            Command.RemoveIndustry(0, 0),
            Command.EditTrees(0, 5, 5, 9, 9, plant = true),
            Command.SetGoals(0, listOf(Goal(Goal.COMPANY_VALUE, 1_000_000, 1930))),
            Command.SetScenario(0, "A", "B", 1, 0, Scenario.ALL, 1950),
        )
        val sum = w.checksum()
        for (c in commands) {
            w.apply(c)
            assertEquals(sum, w.checksum(), "$c changed the world")
        }
        assertEquals(null, w.scenario)
        // Building isn't free either.
        w.apply(Command.Rail(0, 5, 10, 30, 10, Track.X, remove = false))
        assertTrue(w.companies[0].money < 10_000_000)
    }

    @Test
    fun anExportedScenarioPlaysFromItsFirstDay() {
        val w = editor()
        w.apply(Command.PlaceTown(0, 20, 20, "Hope", TownSize.SMALL))
        w.apply(Command.Rail(0, 5, 40, 30, 40, Track.X, remove = false))
        w.apply(Command.SetGoals(0, listOf(Goal(Goal.TOWN_POP, 500, 1920, town = 0))))
        w.apply(Command.SetScenario(0, "Hope", "Grow it.", 100_000, 50_000, Scenario.ALL, 1910))
        val file = Scenarios.export(w)
        // The editor's world is left as it was.
        assertTrue(w.editor)
        val play = Scenarios.play(file)
        assertFalse(play.editor)
        assertEquals(0L, play.tick)
        assertEquals(1910, play.startYear)
        assertEquals(150_000L, play.companies[0].money)
        assertEquals(50_000L, play.companies[0].loan)
        assertEquals(1, play.goals.size)
        assertEquals("Hope", play.scenario?.title)
        assertTrue(play.track[play.index(20, 40)].toInt() != 0)
        repeat(100) { play.step() }
        assertEquals(100L, play.tick)
        // And back into the editor to go on with it.
        val again = Scenarios.edit(file)
        assertTrue(again.editor)
        repeat(100) { again.step() }
        assertEquals(0L, again.tick)
    }

    @Test
    fun aScenarioIsWonOrLostOnce() {
        fun world(win: Int, vararg status: Int): World {
            val w = World(NewGame(64, 64, 1, 1900, towns = 0, industries = 0, weather = false))
            w.scenario = Scenario(win = win)
            for (s in status) w.goals += Goal(Goal.COMPANY_VALUE, 1, 1930).also { it.status = s }
            return w
        }
        fun World.told(kind: Int) = chronicle.count { it.kind == kind }
        val all = world(Scenario.ALL, Goal.MET, Goal.OPEN)
        all.scenarioCheck()
        assertEquals(Scenario.OPEN, all.scenario!!.outcome)
        all.goals[1].status = Goal.MET
        all.scenarioCheck()
        all.scenarioCheck()
        assertEquals(Scenario.WON, all.scenario!!.outcome)
        assertEquals(1, all.told(News.SCENARIO_WON))
        val lost = world(Scenario.ALL, Goal.MET, Goal.FAILED)
        lost.scenarioCheck()
        assertEquals(1, lost.told(News.SCENARIO_LOST))
        val any = world(Scenario.ANY, Goal.FAILED, Goal.OPEN)
        any.scenarioCheck()
        assertEquals(Scenario.OPEN, any.scenario!!.outcome)
        any.goals[1].status = Goal.FAILED
        any.scenarioCheck()
        assertEquals(1, any.told(News.SCENARIO_LOST))

        // In play: a company worth anything meets the goal at the month's end, and the scenario's won.
        val play = world(Scenario.ALL, Goal.OPEN)
        repeat(play.dayTicks * 70) { play.step() }
        assertEquals(Goal.MET, play.goals[0].status)
        assertEquals(1, play.told(News.SCENARIO_WON))
        assertEquals(0, play.told(News.SCENARIO_LOST))
    }

    @Test
    fun theScenarioIsSavedAndInTheChecksum() {
        val w = editor()
        w.apply(Command.SetScenario(0, "Hope", "Grow it.", 100_000, 0, Scenario.ANY, 1900, "ch2"))
        w.scenario!!.outcome = Scenario.LOST
        val b = Save.read(Save.write(w))
        assertTrue(b.editor)
        val s = assertNotNull(b.scenario)
        assertEquals("Hope", s.title)
        assertEquals("Grow it.", s.intro)
        assertEquals(100_000L, s.money)
        assertEquals(Scenario.ANY, s.win)
        assertEquals("ch2", s.chapter)
        assertEquals(Scenario.LOST, s.outcome)
        assertEquals(w.checksum(), b.checksum())
        val sum = w.checksum()
        w.scenario!!.title = "Despair"
        assertNotEquals(sum, w.checksum())
        w.scenario!!.title = "Hope"
        w.editor = false
        assertNotEquals(sum, w.checksum())
    }

    @Test
    fun editorCommandsGoThroughTheCodec() {
        val commands = listOf(
            Command.EditTerrain(1, 10, 11, 12, 13, Command.EditTerrain.LEVEL, 7),
            Command.PlaceTown(1, 20, 21, "Smithers", TownSize.CITY),
            Command.GrowTown(1, 3, TownSize.SMALL),
            Command.PlaceIndustry(1, "base:coal_mine", 40, 41, 2),
            Command.RemoveIndustry(1, 4),
            Command.EditTrees(1, 5, 6, 7, 8, plant = false),
            Command.SetScenario(1, "A", "B\n\nC", 5, 6, Scenario.ANY, 1950, "x"),
        )
        for (c in commands) assertEquals(c, Codec.decode(Codec.encode(c)))
        val goals = Codec.decode(Codec.encode(Command.SetGoals(2, listOf(Goal(Goal.DELIVER, 9, 1930, cargo = 3, industry = 2, from = 1901))))) as Command.SetGoals
        assertEquals(2, goals.company)
        val g = goals.goals.single()
        assertEquals(listOf(Goal.DELIVER, 9L, 1930, 3, -1, -1, 2, 1901), listOf(g.kind, g.amount, g.deadline, g.cargo, g.town, g.town2, g.industry, g.from))
    }

    @Test
    fun movingTheStartYearMovesTheGoals() {
        val w = editor()
        w.apply(Command.PlaceTown(0, 20, 20, "Hope", TownSize.SMALL))
        w.apply(Command.SetGoals(0, listOf(Goal(Goal.TOWN_POP, 500, 1920, town = 0), Goal(Goal.DELIVER, 100, 1915, cargo = Cargo.COAL, town = 0, from = 1900))))
        w.apply(Command.SetScenario(0, "Hope", "", 100_000, 0, Scenario.ALL, 1950))
        assertEquals(1950, w.startYear)
        assertEquals(listOf(1970, 1965), w.goals.map { it.deadline })
        assertEquals(listOf(0, 1950), w.goals.map { it.from })
        // Setting it again with the same year leaves them be.
        w.apply(Command.SetScenario(0, "Hope", "", 100_000, 0, Scenario.ALL, 1950))
        assertEquals(listOf(1970, 1965), w.goals.map { it.deadline })
    }

    @Test
    fun joiningLateOrStartingAgainGivesTheScenariosMoneyAndLoan() {
        val w = editor()
        w.apply(Command.SetScenario(0, "Hope", "", 100_000, 50_000, Scenario.ALL, 1900))
        val play = Scenarios.play(Scenarios.export(w))
        play.apply(Command.NewCompany(play.companies.size, "Late"))
        val late = play.companies.last()
        assertEquals(150_000L, late.money)
        assertEquals(50_000L, late.loan)
        late.money = 5
        late.loan = 0
        play.restart(late)
        assertEquals(150_000L, late.money)
        assertEquals(50_000L, late.loan)
    }

    @Test
    fun badPlacesAndSizesAreRefused() {
        val w = editor()
        // A town of no size there is isn't placed.
        w.apply(Command.PlaceTown(0, 20, 20, "Hope", 7))
        assertEquals(0, w.towns.size)
        // Nor an industry under a bridge.
        w.deck[w.index(41, 41)] = 0
        w.apply(Command.PlaceIndustry(0, "base:coal_mine", 40, 40, 0))
        assertEquals(0, w.industries.size)
    }

    @Test
    fun aScenariosHeadingIsReadWithoutTheRest() {
        val w = editor()
        w.apply(Command.SetScenario(0, "Hope", "Grow it.", 100_000, 0, Scenario.ALL, 1920))
        val h = assertNotNull(Save.heading(Scenarios.export(w)))
        assertEquals("Hope", h.title)
        assertEquals("Grow it.", h.intro)
        assertEquals(1920, h.startYear)
        assertEquals(null, Save.heading(Save.write(World(32, 32, 1))))
        assertEquals(null, Save.heading(byteArrayOf(1, 2, 3)))
    }
}
