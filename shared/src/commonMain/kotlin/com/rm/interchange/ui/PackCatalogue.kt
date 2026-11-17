package com.rm.interchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rm.interchange.res.Res
import com.rm.interchange.res.pack_adds
import com.rm.interchange.res.pack_changes
import com.rm.interchange.res.pack_filter_all
import com.rm.interchange.res.pack_line
import com.rm.interchange.res.pack_problems
import com.rm.interchange.res.pack_warning
import com.rm.interchange.res.pack_warnings
import com.rm.interchange.res.pack_turns_off
import com.rm.interchange.res.pack_unit
import com.rm.interchange.res.add_pack
import com.rm.interchange.res.adding_pack
import com.rm.interchange.res.buildings
import com.rm.interchange.res.buildings_count
import com.rm.interchange.res.cargo
import com.rm.interchange.res.cargo_count
import com.rm.interchange.res.industries
import com.rm.interchange.res.industries_count
import com.rm.interchange.res.packs
import com.rm.interchange.res.remove
import com.rm.interchange.res.vehicles
import com.rm.interchange.res.vehicles_count
import com.rm.interchange.sim.BadPack
import com.rm.interchange.sim.Pack
import com.rm.interchange.sim.Packs
import com.rm.interchange.sim.Roster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What a pack's list can be narrowed to: the packs that add or change that kind of thing. */
private enum class PackKind { All, Vehicles, Buildings, Industries, Cargo }

private fun Map<String, String>?.word(lang: String, fallback: String) = this?.get(lang) ?: this?.get("en") ?: fallback

private fun Pack.has(kind: PackKind) = when (kind) {
    PackKind.All -> true
    PackKind.Vehicles -> vehicles.isNotEmpty()
    PackKind.Buildings -> houses.isNotEmpty()
    PackKind.Industries -> industries.isNotEmpty()
    PackKind.Cargo -> cargo.isNotEmpty()
}

/**
 * The content packs added: each with what it adds, tapped for its page with its vehicles, buildings, industries and
 * cargo drawn, what it changes and turns off, and its problems. Adding more from a file, and taking them off, which
 * asks once more. Everything is drawn from the packs themselves, so it works with no game running.
 */
@Composable
fun PacksWindow(packs: List<Pack>, adding: Boolean, message: String?, failed: Boolean, onAdd: () -> Unit, onRemove: (Pack) -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val lang = languageCode()
    var removing by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(PackKind.All) }
    val all = listOf(Packs.BASE) + packs
    val page = open?.let { id -> packs.firstOrNull { it.id == id } }
    if (open != null && page == null) open = null
    Window(page?.name(lang) ?: stringResource(Res.string.packs), { if (page != null) open = null else onClose() }, Glyph.Package) {
        if (page == null) {
            BigButton(stringResource(if (adding) Res.string.adding_pack else Res.string.add_pack), primary = true, enabled = !adding, glyph = Glyph.Plus, onClick = onAdd)
            if (message != null) Text(message, color = if (failed) c.bad else c.text, style = KitType.Body)
        }
        FillChips(PackKind.entries, filter, { kindName(it) }) { filter = it }
        if (page != null) {
            PackPage(page, all, filter, lang)
            if (removing == page.id) ActionButton(stringResource(Res.string.remove), strong = true) { removing = null; open = null; onRemove(page) }
            else BigButton(stringResource(Res.string.remove), glyph = Glyph.Remove) { removing = page.id }
        } else for (p in all) if (p.has(filter)) {
            val about = p.description(lang)
            PictureRow(
                p.name(lang),
                icon = { Badge(Glyph.Package, size = 40.dp) },
                line = stringResource(Res.string.pack_line, p.version, p.author),
                stats = adds(p),
                onClick = if (p.id == Packs.BASE_ID) null else ({ open = p.id }),
                end = if (p.id == Packs.BASE_ID) null else ({
                    if (removing == p.id) ActionButton(stringResource(Res.string.remove), strong = true) { removing = null; onRemove(p) }
                    else SquareButton(Glyph.Remove, stringResource(Res.string.remove), 34.dp) { removing = p.id }
                }),
                below = if (about.isEmpty()) null else ({ Text(about, color = c.textDim, style = KitType.Dim) }),
            )
        }
    }
}

