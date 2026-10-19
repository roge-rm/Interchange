package com.rm.interchange.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.interchange.map.LandPainter
import com.rm.interchange.platform.bundled
import com.rm.interchange.platform.files
import com.rm.interchange.res.*
import com.rm.interchange.server.GameThread
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Cargo
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Goal
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.Scenario
import com.rm.interchange.sim.Scenarios
import com.rm.interchange.sim.World
import com.rm.interchange.sim.industryAt
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * Scenario files: the player's own, NAME.ichscn in the game's files, and the built-in ones in the app's scenarios
 * folder. The list reads each one's title, intro and start year from the front of the file.
 *
 * The world being edited is also kept on its own, as a scenario file, so editing can carry on if the app's closed.
 */
object SavedScenarios {
    private const val FOLDER = "scenarios"
    private const val EDITOR_AUTOSAVE = "editor-autosave.edit"

    class Entry(val name: String, val title: String, val intro: String, val startYear: Int, val own: Boolean)

    /** The player's own, newest first, then the built-in ones by title. */
    fun list(): List<Entry> {
        val own = files.list("").filter { it.endsWith(".${Scenarios.EXTENSION}") }.map { f ->
            val name = f.removeSuffix(".${Scenarios.EXTENSION}")
            entry(name, files.read(f), own = true)
        }
        val builtIn = bundled.list(FOLDER).filter { it.endsWith(".${Scenarios.EXTENSION}") }.map { f ->
            entry(f.removeSuffix(".${Scenarios.EXTENSION}"), bundled.read("$FOLDER/$f"), own = false)
        }.sortedBy { it.title.lowercase() }
        return own + builtIn
    }

    private fun entry(name: String, data: ByteArray?, own: Boolean): Entry {
        val h = data?.let(Save::heading)
        return Entry(name, h?.title.orEmpty().ifBlank { name }, h?.intro.orEmpty(), h?.startYear ?: 0, own)
    }

    fun read(e: Entry): ByteArray? = if (e.own) files.read("${e.name}.${Scenarios.EXTENSION}") else bundled.read("$FOLDER/${e.name}.${Scenarios.EXTENSION}")

    fun exists(name: String) = files.modified("$name.${Scenarios.EXTENSION}") > 0

    /** Writes the editor world in [runner] as scenario [name]; [done] is called once it's written, off the main thread. */
    fun save(runner: GameThread, name: String, done: () -> Unit) {
        runner.onGameThread { g ->
            files.write("$name.${Scenarios.EXTENSION}", Scenarios.export(g.world))
            // Saved on purpose, the editor's own autosave isn't needed until it changes again.
            synchronized(lock) {
                files.delete(EDITOR_AUTOSAVE)
                editorSum = g.world.checksum()
            }
            done()
        }
    }

    fun delete(name: String) = files.delete("$name.${Scenarios.EXTENSION}")

    /** A file name from a title: letters, digits, spaces, dashes and underscores, no longer than a title. */
    fun fileName(text: String): String =
        text.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim().take(Scenario.TITLE_MOST)

    // The editor's autosave is written on the game thread and cleared on the main one, so both hold the lock; once
    // cleared, nothing more is written until [keepEditor].
    private val lock = Any()
    private var keeping = false
    private var editorSum = 0L

    /** Whether there's a world being edited to carry on with. */
    fun editorKept() = files.modified(EDITOR_AUTOSAVE) > 0

    fun readEditor(): ByteArray? = files.read(EDITOR_AUTOSAVE)

    /** Starts keeping the world being edited, from the next [autosaveEditor]. */
    fun keepEditor() = synchronized(lock) { keeping = true; editorSum = 0L }

    /** On the game thread: writes [world], being edited, unless it's the same as when last written. */
    fun autosaveEditor(world: World) = synchronized(lock) {
        if (!keeping || !world.editor) return@synchronized
        val sum = world.checksum()
        if (sum == editorSum) return@synchronized
        files.write(EDITOR_AUTOSAVE, Scenarios.export(world))
        editorSum = sum
    }

