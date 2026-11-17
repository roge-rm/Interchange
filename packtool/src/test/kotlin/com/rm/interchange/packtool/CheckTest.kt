package com.rm.interchange.packtool

import com.rm.interchange.sim.Packs
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** packtool check on the repo's packs, and on the template with one thing broken for each group of fields. */
class CheckTest {
    private val template = File("packs/template")
    private val made = ArrayList<File>()

    @AfterTest
    fun cleanUp() {
        for (d in made) d.deleteRecursively()
    }

    /** What check says of [f], and whether it passed. */
    private fun check(f: File, with: List<File> = emptyList(), maxTotal: Int = Packs.MAX_TOTAL): Pair<Boolean, List<String>> {
        val out = ArrayList<String>()
        val ok = check(f, with, { out += it }, maxTotal)
        return ok to out
    }

    /** A copy of the template with [edits] made to its files: each takes a file's text and gives it back changed. */
    private fun template(vararg edits: Pair<String, (String) -> String>): File {
        val d = createTempDirectory("packtool").toFile().also { made += it }
        template.copyRecursively(d)
        for ((name, edit) in edits) File(d, name).let { it.writeText(edit(it.readText())) }
        return d
    }

    /** The template's [file] with [old] swapped for [new], which has to be there once. */
    private fun swap(file: String, old: String, new: String): Pair<String, (String) -> String> = file to { text ->
        assertEquals(1, text.split(old).size - 1, "$file: $old")
        text.replace(old, new)
    }

    /** Checks the template with [edits] fails, saying [problem] among its problems. */
    private fun fails(problem: String, vararg edits: Pair<String, (String) -> String>) {
        val (ok, out) = check(template(*edits))
        assertFalse(ok, "$out")
        assertTrue(out.any { problem in it }, "\"$problem\" not in $out")
    }

    @Test
    fun theBaseAndTemplatePacksPass() {
        val (base, baseOut) = check(File("packs/base"))
        assertTrue(base, "$baseOut")
        assertTrue(baseOut.any { "sprite sheets not here" in it }, "$baseOut")
        assertTrue(baseOut.any { it.trim().startsWith("needs format ${Packs.FORMAT}") }, "$baseOut")
        val (ok, out) = check(template)
        assertTrue(ok, "$out")
        assertTrue(out.none { "warning" in it }, "$out")
        assertEquals("  needs format 4 (Interchange packs ${Packs.SPEC} is format ${Packs.FORMAT}), says 4", out.last())
    }

    @Test
    fun manifest() {
        fails("\"format\" should be a whole number", swap("manifest.json", "\"format\": 4", "\"format\": \"4\""))
        fails("made for a newer version of the game (pack format 5, this game reads up to 4, Interchange packs 1.0)", swap("manifest.json", "\"format\": 4", "\"format\": 5"))
        fails("manifest.json: a pack with \"traction\": \"hydrogen\" needs \"format\": 4", swap("manifest.json", "\"format\": 4", "\"format\": 3"))
        fails("id \"Template\" should be lower case", swap("manifest.json", "\"id\": \"template\"", "\"id\": \"Template\""))
        fails("\"name\" needs English", swap("manifest.json", "\"en\": \"Template pack\", ", ""))
    }

    @Test
    fun vehicles() {
        fails("traction should be steam, diesel", swap("vehicles.json", "\"traction\": \"hydrogen\"", "\"traction\": \"fusion\""))
        fails("\"range\" should be a whole number", swap("vehicles.json", "\"range\": 380", "\"range\": \"far\""))
        fails("\"autonomous\" should be true or false", swap("vehicles.json", "\"autonomous\": true", "\"autonomous\": 1"))
        fails("only road vehicles are autonomous", swap("vehicles.json", "\"capacity\": 55", "\"capacity\": 55, \"autonomous\": true"))
        fails("only engines and wagons are hyperloop pods", swap("vehicles.json", "\"traction\": \"hydrogen\"", "\"traction\": \"hyperloop\""))
        fails("hyperloop pods carry only passengers and mail", swap("vehicles.json", "\"capacity\": 55", "\"capacity\": 55, \"traction\": \"hyperloop\""))
        fails("unknown cargo \"maple_taffy\"", swap("vehicles.json", "\"carries\": \"maple_syrup\",\n      \"capacity\": 30", "\"carries\": \"maple_taffy\",\n      \"capacity\": 30"))
        fails("\"length\" should be from 1 to 16", swap("vehicles.json", "\"length\": 11", "\"length\": 17"))
        fails("isn't a tender in these packs", swap("vehicles.json", "\"tender\": \"prairie_tender\"", "\"tender\": \"prairie_van\""))
    }

