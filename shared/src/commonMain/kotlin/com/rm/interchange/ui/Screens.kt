package com.rm.interchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.interchange.res.*
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** A screen of its own over the page colour, its content in a column no wider than a phone, scrolling if it has to. */
@Composable
private fun Page(title: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    val c = LocalColors.current
    Box(Modifier.fillMaxSize().background(c.page).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (onBack != null) ActionButton(stringResource(Res.string.back), onClick = onBack)
            }
            content()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = LocalColors.current.textDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

/** The title screen: carry on, start afresh, load or change settings. */
@Composable
fun TitleScreen(canContinue: Boolean, onContinue: () -> Unit, onNew: () -> Unit, onLoad: () -> Unit, onSettings: () -> Unit) {
    Page(stringResource(Res.string.app_name), null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canContinue) ActionButton(stringResource(Res.string.continue_game), strong = true, onClick = onContinue)
            ActionButton(stringResource(Res.string.new_game), strong = !canContinue, onClick = onNew)
            ActionButton(stringResource(Res.string.load), onClick = onLoad)
            ActionButton(stringResource(Res.string.settings), onClick = onSettings)
        }
    }
}

/** Choosing a new map: its size, region, start year, seed, and how much sea, hills and trees. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewGameScreen(onStart: (NewGame) -> Unit, onBack: () -> Unit) {
    val c = LocalColors.current
    var width by remember { mutableIntStateOf(256) }
    var height by remember { mutableIntStateOf(256) }
    var region by remember { mutableStateOf(Region.MIXED) }
    var year by remember { mutableIntStateOf(1850) }
    var seed by remember { mutableLongStateOf(kotlin.random.Random.nextLong(1, 1_000_000)) }
    var sea by remember { mutableIntStateOf(28) }
    var hills by remember { mutableIntStateOf(1) }
    var trees by remember { mutableIntStateOf(2) }
    val gap = Arrangement.spacedBy(6.dp)
    Page(stringResource(Res.string.new_game), onBack) {
        Label(stringResource(Res.string.region))
        val regions = stringArrayResource(Res.array.regions)
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            for (r in Region.entries) Chip(regions.getOrElse(r.ordinal) { r.name }, r == region) { region = r }
        }
        Label(stringResource(Res.string.map_size))
        for ((name, value, set) in listOf(Triple(Res.string.width, width, { v: Int -> width = v }), Triple(Res.string.height, height, { v: Int -> height = v }))) {
            Text(stringResource(name), color = c.text, fontSize = 13.sp)
            FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
                for (size in NewGame.SIZES) Chip("$size", size == value) { set(size) }
            }
        }
        Label(stringResource(Res.string.start_year))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(stringResource(Res.string.earlier), enabled = year > NewGame.FIRST_YEAR) { year = maxOf(NewGame.FIRST_YEAR, year - 10) }
            Text("$year", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            ActionButton(stringResource(Res.string.later), enabled = year < NewGame.LAST_YEAR) { year = minOf(NewGame.LAST_YEAR, year + 10) }
        }
        Label(stringResource(Res.string.sea))
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            for (p in intArrayOf(10, 20, 28, 40, 55)) Chip(stringResource(Res.string.sea_percent, p), p == sea) { sea = p }
        }
        Label(stringResource(Res.string.hills))
        val hillNames = stringArrayResource(Res.array.hill_levels)
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            for (h in 0..3) Chip(hillNames.getOrElse(h) { "$h" }, h == hills) { hills = h }
        }
        Label(stringResource(Res.string.trees))
        val treeNames = stringArrayResource(Res.array.tree_levels)
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            for (t in 0..3) Chip(treeNames.getOrElse(t) { "$t" }, t == trees) { trees = t }
        }
        Label(stringResource(Res.string.seed))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$seed", color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            ActionButton(stringResource(Res.string.new_seed)) { seed = kotlin.random.Random.nextLong(1, 1_000_000) }
        }
        ActionButton(stringResource(Res.string.start_game), strong = true) {
            onStart(NewGame(width, height, seed, year, region, sea, hills, trees))
        }
    }
}

/** The saved games, newest first: tap one to load it. */
@Composable
fun LoadScreen(saves: List<SavedGames.Entry>, error: String?, onLoad: (SavedGames.Entry) -> Unit, onDelete: (SavedGames.Entry) -> Unit, onBack: () -> Unit) {
    val c = LocalColors.current
    val regions = stringArrayResource(Res.array.regions)
    Page(stringResource(Res.string.load), onBack) {
        if (error != null) Text(error, color = c.bad, fontSize = 14.sp)
        if (saves.isEmpty()) Text(stringResource(Res.string.no_saves), color = c.textDim, fontSize = 14.sp)
        for (s in saves) {
            ChromeBox(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        if (s.name == SavedGames.AUTOSAVE) Text(stringResource(Res.string.autosave), color = c.accent, fontSize = 12.sp)
                        Text(
                            stringResource(Res.string.save_line, date(s.day, s.startYear), money(s.money), s.width, s.height, regions.getOrElse(s.region) { "" }),
                            color = c.text, fontSize = 14.sp,
                        )
                    }
                    ActionButton(stringResource(Res.string.load), strong = true) { onLoad(s) }
                    ActionButton(stringResource(Res.string.delete)) { onDelete(s) }
                }
            }
        }
    }
}

/** The player's settings: the language, and how often the game saves itself. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(settings: Settings, onBack: () -> Unit) {
    val gap = Arrangement.spacedBy(6.dp)
    Page(stringResource(Res.string.settings), onBack) {
        Label(stringResource(Res.string.language))
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            // Each language named in itself, so it can be found from any other.
            for ((code, name) in listOf("" to stringResource(Res.string.language_phone), "en" to "English", "fr" to "Français")) {
                Chip(name, settings.language == code) { settings.language = code; settings.save() }
            }
        }
        Label(stringResource(Res.string.autosave_every))
        val levels = stringArrayResource(Res.array.autosave_levels)
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            for (i in Settings.AUTOSAVE_DAYS.indices) Chip(levels.getOrElse(i) { "$i" }, settings.autosave == i) { settings.autosave = i; settings.save() }
        }
    }
}

/** The in-game menu: save, load, start afresh, settings, or back to the title screen. */
@Composable
fun MenuCard(saved: Boolean, onSave: () -> Unit, onLoad: () -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onTitle: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Card(stringResource(Res.string.menu), onClose, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(stringResource(if (saved) Res.string.saved else Res.string.save), strong = !saved, onClick = onSave)
            ActionButton(stringResource(Res.string.load), onClick = onLoad)
            ActionButton(stringResource(Res.string.new_game), onClick = onNew)
            ActionButton(stringResource(Res.string.settings), onClick = onSettings)
            ActionButton(stringResource(Res.string.title_screen), onClick = onTitle)
        }
    }
}
