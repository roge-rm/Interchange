package com.rm.interchange.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.rm.interchange.res.*
import com.rm.interchange.sim.Campaign
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A chapter's title in the game's strings, by its id. */
private fun chapterTitle(id: String): StringResource = when (id) {
    "portage" -> Res.string.chapter_portage
    "trunk" -> Res.string.chapter_trunk
    "last_spike" -> Res.string.chapter_last_spike
    "seaway" -> Res.string.chapter_seaway
    "iron_ore" -> Res.string.chapter_iron_ore
    "high_speed" -> Res.string.chapter_high_speed
    else -> Res.string.chapter_warming_north
}

/** A chapter's intro, null for one still to come. */
private fun chapterIntro(id: String): StringResource? = when (id) {
    "portage" -> Res.string.chapter_portage_intro
    "trunk" -> Res.string.chapter_trunk_intro
    "last_spike" -> Res.string.chapter_last_spike_intro
    "seaway" -> Res.string.chapter_seaway_intro
    "iron_ore" -> Res.string.chapter_iron_ore_intro
    "high_speed" -> Res.string.chapter_high_speed_intro
    "warming_north" -> Res.string.chapter_warming_north_intro
    else -> null
}

/** Whether the chapter at [k] can be played: it's made, and it's the first or the one before it is won. */
fun chapterOpen(k: Int, won: Set<String>): Boolean {
    val ch = Campaign.CHAPTERS.getOrNull(k) ?: return false
    return ch.ready && (k == 0 || Campaign.CHAPTERS[k - 1].id in won)
}

/**
 * The campaign's chapters in order, each with its years. The first is open, and each after it opens once the one
 * before is won. A tap on an open one plays it, in the language the game's in, from its intro.
 */
@Composable
fun CampaignWindow(won: Set<String>, error: String?, onPlay: (id: String, title: String, intro: String) -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val coming = stringResource(Res.string.chapter_coming)
    val locked = stringResource(Res.string.chapter_locked)
    val wonLine = stringResource(Res.string.chapter_won)
    Window(stringResource(Res.string.campaign), onClose, Glyph.Flag) {
        if (error != null) Text(error, color = c.bad, style = KitType.Body)
        for ((k, ch) in Campaign.CHAPTERS.withIndex()) {
            val title = stringResource(chapterTitle(ch.id))
            val intro = chapterIntro(ch.id)?.let { stringResource(it) }
            val open = chapterOpen(k, won) && intro != null
            val state = when {
                !ch.ready -> coming
                ch.id in won -> wonLine
                !open -> locked
                else -> null
            }
            val line = listOfNotNull(stringResource(Res.string.chapter_years, ch.startYear, ch.endYear), state).joinToString(" · ")
            ListRow(
                if (ch.id in won) Glyph.Tick else Glyph.Flag, "${k + 1}. $title", line,
                if (open && intro != null) ({ onPlay(ch.id, title, intro) }) else null,
                tint = if (open) null else c.textDim,
            )
        }
    }
}