    @Test
    fun aircraftAndDrones() {
        val drone = """{ "id": "syrup_drone", "kind": "aircraft", "name": { "en": "Syrup drone" }, "from": 2035, "until": 2100, "speed": 80, "power": 20, "weight": 1, "cost": 900, "running": 60, "length": 1, "runway": "drone", "range": 12, "carries": "mail", "capacity": 4, "sprite": "syrup_hauler" },"""
        val good = template(swap("vehicles.json", "\"vehicles\": [", "\"vehicles\": [ $drone"))
        assertTrue(check(good).first, "${check(good).second}")
        fails("drones carry only mail, goods, food", swap("vehicles.json", "\"vehicles\": [", "\"vehicles\": [ ${drone.replace("\"mail\"", "\"coal\"")}"))
        fails("drones carry at most 6", swap("vehicles.json", "\"vehicles\": [", "\"vehicles\": [ ${drone.replace("\"capacity\": 4", "\"capacity\": 9")}"))
        fails("runway should be strip", swap("vehicles.json", "\"vehicles\": [", "\"vehicles\": [ ${drone.replace("\"drone\"", "\"pad\"")}"))
    }

    @Test
    fun houses() {
        fails("zone \"downtown\" should be outskirts", swap("houses.json", "\"suburbs\"", "\"downtown\""))
        fails("\"residents\" should be from 0 to 10000", swap("houses.json", "\"residents\": 4", "\"residents\": 20000"))
        fails("\"jobs\" is missing", swap("houses.json", "\"jobs\": 6,", ""))
    }

    @Test
    fun industries() {
        fails("unknown region \"yukon\"", swap("industries.json", "\"mixed\": 2", "\"yukon\": 2"))
        fails("\"future\" should be true or false", swap("industries.json", "\"future\": true", "\"future\": \"yes\""))
        fails("\"fades\" should be from 1700 to 2300", swap("industries.json", "\"future\": true", "\"fades\": 3000"))
        fails("recipe: \"maple_syrup\" should be in \"accepts\" too", swap("industries.json", "\"accepts\": [\"maple_syrup\"]", "\"accepts\": [\"timber\"]"))
        fails("recipe: \"hydrogen\" should be in \"produces\" too, at 0", swap("industries.json", "\"produces\": { \"hydrogen\": 0 }", "\"produces\": { \"hydrogen\": 5 }"))
        fails("\"recipe\" amounts should be from 1 to 100", swap("industries.json", "\"maple_syrup\": 3 }", "\"maple_syrup\": 300 }"))
        fails("part \"syrup_vat\" isn't in \"parts\" or the base pack", swap("industries.json", "\"hydrogen_hall hydrogen_tanks\"", "\"hydrogen_hall syrup_vat\""))
        fails("\"layout\" needs rows of the same length", swap("industries.json", "[\"hydrogen_hall hydrogen_tanks\"]", "[\"hydrogen_hall hydrogen_tanks\", \"hydrogen_hall\"]"))
    }

    @Test
    fun rules() {
        fails("production maple_syrup, column", swap("industries.json", "* level / 16\" }\n    },\n    {\n      \"id\": \"maple", "* sap / 16\" }\n    },\n    {\n      \"id\": \"maple"))
        fails("open, column", swap("industries.json", "\"open\": \"year < 1960 || stations > 0\"", "\"open\": \"year < (1960\""))
        fails("production: \"fish\" should be in \"produces\" too, above 0", swap("industries.json", "\"produces\": { \"fish\": 60 }", "\"produces\": { \"fish\": 0 }"))
    }

