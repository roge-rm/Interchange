package com.rm.interchange.sim

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** PNG through ImageIO, as the checking tool does. */
object JvmPictures : Pictures {
    override fun decode(png: ByteArray): Picture {
        val img = ImageIO.read(ByteArrayInputStream(png))
        val p = Picture(img.width, img.height)
        img.getRGB(0, 0, img.width, img.height, p.argb, 0, img.width)
        return p
    }

    override fun encode(picture: Picture): ByteArray {
        val img = BufferedImage(picture.width, picture.height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, picture.width, picture.height, picture.argb, 0, picture.width)
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }
}

class PackTest {
    /** A sprite sheet with a block in each cell round where the vehicle stands, [empty] cells left out. */
    private fun sheet(empty: Set<Int> = emptySet(), cutOff: Int = -1): Picture {
        val p = Picture(PackSprites.SIZE, PackSprites.SIZE)
        for (c in 0 until 16) {
            if (c in empty) continue
            val cx = c % 4 * Packs.CELL
            val cy = c / 4 * Packs.CELL
            val (x0, y0) = if (c == cutOff) 0 to 0 else Packs.STAND_X - 40 to Packs.STAND_Y - 50
            for (y in y0 until Packs.STAND_Y + 20) for (x in x0 until Packs.STAND_X + 40) p.argb[(cy + y) * PackSprites.SIZE + cx + x] = (0xff shl 24) or (c * 15 shl 16)
        }
        return p
    }

    private fun zip(files: Map<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, v) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(if (v is String) v.encodeToByteArray() else v as ByteArray)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val sprite by lazy { JvmPictures.encode(sheet()) }

    /** A pack as someone else might make one: a new engine, the caboose renamed and drawn anew, the stock car turned off. */
    private fun thirdParty(version: Int = 1, extraFirst: Boolean = false): Pack {
        val extra = if (extraFirst) """{ "id": "railbus", "kind": "engine", "name": { "en": "Railbus" }, "from": 1900, "until": 2000, "speed": 80, "power": 300, "weight": 20, "cost": 9000, "running": 800, "length": 8, "sprite": "prairie" },""" else ""
        return Packs.unzip(zip(mapOf(
            "manifest.json" to """{ "format": 1, "id": "prairie", "version": $version, "name": { "en": "Prairie lines", "fr": "Lignes des Prairies" }, "author": "Someone", "needs": ["base"], "disables": ["base:stock_car"] }""",
            "vehicles.json" to """{ "vehicles": [ $extra
                { "id": "prairie", "kind": "engine", "name": { "en": "Prairie", "fr": "Prairie" }, "type": { "en": "2-6-2" }, "from": 1840, "until": 1950, "speed": 95, "power": 1100, "weight": 60, "cost": 20000, "running": 2500, "length": 11, "tender": "base:ten_wheeler_tender" },
                { "id": "van", "replaces": "base:caboose", "name": { "en": "Van", "fr": "Fourgon" }, "sprite": "van" }
            ] }""",
            "sprites/prairie.png" to sprite,
            "sprites/van.png" to sprite,
        )))
    }

    private fun problems(files: Map<String, Any>): List<String> = assertFailsWith<BadPack> { Packs.unzip(zip(files)) }.problems

    @Test
    fun theBasePackHasTheWholeRoster() {
        val r = Roster.BASE
        assertEquals(67, r.engines.size)
        assertEquals(Plows.ROTARY, r.engines.first { it.key == "base:rotary_plow" }.plow)
        assertEquals("base:american", r[2].key)
        assertEquals(r[3].id, r[2].tender)
        assertTrue(r[3].isTender)
        assertEquals("Wagon plat", r.engines.first { it.key == "base:flatcar" }.name("fr"))
        assertEquals("Wooden coaches", r.engines.first { it.key == "base:coach_wood" }.plural("en"))
        assertEquals(listOf(Packs.BASE.ref), r.packs)
    }