@Composable
private fun kindName(k: PackKind) = stringResource(
    when (k) {
        PackKind.All -> Res.string.pack_filter_all
        PackKind.Vehicles -> Res.string.vehicles
        PackKind.Buildings -> Res.string.buildings
        PackKind.Industries -> Res.string.industries
        PackKind.Cargo -> Res.string.cargo
    },
)

/** How many of each kind [p] adds, as one line. */
@Composable
private fun adds(p: Pack): String {
    val vehicles = p.vehicles.count { it.replaces == null }
    val houses = p.houses.count { it.replaces == null }
    val industries = p.industries.count { it.replaces == null }
    val cargo = p.cargo.count { it.replaces == null }
    return listOfNotNull(
        if (vehicles > 0) pluralStringResource(Res.plurals.vehicles_count, vehicles, vehicles) else null,
        if (houses > 0) pluralStringResource(Res.plurals.buildings_count, houses, houses) else null,
        if (industries > 0) pluralStringResource(Res.plurals.industries_count, industries, industries) else null,
        if (cargo > 0) pluralStringResource(Res.plurals.cargo_count, cargo, cargo) else null,
    ).joinToString(" · ")
}

/** One pack's page: its words, problems, then what it adds, changes and turns off, narrowed to [filter]. */
@Composable
private fun PackPage(p: Pack, all: List<Pack>, filter: PackKind, lang: String) {
    val c = LocalColors.current
    val about = p.description(lang)
    Text(stringResource(Res.string.pack_line, p.version, p.author), color = c.textDim, style = KitType.Body)
    if (about.isNotEmpty()) Text(about, color = c.text, style = KitType.Body)
    // What building the game from this pack and those it needs says, if it's not well.
    val problems by produceState<List<String>>(emptyList(), p, all) {
        value = withContext(Dispatchers.Default) {
            try {
                // All it needs, down the chain, each once and without base, in the order the packs are installed.
                val wanted = HashSet<String>()
                fun walk(q: Pack) { for (n in q.needs) if (n != Packs.BASE_ID && wanted.add(n)) all.firstOrNull { it.id == n }?.let { walk(it) } }
                walk(p)
                val chain = all.filter { it.id in wanted && it.id != p.id }
                Roster.build(listOf(Packs.BASE) + chain + p)
                emptyList()
            } catch (e: BadPack) {
                e.problems
            }
        }
    }
    if (problems.isNotEmpty()) {
        Head(stringResource(Res.string.pack_problems), Glyph.Alert)
        for (line in problems) Text(line, color = c.bad, style = KitType.Body)
    }
    if (p.warnings.isNotEmpty()) {
        Head(stringResource(Res.string.pack_warnings), Glyph.Alert)
        for (w in p.warnings) Text(stringResource(Res.string.pack_warning, w), color = c.textDim, style = KitType.Body)
    }
    fun show(k: PackKind) = filter == PackKind.All || filter == k
    // Each thing the pack names of another pack as pack:id, by the name it gives it.
    val named = remember(all, lang) {
        buildMap {
            for (q in all) {
                for (v in q.vehicles) put("${q.id}:${v.id}", v.names.word(lang, v.id))
                for (h in q.houses) put("${q.id}:${h.id}", h.names.word(lang, h.id))
                for (i in q.industries) put("${q.id}:${i.id}", i.names.word(lang, i.id))
            }
        }
    }
    val adds = stringResource(Res.string.pack_adds)
    val changes = stringResource(Res.string.pack_changes)
    for (section in listOf(adds to false, changes to true)) {
        val (title, replacing) = section
        val vs = if (show(PackKind.Vehicles)) p.vehicles.filter { (it.replaces != null) == replacing } else emptyList()
        val hs = if (show(PackKind.Buildings)) p.houses.filter { (it.replaces != null) == replacing } else emptyList()
        val ins = if (show(PackKind.Industries)) p.industries.filter { (it.replaces != null) == replacing } else emptyList()
        val cs = if (show(PackKind.Cargo)) p.cargo.filter { (it.replaces != null) == replacing } else emptyList()
        if (vs.isEmpty() && hs.isEmpty() && ins.isEmpty() && cs.isEmpty()) continue
        Head(title)
        for (v in vs) PictureRow(
            v.names.word(lang, v.replaces ?: v.id),
            picture = { PackPicture(p.id, listOfNotNull(v.sprite), VEHICLE_CELL, it) },
            line = v.replaces?.let { named[it] ?: it },
        )
        for (h in hs) PictureRow(
            h.names.word(lang, h.replaces ?: h.id),
            picture = { PackPicture(p.id, listOfNotNull(h.sprite), BUILDING_CELL, it) },
            line = h.replaces?.let { named[it] ?: it },
        )
        for (i in ins) PictureRow(
            i.names.word(lang, i.replaces ?: i.id),
            picture = { PackPicture(p.id, i.parts.orEmpty().values.toList(), BUILDING_CELL, it) },
            line = i.replaces?.let { named[it] ?: it },
        )
        for (k in cs) PictureRow(
            k.names.word(lang, k.replaces ?: k.id),
            icon = { k.colour?.let { ColourSwatch(Color(0xFF000000.toInt() or it)) } },
            line = k.units?.let { u -> u.word(lang, "").takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.pack_unit, it) } },
        )
    }
    if (p.disables.isNotEmpty() && filter == PackKind.All) {
        Head(stringResource(Res.string.pack_turns_off))
        for (d in p.disables) Text(named[d] ?: d, color = c.text, style = KitType.Body)
    }
}

