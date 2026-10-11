package com.rm.interchange.ui

import com.rm.interchange.platform.files
import com.rm.interchange.platform.pictures
import com.rm.interchange.sim.BadPack
import com.rm.interchange.sim.Pack
import com.rm.interchange.sim.PackRef
import com.rm.interchange.sim.PackSprites
import com.rm.interchange.sim.Packs
import com.rm.interchange.sim.Roster

/**
 * The content packs added to the game, each kept as it came, pack_ID.ichpack,
 * one version of each. Their sprite sheets are cut up and shrunk once, when
 * added, and kept as spr_ID_HASH_LEVEL.png and spr_ID_HASH.rects beside them,
 * HASH being the sheet's, so a changed sheet is done again.
 *
 * Reading a pack unzips it, so call these off the main thread.
 */
object InstalledPacks {
    private val loaded = HashMap<String, Pair<Long, Pack>>()
    /** Each loaded pack's sheets by name to their cache keys. */
    private val keys = HashMap<Pack, Map<String, String>>()

    /** Every pack added, oldest first, the base pack left out. */
    @Synchronized
    fun list(): List<Pack> = files.list("pack_").filter { it.endsWith(".ichpack") }.reversed().mapNotNull { name ->
        val at = files.modified(name)
        loaded[name]?.takeIf { it.first == at }?.second ?: try {
            Packs.unzip(files.read(name) ?: return@mapNotNull null).also { loaded[name] = at to it }
        } catch (e: BadPack) {
            null
        }
    }

    /** The pack a save or game needs: the one with that id, at that version or newer. */
    fun find(ref: PackRef): Pack? =
        if (ref.id == Packs.BASE_ID) Packs.BASE.takeIf { it.version >= ref.version }
        else list().firstOrNull { it.id == ref.id && it.version >= ref.version }

    /**
     * Adds a pack from its zip, or a newer version of one already added, once
     * it's checked: its data, that it fits with the base pack and those it
     * needs, and every sprite sheet. Throws [BadPack] saying what's wrong.
     */
    fun add(zip: ByteArray): Pack {
        val pack = Packs.unzip(zip)
        if (pack.id == Packs.BASE_ID) throw BadPack(listOf("the base pack is already in the game"))
        val others = list().filter { it.id != pack.id }
        Roster.build(listOf(Packs.BASE) + pack.needs.mapNotNull { n -> others.firstOrNull { it.id == n } } + pack)
        val problems = ArrayList<String>()
        for ((name, png) in pack.sprites) {
            val picture = try {
                pictures.decode(png)
            } catch (e: Exception) {
                problems += "sprites/$name.png can't be read as a PNG"
                continue
            }
            problems += PackSprites.problems(picture).map { "sprites/$name.png: $it" }
        }
        if (problems.isNotEmpty()) throw BadPack(problems)
        remove(pack.id)
        files.write("pack_${pack.id}.ichpack", zip)
        prepare(pack)
        return pack
    }

    fun remove(id: String) {
        files.delete("pack_$id.ichpack")
        for (f in files.list("spr_${id}_")) files.delete(f)
        forget("pack_$id.ichpack")
    }

    @Synchronized
    private fun forget(name: String) {
        loaded.remove(name)?.let { keys.remove(it.second) }
    }

    /** The packs [roster] was built from, their sprites cut up if they weren't already. */
    fun prepare(roster: Roster) {
        for (ref in roster.packs) if (ref.id != Packs.BASE_ID) find(ref)?.let { prepare(it) }
    }

    private fun prepare(pack: Pack) {
        for ((name, png) in pack.sprites) {
            val key = key(pack.id, png)
            if (files.read("$key.rects") != null) continue
            val packed = PackSprites.pack(pictures.decode(png))
            packed.levels.forEachIndexed { level, p -> files.write("${key}_$level.png", pictures.encode(p)) }
            // The rects last, so a sheet half done when the app stopped is done again.
            files.write("$key.rects", packed.rects.joinToString(",").encodeToByteArray())
        }
    }

    /** Sheet [name] of pack [id] at [level], ready packed, or null. */
    fun sheet(id: String, name: String, level: Int): ByteArray? = sprite(id, name)?.let { files.read("${it}_$level.png") }

    /** The rects of sheet [name] of pack [id], or null. */
    fun rects(id: String, name: String): IntArray? =
        sprite(id, name)?.let { k -> files.read("$k.rects")?.decodeToString()?.split(",")?.mapNotNull { it.toIntOrNull() }?.toIntArray()?.takeIf { it.size == 96 } }

    @Synchronized
    private fun sprite(id: String, name: String): String? {
        val pack = list().firstOrNull { it.id == id } ?: return null
        return keys.getOrPut(pack) { pack.sprites.mapValues { key(id, it.value) } }[name]
    }

    private fun key(id: String, png: ByteArray) = "spr_${id}_${Packs.hash(png).toULong().toString(16)}"
}
