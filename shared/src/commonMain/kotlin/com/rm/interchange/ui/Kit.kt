package com.rm.interchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.interchange.audio.Sounds
import com.rm.interchange.res.Res
import com.rm.interchange.res.close
import com.rm.interchange.res.percent
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** A wide button for the screens and windows, with a glyph before its words. [primary] is the one most likely wanted. */
@Composable
fun BigButton(text: String, primary: Boolean = false, enabled: Boolean = true, glyph: Glyph? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalColors.current
    val tint = if (primary) c.onAccent else c.text
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) c.accent else c.button)
            .clickable(enabled = enabled, role = Role.Button) {
                Sounds.click()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        glyph?.let { GlyphIcon(it, tint, Modifier.size(20.dp)) }
        // Smaller words when a line would break inside a word, so a narrow button in a Grid wraps between words only.
        val style = KitType.Lead.copy(fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal)
        var scale by remember(text) { mutableFloatStateOf(1f) }
        var width by remember(text) { mutableIntStateOf(-1) }
        // Words aren't drawn while they're still shrinking, so a broken word never shows.
        var settled by remember(text) { mutableStateOf(false) }
        Text(
            text, color = tint, style = if (scale < 1f) style.copy(fontSize = style.fontSize * scale) else style,
            modifier = Modifier.drawWithContent { if (settled) drawContent() },
            onTextLayout = { r ->
                // A new width starts again from full size.
                val room = r.layoutInput.constraints.maxWidth
                val fresh = room != width
                width = room
                val shrink = scale > 0.7f && (0 until r.lineCount - 1).any { breaksWord(text, r.getLineEnd(it)) }
                if (fresh && scale < 1f) { scale = 1f; settled = false }
                else if (shrink) { scale = (scale - 0.05f).coerceAtLeast(0.7f); settled = false }
                else settled = true
            },
        )
    }
}

/** Whether a line ending at [end] in [text] breaks inside a word. */
private fun breaksWord(text: String, end: Int) =
    end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace() && text[end - 1] != '-'

/**
 * A window over whatever's behind it, which dims and closes the window when
 * tapped. Its title has a glyph and a close button. It keeps clear of the
 * camera cutout and the bars, and scrolls if the screen is short.
 */
@Composable
fun Window(title: String, onClose: () -> Unit, glyph: Glyph? = null, maxWidth: Dp = 480.dp, scrollable: Boolean = true, content: @Composable () -> Unit) {
    val c = LocalColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .background(Color(0x66000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                .clearAndSetSemantics { },
        )
        ChromeBox(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp)
                .widthIn(max = maxWidth)
                // Taps on the window stay in it.
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { paneTitle = title },
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    glyph?.let {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                            GlyphIcon(it, c.onAccent, Modifier.size(20.dp))
                        }
                    }
                    Text(
                        title, color = c.text, style = KitType.WindowTitle,
                        modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    SquareButton(Glyph.Remove, stringResource(Res.string.close), 34.dp, onClick = onClose)
                }
                Column(Modifier.padding(top = 14.dp).then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
            }
        }
    }
}

/** A small heading in capitals over a group of rows, settings or choices, with a glyph. See KitInfo.kt for the rest of the kit. */
@Composable
fun Head(text: String, glyph: Glyph? = null) {
    val c = LocalColors.current
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        glyph?.let { GlyphIcon(it, c.accent, Modifier.size(15.dp)) }
        Text(
            text.uppercase(), color = c.textDim, style = KitType.Head,
            modifier = Modifier.semantics { heading(); contentDescription = text },
        )
    }
}

/**
 * A row for a list in a window: a glyph in a tile, or a company's [insignia] in its colour [tint], a title and a line
 * under it, and what can be done with it at the end. [lineColour] draws the line in that colour, dim if it's left out.
 */
@Composable
fun ListRow(
    glyph: Glyph, title: String, line: String?, onClick: (() -> Unit)?, tint: Color? = null, insignia: ByteArray? = null,
    lineColour: Color? = null,
    end: @Composable () -> Unit = {},
) {
    val c = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.button)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Badge(glyph, tint, insignia)
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, style = KitType.ListTitle)
            if (line != null) Text(line, color = lineColour ?: c.textDim, style = KitType.ListLine)
        }
        end()
    }
}

/**
 * A tile [size] across with a company's [insignia] in its colour [tint], or [glyph] on [tint] (the accent if none)
 * for a company with no insignia or a row that isn't a company's.
 */
@Composable
fun Badge(glyph: Glyph, tint: Color? = null, insignia: ByteArray? = null, size: androidx.compose.ui.unit.Dp = 36.dp) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(size * 0.22f)
    if (tint != null && insignia != null && com.rm.interchange.sim.Insignia.valid(insignia)) {
        Box(Modifier.size(size).clip(shape).background(c.page), contentAlignment = Alignment.Center) {
            InsigniaImage(insignia, tint, Modifier.size(size * 0.89f))
        }
    } else Box(Modifier.size(size).clip(shape).background(tint ?: c.accent), contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, if (tint != null) Color.White else c.onAccent, Modifier.size(size / 2))
    }
}

/** A labelled slider that stops at each of [options], the one chosen named beside the label. */
@Composable
fun <T> StepSlider(label: String, options: List<T>, value: T, name: @Composable (T) -> String, set: (T) -> Unit) {
    val c = LocalColors.current
    val at = options.indexOf(value).coerceAtLeast(0)
    val shown = name(value)
    Column {
        // The slider reads out its name and value itself, so this line is for the eyes only.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.text, style = KitType.Label, modifier = Modifier.weight(1f))
            Text(shown, color = c.text, style = KitType.Label, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = at.toFloat(),
            onValueChange = { v -> options.getOrNull(v.roundToInt())?.let { if (it != value) set(it) } },
            valueRange = 0f..(options.size - 1).toFloat(),
            steps = (options.size - 2).coerceAtLeast(0),
            modifier = Modifier.semantics { contentDescription = label; stateDescription = shown },
            colors = SliderDefaults.colors(
                thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.button,
                activeTickColor = c.onAccent, inactiveTickColor = c.textDim,
            ),
        )
    }
}