    /** Writes [bytes], the world being edited as a scenario file, as it's left to try it. */
    fun writeEditor(bytes: ByteArray) = synchronized(lock) {
        if (keeping) files.write(EDITOR_AUTOSAVE, bytes)
    }

    /** The editor left on purpose: its autosave goes and nothing more is written. */
    fun clearEditor() = synchronized(lock) {
        keeping = false
        files.delete(EDITOR_AUTOSAVE)
    }
}

/**
 * The scenarios to play: the one being edited to carry on with if there is one, the campaign, a new one to make in the editor, then each with its start year. A tap shows its intro, with
 * Play, and for the player's own Edit and Delete. Deleting asks once more.
 */
@Composable
fun ScenariosWindow(
    list: List<SavedScenarios.Entry>,
    error: String?,
    onContinue: (() -> Unit)?,
    onCampaign: () -> Unit,
    onNew: () -> Unit,
    onPlay: (SavedScenarios.Entry) -> Unit,
    onEdit: (SavedScenarios.Entry) -> Unit,
    onDelete: (SavedScenarios.Entry) -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalColors.current
    var open by remember { mutableStateOf<SavedScenarios.Entry?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val shown = open
    if (shown != null) {
        Window(shown.title, { open = null; deleting = false }, Glyph.Flag) {
            if (error != null) Text(error, color = c.bad, style = KitType.Body)
            if (shown.startYear > 0) Text(stringResource(Res.string.scenario_line, shown.startYear), color = c.textDim, style = KitType.ListLine)
            Paragraphs(shown.intro)
            BigButton(stringResource(Res.string.play), primary = true, glyph = Glyph.Play) { onPlay(shown) }
            if (shown.own) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { BigButton(stringResource(Res.string.edit), glyph = Glyph.Land) { onEdit(shown) } }
                Box(Modifier.weight(1f)) {
                    BigButton(stringResource(Res.string.delete), primary = deleting, glyph = Glyph.Remove) {
                        if (deleting) { deleting = false; open = null; onDelete(shown) } else deleting = true
                    }
                }
            }
        }
        return
    }
    Window(stringResource(Res.string.scenarios), onClose, Glyph.Flag) {
        if (error != null) Text(error, color = c.bad, style = KitType.Body)
        onContinue?.let { BigButton(stringResource(Res.string.continue_editing), primary = true, glyph = Glyph.Land, onClick = it) }
        BigButton(stringResource(Res.string.campaign), primary = onContinue == null, glyph = Glyph.Flag, onClick = onCampaign)
        BigButton(stringResource(Res.string.new_scenario), glyph = Glyph.Plus, onClick = onNew)
        if (list.isEmpty()) Text(stringResource(Res.string.no_scenarios), color = c.textDim, style = KitType.Body)
        val builtIn = stringResource(Res.string.scenario_built_in)
        for (e in list) {
            val year = if (e.startYear > 0) stringResource(Res.string.scenario_line, e.startYear) else null
            val line = listOfNotNull(year, if (e.own) null else builtIn).joinToString(" · ").ifEmpty { null }
            ListRow(if (e.own) Glyph.Flag else Glyph.Package, e.title, line, { open = e })
        }
    }
}

/** Text in paragraphs split by blank lines. */
@Composable
private fun Paragraphs(text: String) {
    val c = LocalColors.current
    for (p in text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }) Text(p, color = c.text, style = KitType.Label)
}