/** The cell vehicles are drawn at in a picture, three quarters on, and the one buildings are. */
private const val VEHICLE_CELL = 2
private const val BUILDING_CELL = 3
/** The sheet level pictures use, each level a halving: 64 pixel cells. */
private const val PICTURE_LEVEL = 2

private class Drawn(val image: ImageBitmap, val rects: IntArray)

/**
 * Cell [cell] of the biggest of [names], sheets of pack [pack] as they were cut when it was added, scaled to fit the
 * box and centred; blank if there are none or they can't be read.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun PackPicture(pack: String, names: List<String>, cell: Int, modifier: Modifier) {
    val drawn by produceState<Drawn?>(null, pack, names, cell) {
        value = withContext(Dispatchers.Default) {
            val best = names.mapNotNull { n -> InstalledPacks.rects(pack, n)?.let { n to it } }
                .maxByOrNull { (_, r) -> r[cell * 6 + 2] * r[cell * 6 + 3] } ?: return@withContext null
            val bytes = InstalledPacks.sheet(pack, best.first, PICTURE_LEVEL) ?: return@withContext null
            try {
                Drawn(bytes.decodeToImageBitmap(), best.second)
            } catch (e: Exception) {
                null
            }
        }
    }
    Canvas(modifier) {
        val d = drawn ?: return@Canvas
        val r = cell * 6
        val w0 = d.rects[r + 2]
        val h0 = d.rects[r + 3]
        if (w0 == 0 || h0 == 0) return@Canvas
        val f = 1 shl PICTURE_LEVEL
        val sw = w0 / f
        val sh = h0 / f
        if (sw == 0 || sh == 0) return@Canvas
        val scale = minOf(size.width / w0, size.height / h0)
        val dw = (w0 * scale).toInt()
        val dh = (h0 * scale).toInt()
        drawImage(
            d.image, IntOffset(d.rects[r] / f, d.rects[r + 1] / f), IntSize(sw, sh),
            IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()), IntSize(dw, dh),
            filterQuality = FilterQuality.Medium,
        )
    }
}
