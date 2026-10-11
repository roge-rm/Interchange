package com.rm.interchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BarShape = RoundedCornerShape(12.dp)
private val ButtonShape = RoundedCornerShape(8.dp)

/** A bar or panel floating over the map. */
@Composable
fun ChromeBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalColors.current
    Box(modifier.clip(BarShape).background(c.chrome).border(1.dp, c.chromeEdge, BarShape)) { content() }
}

/** A square button with a glyph, lit while [selected]. */
@Composable
fun SquareButton(glyph: Glyph, description: String, side: Dp, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        Modifier
            .size(side)
            .clip(ButtonShape)
            .background(if (selected) c.accent else c.button)
            .semantics { contentDescription = description; this.selected = selected }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (selected) c.onAccent else if (enabled) c.text else c.textDim.copy(alpha = 0.4f), Modifier.size(side * 0.55f))
    }
}

/**
 * Pause, the speed, the date, the money and turning the view, along the top.
 * On a narrow screen the readings go on a second line.
 */
@Composable
fun StatusStrip(
    paused: Boolean,
    onPause: () -> Unit,
    speed: Int,
    onSpeed: () -> Unit,
    date: String,
    money: String,
    moneyLow: Boolean,
    onTurnLeft: () -> Unit,
    onTurnRight: () -> Unit,
    compact: Boolean,
    twoLines: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = LocalColors.current
    val side = if (compact) 36.dp else 40.dp
    val textSize = if (compact) 13.sp else 15.sp
    val gap = if (compact) 6.dp else 8.dp
    val time = @Composable {
        SquareButton(if (paused) Glyph.Play else Glyph.Pause, if (paused) "Play" else "Pause", side, selected = paused, onClick = onPause)
        Box(contentAlignment = Alignment.BottomEnd) {
            SquareButton(Glyph.Speed, "Speed", side, onClick = onSpeed)
            Text("${SPEEDS[speed]}×", color = c.textDim, fontSize = 9.sp, modifier = Modifier.padding(end = 3.dp))
        }
    }
    val readings = @Composable {
        Text(date, color = c.text, fontSize = textSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(money, color = if (moneyLow) c.bad else c.text, fontSize = textSize, maxLines = 1)
    }
    val turns = @Composable {
        SquareButton(Glyph.TurnLeft, "Turn left", side, onClick = onTurnLeft)
        SquareButton(Glyph.TurnRight, "Turn right", side, onClick = onTurnRight)
    }
    ChromeBox(modifier) {
        if (twoLines) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) { time() }
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) { turns() }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { readings() }
            }
        } else {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(gap * 1.5f), verticalAlignment = Alignment.CenterVertically) {
                time()
                readings()
                turns()
            }
        }
    }
}

/** How many ticks run for each one at normal speed. */
val SPEEDS = intArrayOf(1, 2, 4, 8)

/** The tool buttons, in a row along the bottom or a column down the side. The row shares out the width. */
@Composable
fun ToolBar(selected: ToolGroup, onSelect: (ToolGroup) -> Unit, vertical: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
    if (vertical) {
        ChromeBox(modifier) {
            // Scrolls only on a screen too short for the whole rail.
            Column(Modifier.verticalScroll(rememberScrollState()).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (g in ToolGroup.entries) GroupButton(g, g == selected, compact, Modifier.width(if (compact) 56.dp else 64.dp)) { onSelect(g) }
            }
        }
    } else {
        ChromeBox(modifier.fillMaxWidth()) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (g in ToolGroup.entries) GroupButton(g, g == selected, compact, Modifier.weight(1f)) { onSelect(g) }
            }
        }
    }
}

@Composable
private fun GroupButton(group: ToolGroup, selected: Boolean, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalColors.current
    Column(
        modifier
            .clip(ButtonShape)
            .background(if (selected) c.accent else c.button)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = if (compact) 4.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphIcon(group.glyph, if (selected) c.onAccent else c.text, Modifier.size(if (compact) 24.dp else 28.dp))
        BasicText(
            group.title, maxLines = 1,
            style = TextStyle(color = if (selected) c.onAccent else c.textDim, textAlign = TextAlign.Center),
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = if (compact) 10.sp else 11.sp),
        )
    }
}

/**
 * The chosen group's tools as tabs, with a line under them naming the tool,
 * what it costs and its toggles ([extras]). Tools still to come are dimmed.
 * Folded, it's only the line, which opens it again. On a [compact] screen the
 * tabs are only their drawings.
 */
@Composable
fun ToolTray(
    tool: Tool,
    onTool: (Tool) -> Unit,
    detail: String?,
    folded: Boolean,
    onFold: (Boolean) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
    extras: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalColors.current
    val tools = Tool.of(tool.group)
    ChromeBox(modifier) {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (tools.size > 1 && !folded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in tools) {
                        val on = t == tool
                        Column(
                            Modifier
                                .weight(1f)
                                .alpha(if (t.ready) 1f else 0.4f)
                                .clip(ButtonShape)
                                .background(if (on) c.accent else c.button)
                                .semantics(mergeDescendants = true) { selected = on }
                                .semantics { contentDescription = t.title }
                                .clickable(enabled = t.ready, role = Role.Tab) { onTool(t) }
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            GlyphIcon(t.glyph, if (on) c.onAccent else c.text, Modifier.size(22.dp))
                            if (!compact) BasicText(
                                t.title, maxLines = 1,
                                style = TextStyle(color = if (on) c.onAccent else c.textDim, textAlign = TextAlign.Center),
                                autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 10.sp),
                            )
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clip(ButtonShape)
                    .clickable(role = Role.Button, onClickLabel = if (folded) "Show tools" else "Hide tools") { onFold(!folded) }
                    .padding(start = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (folded) GlyphIcon(tool.glyph, c.text, Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(tool.title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    detail?.let { Text(it, color = c.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                extras()
            }
        }
    }
}

/** A small on or off button for the tray's line, such as removing instead of building. */
@Composable
fun TrayToggle(glyph: Glyph, label: String, on: Boolean, onClick: () -> Unit) {
    SquareButton(glyph, label, 32.dp, selected = on, onClick = onClick)
}

/** A panel of facts about something on the map, with a close button. */
@Composable
fun InfoCard(title: String, lines: List<String>, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    ChromeBox(modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                SquareButton(Glyph.Remove, "Close", 28.dp, onClick = onClose)
            }
            for (l in lines) Text(l, color = c.textDim, fontSize = 13.sp)
        }
    }
}

/** 250000 as $250,000, and owing as −$861. */
fun moneyText(amount: Long): String {
    val digits = kotlin.math.abs(amount).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (amount < 0) "−$$grouped" else "$$grouped"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

fun dateText(day: Int, month: Int, year: Int) = "$day ${MONTHS[month]} $year"

/** A pill that's on or off, for picking one of a few things. */
@Composable
fun Chip(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) c.accent else c.button)
            .semantics { selected = on }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (on) c.onAccent else c.text, fontSize = 13.sp, maxLines = 1)
    }
}

/** A button with words on it, for what a card can do. */
@Composable
fun ActionButton(label: String, enabled: Boolean = true, strong: Boolean = false, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(ButtonShape)
            .background(if (strong) c.accent else c.button)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (strong) c.onAccent else c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A card with a title, a close button and whatever goes under them. */
@Composable
fun Card(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalColors.current
    ChromeBox(modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                SquareButton(Glyph.Remove, "Close", 28.dp, onClick = onClose)
            }
            content()
        }
    }
}