/** The menu in the scenario editor: the scenario, its goals, saving it, trying it, settings and the title screen, which asks once more. */
@Composable
fun EditorMenuWindow(saved: Boolean, onMap: () -> Unit, onScenario: () -> Unit, onGoals: () -> Unit, onSave: () -> Unit, onTest: () -> Unit, onSettings: () -> Unit, onTitle: () -> Unit, onClose: () -> Unit) {
    var leaving by remember { mutableStateOf(false) }
    // Each entry's words, glyph, whether it's the main one, and what it does, in two columns as the game's menu has.
    class Entry(val text: String, val glyph: Glyph, val primary: Boolean = false, val onClick: () -> Unit)
    val entries = listOf(
        Entry(stringResource(Res.string.overview_map), Glyph.Map, onClick = onMap),
        Entry(stringResource(Res.string.scenario), Glyph.Newspaper, onClick = onScenario),
        Entry(stringResource(Res.string.goals), Glyph.Flag, onClick = onGoals),
        Entry(stringResource(if (saved) Res.string.saved else Res.string.save_scenario), Glyph.Save, !saved, onSave),
        Entry(stringResource(Res.string.test_play), Glyph.Play, onClick = onTest),
        Entry(stringResource(Res.string.settings), Glyph.Gear, onClick = onSettings),
        Entry(stringResource(if (leaving && !saved) Res.string.leave_unsaved else Res.string.title_screen), Glyph.Exit, leaving && !saved) {
            if (saved || leaving) onTitle() else leaving = true
        },
    )
    Window(stringResource(Res.string.editor), onClose, Glyph.Menu) {
        Grid(entries) { e -> BigButton(e.text, e.primary, glyph = e.glyph, modifier = Modifier.fillMaxHeight(), onClick = e.onClick) }
    }
}

