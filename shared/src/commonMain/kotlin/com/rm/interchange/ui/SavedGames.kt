package com.rm.interchange.ui

import com.rm.interchange.platform.files
import com.rm.interchange.server.GameThread
import com.rm.interchange.sim.World

/**
 * The saved games: each a save file, NAME.ichg, and beside it NAME.info, a few
 * name=value lines about it for the list, so the list needn't read whole saves.
 */
object SavedGames {
    const val AUTOSAVE = "autosave"

    class Entry(val name: String, val day: Int, val startYear: Int, val money: Long, val width: Int, val height: Int, val region: Int, val savedAt: Long)

    /** Saves the game running in [runner] as [name], or a new name if null; [done] is called once it's written, off the main thread. */
    fun save(runner: GameThread, name: String? = null, done: () -> Unit = {}) {
        runner.onGameThread { g ->
            val bytes = com.rm.interchange.sim.Save.write(g.world)
            val info = info(g.world, runner.company)
            val file = name ?: nextName()
            files.write("$file.ichg", bytes)
            files.write("$file.info", info)
            done()
        }
    }

    /** The next free save name: save_1, save_2 and on. */
    private fun nextName(): String {
        val used = files.list("save_").mapNotNull { it.removePrefix("save_").substringBefore('.').toIntOrNull() }
        return "save_${(used.maxOrNull() ?: 0) + 1}"
    }

    /** The bytes of an autosave handed over by the game thread, written with its notes. */
    fun writeAutosave(bytes: ByteArray, world: World, company: Int) {
        files.write("$AUTOSAVE.ichg", bytes)
        files.write("$AUTOSAVE.info", info(world, company))
    }

    /** A save's notes for the list, with the money of [company], the one played. */
    fun info(world: World, company: Int): ByteArray {
        val g = world.settings
        return ("day=${world.day}\nstartYear=${g.startYear}\nmoney=${world.companies.getOrNull(company)?.money ?: 0L}\n" +
            "width=${g.width}\nheight=${g.height}\nregion=${g.region.ordinal}\n").encodeToByteArray()
    }

    /** Every saved game, newest first, the autosave among them. */
    fun list(): List<Entry> = files.list("").filter { it.endsWith(".ichg") }.mapNotNull { f ->
        val name = f.removeSuffix(".ichg")
        val text = files.read("$name.info")?.decodeToString() ?: return@mapNotNull null
        val v = text.lines().mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        Entry(
            name, v["day"]?.toIntOrNull() ?: 0, v["startYear"]?.toIntOrNull() ?: 1850, v["money"]?.toLongOrNull() ?: 0,
            v["width"]?.toIntOrNull() ?: 0, v["height"]?.toIntOrNull() ?: 0, v["region"]?.toIntOrNull() ?: 0, files.modified(f),
        )
    }

    fun read(name: String): ByteArray? = files.read("$name.ichg")

    fun delete(name: String) {
        files.delete("$name.ichg")
        files.delete("$name.info")
    }
}
