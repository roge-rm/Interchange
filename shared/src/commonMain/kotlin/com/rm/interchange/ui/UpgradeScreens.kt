package com.rm.interchange.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rm.interchange.res.*
import com.rm.interchange.server.GameThread
import com.rm.interchange.sim.Autorenew
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Engine
import com.rm.interchange.sim.future
import com.rm.interchange.sim.trackKind
import com.rm.interchange.sim.Roster
import com.rm.interchange.sim.UpgradeWhy
import com.rm.interchange.sim.VehicleKind
import com.rm.interchange.sim.WorldView
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** The most rules a company can have, as the sim keeps. */
private const val RULES_MOST = 64

/** Why a model can't be had, in a few words. */
@Composable
fun upgradeWhyText(why: Int): String = stringResource(when (why) {
    UpgradeWhy.NOT_ON_SALE -> Res.string.why_not_on_sale
    UpgradeWhy.NO_MONEY -> Res.string.not_enough_money
    UpgradeWhy.NO_WIRES -> Res.string.why_no_wires
    UpgradeWhy.TOO_LONG -> Res.string.why_too_long
    UpgradeWhy.AIRPORT -> Res.string.why_airport
    UpgradeWhy.TRAM -> Res.string.why_tram
    else -> Res.string.why_other
})

/** Whether a vehicle's autorenew switch is worth showing: the company renews, or has upgrade rules. */
fun companyRenews(view: WorldView) = view.company?.let { it.autorenew || (it.upgrade && view.upgrades.isNotEmpty()) } == true

/** On a card: what a rule would make this vehicle, or in amber why it can't, or that it isn't serviced. */
@Composable
fun UpgradeLine(roster: Roster, upgradeTo: Int, why: Int, service: Boolean) {
    if (upgradeTo < 0) return
    val name = roster.engines.getOrNull(upgradeTo)?.name(languageCode()) ?: return
    val c = LocalColors.current
    if (!service) Text(stringResource(Res.string.upgrade_blocked, name, stringResource(Res.string.why_servicing_off)), color = c.warn, style = KitType.Dim)
    else if (why == UpgradeWhy.NONE || why == UpgradeWhy.OK) Text(stringResource(Res.string.upgrades_to, name), color = c.textDim, style = KitType.Dim)
    else Text(stringResource(Res.string.upgrade_blocked, name, upgradeWhyText(why)), color = c.warn, style = KitType.Dim)
}

/** Autorenew and upgrade settings, then the rules of each kind. */
@Composable
fun UpgradesWindow(runner: GameThread, onClose: () -> Unit) {
    val view by produceState(runner.view, runner) {
        while (true) androidx.compose.runtime.withFrameNanos { value = runner.view }
    }
    val v = view ?: return
    val co = v.company ?: return
    val me = LocalCompany.current
    var kind by remember { mutableIntStateOf(ShowVehicle.TRAIN) }
    Window(stringResource(Res.string.upgrades), onClose, Glyph.Renew) {
        fun renew(on: Boolean = co.autorenew, months: Int = co.renewMonths, floor: Long = co.renewFloor) =
            runner.request(Command.SetAutorenew(me, on, months, floor))
        Head(stringResource(Res.string.automation), Glyph.Gear)
        TogglePill(stringResource(Res.string.autorenew_on), co.autorenew, Modifier.fillMaxWidth()) { renew(on = it) }
        TogglePill(stringResource(Res.string.upgrade_on), co.upgrade, Modifier.fillMaxWidth()) { runner.request(Command.SetUpgrade(me, it)) }
        if (co.autorenew) {
            val months = Autorenew.MONTHS.toList()
            StepSlider(stringResource(Res.string.renew_when), months, co.renewMonths.let { m -> months.minBy { kotlin.math.abs(it - m) } }, { m ->
                when {
                    m < 0 -> stringResource(Res.string.renew_before, -m)
                    m > 0 -> stringResource(Res.string.renew_after, m)
                    else -> stringResource(Res.string.renew_at_end)
                }
            }) { renew(months = it) }
        }
        if (co.autorenew || co.upgrade) {
            val floors = Autorenew.FLOORS.toList()
            StepSlider(stringResource(Res.string.renew_floor), floors, floors.lastOrNull { it <= co.renewFloor } ?: 0L, { money(it) }) { renew(floor = it) }
        }
        InfoRow(stringResource(Res.string.breakdowns_setting), stringArrayResource(Res.array.breakdown_levels).getOrElse(v.breakdowns) { "" })
        Head(stringResource(Res.string.upgrade_rules), Glyph.Replace)
        FillChips(listOf(ShowVehicle.TRAIN, ShowVehicle.ROAD, ShowVehicle.SHIP, ShowVehicle.AIRCRAFT), kind, { k ->
            stringResource(when (k) {
                ShowVehicle.TRAIN -> Res.string.group_rail
                ShowVehicle.ROAD -> Res.string.group_road
                ShowVehicle.SHIP -> Res.string.group_water
                else -> Res.string.group_air
            })
        }) { kind = it }
        UpgradeRules(runner, v, kind, null)
    }
}