    @Test
    fun cargo() {
        fails("\"handling\" should be one of people, bulk, general, liquid, livestock", swap("cargo.json", "\"liquid\"", "\"syrup\""))
        fails("\"colour\" should be \"#rrggbb\"", swap("cargo.json", "\"#b5651d\"", "\"brown\""))
        fails("\"keeps\" should be true or false", swap("cargo.json", "\"towns\": true,", "\"towns\": true, \"keeps\": \"no\","))
        fails("\"rate\" should be from 1 to 1000", swap("cargo.json", "\"rate\": 46", "\"rate\": 0"))
    }

    @Test
    fun sprites() {
        val d = template()
        File(d, "sprites/spare.png").writeBytes(File(d, "sprites/van.png").readBytes())
        val (ok, out) = check(d)
        assertFalse(ok)
        assertTrue(out.any { "sprites/spare.png isn't used" in it }, "$out")
        val e = template()
        File(e, "sprites/syrup_hauler.png").writeText("not a picture")
        assertTrue(check(e).second.any { "sprites/syrup_hauler.png can't be read as a PNG" in it })
        fails("sprites/syrup_tanker.png is missing", swap("vehicles.json", "\"autonomous\": true", "\"autonomous\": true, \"sprite\": \"syrup_tanker\""))
    }

    @Test
    fun unknownFieldsAreWarnings() {
        val (ok, out) = check(template(swap("vehicles.json", "\"range\": 380", "\"range\": 380, \"rnage\": 400"), swap("manifest.json", "\"version\": 2", "\"version\": 2, \"website\": \"x\"")))
        assertTrue(ok, "$out")
        assertTrue("  warning: vehicles.json, \"syrup_hauler\": unknown field \"rnage\", which the game leaves out" in out, "$out")
        assertTrue("  warning: manifest.json: unknown field \"website\", which the game leaves out" in out, "$out")
    }

    @Test
    fun theFormatAPackNeeds() {
        // Just the engine and its tender need format 1; saying 4 is fine, and check says older games could load it with 1.
        val d = createTempDirectory("packtool").toFile().also { made += it }
        File(d, "sprites").mkdirs()
        for (s in listOf("prairie", "prairie_tender")) File(template, "sprites/$s.png").copyTo(File(d, "sprites/$s.png"))
        File(d, "manifest.json").writeText("""{ "format": 4, "id": "steam", "version": 1, "name": { "en": "Steam" } }""")
        val text = File(template, "vehicles.json").readText()
        File(d, "vehicles.json").writeText(text.substring(0, text.indexOf("    {\n      \"id\": \"ore_car\"")).trimEnd().removeSuffix(",") + "\n  ]\n}\n")
        val (ok, out) = check(d)
        assertTrue(ok, "$out")
        assertEquals("  needs format 1 (Interchange packs ${Packs.SPEC} is format ${Packs.FORMAT}), says 4, so \"format\": 1 would let older games load it too", out.last())
    }

    @Test
    fun packsItNeeds() {
        val d = createTempDirectory("packtool").toFile().also { made += it }
        File(d, "sprites").mkdirs()
        File(template, "sprites/ore_car.png").copyTo(File(d, "sprites/cask.png"))
        File(d, "manifest.json").writeText("""{ "format": 3, "id": "casks", "version": 1, "name": { "en": "Casks" }, "needs": ["base", "template"] }""")
        File(d, "vehicles.json").writeText("""{ "vehicles": [ { "id": "cask", "kind": "wagon", "name": { "en": "Cask car" }, "from": 1900, "until": 2000, "weight": 12, "cost": 2000, "running": 200, "length": 8, "carries": "maple_syrup", "capacity": 40 } ] }""")
        assertTrue(check(d, listOf(template)).first)
        assertTrue(check(d).second.any { "casks needs template before it" in it })
    }

    @Test
    fun theSizeLimit() {
        // Packs added in the game are held to the limit; the base pack, which never is, isn't.
        assertTrue(check(template, maxTotal = 1 shl 20).second.any { "the pack is bigger than 1 MB unpacked" in it })
        assertTrue(check(File("packs/base"), maxTotal = 1 shl 10).first)
        // Files a zip leaves out don't count towards the limits.
        val extra = template()
        File(extra, "art").mkdirs()
        File(extra, "art/source.psd").writeBytes(ByteArray(3 shl 20))
        File(extra, "README.md").writeBytes(ByteArray(3 shl 20))
        assertTrue(check(extra, maxTotal = 3 shl 20).first)
    }
}
