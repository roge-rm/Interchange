package com.rm.interchange.sim

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackBuildingsTest {
    private val png = byteArrayOf(1)

    private fun pack(houses: String? = null, industries: String? = null, format: Int = 2, disables: String = "[]", sprites: List<String> = listOf("cottage", "tower", "future", "works"), version: Int = 1): Pack {
        val files = HashMap<String, ByteArray>()
        files["manifest.json"] = """{ "format": $format, "id": "town", "version": $version, "name": { "en": "Town" }, "needs": ["base"], "disables": $disables }""".encodeToByteArray()
        if (houses != null) files["houses.json"] = """{ "houses": [ $houses ] }""".encodeToByteArray()
        if (industries != null) files["industries.json"] = """{ "industries": [ $industries ] }""".encodeToByteArray()
        for (s in sprites) files["sprites/$s.png"] = png
        return Packs.read(files)
    }

    private fun problems(houses: String? = null, industries: String? = null, format: Int = 2) =
        assertFailsWith<BadPack> { pack(houses, industries, format) }.problems

    /** An industry making coal by [production], with [open] and [closes] rules if given. */
    private fun works(production: String = "pick(month, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000, 11000, 12000) * level / 16", open: String? = null, closes: String? = null): String {
        val rules = listOfNotNull(open?.let { "\"open\": \"$it\"" }, closes?.let { "\"closes\": \"$it\"" }).joinToString("") { ", $it" }
        return """{ "id": "works", "name": { "en": "Works" }, "from": 1800, "until": 2100, "regions": { "mixed": 50 },
            "layout": ["works coal_heap"], "parts": { "works": "works" }, "produces": { "coal": 50 },
            "production": { "coal": "$production" } $rules }"""
    }

    private fun world(p: Pack, year: Int = 1900, towns: Int = 0, industries: Int = 0, size: Int = 128, seed: Long = 4) =
        World(NewGame(size, size, seed, year, towns = towns, industries = industries, weather = false), Roster.build(listOf(Packs.BASE, p)))

    /** Places one [key] where it fits and gives it back. */
    private fun place(w: World, key: String = "town:works"): Industry {
        assertTrue(w.placeIndustry(w.roster.industries.first { it.key == key }), "nowhere for $key")
        return w.industries.last()
    }

    /** Steps [w] to the first of the next month. */
    private fun nextMonth(w: World) {
        val m = Calendar.month(w.startYear, w.day)
        while (Calendar.month(w.startYear, w.day) == m) w.step()
    }

    @Test
    fun packHousesAreBuiltByGrowingTownsInTheirYearsAndSize() {
        val p = pack(
            houses = """
            { "id": "cottage", "name": { "en": "Cottage" }, "from": 1800, "until": 2100, "residents": 6, "jobs": 0, "zones": ["outskirts", "suburbs"], "weight": 100 },
            { "id": "tower", "name": { "en": "Tower" }, "from": 1800, "until": 2100, "residents": 600, "jobs": 0, "zones": ["centre"], "weight": 100, "min_population": 10000000 },
            { "id": "future", "name": { "en": "Future" }, "from": 2000, "until": 2100, "residents": 6, "jobs": 0, "zones": ["outskirts", "suburbs", "inner", "centre"], "weight": 100 }
            """,
            disables = """["base:log_cabin"]""",
        )
        val w = world(p, year = 1850, towns = 2, size = 192, seed = 5)
        val roster = w.roster
        val cottage = roster.houses.first { it.key == "town:cottage" }
        assertEquals(SpriteRef("town", "cottage"), roster.sprites[cottage.sprite])
        assertEquals(-1, roster.houses.first { it.key == "base:farmhouse" }.sprite)
        assertFalse(roster.houses.first { it.key == "base:log_cabin" }.enabled)
        fun count(key: String) = w.house.count { it.toInt() != 0 && roster.houses[houseOf(it.toInt())].key == key }
        val before = count("town:cottage")
        repeat(Calendar.TICKS_PER_DAY * 365 * 3) { w.step() }
        assertTrue(count("town:cottage") > before, "cottages $before then ${count("town:cottage")}")
        assertEquals(0, count("town:tower"))
        assertEquals(0, count("town:future"))
        assertEquals(0, count("base:log_cabin"))
    }

    @Test
    fun aPackIndustryMakesWhatItsRuleSaysEachMonth() {
        val w = world(pack(industries = works()))
        val ind = place(w)
        val kind = w.roster.industries[ind.kind]
        assertEquals(SpriteRef("town", "works"), w.roster.sprites[kind.parts.getValue("works")])
        assertTrue("coal_heap" !in kind.parts)
        assertTrue(kind.primary)
        repeat(12) {
            val month = Calendar.month(w.startYear, w.day)
            val level = ind.level
            val want = (month + 1) * 1000 * level / 16
            assertEquals(want, w.monthlyAmount(ind, kind, Cargo.COAL, 100), "month $month")
            val start = w.day
            nextMonth(w)
            // Shared over the month's days, each rounded up or down at random; the next month's first day counts in it.
            val days = (w.day - start).toInt()
            val next = ((month + 1) % 12 + 1) * 1000 * level / 16
            val made = ind.madeLastMonth[Cargo.COAL]
            assertTrue(kotlin.math.abs(made - (want * (days - 1) + next) / 30) <= days, "month $month made $made of $want over $days days")
        }
    }

    @Test
    fun anOpenRuleOfZeroKeepsOneFromOpening() {
        val never = world(pack(industries = works(open = "0")))
        assertFalse(never.placeIndustry(never.roster.industries.last()))
        assertTrue(never.industries.isEmpty())
        val later = world(pack(industries = works(open = "year >= 1950")))
        assertFalse(later.placeIndustry(later.roster.industries.last()))
        place(world(pack(industries = works(open = "year >= 1900 && stations == 0 && level == 16"))))
        // Nor does a new map get one.
        val g = world(pack(industries = works(open = "0")), industries = 3, size = 192)
        assertTrue(g.industries.isNotEmpty())
        assertTrue(g.industries.none { g.roster.industries[it.kind].key == "town:works" })
    }

    @Test
    fun aClosesRuleClosesItAtTheMonth() {
        val w = world(pack(industries = works(closes = "year >= 1901")))
        val ind = place(w)
        repeat(12) { nextMonth(w) }
        assertEquals(1901, Calendar.year(w.startYear, w.day))
        assertTrue(ind.closed)
        val open = world(pack(industries = works(closes = "year >= 1901")))
        val other = place(open)
        repeat(11) { nextMonth(open) }
        assertFalse(other.closed)
    }

    @Test
    fun replacesChangesOnlyWhatItGives() {
        val p = pack(
            houses = """{ "id": "big_cabin", "replaces": "base:log_cabin", "residents": 9 }""",
            industries = """{ "id": "big_farm", "replaces": "base:farm", "produces": { "grain": 120 } }""",
            sprites = emptyList(),
        )
        val r = Roster.build(listOf(Packs.BASE, p))
        val base = Roster.BASE
        assertEquals(base.industries.size, r.industries.size)
        assertEquals(base.houses.size, r.houses.size)
        val farm = r.industries.first { it.key == "base:farm" }
        val was = base.industries.first { it.key == "base:farm" }
        assertEquals(120, farm.produces[Cargo.GRAIN])
        assertEquals(was.layout, farm.layout)
        assertEquals(was.seasons.toList(), farm.seasons.toList())
        assertEquals(was.weather, farm.weather)
        assertEquals(was.name("fr"), farm.name("fr"))
        val cabin = r.houses.first { it.key == "base:log_cabin" }
        assertEquals(9, cabin.residents)
        assertEquals(base.houses.first { it.key == "base:log_cabin" }.zones, cabin.zones)
        assertEquals(-1, cabin.sprite)
    }

    /** Two years of a map with the base pack alone come out as they did before rules. */
    @Test
    fun theBasePackRunsAsBefore() {
        val w = World(NewGame(width = 128, height = 128, seed = 11, startYear = 1900))
        w.companies[0].autorenew = true
        repeat(Calendar.TICKS_PER_DAY * 730) { w.step() }
        assertEquals(-5297157223404888104L, w.checksum())
        assertEquals(544, w.industries.sumOf { it.madeLastMonth.sum() })
    }

    /** The same world with the future off; main is 6059885551085355865, which differs only by the pack hash (the future industries and their wagons and trucks). */
    @Test
    fun theBasePackRunsAsBeforeWithTheFutureOff() {
        val w = World(NewGame(width = 128, height = 128, seed = 11, startYear = 1900, future = false))
        w.companies[0].autorenew = true
        repeat(Calendar.TICKS_PER_DAY * 730) { w.step() }
        assertEquals(-484171979261928376L, w.checksum())
    }

    @Test
    fun aSavedGameWithAPackIndustryKeepsStep() {
        val p = pack(industries = works(production = "base * (wet + snow + mud + ice + stations + carried + brought(coal) + 1) + month", closes = "year > 1990"))
        val w = world(p)
        place(w)
        repeat(Calendar.TICKS_PER_DAY * 40) { w.step() }
        val copy = Save.read(Save.write(w)) { ref -> listOf(Packs.BASE, p).firstOrNull { it.id == ref.id } }
        repeat(Calendar.TICKS_PER_DAY * 60) { w.step(); copy.step() }
        assertEquals(w.checksum(), copy.checksum())
        assertEquals(w.industries.last().madeLastMonth.toList(), copy.industries.last().madeLastMonth.toList())
        assertTrue(w.industries.last().madeLastMonth[Cargo.COAL] > 0)
    }

    @Test
    fun aSavedIndustryLoadsAgainstASmallerLayout() {
        val big = works().replace("[\"works coal_heap\"]", "[\"works coal_heap\", \"works coal_heap\"]")
        val w = world(pack(industries = big))
        val ind = place(w)
        val mark = (ind.id + 1).toShort()
        assertEquals(4, w.industryOf.count { it == mark })
        repeat(Calendar.TICKS_PER_DAY * 5) { w.step() }
        // A newer version of the pack with a one-tile layout.
        val small = pack(industries = works().replace("\"works coal_heap\"", "\"works\""), version = 2)
        val copy = Save.read(Save.write(w)) { ref -> listOf(Packs.BASE, small).firstOrNull { it.id == ref.id } }
        val kind = copy.roster.industries.first { it.key == "town:works" }
        assertEquals(1, kind.width * kind.height)
        for (turn in 0..3) for (fy in -1..5) for (fx in -1..5) {
            val part = kind.part(fx, fy, turn)
            assertTrue(part == "" || part == "works")
        }
        val tiles = (0 until copy.width * copy.height).filter { copy.industryOf[it] == mark }
        assertEquals(4, tiles.size)
        assertEquals(3, tiles.count { kind.part(it % copy.width - ind.x, it / copy.width - ind.y, ind.turn) == "" })
        repeat(Calendar.TICKS_PER_DAY * 40) { copy.step() }
        copy.closeIndustry(copy.industries[ind.id])
        assertEquals(0, copy.industryOf.count { it == mark })
        repeat(Calendar.TICKS_PER_DAY * 5) { copy.step() }
    }

    @Test
    fun aGameHoldsNoMoreBuildingsThanASaveCanLoad() {
        fun houses(n: Int) = (0 until n).joinToString(", ") {
            """{ "id": "h$it", "name": { "en": "H" }, "sprite": "cottage", "from": 1800, "until": 2100, "residents": 1, "jobs": 0, "zones": ["outskirts"] }"""
        }
        val baseHouses = Packs.BASE.houses.size
        Roster.build(listOf(Packs.BASE, pack(houses = houses(Roster.MOST_HOUSES - baseHouses))))
        val e = assertFailsWith<BadPack> { Roster.build(listOf(Packs.BASE, pack(houses = houses(Roster.MOST_HOUSES - baseHouses + 1)))) }
        assertEquals(listOf("These packs have ${Roster.MOST_HOUSES + 1} town buildings, more than the ${Roster.MOST_HOUSES} a game can hold"), e.problems)
    }

    @Test
    fun formatsAndProblems() {
        // A format 1 pack of vehicles alone still loads; buildings need format 2.
        Packs.read(mapOf("manifest.json" to """{ "format": 1, "id": "old", "version": 1, "name": { "en": "Old" } }""".encodeToByteArray()))
        assertTrue("manifest.json: a pack with houses.json or industries.json needs \"format\": 2" in problems(industries = works(), format = 1))
        val p = problems(industries = """
            { "id": "a", "name": { "en": "A" }, "from": 1800, "until": 2100, "layout": ["mystery coal_heap", "x"], "parts": { "b": "nothere" },
              "produces": { "sand": 5 }, "production": { "coal": "1 + bogus" }, "open": "(1", "ground": ["lava"], "regions": { "moon": 1 } },
            { "id": "big", "name": { "en": "Big" }, "from": 1800, "until": 2100, "layout": ["a a a a a a"] }
        """)
        val want = listOf(
            "industries.json, \"a\": unknown region \"moon\"",
            "industries.json, \"a\": ground \"lava\" should be meadow, prairie, forest, rock, tundra, snow",
            "industries.json, \"a\": \"layout\" needs rows of the same length",
            "industries.json, \"a\": unknown cargo \"sand\"",
            "industries.json, \"a\": sprites/nothere.png is missing",
            "industries.json, \"a\": part \"mystery\" isn't in \"parts\" or the base pack",
            "industries.json, \"a\": production: \"coal\" should be in \"produces\" too, above 0",
            "industries.json, \"a\": production coal, column 5: unknown name \"bogus\"",
            "industries.json, \"a\": open, column 3: expected \")\"",
            "industries.json, \"big\": \"layout\" can be at most 5 by 5",
        )
        for (s in want) assertTrue(s in p, "no \"$s\" in\n${p.joinToString("\n")}")
    }

    @Test
    fun theTemplatePacksBuildingsAndIndustryLoad() {
        val dir = File("../packs/template")
        val files = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }
        val pack = Packs.read(files)
        val r = Roster.build(listOf(Packs.BASE, pack))
        val cannery = r.industries.first { it.key == "template:salmon_cannery" }
        assertTrue(cannery.production[Cargo.FISH] != null && cannery.open != null)
        assertEquals(setOf("salmon_cannery", "salmon_dock"), cannery.parts.keys)
        assertTrue(r.houses.any { it.key == "template:general_store" && it.sprite >= 0 })
    }
}
