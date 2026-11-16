package com.rm.interchange.sim

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackCargoTest {
    private val png = byteArrayOf(1)

    /** Maple syrup, as the template pack gives it, with [rate] and any [more] fields. */
    private fun syrup(id: String = "maple_syrup", rate: Int = 50, more: String = "") = """
        { "id": "$id", "name": { "en": "Maple syrup", "fr": "Sirop d'érable" }, "unit": { "en": "casks", "fr": "barils" },
          "rate": $rate, "grace": 20, "decay": 1, "pace": 2, "kg": 1000, "colour": "#b5651d", "towns": true $more }"""

    /** A wagon carrying [carries]. */
    private fun wagon(carries: String = "maple_syrup") = """
        { "id": "cask_car", "kind": "wagon", "name": { "en": "Cask car" }, "from": 1850, "until": 2100, "weight": 12, "cost": 2000,
          "running": 200, "length": 8, "carries": "$carries", "capacity": 40 }"""

    /** An industry making [makes], by a rule that reads what's brought of [reads]. */
    private fun shack(makes: String = "maple_syrup", reads: String = "maple_syrup") = """
        { "id": "shack", "name": { "en": "Shack" }, "from": 1800, "until": 2100, "regions": { "mixed": 50 }, "layout": ["coal_heap"],
          "produces": { "$makes": 60 }, "accepts": ["$reads"], "production": { "$makes": "base + brought($reads)" } }"""

    private fun pack(
        id: String = "maple", cargo: List<String> = listOf(syrup()), vehicles: List<String> = emptyList(), industries: List<String> = emptyList(),
        needs: String = "\"base\"", version: Int = 1, format: Int = 3,
    ): Pack {
        val files = HashMap<String, ByteArray>()
        files["manifest.json"] = """{ "format": $format, "id": "$id", "version": $version, "name": { "en": "Maple" }, "needs": [$needs] }""".encodeToByteArray()
        if (cargo.isNotEmpty()) files["cargo.json"] = """{ "cargo": [ ${cargo.joinToString()} ] }""".encodeToByteArray()
        if (vehicles.isNotEmpty()) files["vehicles.json"] = """{ "vehicles": [ ${vehicles.joinToString()} ] }""".encodeToByteArray()
        if (industries.isNotEmpty()) files["industries.json"] = """{ "industries": [ ${industries.joinToString()} ] }""".encodeToByteArray()
        files["sprites/cask_car.png"] = png
        return Packs.read(files)
    }

    private fun roster(vararg packs: Pack) = Roster.build(listOf(Packs.BASE) + packs)

    /** Two stations on a line with a row of the biggest houses round each, each row a town, so both take town cargo. */
    private fun line(roster: Roster): World {
        val w = World(NewGame(64, 64, 1, 1900, towns = 0, industries = 0, weather = false), roster)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        val house = w.roster.houses.filter { it.enabled && !it.monument }.maxBy { it.residents + it.jobs }
        for ((n, xs) in listOf(10..13, 36..39).withIndex()) {
            val t = Town(n, "Town $n", xs.first, 12, 3)
            w.towns += t
            for (x in xs) {
                val i = w.index(x, 12)
                w.house[i] = packHouse(house.id, Track.YM).toShort()
                w.townOf[i] = n.toShort()
                t.houses++
                t.population += house.residents
            }
        }
        w.catchmentDirty = true
        return w
    }

    private fun nextMonth(w: World) {
        val m = Calendar.month(w.startYear, w.day)
        while (Calendar.month(w.startYear, w.day) == m) w.step()
    }

    @Test
    fun aPackCargoGetsTheNextIdAndIsCarriedPaidAndDeliveredToATown() {
        val r = roster(pack(vehicles = listOf(wagon())))
        val c = r.cargo.of("maple_syrup")
        assertEquals(Cargo.COUNT, c)
        assertEquals(Cargo.COUNT + 1, r.cargo.size)
        assertEquals("maple", r.cargo[c].pack)
        assertEquals("Sirop d'érable", r.cargo[c].name("fr"))
        assertEquals("casks", r.cargo[c].unit("en"))
        assertEquals(0xb5651d, r.cargo[c].colour)
        val car = r.engines.first { it.key == "maple:cask_car" }
        assertContentEquals(intArrayOf(c), car.cargoes)

        val w = line(r)
        val a = w.stations[0]!!
        val b = w.stations[1]!!
        assertTrue(w.accepts(b, c), "a town of ${w.towns[1].population} doesn't take syrup")
        // Paid at its own rate: the distance in tiles, times the rate, for each.
        val dist = kotlin.math.abs(a.signX - b.signX) + kotlin.math.abs(a.signY - b.signY)
        assertEquals(100L * dist * 50 * 100 / 10_000, fare(w.roster.cargo, c, 100, a, b, 1))
        a.cargo[c] += Packet(b.id, a.id, 40, w.tick)
        w.apply(Command.BuyTrain(0, 0, 2, car.id, 3, cargo = c))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        assertEquals(120, t.capacity(c))
        var carried = false
        var paid = 0L
        var delivered = 0
        repeat(30_000) {
            w.step()
            if (t.load(c) > 0) carried = true
            paid += w.payments.filter { it.tick == w.tick }.sumOf { it.amount }
            delivered = maxOf(delivered, w.towns[1].delivered[c] + w.towns[1].deliveredLastMonth[c])
        }
        assertTrue(carried)
        assertTrue(paid > 0, "nothing paid")
        assertEquals(40, delivered)
    }

    @Test
    fun replacingABaseCargoChangesItsNumbersAndKeepsItsId() {
        val r = roster(pack(cargo = listOf("""{ "id": "dear_coal", "replaces": "coal", "rate": 99, "colour": "#101010", "name": { "en": "Dear coal" } }""")))
        assertEquals(Cargo.COUNT, r.cargo.size)
        assertEquals(Cargo.COAL, r.cargo.of("coal"))
        assertEquals(-1, r.cargo.of("dear_coal"))
        assertEquals(99, r.cargo.rate[Cargo.COAL])
        assertEquals(Cargo.GRACE[Cargo.COAL], r.cargo.grace[Cargo.COAL])
        assertEquals(0x101010, r.cargo[Cargo.COAL].colour)
        assertEquals("Dear coal", r.cargo[Cargo.COAL].name("fr"))
        assertEquals("base", r.cargo[Cargo.COAL].pack)
        // A later pack can change a pack cargo as well.
        val later = pack(id = "later", cargo = listOf("""{ "id": "x", "replaces": "maple_syrup", "kg": 1300 }"""), needs = "\"base\", \"maple\"")
        val r2 = roster(pack(), later)
        assertEquals(1300, r2.cargo.kg[Cargo.COUNT])
        assertEquals(50, r2.cargo.rate[Cargo.COUNT])
        val e = assertFailsWith<BadPack> { roster(pack(cargo = listOf("""{ "id": "x", "replaces": "sap", "rate": 5 }"""))) }
        assertEquals(listOf("maple: cargo x replaces sap, which isn't there"), e.problems)
    }

    @Test
    fun cargoPastThirtyTwoIsRefused() {
        val most = Cargo.MAX - Cargo.COUNT
        roster(pack(cargo = List(most) { syrup("c$it") }))
        val e = assertFailsWith<BadPack> { roster(pack(cargo = List(most + 1) { syrup("c$it") })) }
        assertEquals(listOf("maple: cargo c$most is past the ${Cargo.MAX} cargo a game can have"), e.problems)
        val twice = assertFailsWith<BadPack> { roster(pack(), pack(id = "again")) }
        assertEquals(listOf("again: cargo maple_syrup is there already"), twice.problems)
    }

    @Test
    fun anUnknownCargoNameIsRefused() {
        // A pack needing the base pack alone is checked as it's read.
        val read = assertFailsWith<BadPack> { pack(cargo = emptyList(), vehicles = listOf(wagon("sap")), industries = listOf(shack(makes = "coal", reads = "sap"))) }.problems
        for (s in listOf("vehicles.json, \"cask_car\": unknown cargo \"sap\"", "industries.json, \"shack\": unknown cargo \"sap\"", "production coal, column 16: unknown cargo \"sap\"")) {
            assertTrue(read.any { s in it }, "no \"$s\" in\n${read.joinToString("\n")}")
        }
        // One needing other packs is checked as the roster is built, rules too.
        val other = pack(id = "other", cargo = emptyList(), vehicles = listOf(wagon("sap")), industries = listOf(shack(makes = "maple_syrup", reads = "sap")), needs = "\"base\", \"maple\"")
        val built = assertFailsWith<BadPack> { roster(pack(), other) }.problems
        assertTrue(built.any { it.startsWith("other:cask_car: unknown cargo \"sap\"") }, built.joinToString("\n"))
        assertTrue(built.any { it.startsWith("other:shack: unknown cargo \"sap\"") }, built.joinToString("\n"))
        // cargo.json needs format 3, and its fields are checked.
        assertTrue("manifest.json: a pack with cargo.json needs \"format\": 3" in assertFailsWith<BadPack> { pack(format = 2) }.problems)
        val bad = assertFailsWith<BadPack> { pack(cargo = listOf(syrup(rate = 0).replace("#b5651d", "red"))) }.problems
        assertTrue("cargo.json, \"maple_syrup\": \"rate\" should be from 1 to 1000" in bad, bad.joinToString("\n"))
        assertTrue("cargo.json, \"maple_syrup\": \"colour\" should be \"#rrggbb\"" in bad, bad.joinToString("\n"))
    }

    @Test
    fun vehiclesAndIndustriesOfAnyPackCanNamePackCargo() {
        val other = pack(id = "other", cargo = emptyList(), vehicles = listOf(wagon()), industries = listOf(shack()), needs = "\"base\", \"maple\"")
        val r = roster(pack(), other)
        val c = r.cargo.of("maple_syrup")
        assertContentEquals(intArrayOf(c), r.engines.first { it.key == "other:cask_car" }.cargoes)
        val kind = r.industries.first { it.key == "other:shack" }
        assertEquals(60, kind.produces[c])
        assertTrue(kind.takes(c) && kind.outputs == 1 shl c && kind.production[c] != null)
        val w = World(NewGame(128, 128, 4, 1900, towns = 0, industries = 0, weather = false), r)
        assertTrue(w.placeIndustry(kind))
        nextMonth(w)
        nextMonth(w)
        assertTrue(w.industries.last().madeLastMonth[c] > 0)
    }

    @Test
    fun aSaveWithAPackCargoKeepsStep() {
        val p = pack(vehicles = listOf(wagon()), industries = listOf(shack()))
        val w = line(roster(p))
        val c = w.roster.cargo.of("maple_syrup")
        assertTrue(w.placeIndustry(w.roster.industries.first { it.key == "maple:shack" }))
        w.stations[0]!!.cargo[c] += Packet(1, 0, 40, w.tick)
        w.apply(Command.BuyTrain(0, 0, 2, w.roster.engines.first { it.key == "maple:cask_car" }.id, 3, cargo = c))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        repeat(Calendar.TICKS_PER_DAY * 5) { w.step() }
        val copy = Save.read(Save.write(w)) { ref -> listOf(Packs.BASE, p).firstOrNull { it.id == ref.id } }
        assertEquals(w.checksum(), copy.checksum())
        repeat(Calendar.TICKS_PER_DAY * 40) { w.step(); copy.step() }
        assertEquals(w.checksum(), copy.checksum())
        assertTrue(w.industries.last().madeLastMonth[c] > 0)
        assertEquals(w.towns[1].delivered[c], copy.towns[1].delivered[c])
    }

    @Test
    fun aVersion63SaveLoads() {
        // Without stockpiles or handling, as an older save loads.
        val w = World(NewGame(64, 64, 3, 1900, towns = 2, industries = 4, weather = false, stockpiles = false, contracts = false, handling = false))
        repeat(Calendar.TICKS_PER_DAY * 20) { w.step() }
        val copy = Save.read(Save.write(w, version = 63))
        assertEquals(Cargo.COUNT, copy.cargoCount)
        repeat(Calendar.TICKS_PER_DAY * 20) { w.step(); copy.step() }
        assertEquals(w.checksum(), copy.checksum())
    }

    @Test
    fun aSaveWhosePacksGiveDifferentCargoIsRefused() {
        val w = World(NewGame(64, 64, 3, 1900, towns = 0, industries = 0, weather = false), roster(pack()))
        val newer = pack(cargo = listOf(syrup("birch_syrup"), syrup()), version = 2)
        val e = assertFailsWith<BadData> { Save.read(Save.write(w)) { ref -> listOf(Packs.BASE, newer).firstOrNull { it.id == ref.id } } }
        assertTrue(e.message!!.startsWith("this game's packs give different cargo now"), e.message)
        // The same cargo at a newer version loads.
        val same = pack(cargo = listOf(syrup(rate = 70)), version = 2)
        assertEquals(70, Save.read(Save.write(w)) { ref -> listOf(Packs.BASE, same).firstOrNull { it.id == ref.id } }.roster.cargo.rate[Cargo.COUNT])
    }

    @Test
    fun twoWorldsWithDifferentPacksDontShareCargo() {
        val a = World(NewGame(64, 64, 3, 1900, towns = 0, industries = 0, weather = false), roster(pack()))
        val ice = pack(id = "ice", cargo = listOf(syrup("ice", rate = 10), """{ "id": "cheap_coal", "replaces": "coal", "rate": 5 }"""))
        val b = World(NewGame(64, 64, 3, 1900, towns = 0, industries = 0, weather = false), roster(ice))
        assertEquals("maple_syrup", a.roster.cargo[Cargo.COUNT].key)
        assertEquals("ice", b.roster.cargo[Cargo.COUNT].key)
        assertEquals(50, a.roster.cargo.rate[Cargo.COUNT])
        assertEquals(10, b.roster.cargo.rate[Cargo.COUNT])
        assertEquals(Cargo.RATE[Cargo.COAL], a.roster.cargo.rate[Cargo.COAL])
        assertEquals(5, b.roster.cargo.rate[Cargo.COAL])
        assertEquals(Cargo.RATE[Cargo.COAL], Roster.BASE.cargo.rate[Cargo.COAL])
        assertEquals(Cargo.COUNT, Roster.BASE.cargo.size)
        assertEquals(Cargo.COUNT, World(64, 64, 1).cargoCount)
    }

    @Test
    fun theTemplatePacksSyrupIsMadeInSpringAndCarried() {
        val dir = File("../packs/template")
        val files = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }
        val r = roster(Packs.read(files))
        val c = r.cargo.of("maple_syrup")
        assertEquals(Cargo.COUNT, c)
        assertTrue(r.cargo.towns[c])
        val shack = r.industries.first { it.key == "template:sugar_shack" }
        assertTrue(shack.produces[c] > 0 && shack.production[c] != null)
        assertTrue(r.engines.any { it.enabled && c in it.cargoes })
    }
}
