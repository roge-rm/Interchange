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
 * HASH being the sheet's, so a changed sheet is done again. Packs downloaded
 * from a game joined are kept apart, as netpack_ID_VERSION_HASH.ichpack, so
 * they never replace a pack added here; the newest [NET_KEPT] of each id are
 * kept.
 *
 * Reading a pack unzips it, so call these off the main thread.
 */
object InstalledPacks {
    private val loaded = HashMap<String, Pair<Long, Pack>>()
    /** Each loaded pack's sheets by name to their cache keys. */
    private val keys = HashMap<Pack, Map<String, String>>()
    /** The pack of each id the game last prepared, whose sprites it draws. */
    private val using = HashMap<String, Pack>()

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
     * The very pack a game joined needs: the one added here if it's that version with that hash, otherwise one
     * downloaded before. A different version could put the game out of step.
     */
    fun findExact(ref: PackRef): Pack? {
        if (ref.id == Packs.BASE_ID) return Packs.BASE.takeIf { it.ref == ref }
        list().firstOrNull { it.ref == ref }?.let { return it }
        val name = netName(ref) ?: return null
        return loadNet(name)?.takeIf { it.ref == ref }
    }

    /**
     * The zip of the pack [ref] names exactly, added or downloaded, for a player joining a game hosted here. A
     * downloaded one's name says which it is, as it was checked when kept.
     */
    fun zip(ref: PackRef): ByteArray? {
        if (ref.id == Packs.BASE_ID) return null
        if (list().any { it.ref == ref }) return files.read("pack_${ref.id}.ichpack")
        return netName(ref)?.let { files.read(it) }
    }

    /**
     * Keeps a pack downloaded from a game joined, once its sprite sheets are checked; the download has been checked
     * to be the pack [ref] names. Throws [BadPack] saying what's wrong.
     */
    fun keepDownloaded(ref: PackRef, zip: ByteArray) {
        val name = netName(ref) ?: throw BadPack(listOf("a bad pack id"))
        val pack = Packs.unzip(zip)
        if (pack.ref != ref) throw BadPack(listOf("not the pack asked for"))
        val problems = spriteProblems(pack)
        if (problems.isNotEmpty()) throw BadPack(problems)
        synchronized(this) {
            files.write(name, zip)
            forget(name)
        }
        // Older downloads of the same pack go, with their cut sprites unless another copy uses them.
        val nets = netNames(ref.id).sortedByDescending { files.modified(it) }
        val kept = nets.take(NET_KEPT).mapNotNull { loadNet(it) }
        for (old in nets.drop(NET_KEPT)) {
            val gone = loadNet(old)
            files.delete(old)
            forget(old)
            if (gone != null) dropSprites(gone, kept + listOfNotNull(list().firstOrNull { it.id == ref.id }, synchronized(this) { using[ref.id] }))
        }
    }

    /** The downloaded copies of pack [id]. */
    private fun netNames(id: String): List<String> {
        val pattern = Regex("netpack_${Regex.escape(id)}_\\d+_[0-9a-f]+\\.ichpack")
        return files.list("netpack_${id}_").filter { pattern.matches(it) }
    }

    /** Deletes the cut sprites of [pack], all but those [keep] use too. */
    private fun dropSprites(pack: Pack, keep: List<Pack>) {
        val kept = keep.flatMap { p -> p.sprites.values.map { key(p.id, it) } }.toSet()
        for (k in pack.sprites.values.map { key(pack.id, it) }.toSet() - kept) {
            files.delete("$k.rects")
            for (f in files.list("${k}_")) files.delete(f)
        }
    }

    private fun netName(ref: PackRef) =
        if (Packs.validId(ref.id)) "netpack_${ref.id}_${ref.version}_${ref.hash.toULong().toString(16)}.ichpack" else null

    @Synchronized
    private fun loadNet(name: String): Pack? {
        val at = files.modified(name)
        loaded[name]?.takeIf { it.first == at }?.let { return it.second }
        val zip = files.read(name) ?: return null
        return try {
            Packs.unzip(zip).also { loaded[name] = at to it }
        } catch (e: BadPack) {
            null
        }
    }

    private fun spriteProblems(pack: Pack): List<String> {
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
        return problems
    }

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
        val problems = spriteProblems(pack)
        if (problems.isNotEmpty()) throw BadPack(problems)
        remove(pack.id)
        files.write("pack_${pack.id}.ichpack", zip)
        prepare(pack)
        return pack
    }

    fun remove(id: String) {
        val pack = list().firstOrNull { it.id == id }
        val inUse = synchronized(this) { using[id]?.let { if (it === pack) { using.remove(id); null } else it } }
        files.delete("pack_$id.ichpack")
        forget("pack_$id.ichpack")
        // Sprites a downloaded copy uses too are left.
        if (pack != null) dropSprites(pack, netNames(id).mapNotNull { loadNet(it) } + listOfNotNull(inUse))
    }

    @Synchronized
    private fun forget(name: String) {
        loaded.remove(name)?.let { keys.remove(it.second) }
    }

    /**
     * The packs [roster] was built from, as [find] finds them, their sprites cut up if they weren't already. Their
     * sprites are the ones drawn from now on.
     */
    fun prepare(roster: Roster, find: (PackRef) -> Pack? = ::find) {
        for (ref in roster.packs) if (ref.id != Packs.BASE_ID) find(ref)?.let { p ->
            prepare(p)
            synchronized(this) { using[p.id] = p }
        }
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
        val pack = using[id] ?: list().firstOrNull { it.id == id } ?: return null
        return keys.getOrPut(pack) { pack.sprites.mapValues { key(id, it.value) } }[name]
    }

    /** Downloaded copies kept of each pack. */
    const val NET_KEPT = 2

    private fun key(id: String, png: ByteArray) = "spr_${id}_${Packs.hash(png).toULong().toString(16)}"
}