/** A text box in the game's colours over a few lines. */
@Composable
private fun LongField(value: String, label: String, onChange: (String) -> Unit) {
    val c = LocalColors.current
    OutlinedTextField(
        value, onChange, label = { Text(label) }, minLines = 4, maxLines = 10,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accent, unfocusedBorderColor = c.chromeEdge,
            focusedLabelColor = c.accent, unfocusedLabelColor = c.textDim, cursorColor = c.accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Only the digits of [text], at most [most] of them. */
private fun digits(text: String, most: Int = 13) = text.filter { it.isDigit() }.take(most)

/** The scenario's title, intro, money, loan, how it's won and the year it starts; set when closed or done. */
@Composable
fun ScenarioWindow(world: World, send: (Command) -> Unit, onClose: () -> Unit) {
    val me = LocalCompany.current
    val s = world.scenario ?: Scenario()
    var title by remember { mutableStateOf(s.title) }
    var intro by remember { mutableStateOf(s.intro) }
    var moneyText by remember { mutableStateOf(s.money.toString()) }
    var loanText by remember { mutableStateOf(s.loan.toString()) }
    var win by remember { mutableIntStateOf(s.win) }
    var year by remember { mutableIntStateOf(world.settings.startYear) }
    fun set() {
        send(Command.SetScenario(me, title, intro, moneyText.toLongOrNull() ?: s.money, loanText.toLongOrNull() ?: 0L, win, year, s.chapter))
        onClose()
    }
    Window(stringResource(Res.string.scenario), ::set, Glyph.Newspaper) {
        Field(title, stringResource(Res.string.scenario_title), { title = it.take(Scenario.TITLE_MOST) })
        LongField(intro, stringResource(Res.string.scenario_intro)) { intro = it.take(Scenario.INTRO_MOST) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Field(moneyText, stringResource(Res.string.start_money), { moneyText = digits(it) }, keyboard = KeyboardType.Number) }
            Box(Modifier.weight(1f)) { Field(loanText, stringResource(Res.string.start_loan), { loanText = digits(it) }, keyboard = KeyboardType.Number) }
        }
        Head(stringResource(Res.string.win_rule))
        val rules = stringArrayResource(Res.array.win_rules)
        FillChips(listOf(Scenario.ALL, Scenario.ANY), win, { rules.getOrElse(it) { "" } }) { win = it }
        StepSlider(stringResource(Res.string.start_year), START_YEARS, year.coerceIn(START_YEARS.first(), START_YEARS.last()).let { y -> START_YEARS.minBy { kotlin.math.abs(it - y) } }, { "$it" }) { year = it }
        BigButton(stringResource(Res.string.done), primary = true, glyph = Glyph.Tick, onClick = ::set)
    }
}

/** Years a scenario can start in, as on the new game screen. */
private val START_YEARS = listOf(NewGame.FIRST_YEAR) + (1840..NewGame.LAST_YEAR step 10).toList()

/** A goal being made in the editor, kept while the map's tapped to pick its town or industry. */
class GoalDraft {
    var kind by mutableIntStateOf(Goal.TOWN_POP)
    var town by mutableIntStateOf(-1)
    var town2 by mutableIntStateOf(-1)
    var industry by mutableIntStateOf(-1)
    var cargo by mutableIntStateOf(Cargo.PASSENGERS)
    var amount by mutableStateOf("")
    var deadline by mutableStateOf("")
}

/** Which of a draft goal's places a tap on the map picks: its town, its second town, or a town or an industry. */
enum class GoalSlot { Town, Town2, Place }

/** The town round tile [i]: the one it's in, or the nearest middle within a few tiles; -1 for none. */
fun townNear(world: World, i: Int): Int {
    world.townOf[i].toInt().takeIf { it >= 0 && it < world.towns.size }?.let { return it }
    val x = i % world.width
    val y = i / world.width
    return townsOf(world).filter { maxOf(kotlin.math.abs(it.x - x), kotlin.math.abs(it.y - y)) <= TOWN_REACH }
        .minByOrNull { maxOf(kotlin.math.abs(it.x - x), kotlin.math.abs(it.y - y)) }?.id ?: -1
}

/** The towns and industries as they are now. The game thread may add to them, so they're copied by index up to the size read once. */
fun townsOf(world: World) = List(world.towns.size) { world.towns[it] }
fun industriesOf(world: World) = List(world.industries.size) { world.industries[it] }

/** Puts what's at tile [i] into [draft]'s [slot]; false if there's nothing there it can take. */
fun pickInto(world: World, draft: GoalDraft, slot: GoalSlot, i: Int): Boolean {
    if (slot == GoalSlot.Place) {
        // Only an industry that takes some cargo; a tap on one that takes none falls back to the town nearby.
        world.industryAt(i)?.takeIf { !it.closed }?.let { ind ->
            val kind = world.roster.industries[ind.kind]
            val takes = (0 until Cargo.COUNT).firstOrNull { kind.takes(it) }
            if (takes != null) {
                draft.industry = ind.id
                draft.town = -1
                if (!kind.takes(draft.cargo)) draft.cargo = takes
                return true
            }
        }
    }
    val t = townNear(world, i)
    if (t < 0) return false
    when (slot) {
        GoalSlot.Town2 -> draft.town2 = t
        else -> { draft.town = t; draft.industry = -1 }
    }
    return true
}

/** How far round a tap reaches for a town's middle, in tiles. */
private const val TOWN_REACH = 6

/** A copy of [g] asking the same, for sending back with the goals. */
private fun asked(g: Goal) = Goal(g.kind, g.amount, g.deadline, g.cargo, g.town, g.town2, g.industry, g.from)

/**
 * The scenario's goals in the editor, each with a delete button, and a goal being added: its kind, its town or
 * industry picked on the map or from a list, the amount, the cargo if it needs one, and the year it's due by.
 */
@Composable
fun EditorGoalsWindow(world: World, draft: GoalDraft?, onDraft: (GoalDraft?) -> Unit, onPickOnMap: (GoalSlot) -> Unit, send: (Command) -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val me = LocalCompany.current
    // The goals are changed on the game thread, so this looks again a few times a second.
    val beat by produceState(0) { while (true) { delay(250); value++ } }
    val goals = remember(beat) { List(world.goals.size) { world.goals.getOrNull(it) }.filterNotNull() }
    val start = world.settings.startYear
    if (draft == null) {
        Window(stringResource(Res.string.goals), onClose, Glyph.Flag) {
            BigButton(stringResource(Res.string.add_goal), primary = true, glyph = Glyph.Plus) {
                onDraft(GoalDraft().also { it.deadline = (start + 10).toString(); it.amount = defaultAmount(world, it).toString() })
            }
            if (goals.isEmpty()) Text(stringResource(Res.string.no_goals_yet), color = c.textDim, style = KitType.Body)
            for ((k, g) in goals.withIndex()) {
                ListRow(goalGlyph(g.kind), goalText(world, g), null, null) {
                    SquareButton(Glyph.Remove, stringResource(Res.string.delete), 34.dp) {
                        send(Command.SetGoals(me, goals.filterIndexed { j, _ -> j != k }.map(::asked)))
                    }
                }
            }
        }
        return
    }
    val kinds = stringArrayResource(Res.array.goal_kinds)
    val cargoes = stringArrayResource(Res.array.cargoes)
    val lang = languageCode()
    var listing by remember { mutableStateOf<GoalSlot?>(null) }
    Window(stringResource(Res.string.add_goal), { onDraft(null) }, Glyph.Plus) {
        Head(stringResource(Res.string.goal_kind))
        OptionGroup((0 until Goal.KINDS).toList(), draft.kind, ::goalGlyph, { kinds.getOrElse(it) { "" } }, perRow = 3) {
            draft.kind = it
            draft.amount = defaultAmount(world, draft).toString()
            listing = null
        }
        val slots = when (draft.kind) {
            Goal.TOWN_POP -> listOf(GoalSlot.Town)
            Goal.DELIVER -> listOf(GoalSlot.Place)
            Goal.CONNECT -> listOf(GoalSlot.Town, GoalSlot.Town2)
            else -> emptyList()
        }
        for (slot in slots) {
            val name = when (slot) {
                GoalSlot.Town2 -> world.towns.getOrNull(draft.town2)?.name
                GoalSlot.Place -> world.industries.getOrNull(draft.industry)?.let { industryName(world, it, lang) } ?: world.towns.getOrNull(draft.town)?.name
                GoalSlot.Town -> world.towns.getOrNull(draft.town)?.name
            }
            val chosenIndustry = slot == GoalSlot.Place && draft.industry >= 0 && name != null
            Head(stringResource(when (slot) { GoalSlot.Town -> Res.string.goal_target_town; GoalSlot.Town2 -> Res.string.goal_target_town2; GoalSlot.Place -> Res.string.goal_target_place }))
            ActionRow(if (chosenIndustry) Glyph.Factory else Glyph.Home, name ?: stringResource(Res.string.none_chosen), dim = name == null)
            ButtonTiles(listOf(
                TileButton(Glyph.Inspect, stringResource(Res.string.on_the_map), strong = true) { onPickOnMap(slot) },
                TileButton(Glyph.Menu, stringResource(Res.string.from_a_list), lit = listing == slot) { listing = if (listing == slot) null else slot },
            ))
            if (listing == slot) {
                for (t in townsOf(world).sortedBy { it.name }) PictureRow(t.name, icon = { Badge(Glyph.Home) }, onClick = {
                    if (slot == GoalSlot.Town2) draft.town2 = t.id else { draft.town = t.id; draft.industry = -1 }
                    listing = null
                })
                if (slot == GoalSlot.Place) for (ind in industriesOf(world).filter { !it.closed && world.roster.industries[it.kind].accepts != 0 }.sortedBy { industryName(world, it, lang) }) {
                    PictureRow(industryName(world, ind, lang), picture = { IndustryPicture(world.roster.industries[ind.kind], it) }, onClick = {
                        pickInto(world, draft, GoalSlot.Place, world.index(ind.x, ind.y))
                        listing = null
                    })
                }
            }
        }
        if (draft.kind == Goal.DELIVER) {
            Head(stringResource(Res.string.cargo))
            val takes = world.industries.getOrNull(draft.industry)?.let { world.roster.industries[it.kind] }
            val offered = (0 until Cargo.COUNT).filter { takes == null || takes.takes(it) }
            if (offered.isNotEmpty()) SegmentedChoice(offered, draft.cargo, { cargoes.getOrElse(it) { "" } }, perRow = 3) { draft.cargo = it }
        }
        val amountLabel = when (draft.kind) {
            Goal.TOWN_POP -> Res.string.amount_people
            Goal.CONNECT -> Res.string.amount_passengers
            Goal.DELIVER -> Res.string.amount_units
            else -> Res.string.amount_dollars
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Field(draft.amount, stringResource(amountLabel), { draft.amount = digits(it) }, keyboard = KeyboardType.Number) }
            Box(Modifier.weight(1f)) { Field(draft.deadline, stringResource(Res.string.deadline_year), { draft.deadline = digits(it, 4) }, keyboard = KeyboardType.Number) }
        }
        val goal = draftGoal(draft, start)
        BigButton(stringResource(Res.string.add_goal), primary = true, enabled = goal != null && goals.size < GOALS_MOST, glyph = Glyph.Tick) {
            if (goal != null) send(Command.SetGoals(me, goals.map(::asked) + goal))
            onDraft(null)
        }
    }
}

