package com.rm.interchange.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.interchange.platform.files

/** The player's settings, kept in a small file of name=value lines. */
class Settings {
    /** Colours: false for Standard, true for the set safe for colour blindness. */
    var safeColours by mutableStateOf(false)
    /** "" for the phone's own, else "en" or "fr". */
    var language by mutableStateOf("")
    /** Which of [AUTOSAVE_DAYS] autosaves come at. */
    var autosave by mutableIntStateOf(1)
    /** The tile grid and the bands of height drawn over the map. */
    var grid by mutableStateOf(false)
    var heights by mutableStateOf(false)
    /** Snow and ice by depth over the map. */
    var snow by mutableStateOf(false)
    /** Track coloured by its condition. */
    var condition by mutableStateOf(false)
    /** Priority track and road, and the next days' snow risk on the company's lines, lit over the map. */
    var priority by mutableStateOf(false)
    var risk by mutableStateOf(false)
    /** The track trains hold paths through, lit over the map. */
    var reserved by mutableStateOf(false)
    /** Master, the world, and tools and news, each from 0 to 100. A missing line stays at 100. */
    var sound by mutableIntStateOf(100)
    var world by mutableIntStateOf(100)
    var tools by mutableIntStateOf(100)
    /** The music, 0 to 100; a missing line is 60. */
    var music by mutableIntStateOf(60)
    /** The name other players see, and the address last joined. */
    var playerName by mutableStateOf("")
    var lastAddress by mutableStateOf("")
    /** Made once, so a server knows this phone again and gives back its company. */
    var client = ""
    /**
     * The last game joined, for rejoining it from the multiplayer screen: its address and port, the company played
     * there (-2, Message.SPECTATE, watching) and the server's name; an empty address for none. No password is kept.
     */
    var rejoin by mutableStateOf(LastGame("", 0, -1, ""))
    /** The first-route checklist in new games. Turned off when it's dismissed or done. */
    var checklist by mutableStateOf(true)
    /** The game the checklist is going in, as [checklistKey] gives it, and the steps ticked there, a bit each. */
    var checklistGame = ""
    var checklistTicks = 0
    /** The campaign chapters won, by id. */
    var chaptersWon by mutableStateOf(emptySet<String>())

    val autosaveDays get() = AUTOSAVE_DAYS[autosave.coerceIn(0, AUTOSAVE_DAYS.size - 1)]

    fun save() {
        files.write(FILE, ("language=$language\ncolours=${if (safeColours) "safe" else "standard"}\nautosave=$autosave\ngrid=$grid\nheights=$heights\nsnow=$snow\ncondition=$condition\nreserved=$reserved\npriority=$priority\nrisk=$risk\n" +
            "sound=$sound\nworld=$world\ntools=$tools\nmusic=$music\n" +
            "player=${playerName.replace('\n', ' ')}\naddress=${lastAddress.replace('\n', ' ')}\nclient=$client\n" +
            "checklist=$checklist\nchecklistGame=$checklistGame\nchecklistTicks=$checklistTicks\n" +
            "chapters=${chaptersWon.sorted().joinToString(",")}\n" +
            "rejoinAddress=${rejoin.address.replace('\n', ' ')}\nrejoinPort=${rejoin.port}\nrejoinCompany=${rejoin.company}\n" +
            "rejoinServer=${rejoin.server.replace('\n', ' ')}\n").encodeToByteArray())
    }

    companion object {
        private const val FILE = "settings.txt"
        val LANGUAGES = listOf("", "en", "fr")
        /** Off, a month, three months, a year, in days. */
        val AUTOSAVE_DAYS = intArrayOf(0, 30, 91, 365)

        fun load(): Settings {
            val s = Settings()
            val text = files.read(FILE)?.decodeToString() ?: ""
            for (line in text.lines()) {
                val (k, v) = line.split("=", limit = 2).takeIf { it.size == 2 } ?: continue
                when (k) {
                    "language" -> if (v in LANGUAGES) s.language = v
                    "colours" -> s.safeColours = v == "safe"
                    "autosave" -> v.toIntOrNull()?.let { s.autosave = it }
                    "grid" -> s.grid = v == "true"
                    "heights" -> s.heights = v == "true"
                    "snow" -> s.snow = v == "true"
                    "condition" -> s.condition = v == "true"
                    "reserved" -> s.reserved = v == "true"
                    "priority" -> s.priority = v == "true"
                    "risk" -> s.risk = v == "true"
                    "sound" -> v.toIntOrNull()?.let { s.sound = it.coerceIn(0, 100) }
                    "world" -> v.toIntOrNull()?.let { s.world = it.coerceIn(0, 100) }
                    "tools" -> v.toIntOrNull()?.let { s.tools = it.coerceIn(0, 100) }
                    "music" -> v.toIntOrNull()?.let { s.music = it.coerceIn(0, 100) }
                    "player" -> s.playerName = v
                    "address" -> s.lastAddress = v
                    "client" -> s.client = v
                    "checklist" -> s.checklist = v != "false"
                    "checklistGame" -> s.checklistGame = v
                    "checklistTicks" -> v.toIntOrNull()?.let { s.checklistTicks = it }
                    "chapters" -> s.chaptersWon = v.split(",").filter { it.isNotBlank() }.toSet()
                    "rejoinAddress" -> s.rejoin = s.rejoin.copy(address = v)
                    "rejoinPort" -> v.toIntOrNull()?.let { s.rejoin = s.rejoin.copy(port = it) }
                    "rejoinCompany" -> v.toIntOrNull()?.let { s.rejoin = s.rejoin.copy(company = it) }
                    "rejoinServer" -> s.rejoin = s.rejoin.copy(server = v)
                }
            }
            if (s.client.isEmpty()) {
                s.client = kotlin.random.Random.nextBytes(12).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                s.save()
            }
            return s
        }
    }
}

/** A game joined: where it is, the company played there (-2, Message.SPECTATE, watching) and the server's name. */
data class LastGame(val address: String, val port: Int, val company: Int, val server: String)