/** The models of [kind] this company runs, in a group if [group] is one. Trains count engines and cars. */
private fun modelsRun(v: WorldView, me: Int, kind: Int, group: Int): List<Int> {
    fun inGroup(g: Int) = group < 0 || g == group
    return when (kind) {
        ShowVehicle.TRAIN -> v.trains.filter { it.owner == me && inGroup(it.group) }.flatMap { t -> t.formation.map { it.model } }
        ShowVehicle.ROAD -> v.roadVehicles.filter { it.owner == me && inGroup(it.group) }.map { it.model }
        ShowVehicle.SHIP -> v.ships.filter { it.owner == me && inGroup(it.group) }.map { it.model }
        else -> v.aircraft.filter { it.owner == me && inGroup(it.group) }.map { it.model }
    }.distinct().sorted()
}

/**
 * The upgrade rules of [kind]: with [only] null all of them and a choice of group when adding, else just that group's
 * (-1 is the whole fleet), each with a button to take it off, and a form to add one.
 */
@Composable
fun UpgradeRules(runner: GameThread, v: WorldView, kind: Int, only: Int?) {
    val me = LocalCompany.current
    val c = LocalColors.current
    val lang = languageCode()
    val world = runner.game?.world ?: return
    val roster = world.roster
    val year = v.weather?.year ?: com.rm.interchange.sim.Calendar.year(world.startYear, world.day)
    val rules = v.upgrades.filter { it.kind == kind && (only == null || it.group == only) }
    if (rules.isEmpty()) Text(stringResource(Res.string.no_upgrade_rules), color = c.textDim, style = KitType.Body)
    for (r in rules) key(r.kind, r.group, r.from) {
        val from = r.fromNames[lang] ?: r.fromNames["en"] ?: ""
        val to = r.toNames[lang] ?: r.toNames["en"] ?: ""
        ListRow(
            Glyph.Replace,
            if (r.group < 0) stringResource(Res.string.rule_fleet, from, to) else stringResource(Res.string.rule_group, from, to, r.groupName),
            null, null,
        ) {
            SquareButton(Glyph.Remove, stringResource(Res.string.remove), 36.dp) {
                runner.request(Command.Upgrade(me, kind, r.from, r.to, r.group, remove = true))
            }
        }
    }
    var adding by remember(kind, only) { mutableStateOf(false) }
    var from by remember(kind, only) { mutableIntStateOf(-1) }
    var to by remember(kind, only) { mutableIntStateOf(-1) }
    var group by remember(kind, only) { mutableIntStateOf(-1) }
    if (v.upgrades.size >= RULES_MOST) return
    ActionButton(stringResource(Res.string.add_rule), strong = adding) { adding = !adding; from = -1; to = -1 }
    if (!adding) return
    val run = modelsRun(v, me, kind, only ?: -1).mapNotNull { roster.engines.getOrNull(it) }
    Head(stringResource(Res.string.upgrade_from))
    if (run.isEmpty()) Text(stringResource(Res.string.no_models_run), color = c.textDim, style = KitType.Body)
    for (e in run) key(e.id) {
        PictureRow(
            e.name(lang), picture = { VehiclePicture(e, it) }, selected = from == e.id, role = Role.RadioButton,
            onClick = { from = e.id; to = -1 },
        )
    }
    val old = roster.engines.getOrNull(from) ?: return
    val options: List<Engine> = when (kind) {
        ShowVehicle.TRAIN -> roster.available(year, pulls = old.pulls).filter { it.trackKind == old.trackKind && (!it.future || world.settings.future) }
        ShowVehicle.ROAD -> roster.availableRoad(year, world.settings.future).filter { it.tram == old.tram }
        ShowVehicle.SHIP -> roster.availableOf(VehicleKind.SHIP, year)
        else -> roster.availableOf(VehicleKind.AIRCRAFT, year, world.settings.future)
    }.filter { it.id != old.id }
    Head(stringResource(Res.string.upgrade_to))
    for (e in options) key(e.id) {
        PictureRow(
            e.name(lang), picture = { VehiclePicture(e, it) }, selected = to == e.id, role = Role.RadioButton,
            onClick = { to = e.id },
        )
    }
    val groups = v.groups.getOrNull(me)?.getOrNull(kind).orEmpty().sortedBy { it.id }
    val target = only ?: group.takeIf { id -> groups.any { it.id == id } } ?: -1
    if (only == null && groups.isNotEmpty()) {
        FillChips(listOf(-1) + groups.map { it.id }, target, { id ->
            if (id == -1) stringResource(Res.string.whole_fleet) else groups.first { it.id == id }.name
        }) { group = it }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton(stringResource(Res.string.add_rule), enabled = to >= 0, strong = true) {
            runner.request(Command.Upgrade(me, kind, from, to, target))
            adding = false; from = -1; to = -1
        }
    }
}
