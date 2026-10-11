package com.rm.interchange.ui

import com.rm.interchange.res.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.stringArrayResource
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
import kotlin.math.ceil
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

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
 * The menu, pause, the speed and the overlays, the date and the money, and
 * turning the view, along the top. On an upright phone the readings go on a
 * second line, and the first row moves along past any [cameras] in the top
 * edge (stretches across the window, in pixels).
 */
@Composable
fun StatusStrip(
    onMenu: () -> Unit,
    paused: Boolean,
    onPause: () -> Unit,
    speed: Int,
    onSpeed: () -> Unit,
    overlaysOn: Boolean,
    onOverlays: () -> Unit,
    date: String,
    money: String,
    moneyLow: Boolean,
    onTurnLeft: () -> Unit,
    onTurnRight: () -> Unit,
    compact: Boolean,
    twoLines: Boolean,
    modifier: Modifier = Modifier,
    cameras: List<ClosedFloatingPointRange<Float>> = emptyList(),
) {
    val c = LocalColors.current
    val side = if (compact) 36.dp else 40.dp
    val textSize = if (compact) 13.sp else 15.sp
    val gap = if (compact) 6.dp else 8.dp
    val time = @Composable {
        SquareButton(Glyph.Menu, stringResource(Res.string.menu), side, onClick = onMenu)
        SquareButton(if (paused) Glyph.Play else Glyph.Pause, stringResource(if (paused) Res.string.play else Res.string.pause), side, selected = paused, onClick = onPause)
        Box(contentAlignment = Alignment.BottomEnd) {
            SquareButton(Glyph.Speed, stringResource(Res.string.speed), side, onClick = onSpeed)
            Text("${SPEEDS[speed]}×", color = c.textDim, fontSize = 9.sp, modifier = Modifier.padding(end = 3.dp))
        }
        SquareButton(Glyph.Layers, stringResource(Res.string.overlays), side, selected = overlaysOn, onClick = onOverlays)
    }
    val readings = @Composable {
        Text(date, color = c.text, fontSize = textSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(money, color = if (moneyLow) c.bad else c.text, fontSize = textSize, maxLines = 1)
    }
    val turns = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            SquareButton(Glyph.TurnLeft, stringResource(Res.string.turn_left), side, onClick = onTurnLeft)
            SquareButton(Glyph.TurnRight, stringResource(Res.string.turn_right), side, onClick = onTurnRight)
        }
    }
    ChromeBox(modifier) {
        if (twoLines) {
            // Across the whole width: the buttons on the left and turning on the right, clear of the cameras, then
            // the date and money spread out under them.
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ClearOf(cameras, gap, Modifier.fillMaxWidth(), pinLast = true) {
                    time()
                    turns()
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

/**
 * A row of [content] spaced by [spacing], each moved along past any of the
 * [cameras] (stretches across the window, in pixels) it would sit over. With
 * [pinLast], the last one goes at the far end, or as near it as the cameras let it.
 */
@Composable
private fun ClearOf(
    cameras: List<ClosedFloatingPointRange<Float>>,
    spacing: Dp,
    modifier: Modifier = Modifier,
    pinLast: Boolean = false,
    content: @Composable () -> Unit,
) {
    var left by remember { mutableFloatStateOf(0f) }
    val sorted = remember(cameras) { cameras.sortedBy { it.start } }
    Layout(content, modifier.onGloballyPositioned { left = it.positionInWindow().x }) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val space = spacing.roundToPx()
        val xs = IntArray(placeables.size)
        var x = 0
        for ((k, pl) in placeables.withIndex()) {
            for (cam in sorted) {
                val from = cam.start - left - space
                val to = cam.endInclusive - left + space
                if (x < to && x + pl.width > from) x = ceil(to).toInt()
            }
            xs[k] = x
            x += pl.width + space
        }
        if (pinLast && placeables.isNotEmpty() && constraints.hasBoundedWidth) {
            val k = placeables.size - 1
            val w = placeables[k].width
            var end = constraints.maxWidth - w
            // Back from the far end past any camera, but never back over the one before.
            for (cam in sorted.reversed()) {
                val from = cam.start - left - space
                val to = cam.endInclusive - left + space
                if (end < to && end + w > from) end = (from - w).toInt()
            }
            if (end > xs[k]) xs[k] = end
            x = maxOf(x, xs[k] + w + space)
        }
        val width = (if (pinLast && constraints.hasBoundedWidth) constraints.maxWidth else x - space).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) { placeables.forEachIndexed { k, pl -> pl.place(xs[k], (height - pl.height) / 2) } }
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
            stringResource(group.title), maxLines = 1,
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
                        val toolTitle = stringResource(t.title)
                        Column(
                            Modifier
                                .weight(1f)
                                .alpha(if (t.ready) 1f else 0.4f)
                                .clip(ButtonShape)
                                .background(if (on) c.accent else c.button)
                                .semantics(mergeDescendants = true) { selected = on }
                                .semantics { contentDescription = toolTitle }
                                .clickable(enabled = t.ready, role = Role.Tab) { onTool(t) }
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            GlyphIcon(t.glyph, if (on) c.onAccent else c.text, Modifier.size(22.dp))
                            if (!compact) BasicText(
                                toolTitle, maxLines = 1,
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
                    .clickable(role = Role.Button, onClickLabel = stringResource(if (folded) Res.string.show_tools else Res.string.hide_tools)) { onFold(!folded) }
                    .padding(start = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (folded) GlyphIcon(tool.glyph, c.text, Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(tool.title), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
                SquareButton(Glyph.Remove, stringResource(Res.string.close), 28.dp, onClick = onClose)
            }
            for (l in lines) Text(l, color = c.textDim, fontSize = 13.sp)
        }
    }
}

/** An amount of money as $250,000, or as the language writes it; owing as −$861. */
@Composable
fun money(amount: Long): String =
    stringResource(if (amount < 0) Res.string.money_owed else Res.string.money, Numbers.grouped(kotlin.math.abs(amount)))

/** Game day [day] of a game starting in [startYear], as 4 Mar 1852 or as the language writes it. */
@Composable
fun date(day: Int, startYear: Int): String {
    val months = stringArrayResource(Res.array.months)
    return stringResource(
        Res.string.date, com.rm.interchange.sim.Calendar.dayOfMonth(day),
        months.getOrElse(com.rm.interchange.sim.Calendar.month(day)) { "" }, com.rm.interchange.sim.Calendar.year(startYear, day),
    )
}

/** Game day [day] with its day of the week, as Thu 4 Mar 1852, for the status strip. */
@Composable
fun weekdayDate(day: Int, startYear: Int): String {
    val days = stringArrayResource(Res.array.weekdays)
    return stringResource(Res.string.weekday_date, days.getOrElse(com.rm.interchange.sim.Calendar.weekday(startYear, day)) { "" }, date(day, startYear))
}

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
                SquareButton(Glyph.Remove, stringResource(Res.string.close), 28.dp, onClick = onClose)
            }
            content()
        }
    }
}
