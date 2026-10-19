package com.rm.interchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rm.interchange.res.*
import org.jetbrains.compose.resources.stringResource

/** Map controls use the same selected tiles as the top bar, with names on a long press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapTile(glyph: Glyph, label: String, selected: Boolean, side: Dp, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        SquareButton(glyph, label, side, selected, enabled, onClick)
    }
}

/** A compact filter with its choices in a menu instead of a full-width slider. */
@Composable
internal fun MapChoice(label: String, description: String, glyph: Glyph, modifier: Modifier,
    ids: List<Int>, name: @Composable (Int) -> String, selected: Int, colour: ((Int) -> Color?)? = null, onSelect: (Int) -> Unit) {
    val c = LocalColors.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(10.dp)).background(c.button)
            .semantics { contentDescription = description }.clickable { open = true }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GlyphIcon(glyph, colour?.invoke(selected) ?: c.accent, Modifier.size(17.dp))
            Text(label, color = c.text, style = KitType.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            GlyphIcon(Glyph.Down, c.textDim, Modifier.size(12.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.chrome) {
            for (id in ids) DropdownMenuItem(text = { Text(name(id), color = if (selected == id) c.accent else c.text, style = KitType.Dim) },
                onClick = { onSelect(id); open = false },
                leadingIcon = { colour?.invoke(id)?.let { tint -> Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(tint)) } },
                trailingIcon = { if (selected == id) GlyphIcon(Glyph.Tick, c.accent, Modifier.size(16.dp)) })
        }
    }
}

@Composable
internal fun MapControlsHelp(onClose: () -> Unit) {
    val c = LocalColors.current
    Window(stringResource(Res.string.map_controls), onClose, Glyph.Map) {
        Text(stringResource(Res.string.map_controls_hint), color = c.textDim, style = KitType.Dim)
        Text(stringResource(Res.string.map_help), color = c.text, style = KitType.Dim)
        val labels = listOf(Res.string.map_rail, Res.string.map_roads, Res.string.map_stations, Res.string.industries,
            Res.string.map_town_names, Res.string.map_company_colours, Res.string.map_trains, Res.string.map_road_vehicles,
            Res.string.map_ships, Res.string.map_aircraft, Res.string.map_cargo_flow, Res.string.map_coverage)
        val glyphs = listOf(Glyph.Rail, Glyph.Road, Glyph.Station, Glyph.Factory, Glyph.Home, Glyph.Flag,
            Glyph.Train, Glyph.Bus, Glyph.Ship, Glyph.Air, Glyph.Graph, Glyph.People)
        Grid(labels.indices.toList()) { i ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlyphIcon(glyphs[i], c.accent, Modifier.size(20.dp))
                Text(stringResource(labels[i]), color = c.text, style = KitType.Dim)
            }
        }
        Text(stringResource(Res.string.map_coverage_help), color = c.textDim, style = KitType.Dim)
        Text(stringResource(Res.string.map_on_board), color = c.textDim, style = KitType.Dim)
    }
}
