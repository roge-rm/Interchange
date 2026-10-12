package com.rm.interchange.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.interchange.platform.files

/** The player's settings, kept in a small file of name=value lines. */
class Settings {
    /** "" for the phone's own, else "en" or "fr". */
    var language by mutableStateOf("")
    /** Which of [AUTOSAVE_DAYS] autosaves come at. */
    var autosave by mutableIntStateOf(1)
    /** The tile grid and the bands of height drawn over the map. */
    var grid by mutableStateOf(false)
    var heights by mutableStateOf(false)
    /** Snow and ice by depth over the map. */
    var snow by mutableStateOf(false)

    val autosaveDays get() = AUTOSAVE_DAYS[autosave.coerceIn(0, AUTOSAVE_DAYS.size - 1)]

    fun save() {
        files.write(FILE, "language=$language\nautosave=$autosave\ngrid=$grid\nheights=$heights\nsnow=$snow\n".encodeToByteArray())
    }

    companion object {
        private const val FILE = "settings.txt"
        val LANGUAGES = listOf("", "en", "fr")
        /** Off, a month, three months, a year, in days. */
        val AUTOSAVE_DAYS = intArrayOf(0, 30, 91, 365)

        fun load(): Settings {
            val s = Settings()
            val text = files.read(FILE)?.decodeToString() ?: return s
            for (line in text.lines()) {
                val (k, v) = line.split("=", limit = 2).takeIf { it.size == 2 } ?: continue
                when (k) {
                    "language" -> if (v in LANGUAGES) s.language = v
                    "autosave" -> v.toIntOrNull()?.let { s.autosave = it }
                    "grid" -> s.grid = v == "true"
                    "heights" -> s.heights = v == "true"
                    "snow" -> s.snow = v == "true"
                }
            }
            return s
        }
    }
}
