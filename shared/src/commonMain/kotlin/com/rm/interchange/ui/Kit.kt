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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.interchange.res.Res
import com.rm.interchange.res.close
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** A wide button for the screens and windows, with a glyph before its words. [primary] is the one most likely wanted. */
@Composable
fun BigButton(text: String, primary: Boolean = false, enabled: Boolean = true, glyph: Glyph? = null, onClick: () -> Unit) {
    val c = LocalColors.current
    val tint = if (primary) c.onAccent else c.text
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) c.accent else c.button)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        glyph?.let { GlyphIcon(it, tint, Modifier.size(20.dp)) }
        Text(text, color = tint, fontSize = 16.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/**
 * A window over whatever's behind it, which dims and closes the window when
 * tapped. Its title has a glyph and a close button. It keeps clear of the
 * camera cutout and the bars, and scrolls if the screen is short.
 */
@Composable
fun Window(title: String, onClose: () -> Unit, glyph: Glyph? = null, content: @Composable () -> Unit) {
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
                .widthIn(max = 480.dp)
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
                        title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    SquareButton(Glyph.Remove, stringResource(Res.string.close), 34.dp, onClick = onClose)
                }
                Column(Modifier.padding(top = 14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
            }
        }
    }
}

/** A small heading over a group of settings or choices, with a glyph. */
@Composable
fun Head(text: String, glyph: Glyph? = null) {
    val c = LocalColors.current
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        glyph?.let { GlyphIcon(it, c.accent, Modifier.size(15.dp)) }
        Text(
            text.uppercase(), color = c.textDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
            modifier = Modifier.semantics { heading(); contentDescription = text },
        )
    }
}

/**
 * A row for a list in a window: a glyph in a tile, or a company's [insignia] in its colour [tint], a title and a line
 * under it, and what can be done with it at the end.
 */
@Composable
fun ListRow(
    glyph: Glyph, title: String, line: String?, onClick: (() -> Unit)?, tint: Color? = null, insignia: ByteArray? = null,
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
        if (tint != null && insignia != null && com.rm.interchange.sim.Insignia.valid(insignia)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(c.page), contentAlignment = Alignment.Center) {
                InsigniaImage(insignia, tint, Modifier.size(32.dp))
            }
        } else Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(tint ?: c.accent), contentAlignment = Alignment.Center) {
            GlyphIcon(glyph, if (tint != null) Color.White else c.onAccent, Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (line != null) Text(line, color = c.textDim, fontSize = 13.sp)
        }
        end()
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
            Text(label, color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(shown, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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

/** One of a set across the whole width, each choice an equal share and all as tall as the tallest. */
@Composable
fun <T> FillChips(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) {
            val on = o == selected
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) c.accent else c.button)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(o) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(o), color = if (on) c.onAccent else c.text, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
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
        Text(text, color = if (on) c.onAccent else c.text, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
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
