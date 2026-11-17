package com.rm.interchange.packtool

import com.rm.interchange.sim.BadPack
import com.rm.interchange.sim.Pack
import com.rm.interchange.sim.PackSprites
import com.rm.interchange.sim.Packs
import com.rm.interchange.sim.Picture
import com.rm.interchange.sim.Pictures
import com.rm.interchange.sim.Roster
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private const val USAGE = """packtool check PACK [--with PACK]...
    Checks a pack, a .ichpack zip or a folder, against Interchange packs ${Packs.SPEC} (format ${Packs.FORMAT}), says everything
    wrong with it and the format it needs.
    --with names packs it needs besides the base pack, in order.
packtool zip FOLDER OUT.ichpack
    Zips a pack's folder up, after checking it.
packtool look PACK OUT.png
    Lists the pack's cargo with their colours, and draws its buildings as the map shows them on their tiles at each
    edge and each turn of the view."""

fun main(args: Array<String>) {
    val ok = when (args.firstOrNull()) {
        "check" -> args.size >= 2 && check(File(args[1]), args.drop(2).chunked(2).map { (flag, path) -> require(flag == "--with") { USAGE }; File(path) })
        "zip" -> args.size == 3 && check(File(args[1]), emptyList()) && zip(File(args[1]), File(args[2]))
        "look" -> args.size == 3 && look(load(File(args[1]), base = true), File(args[2]))
        else -> { println(USAGE); false }
    }
    exitProcess(if (ok) 0 else 1)
}

private val DATA_FILES = setOf("manifest.json", "vehicles.json", "houses.json", "industries.json", "cargo.json")

/** Whether a file is part of a pack: a data file or a sprite sheet. Art sources, READMEs, etc. are left out. */
private fun kept(name: String) = name in DATA_FILES || (name.startsWith("sprites/") && name.endsWith(".png"))

/**
 * A pack's files by path, from a zip or a folder, held to the game's limits. The base pack may be up to
 * [Packs.MAX_TOTAL_BASE] unpacked, as the game never adds it from a file; [check] holds other packs to [Packs.MAX_TOTAL].
 */
private fun files(f: File): Map<String, ByteArray> {
    if (!f.isDirectory) return com.rm.interchange.sim.Zip.read(f.readBytes(), Packs.MAX_FILE, Packs.MAX_TOTAL_BASE).filterKeys(::kept)
    val out = LinkedHashMap<String, ByteArray>()
    var total = 0L
    for (file in f.walkTopDown().filter { it.isFile }) {
        val name = file.relativeTo(f).invariantSeparatorsPath
        if (!kept(name)) continue
        if (file.length() > Packs.MAX_FILE) throw BadPack(listOf("$name is bigger than ${Packs.MAX_FILE shr 20} MB"))
        total += file.length()
        if (total > Packs.MAX_TOTAL_BASE) throw BadPack(listOf("the pack is bigger than ${Packs.MAX_TOTAL_BASE shr 20} MB unpacked"))
        out[name] = file.readBytes()
    }
    return out
}

/**
 * The base pack read from its folder in the repo, which has no sprites: its sheets are drawn by tools/gen_tiles.py
 * into build/packs/base.ichpack, so they're checked there.
 */
private fun bareBase(files: Map<String, ByteArray>) = files.keys.none { it.startsWith("sprites/") } &&
    (files["manifest.json"]?.decodeToString()?.let { Regex("\"id\"\\s*:\\s*\"base\"").containsMatchIn(it) } ?: false)

private fun load(f: File, base: Boolean = false): Pack = files(f).let { Packs.read(it, spritesElsewhere = base && bareBase(it), base = base) }

