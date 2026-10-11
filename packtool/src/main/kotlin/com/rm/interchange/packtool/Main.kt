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
    Checks a pack, a .ichpack zip or a folder, and says everything wrong with it.
    --with names packs it needs besides the base pack, in order.
packtool zip FOLDER OUT.ichpack
    Zips a pack's folder up, after checking it."""

fun main(args: Array<String>) {
    val ok = when (args.firstOrNull()) {
        "check" -> args.size >= 2 && check(File(args[1]), args.drop(2).chunked(2).map { (flag, path) -> require(flag == "--with") { USAGE }; File(path) })
        "zip" -> args.size == 3 && check(File(args[1]), emptyList()) && zip(File(args[1]), File(args[2]))
        else -> { println(USAGE); false }
    }
    exitProcess(if (ok) 0 else 1)
}

/** A pack's files by path, from a zip or a folder. */
private fun files(f: File): Map<String, ByteArray> =
    if (f.isDirectory) f.walkTopDown().filter { it.isFile }.associate { it.relativeTo(f).invariantSeparatorsPath to it.readBytes() }
    else com.rm.interchange.sim.Zip.read(f.readBytes(), Packs.MAX_FILE, Packs.MAX_TOTAL)

private fun load(f: File, base: Boolean = false): Pack = Packs.read(files(f), base = base)

private fun check(f: File, with: List<File>): Boolean {
    if (!f.exists()) { println("$f isn't there"); return false }
    val problems = ArrayList<String>()
    val pack = try {
        load(f, base = true)
    } catch (e: BadPack) {
        report(f, e.problems)
        return false
    }
    try {
        Roster.build(if (pack.id == Packs.BASE_ID) listOf(pack) else listOf(Packs.BASE) + with.map { load(it) } + pack)
    } catch (e: BadPack) {
        problems += e.problems
    }
    val used = pack.vehicles.mapNotNull { it.sprite }.toSet()
    for ((name, png) in pack.sprites) {
        if (name !in used) { problems += "sprites/$name.png isn't used by any vehicle"; continue }
        val picture = try {
            JvmPictures.decode(png)
        } catch (e: Exception) {
            problems += "sprites/$name.png can't be read as a PNG"
            continue
        }
        val wrong = PackSprites.problems(picture)
        problems += wrong.map { "sprites/$name.png: $it" }
        if (wrong.isEmpty()) PackSprites.pack(picture)
    }
    if (problems.isNotEmpty()) { report(f, problems); return false }
    println("${pack.id} ${pack.version}: ${pack.vehicles.size} vehicles, ${pack.sprites.size} sprite sheets, all good")
    return true
}

private fun report(f: File, problems: List<String>) {
    println("$f has ${problems.size} problem${if (problems.size == 1) "" else "s"}:")
    for (p in problems) println("  $p")
}

/** Only what a pack is made of goes in: the two data files and the sprite sheets. */
private fun zip(dir: File, out: File): Boolean {
    val files = files(dir).filterKeys { it == "manifest.json" || it == "vehicles.json" || (it.startsWith("sprites/") && it.endsWith(".png")) }
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

private object JvmPictures : Pictures {
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