/** A level from 0 to 100. [set] follows the finger and [settled] runs when it lifts. */
@Composable
fun VolumeSlider(label: String, value: Int, set: (Int) -> Unit, settled: () -> Unit) {
    val c = LocalColors.current
    val clamped = value.coerceIn(0, 100)
    val shown = stringResource(Res.string.percent, clamped)
    var dragging by remember { mutableStateOf(false) }
    var dragged by remember { mutableFloatStateOf(clamped / 100f) }
    val position = if (dragging) dragged else clamped / 100f
    Column {
        // The slider reads out its name and value itself, so this line is for the eyes only.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.text, style = KitType.Label, modifier = Modifier.weight(1f))
            Text(shown, color = c.text, style = KitType.Value)
        }
        Slider(
            value = position,
            onValueChange = { v ->
                dragging = true
                dragged = v
                val n = (v * 100f).roundToInt().coerceIn(0, 100)
                if (n != clamped) set(n)
            },
            onValueChangeFinished = {
                dragging = false
                settled()
            },
            modifier = Modifier.semantics { contentDescription = label; stateDescription = shown },
            colors = SliderDefaults.colors(
                thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.button,
            ),
        )
    }
}

/**
 * [StepSlider] on one line, for the tool tray: [glyph], the slider, then the one chosen named at the end. The slider
 * reads out [label] and the value.
 */
@Composable
fun <T> StepSliderRow(glyph: Glyph, label: String, options: List<T>, value: T, name: @Composable (T) -> String, modifier: Modifier = Modifier, set: (T) -> Unit) {
    val c = LocalColors.current
    val at = options.indexOf(value).coerceAtLeast(0)
    val shown = name(value)
    Row(modifier.fillMaxWidth().height(34.dp).padding(start = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlyphIcon(glyph, c.textDim, Modifier.size(20.dp).clearAndSetSemantics { })
        Slider(
            value = at.toFloat(),
            onValueChange = { v -> options.getOrNull(v.roundToInt())?.let { if (it != value) set(it) } },
            valueRange = 0f..(options.size - 1).toFloat(),
            steps = (options.size - 2).coerceAtLeast(0),
            modifier = Modifier.weight(1f).semantics { contentDescription = label; stateDescription = shown },
            colors = SliderDefaults.colors(
                thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.button,
                activeTickColor = c.onAccent, inactiveTickColor = c.textDim,
            ),
        )
        // As wide as the widest name, so the slider keeps its length as the value changes.
        Box(Modifier.clearAndSetSemantics { }, contentAlignment = Alignment.CenterEnd) {
            for (o in options) Text(name(o), style = KitType.Value, maxLines = 1, modifier = Modifier.alpha(0f))
            Text(shown, color = c.text, style = KitType.Value, maxLines = 1)
        }
    }
}

/** One of a set across the whole width, sized in proportion to its label and all as tall as the tallest. */
@Composable
fun <T> FillChips(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) {
            val on = o == selected
            // Give longer labels more room while keeping the entire set within the row.
            val text = label(o)
            val width = text.length.coerceAtLeast(1).toFloat()
            Box(
                Modifier.weight(width).fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) c.accent else c.button)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(o) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, color = if (on) c.onAccent else c.text, style = KitType.Body, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

/** A choice that's on or off, lit when on. */
@Composable
fun TogglePill(text: String, on: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val c = LocalColors.current
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(if (on) c.accent else c.button)
            .semantics(mergeDescendants = true) {}
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (on) c.onAccent else c.text, style = KitType.Body, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** [first] and [second] side by side, an equal share each, when [wide]; else one above the other. */
@Composable
fun SideBySide(wide: Boolean, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { first() }
        Box(Modifier.weight(1f)) { second() }
    } else {
        first()
        second()
    }
}

/**
 * A card cut down to a [list] and the [controls] under it, for while the map is being tapped, like adding stops. It's
 * only as tall as it needs, and the list scrolls once it's taller than [listMax], a share of a short screen or the
 * room left. It scrolls to its end when it opens and each time [follow] changes, so what was just added is in view.
 */
@Composable
fun SlimCard(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, follow: Any? = null, listMax: Dp = 150.dp, list: @Composable () -> Unit, controls: @Composable () -> Unit) {
    val c = LocalColors.current
    val scroll = rememberScrollState()
    LaunchedEffect(follow) {
        // After the list is laid out, so its new end is known.
        withFrameNanos { }
        scroll.animateScrollTo(scroll.maxValue)
    }
    ChromeBox(modifier) {
        Column(Modifier.padding(KitSpace.Card), verticalArrangement = Arrangement.spacedBy(KitSpace.Gap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.text, style = KitType.CardTitle, modifier = Modifier.weight(1f), maxLines = 1)
                SquareButton(Glyph.Remove, stringResource(Res.string.close), 28.dp, onClick = onClose)
            }
            // On a short screen, like a phone on its side, the list gets less, so most of the map stays clear.
            val screen = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
            Column(
                Modifier.weight(1f, fill = false).heightIn(max = minOf(listMax, maxOf(screen * 0.22f, 110.dp))).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(KitSpace.Gap),
            ) { list() }
            controls()
        }
    }
}