/** The goal [d] makes, or null if something it needs is missing or out of range. */
private fun draftGoal(d: GoalDraft, start: Int): Goal? {
    val amount = d.amount.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val deadline = d.deadline.toIntOrNull()?.takeIf { it in start..NewGame.LAST_YEAR + 100 } ?: return null
    return when (d.kind) {
        Goal.TOWN_POP -> if (d.town < 0) null else Goal(Goal.TOWN_POP, amount, deadline, town = d.town)
        Goal.DELIVER -> if (d.town < 0 && d.industry < 0) null
            else Goal(Goal.DELIVER, amount, deadline, cargo = d.cargo, town = if (d.industry >= 0) -1 else d.town, industry = d.industry, from = start)
        Goal.CONNECT -> if (d.town < 0 || d.town2 < 0 || d.town == d.town2) null else Goal(Goal.CONNECT, amount, deadline, town = d.town, town2 = d.town2)
        else -> Goal(d.kind, amount, deadline)
    }
}

/** A starting amount for a goal of [d]'s kind: a town twice its size, or a round figure. */
private fun defaultAmount(world: World, d: GoalDraft): Long = when (d.kind) {
    Goal.TOWN_POP -> world.towns.getOrNull(d.town)?.let { (it.population * 2L / 100 + 1) * 100 } ?: 2000L
    Goal.DELIVER -> 2000L
    Goal.CONNECT -> 1000L
    Goal.COMPANY_VALUE -> 1_000_000L
    else -> 200_000L
}

