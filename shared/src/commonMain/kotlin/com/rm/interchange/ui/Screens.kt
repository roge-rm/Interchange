package com.rm.interchange.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.interchange.map.mapPicture
import com.rm.interchange.res.*
import com.rm.interchange.sim.BadPack
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Pack
import com.rm.interchange.sim.Packs
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.Roster
import com.rm.interchange.sim.World
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.random.Random

/** A full screen page on the plain background, a map faint behind it, its content in a column that scrolls and keeps clear of the cutout. */
@Composable
private fun Page(backdrop: ImageBitmap? = null, content: @Composable () -> Unit) {
    val c = LocalColors.current
    Box(Modifier.fillMaxSize().background(c.page)) {
        if (backdrop != null) {
            // Soft, so the map's tiles don't show as steps once it's scaled up to fill the screen.
            Image(backdrop, null, Modifier.fillMaxSize().blur(6.dp), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low, alpha = 0.3f)
        }
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

/** A map made only to be looked at behind the title, the same every time. */
@Composable
private fun rememberBackdrop(): ImageBitmap? = produceState<ImageBitmap?>(null) {
    value = withContext(Dispatchers.Default) { mapPicture(World(NewGame(width = 256, height = 256, seed = 1867, hills = 2, trees = 3))) }
}.value

/** The first screen: carry on, start a new game, load one, add packs or change settings. */
@Composable
fun TitleScreen(continueLine: String?, onContinue: () -> Unit, onNew: () -> Unit, onLoad: () -> Unit, onPacks: () -> Unit, onSettings: () -> Unit) {
    val c = LocalColors.current
    Page(rememberBackdrop()) {
        Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(painterResource(Res.drawable.app_icon), null, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
            Text(stringResource(Res.string.app_name), color = c.text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        }
        if (continueLine != null) {
            BigButton(stringResource(Res.string.continue_game), primary = true, glyph = Glyph.Play, onClick = onContinue)
            Text(continueLine, color = c.textDim, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        BigButton(stringResource(Res.string.new_game), primary = continueLine == null, glyph = Glyph.Plus, onClick = onNew)
        BigButton(stringResource(Res.string.load), glyph = Glyph.Folder, onClick = onLoad)
        BigButton(stringResource(Res.string.packs), glyph = Glyph.Package, onClick = onPacks)
        BigButton(stringResource(Res.string.settings), glyph = Glyph.Gear, onClick = onSettings)
    }
}

/** What a saved game's line says: its money, size and region. */
@Composable
fun saveLine(s: SavedGames.Entry): String {
    val regions = stringArrayResource(Res.array.regions)
    return stringResource(Res.string.load_line, money(s.money), s.width, s.height, regions.getOrElse(s.region) { "" })
}

/** Start years to choose from: the first year there's anything, then each decade. */
private val YEARS = listOf(NewGame.FIRST_YEAR) + (1840..NewGame.LAST_YEAR step 10).toList()
private val SEAS = listOf(10, 20, 28, 40, 55)

/** The most tiles a preview is made for: past that, making it would take too long and too much memory. */
private const val PREVIEW_MOST = 2048 * 2048

/**
 * Choosing a new map: its region, size, start year, how much sea, hills and
 * trees, its seed, and which of [packs] are on, with a picture of it.
 */
@Composable
fun NewGameScreen(packs: List<Pack>, onStart: (NewGame, List<Pack>) -> Unit, onBack: () -> Unit) {
    val c = LocalColors.current
    var width by remember { mutableIntStateOf(256) }
    var height by remember { mutableIntStateOf(256) }
    var region by remember { mutableStateOf(Region.MIXED) }
    var year by remember { mutableIntStateOf(1850) }
    var seed by remember { mutableLongStateOf(Random.nextLong(1, 1_000_000)) }
    var sea by remember { mutableIntStateOf(28) }
    var hills by remember { mutableIntStateOf(1) }
    var trees by remember { mutableIntStateOf(2) }
    var towns by remember { mutableIntStateOf(2) }
    var industries by remember { mutableIntStateOf(2) }
    var inflation by remember { mutableStateOf(true) }
    var off by remember { mutableStateOf(emptySet<String>()) }
    val settings = NewGame(width, height, seed, year, region, sea, hills, trees, towns, industries, inflation)
    val chosen = packs.filter { it.id !in off }
    val clash = remember(chosen) {
        try {
            Roster.build(listOf(Packs.BASE) + chosen)
            null
        } catch (e: BadPack) {
            e.problems.first()
        }
    }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(settings) {
        // A moment's wait, so sliding through sizes doesn't make a map for every stop.
        delay(150)
        preview = if (width.toLong() * height > PREVIEW_MOST) null else withContext(Dispatchers.Default) { mapPicture(World(settings)) }
    }
    val newSeed = stringResource(Res.string.new_seed)
    val title: @Composable () -> Unit = { Text(stringResource(Res.string.new_game), color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
    // The picture: a tap on it makes another map.
    val map: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier.clip(RoundedCornerShape(10.dp)).background(c.button)
                .clickable(onClickLabel = newSeed, role = Role.Button) { seed = Random.nextLong(1, 1_000_000) },
            contentAlignment = Alignment.Center,
        ) {
            val p = preview
            if (p != null) Image(p, null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            else if (width.toLong() * height > PREVIEW_MOST) Text(stringResource(Res.string.no_preview), color = c.textDim, fontSize = 13.sp)
        }
    }
    val identity: @Composable () -> Unit = {
        OutlinedTextField(
            seed.toString(), { v -> v.filter { it.isDigit() }.take(12).toLongOrNull()?.let { seed = it } },
            label = { Text(stringResource(Res.string.seed)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = {
                GlyphIcon(
                    Glyph.Renew, c.text,
                    Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = newSeed, role = Role.Button) { seed = Random.nextLong(1, 1_000_000) }
                        .padding(11.dp).semantics { contentDescription = newSeed },
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accent, unfocusedBorderColor = c.chromeEdge,
                focusedLabelColor = c.accent, unfocusedLabelColor = c.textDim, cursorColor = c.accent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val regionNames = stringArrayResource(Res.array.regions)
    val hillNames = stringArrayResource(Res.array.hill_levels)
    val treeNames = stringArrayResource(Res.array.tree_levels)
    val townNames = stringArrayResource(Res.array.town_levels)
    val lang = languageCode()
    val options: @Composable (Boolean) -> Unit = { wide ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.region), color = c.text, fontSize = 15.sp)
            for (row in Region.entries.chunked(if (wide) 6 else 3)) FillChips(row, region, { r -> regionNames.getOrElse(r.ordinal) { r.name } }) { region = it }
        }
        SideBySide(wide, {
            StepSlider(stringResource(Res.string.width), NewGame.SIZES.toList(), width, { "$it" }) { width = it }
        }, {
            StepSlider(stringResource(Res.string.height), NewGame.SIZES.toList(), height, { "$it" }) { height = it }
        })
        SideBySide(wide, {
            StepSlider(stringResource(Res.string.start_year), YEARS, year, { "$it" }) { year = it }
        }, {
            StepSlider(stringResource(Res.string.sea), SEAS, sea, { stringResource(Res.string.sea_percent, it) }) { sea = it }
        })
        SideBySide(wide, {
            StepSlider(stringResource(Res.string.hills), (0..3).toList(), hills, { hillNames.getOrElse(it) { "$it" } }) { hills = it }
        }, {
            StepSlider(stringResource(Res.string.trees), (0..3).toList(), trees, { treeNames.getOrElse(it) { "$it" } }) { trees = it }
        })
        SideBySide(wide, {
            StepSlider(stringResource(Res.string.towns), (0..3).toList(), towns, { townNames.getOrElse(it) { "$it" } }) { towns = it }
        }, {
            StepSlider(stringResource(Res.string.industries), (0..3).toList(), industries, { townNames.getOrElse(it) { "$it" } }) { industries = it }
        })
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TogglePill(stringResource(Res.string.inflation), inflation, Modifier.weight(1f).fillMaxHeight()) { inflation = it }
            Box(Modifier.weight(1f))
        }
        if (packs.isNotEmpty()) {
            Text(stringResource(Res.string.packs), color = c.text, fontSize = 15.sp)
            for (row in packs.chunked(2)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in row) TogglePill(p.name(lang), p.id !in off, Modifier.weight(1f).fillMaxHeight()) { on -> off = if (on) off - p.id else off + p.id }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
            if (clash != null) Text(stringResource(Res.string.packs_clash, clash), color = c.bad, fontSize = 13.sp)
        }
    }
    val footer: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { BigButton(stringResource(Res.string.back), glyph = Glyph.Back, onClick = onBack) }
            Box(Modifier.weight(2f)) {
                BigButton(stringResource(Res.string.start_game), primary = true, enabled = clash == null, glyph = Glyph.Play) { onStart(settings, chosen) }
            }
        }
    }
    NewGameLayout(map, title, identity, options, footer)
}

/** How wide the choices have to be for two to a row, and the widest they get above the map. */
private val PAIRS_FROM = 440.dp
private val CHOICES_MOST = 560.dp

/** On a screen about square: the band across the top at least this tall, and the whole no wider than this. */
private val BAND_LEAST = 150.dp
private val BAND_MOST = 760.dp

/** The widest the map and the choices together get on a wide screen. */
private val SPREAD_MOST = 1100.dp

/** The preview is twice as wide as it's tall, as the map's diamond is. */
private const val PREVIEW_RATIO = 2f

/**
 * The new game screen, laid out for its shape. Wider than it's tall: the
 * picture on the left and the choices beside it. About square: a band across
 * the top with the picture and, beside it, the title and seed, the rest the
 * full width below. Taller than it's wide: the picture above the choices.
 * The choices scroll if they must, and [footer] stays in view below.
 */
@Composable
private fun NewGameLayout(
    map: @Composable (Modifier) -> Unit,
    title: @Composable () -> Unit,
    identity: @Composable () -> Unit,
    options: @Composable (Boolean) -> Unit,
    footer: @Composable () -> Unit,
) {
    val c = LocalColors.current
    @Composable
    fun ColumnScope.choices(withIdentity: Boolean) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= PAIRS_FROM
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (withIdentity) identity()
                options(wide)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.page).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
        val w = maxWidth
        val h = maxHeight
        val ratio = w / h
        when {
            ratio > 1.2f -> Row(
                Modifier.fillMaxHeight().widthIn(max = SPREAD_MOST).fillMaxWidth().align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val across = minOf(w, SPREAD_MOST)
                val pictureWidth = maxOf(across - PAIRS_FROM - 16.dp, across * 0.4f)
                Column(Modifier.width(pictureWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                    title()
                    map(Modifier.fillMaxWidth().aspectRatio(PREVIEW_RATIO))
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    choices(withIdentity = true)
                    footer()
                }
            }
            ratio >= 0.85f -> Column(
                Modifier.fillMaxHeight().widthIn(max = BAND_MOST).fillMaxWidth().align(Alignment.TopCenter),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val band = (h * 0.3f).coerceIn(BAND_LEAST, w * 0.25f)
                Row(Modifier.fillMaxWidth().height(band), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    map(Modifier.fillMaxHeight().aspectRatio(PREVIEW_RATIO))
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                        title()
                        identity()
                    }
                }
                choices(withIdentity = false)
                footer()
            }
            else -> Column(
                Modifier.fillMaxHeight().widthIn(max = CHOICES_MOST).fillMaxWidth().align(Alignment.TopCenter),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                title()
                map(Modifier.fillMaxWidth().aspectRatio(PREVIEW_RATIO))
                choices(withIdentity = true)
                footer()
            }
        }
    }
}

/** The saved games, newest first: tap one to load it. Deleting asks once more. */
@Composable
fun LoadWindow(saves: List<SavedGames.Entry>, error: String?, onLoad: (SavedGames.Entry) -> Unit, onDelete: (SavedGames.Entry) -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    var deleting by remember { mutableStateOf<String?>(null) }
    Window(stringResource(Res.string.load), onClose, Glyph.Folder) {
        if (error != null) Text(error, color = c.bad, fontSize = 14.sp)
        if (saves.isEmpty()) Text(stringResource(Res.string.no_saves), color = c.textDim, fontSize = 14.sp)
        for (s in saves) {
            val auto = s.name == SavedGames.AUTOSAVE
            val day = date(s.day, s.startYear)
            ListRow(if (auto) Glyph.Renew else Glyph.Save, if (auto) stringResource(Res.string.autosave_of, day) else day, saveLine(s), { onLoad(s) }) {
                if (deleting == s.name) ActionButton(stringResource(Res.string.delete), strong = true) { deleting = null; onDelete(s) }
                else SquareButton(Glyph.Remove, stringResource(Res.string.delete), 34.dp) { deleting = s.name }
            }
        }
    }
}

/** The content packs added: what each is, adding more from a file, and taking them off. Removing asks once more. */
@Composable
fun PacksWindow(packs: List<Pack>, adding: Boolean, message: String?, failed: Boolean, onAdd: () -> Unit, onRemove: (Pack) -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val lang = languageCode()
    var removing by remember { mutableStateOf<String?>(null) }
    Window(stringResource(Res.string.packs), onClose, Glyph.Package) {
        BigButton(stringResource(if (adding) Res.string.adding_pack else Res.string.add_pack), primary = true, enabled = !adding, glyph = Glyph.Plus, onClick = onAdd)
        if (message != null) Text(message, color = if (failed) c.bad else c.text, fontSize = 14.sp)
        for (p in listOf(Packs.BASE) + packs) {
            val line = stringResource(Res.string.pack_line, p.version, p.author) + " · " + pluralStringResource(Res.plurals.vehicles_count, p.vehicles.size, p.vehicles.size)
            val about = p.description(lang)
            ListRow(Glyph.Package, p.name(lang), if (about.isEmpty()) line else "$line\n$about", null) {
                if (p.id == Packs.BASE_ID) return@ListRow
                if (removing == p.id) ActionButton(stringResource(Res.string.remove), strong = true) { removing = null; onRemove(p) }
                else SquareButton(Glyph.Remove, stringResource(Res.string.remove), 34.dp) { removing = p.id }
            }
        }
    }
}

/** The player's settings: the language, how often the game saves itself, and what's drawn over the map. */
@Composable
fun SettingsWindow(settings: Settings, onClose: () -> Unit) {
    Window(stringResource(Res.string.settings), onClose, Glyph.Gear) {
        Head(stringResource(Res.string.language))
        // Each language named in itself, so it can be found from any other.
        val languages = listOf("" to stringResource(Res.string.language_phone), "en" to "English", "fr" to "Français")
        FillChips(languages.map { it.first }, settings.language, { code -> languages.first { it.first == code }.second }) { settings.language = it; settings.save() }
        Head(stringResource(Res.string.autosave_every))
        val levels = stringArrayResource(Res.array.autosave_levels)
        FillChips(Settings.AUTOSAVE_DAYS.indices.toList(), settings.autosave, { levels.getOrElse(it) { "$it" } }) { settings.autosave = it; settings.save() }
        Head(stringResource(Res.string.overlays))
        OverlayToggles(settings)
    }
}

/** The grid and heights overlays, on or off. */
@Composable
private fun OverlayToggles(settings: Settings) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TogglePill(stringResource(Res.string.grid), settings.grid, Modifier.weight(1f).fillMaxHeight()) { settings.grid = it; settings.save() }
        TogglePill(stringResource(Res.string.heights), settings.heights, Modifier.weight(1f).fillMaxHeight()) { settings.heights = it; settings.save() }
    }
}

/** What's drawn over the map, from the strip's button. */
@Composable
fun OverlaysWindow(settings: Settings, onClose: () -> Unit) {
    Window(stringResource(Res.string.overlays), onClose, Glyph.Layers) { OverlayToggles(settings) }
}

/** The in-game menu: save, load, start afresh, settings, or back to the title screen. */
@Composable
fun MenuWindow(saved: Boolean, onSave: () -> Unit, onLoad: () -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onIndustries: () -> Unit, onTitle: () -> Unit, onClose: () -> Unit) {
    Window(stringResource(Res.string.menu), onClose, Glyph.Menu) {
        BigButton(stringResource(if (saved) Res.string.saved else Res.string.save), primary = !saved, glyph = Glyph.Save, onClick = onSave)
        BigButton(stringResource(Res.string.load), glyph = Glyph.Folder, onClick = onLoad)
        BigButton(stringResource(Res.string.new_game), glyph = Glyph.Plus, onClick = onNew)
        BigButton(stringResource(Res.string.industries), glyph = Glyph.Layers, onClick = onIndustries)
        BigButton(stringResource(Res.string.settings), glyph = Glyph.Gear, onClick = onSettings)
        BigButton(stringResource(Res.string.title_screen), glyph = Glyph.Home, onClick = onTitle)
    }
}

/** The company's money: its loan, borrowing and repaying, this year's and last year's books and a graph of the last two years. */
@Composable
fun FinanceWindow(runner: com.rm.interchange.server.GameThread, onClose: () -> Unit) {
    val c = LocalColors.current
    // The game's latest figures, picked up each frame.
    val company by produceState(runner.view?.company, runner) {
        while (true) androidx.compose.runtime.withFrameNanos { value = runner.view?.company }
    }
    val co = company ?: return
    Window(stringResource(Res.string.finances), onClose, Glyph.Save) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.money_line, money(co.money)), color = if (co.money < 0) c.bad else c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(Res.string.loan_line, money(co.loan), money(co.maxLoan)), color = c.textDim, fontSize = 13.sp)
            }
            ActionButton(stringResource(Res.string.borrow), enabled = co.loan < co.maxLoan) { runner.request(com.rm.interchange.sim.Command.Loan(0, borrow = true)) }
            ActionButton(stringResource(Res.string.repay), enabled = co.loan > 0 && co.money >= com.rm.interchange.sim.Prices.LOAN_STEP) { runner.request(com.rm.interchange.sim.Command.Loan(0, borrow = false)) }
        }
        Head(stringResource(Res.string.the_books))
        val rows = listOf(
            Res.string.book_income to com.rm.interchange.sim.Books.INCOME,
            Res.string.book_construction to com.rm.interchange.sim.Books.CONSTRUCTION,
            Res.string.book_new_trains to com.rm.interchange.sim.Books.NEW_TRAINS,
            Res.string.book_running to com.rm.interchange.sim.Books.RUNNING,
            Res.string.book_interest to com.rm.interchange.sim.Books.INTEREST,
            Res.string.book_sold to com.rm.interchange.sim.Books.TRAINS_SOLD,
        )
        @Composable
        fun line(label: String, a: String, b: String, strong: Boolean = false, colour: androidx.compose.ui.graphics.Color = c.text) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f), fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal)
                Text(a, color = colour, fontSize = 13.sp, modifier = Modifier.width(96.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal)
                Text(b, color = c.textDim, fontSize = 13.sp, modifier = Modifier.width(96.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        line("", stringResource(Res.string.this_year), stringResource(Res.string.last_year), colour = c.textDim)
        for ((label, k) in rows) line(stringResource(label), money(co.thisYear[k]), money(co.lastYear[k]))
        val total = co.thisYear.sum()
        line(stringResource(Res.string.book_total), money(total), money(co.lastYear.sum()), strong = true, colour = if (total < 0) c.bad else c.good)
        if (co.history.size >= 2) {
            Head(stringResource(Res.string.money_graph))
            MoneyGraph(co.history, Modifier.fillMaxWidth().height(120.dp))
        }
    }
}

/** Money at the end of each month as a line, and each month's profit or loss as a bar under it. */
@Composable
private fun MoneyGraph(history: List<LongArray>, modifier: Modifier) {
    val c = LocalColors.current
    androidx.compose.foundation.Canvas(modifier) {
        val n = history.size
        val most = history.maxOf { it[0] }.coerceAtLeast(1)
        val least = minOf(0L, history.minOf { it[0] })
        val span = (most - least).toFloat()
        val step = size.width / (n - 1).coerceAtLeast(1)
        fun y(v: Long) = size.height * (1f - (v - least) / span)
        val zero = y(0)
        drawLine(c.chromeEdge, androidx.compose.ui.geometry.Offset(0f, zero), androidx.compose.ui.geometry.Offset(size.width, zero), 1.dp.toPx())
        val bars = history.maxOf { kotlin.math.abs(it[1] - it[2]) }.coerceAtLeast(1)
        for ((k, h) in history.withIndex()) {
            val p = h[1] - h[2]
            val bh = size.height * 0.3f * kotlin.math.abs(p) / bars
            val x = k * step
            drawRect(if (p >= 0) c.good.copy(alpha = 0.5f) else c.bad.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(x - step * 0.3f, if (p >= 0) zero - bh else zero), androidx.compose.ui.geometry.Size(step * 0.6f, bh))
        }
        val path = androidx.compose.ui.graphics.Path()
        for ((k, h) in history.withIndex()) if (k == 0) path.moveTo(0f, y(h[0])) else path.lineTo(k * step, y(h[0]))
        drawPath(path, c.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

/** Every industry open on the map, by kind then name, with what it made last month; a tap goes to it. */
@Composable
fun IndustriesWindow(world: World, onGo: (Float, Float) -> Unit, onClose: () -> Unit) {
    val lang = languageCode()
    val names = stringArrayResource(Res.array.cargoes)
    Window(stringResource(Res.string.industries), onClose, Glyph.Layers) {
        val open = world.industries.filter { !it.closed }.sortedWith(compareBy({ it.kind }, { industryName(world, it, lang) }))
        for (ind in open) {
            val kind = world.roster.industries[ind.kind]
            val made = (0 until com.rm.interchange.sim.Cargo.COUNT).filter { kind.outputs and (1 shl it) != 0 }
                .joinToString(" · ") { "${ind.madeLastMonth[it]} ${names.getOrElse(it) { "" }.lowercase()}" }
            ListRow(Glyph.Layers, industryName(world, ind, lang), made, { onGo(ind.x + 1f, ind.y + 1f) })
        }
    }
}