/** Checks [f] as the game would load it, with [with] before it, and says what it finds through [say]. */
internal fun check(f: File, with: List<File>, say: (String) -> Unit = ::println, maxTotal: Int = Packs.MAX_TOTAL): Boolean {
    if (!f.exists()) { say("$f isn't there"); return false }
    val problems = ArrayList<String>()
    val files = try {
        files(f)
    } catch (e: BadPack) {
        report(f, e.problems, say)
        return false
    }
    val pack = try {
        Packs.read(files, spritesElsewhere = bareBase(files), base = true)
    } catch (e: BadPack) {
        report(f, e.problems, say)
        return false
    }
    val unpacked = files.values.sumOf { it.size.toLong() }
    if (pack.id != Packs.BASE_ID && unpacked > maxTotal) problems += "the pack is bigger than ${maxTotal shr 20} MB unpacked"
    val roster = try {
        Roster.build(if (pack.id == Packs.BASE_ID) listOf(pack) else listOf(Packs.BASE) + with.map { load(it) } + pack)
    } catch (e: BadPack) {
        problems += e.problems
        null
    }
    // Fields the game leaves out, and new cargo nothing carries or makes, which can't be part of a game; the pack still loads.
    val warnings = ArrayList(pack.warnings.map { "$it, which the game leaves out" })
    if (roster != null) for (c in pack.cargo.filter { it.replaces == null }) {
        val id = roster.cargo.of(c.id)
        if (roster.engines.none { it.enabled && id in it.cargoes }) warnings += "cargo.json, \"${c.id}\": no vehicle carries it"
        if (roster.industries.none { it.enabled && it.outputs and (1 shl id) != 0 }) warnings += "cargo.json, \"${c.id}\": no industry makes it"
    }
    // Rules are parsed as the pack is read, so a bad one is already listed above.
    val buildingSheets = pack.houses.mapNotNull { it.sprite }.toSet() + pack.industries.flatMap { it.parts.orEmpty().values }
    val vehicleSheets = pack.vehicles.flatMap { listOfNotNull(it.sprite, it.spriteOffWire) }.toSet()
    val used = vehicleSheets + buildingSheets
    for ((name, png) in pack.sprites) {
        if (name !in used) { problems += "sprites/$name.png isn't used by any vehicle, building or industry"; continue }
        val picture = try {
            JvmPictures.decode(png)
        } catch (e: Exception) {
            problems += "sprites/$name.png can't be read as a PNG"
            continue
        }
        val wrong = PackSprites.problems(picture, if (name in buildingSheets && name !in vehicleSheets) PackSprites.BUILDING_CELLS else 16)
        problems += wrong.map { "sprites/$name.png: $it" }
        if (wrong.isEmpty()) PackSprites.pack(picture)
    }
    if (problems.isNotEmpty()) { report(f, problems, say); return false }
    for (w in warnings) say("  warning: $w")
    val rules = pack.industries.sumOf { it.production.orEmpty().size + (if (it.open != null) 1 else 0) + (if (it.closes != null) 1 else 0) }
    val sheets = if (bareBase(files)) "sprite sheets not here (build/packs/base.ichpack has them)" else "${pack.sprites.size} sprite sheets"
    say("${pack.id} ${pack.version}: ${pack.vehicles.size} vehicles, ${pack.houses.size} buildings, ${pack.industries.size} industries, ${pack.cargo.size} cargo, $rules rules, $sheets, ${String.format(java.util.Locale.ROOT, "%.1f", unpacked / 1048576.0)} MB unpacked, all good")
    val note = when {
        pack.id == Packs.BASE_ID -> if (pack.needsFormat > pack.format) ", fine for the base pack, which comes with the game" else ""
        pack.needsFormat < pack.format && pack.warnings.isEmpty() -> ", so \"format\": ${pack.needsFormat} would let older games load it too"
        else -> ""
    }
    say("  needs format ${pack.needsFormat} (Interchange packs ${Packs.SPEC} is format ${Packs.FORMAT}), says ${pack.format}$note")
    return true
}

private fun report(f: File, problems: List<String>, say: (String) -> Unit) {
    say("$f has ${problems.size} problem${if (problems.size == 1) "" else "s"}:")
    for (p in problems) say("  $p")
}

/** Only what a pack is made of goes in: the data files and the sprite sheets. */
private fun zip(dir: File, out: File): Boolean {
    val files = files(dir)
    ZipOutputStream(out.outputStream()).use { z ->
        for ((name, bytes) in files.toSortedMap()) {
            z.putNextEntry(ZipEntry(name))
            z.write(bytes)
            z.closeEntry()
        }
    }
    println("Wrote $out")
    return true
}

internal object JvmPictures : Pictures {
    override fun decode(png: ByteArray): Picture {
        val img = ImageIO.read(ByteArrayInputStream(png)) ?: error("not a picture")
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