    @Test
    fun theTemplatePackLoadsAndItsSpritesPass() {
        val dir = File("../packs/template")
        val files = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }
        val pack = Packs.read(files)
        val roster = Roster.build(listOf(Packs.BASE, pack))
        assertTrue(roster.engines.size > Roster.BASE.engines.size)
        for ((name, png) in pack.sprites) {
            val p = JvmPictures.decode(png)
            assertEquals(emptyList(), PackSprites.problems(p), name)
        }
    }

    @Test
    fun aPackAddsChangesAndTurnsOffVehicles() {
        val roster = Roster.build(listOf(Packs.BASE, thirdParty()))
        val prairie = roster.engines.last()
        assertEquals("prairie:prairie", prairie.key)
        assertEquals("base:ten_wheeler_tender", roster[prairie.tender].key)
        val van = roster.engines.first { it.key == "base:caboose" }
        assertEquals("Fourgon", van.name("fr"))
        assertEquals(SpriteRef("prairie", "van"), roster.sprites[van.sprite])
        assertEquals(12, van.weight)
        val wagons = roster.available(1900, pulls = false).map { it.key }
        assertTrue("base:stock_car" !in wagons && "base:caboose" in wagons)
        assertTrue(prairie in roster.available(1900, pulls = true))
    }

    @Test
    fun brokenPacksSayWhatsWrong() {
        assertEquals(listOf("manifest.json is missing"), problems(mapOf("vehicles.json" to "{}")))
        assertEquals(listOf("manifest.json, line 2, column 3: expected a name in quotes"), problems(mapOf("manifest.json" to "{\n  id: 1 }")))
        val manifest = """{ "format": 1, "id": "x", "version": 1, "name": { "en": "X" } }"""
        val p = problems(mapOf(
            "manifest.json" to manifest,
            "vehicles.json" to """{ "vehicles": [ { "id": "a", "kind": "engine", "name": { "de": "A" }, "from": 1900, "until": 1890, "weight": 1, "cost": 1, "running": 1, "length": 20, "colour": "red" } ] }""",
        ))
        assertTrue("vehicles.json, \"a\": \"name\" has an unknown language \"de\" (en or fr)" in p, "$p")
        assertTrue("vehicles.json, \"a\": \"name\" needs English, \"en\"" in p, "$p")
        assertTrue("vehicles.json, \"a\": \"speed\" is missing" in p, "$p")
        assertTrue("vehicles.json, \"a\": \"length\" should be from 1 to 16" in p, "$p")
        assertTrue("vehicles.json, \"a\": unknown field \"colour\"" in p, "$p")
        assertTrue("vehicles.json, \"a\": sprites/a.png is missing" in p, "$p")
        assertTrue("vehicles.json, \"a\": from should be before until" in p, "$p")
        assertEquals(
            listOf("made for a newer version of the game (pack format 9, this game reads up to ${Packs.FORMAT})"),
            problems(mapOf("manifest.json" to """{ "format": 9, "id": "x", "version": 1, "name": { "en": "X" } }""")),
        )
        assertEquals(listOf("not a zip file, or an empty one"), assertFailsWith<BadPack> { Packs.unzip("hello".encodeToByteArray()) }.problems)
        // A tender that isn't one, and a replacement of nothing, only show when the roster is put together.
        val wrong = Packs.unzip(zip(mapOf(
            "manifest.json" to manifest,
            "vehicles.json" to """{ "vehicles": [ { "id": "a", "kind": "engine", "name": { "en": "A" }, "from": 1900, "until": 1990, "speed": 50, "power": 50, "weight": 1, "cost": 1, "running": 1, "length": 8, "tender": "base:hopper", "sprite": "s" }, { "id": "b", "replaces": "base:nothing" } ] }""",
            "sprites/s.png" to sprite,
        )))
        val q = assertFailsWith<BadPack> { Roster.build(listOf(Packs.BASE, wrong)) }.problems
        assertEquals(listOf("x:b replaces base:nothing, which isn't there", "x:a: tender base:hopper isn't a tender in these packs"), q)
        assertEquals(listOf("prairie needs base before it"), assertFailsWith<BadPack> { Roster.build(listOf(thirdParty())) }.problems.take(1))
    }

    @Test
    fun spritesAreCutTrimmedAndHalved() {
        val packed = PackSprites.pack(sheet())
        assertEquals(PackSprites.LEVELS, packed.levels.size)
        for (l in 1 until PackSprites.LEVELS) assertEquals(packed.levels[l - 1].width / 2, packed.levels[l].width)
        // Each block reaches 40 either side of where it stands, 50 above and 20 below. The anchor is 64 above where
        // it stands, so the block runs from 40 left to 40 right of it and 14 to 84 below it: out to the grid, -64 to
        // 64 and 0 to 96.
        val r = packed.rects
        for (c in 0 until 16) assertEquals(listOf(128, 96, -64, 0), r.slice(c * 6 + 2 until c * 6 + 6), "cell $c")
        // The first cell's colour lands where its rect says.
        val p = packed.levels[0]
        assertEquals(0xff000000.toInt(), p.argb[(r[1] + 20) * p.width + r[0] + 64])
        assertEquals(listOf("cell 3 is empty", "cell 6 is cut off at its edge"), PackSprites.problems(sheet(empty = setOf(2), cutOff = 5)))
        assertEquals(listOf("should be 1024 by 1024 pixels, not 10 by 10"), PackSprites.problems(Picture(10, 10)))
    }

    /** A line with a depot and two stations, as in the train tests, on [roster]. */
    private fun line(roster: Roster): World {
        val w = World(NewGame(64, 64, 1, startYear = 1880, towns = 0), roster)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        return w
    }

    @Test
    fun aGameWithAPackPlaysSavesAndOnlyLoadsWithIt() {
        val pack = thirdParty()
        val roster = Roster.build(listOf(Packs.BASE, pack))
        val prairie = roster.engines.last().id
        val caboose = roster.engines.first { it.key == "base:caboose" }.id
        val a = line(roster)
        val b = line(roster)
        for (w in listOf(a, b)) {
            w.apply(Command.BuyTrain(0, 0, prairie, caboose, 2))
            w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
            w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
            repeat(3000) { w.step() }
        }
        assertEquals(a.checksum(), b.checksum())
        assertTrue(!a.trains[0]!!.inDepot)
        val saved = Save.write(a)
        assertEquals(listOf(Packs.BASE.ref, pack.ref), Save.packs(saved))
        assertEquals(listOf(pack.ref), assertFailsWith<MissingPacks> { Save.read(saved) }.packs)
        val find = { ref: PackRef -> listOf(Packs.BASE, pack).firstOrNull { it.id == ref.id && it.version >= ref.version } }
        val c = Save.read(saved, find)
        repeat(2000) { a.step(); c.step() }
        assertEquals(a.checksum(), c.checksum())
    }

    @Test
    fun aNewerPackWithMoreVehiclesStillLoadsOldSaves() {
        val roster = Roster.build(listOf(Packs.BASE, thirdParty()))
        val w = line(roster)
        w.apply(Command.BuyTrain(0, 0, roster.engines.last().id, 18, 2))
        val saved = Save.write(w)
        // Version 2 puts a railbus before the prairie, so every place after it moves along.
        val newer = thirdParty(version = 2, extraFirst = true)
        val loaded = Save.read(saved) { ref -> if (ref.id == "prairie") newer else Packs.BASE }
        val t = loaded.trains[0]!!
        assertEquals("prairie:prairie", loaded.roster[t.engine].key)
        assertEquals("base:coach_wood", loaded.roster[t.wagon].key)
    }
}