/** The most goals a scenario can have, as the game takes them. */
private const val GOALS_MOST = com.rm.interchange.sim.Codec.MOST_GOALS

/** The glyph for goals of [kind]. */
fun goalGlyph(kind: Int) = when (kind) {
    Goal.TOWN_POP -> Glyph.Home
    Goal.DELIVER -> Glyph.Package
    Goal.CONNECT -> Glyph.Station
    Goal.COMPANY_VALUE -> Glyph.Briefcase
    Goal.YEAR_PROFIT -> Glyph.Graph
    else -> Glyph.Flag
}

/** Saving the scenario: its file name, from the title to begin with. */
@Composable
fun SaveScenarioWindow(world: World, onSave: (String) -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val untitled = stringResource(Res.string.untitled)
    var name by remember { mutableStateOf(SavedScenarios.fileName(world.scenario?.title.orEmpty()).ifEmpty { SavedScenarios.fileName(untitled) }) }
    val clean = SavedScenarios.fileName(name)
    Window(stringResource(Res.string.save_scenario), onClose, Glyph.Save) {
        Field(name, stringResource(Res.string.file_name), { name = it.take(Scenario.TITLE_MOST) })
        if (clean.isNotEmpty() && SavedScenarios.exists(clean)) Text(stringResource(Res.string.file_exists), color = c.warn, style = KitType.ListLine)
        BigButton(stringResource(Res.string.save_scenario), primary = true, enabled = clean.isNotEmpty(), glyph = Glyph.Save) { onSave(clean) }
    }
}

/** A scenario's start: its title, intro, goals and how it's won, and Start. */
@Composable
fun IntroWindow(world: World, onStart: () -> Unit) {
    val c = LocalColors.current
    val s = world.scenario ?: return
    val rules = stringArrayResource(Res.array.win_rules)
    Window(s.title.ifBlank { stringResource(Res.string.untitled) }, onStart, Glyph.Flag) {
        Text(date(world.day, world.startYear), color = c.textDim, style = KitType.ListLine)
        Paragraphs(s.intro)
        if (world.goals.isNotEmpty()) {
            Head(stringResource(Res.string.goals), Glyph.Flag)
            for (g in world.goals) ListRow(goalGlyph(g.kind), goalText(world, g), null, null)
            if (world.goals.size > 1) Text(rules.getOrElse(s.win) { "" }, color = c.textDim, style = KitType.ListLine)
        }
        BigButton(stringResource(Res.string.start), primary = true, glyph = Glyph.Play, onClick = onStart)
    }
}

/** The scenario won or lost: keep playing, or go back to the title screen. */
@Composable
fun OutcomeWindow(world: World, onKeepPlaying: () -> Unit, onTitle: () -> Unit) {
    val c = LocalColors.current
    val s = world.scenario ?: return
    val won = s.outcome == Scenario.WON
    Window(stringResource(if (won) Res.string.scenario_won else Res.string.scenario_lost), onKeepPlaying, if (won) Glyph.Tick else Glyph.Flag) {
        if (s.title.isNotBlank()) Text(s.title, color = c.text, style = KitType.Lead, fontWeight = FontWeight.SemiBold)
        Text(stringResource(if (won) Res.string.scenario_won_line else Res.string.scenario_lost_line), color = c.text, style = KitType.Label)
        BigButton(stringResource(Res.string.keep_playing), primary = true, glyph = Glyph.Play, onClick = onKeepPlaying)
        BigButton(stringResource(Res.string.title_screen), glyph = Glyph.Exit, onClick = onTitle)
    }
}

/** The industry kinds to place, across the editor's tray, scrolling. */
@Composable
fun RowScope.IndustryKinds(world: World, kind: String, onKind: (String) -> Unit) {
    val lang = languageCode()
    val open = placeableKinds(world)
    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(KitSpace.Gap)) {
        for (k in open) PictureChip(k.name(lang), k.key == kind, { IndustryPicture(k, it) }) { onKind(k.key) }
    }
}

/** The industry kinds that can be placed in [world]'s start year, or all if none can. */
fun placeableKinds(world: World) = Calendar.year(world.startYear, world.day).let { year ->
    world.roster.industries.filter { year >= it.from && year < it.until }.ifEmpty { world.roster.industries }
}

/** An area of the map the editor is about to change, from ([a0], [a1]) to ([a2], [a3]) either way round. */
fun DrawScope.drawAreaPlan(world: World, painter: LandPainter, a: IntArray, removing: Boolean) {
    val colour = if (removing) AREA_REMOVE else AREA_ADD
    for (y in minOf(a[1], a[3])..maxOf(a[1], a[3])) for (x in minOf(a[0], a[2])..maxOf(a[0], a[2])) {
        if (world.inside(x, y)) with(painter) { tile(x, y, colour) }
    }
}

private val AREA_ADD = Color(0x6658B368)
private val AREA_REMOVE = Color(0x66E05A4E)

/** What the editor's land tools send for the area from ([x0], [y0]) to ([x1], [y1]); [height] is what Level levels to. */
fun editAreaCommand(tool: Tool, me: Int, x0: Int, y0: Int, x1: Int, y1: Int, height: Int): Command? = when (tool) {
    Tool.Raise -> Command.EditTerrain(me, x0, y0, x1, y1, Command.EditTerrain.RAISE)
    Tool.Lower -> Command.EditTerrain(me, x0, y0, x1, y1, Command.EditTerrain.LOWER)
    Tool.Level -> Command.EditTerrain(me, x0, y0, x1, y1, Command.EditTerrain.LEVEL, height)
    Tool.Trees -> Command.EditTrees(me, x0, y0, x1, y1, plant = true)
    Tool.ClearTrees -> Command.EditTrees(me, x0, y0, x1, y1, plant = false)
    else -> null
}
