package com.rm.interchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.interchange.map.Atlas
import com.rm.interchange.map.Sheets
import com.rm.interchange.res.Res
import com.rm.interchange.res.cargoes
import com.rm.interchange.res.percent
import com.rm.interchange.sim.Engine
import com.rm.interchange.sim.IndustryKind
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The kit for laying out what windows and cards show, so every one looks alike. Use only these, with KitType for
 * text and KitSpace for gaps, rather than sizing text or padding by hand.
 *
 * - Head (Kit.kt): small capitals over each group of rows. Every group of facts gets one.
 * - InfoRow: a label with its value right-aligned, and a glyph before it if it helps. For single facts:
 *   temperature, money, a date, a count.
 * - StatColumns: a few short facts side by side, each a dim label over its value, lined up from row to row. For
 *   numbers read together: speed, capacity, power, or a company's value, money and loan.
 * - CargoRow: a cargo's colour, its name and an amount right-aligned, with an optional bar and percent. For what a
 *   station has waiting, what an industry makes or a vehicle carries.
 * - FillBar and FillBarRow: a bar filled to a share, coloured from bad to good. For ratings, % carried, approval.
 * - PictureRow: a picture in a box, a title with a value at its end, a dim line, a stats line and anything under
 *   them, tappable and lit when selected. For lists of vehicles, industries, buildings and anything else with a
 *   picture. ListRow (Kit.kt) is the same for a glyph instead of a picture.
 * - VehiclePicture, IndustryPicture, HousePicture: the game's drawings, for a PictureRow's picture.
 * - OptionGroup: one of a few choices, each a glyph with a short label, across the width. For tray choices like
 *   town size, road surface or signal kind.
 * - SegmentedChoice: one of many choices as even segments, wrapped into rows. For cargo choices.
 * - FillChips, StepSlider, TogglePill, BigButton (Kit.kt): one of a few words, a stepped value, on or off, and a
 *   window's main actions.
 */

/** The type scale. Colours come from [LocalColors]: [Colors.text] for titles and values, [Colors.textDim] for the rest. */
object KitType {
    /** A window's title. */
    val WindowTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    /** A card's title, over the map. */
    val CardTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    /** A [Head], written in capitals. */
    val Head = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    /** The title of a row in a list. */
    val RowTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    /** Labels and plain text. */
    val Body = TextStyle(fontSize = 14.sp)
    /** A value, with figures of one width so amounts line up. */
    val Value = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    /** A row's second lines and stats. */
    val Dim = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum")
    /** Labels over values in [StatColumns], percents by a bar. */
    val Small = TextStyle(fontSize = 11.sp, fontFeatureSettings = "tnum")
}

/** Paddings, gaps and sizes. */
object KitSpace {
    /** Inside a card or window's edge. */
    val Card = 10.dp
    /** Inside a row with a background, across and down. */
    val RowAcross = 8.dp
    val RowDown = 6.dp
    /** Between rows, and between a row's parts side by side. */
    val Gap = 6.dp
    val Inline = 10.dp
    /** Between the lines in a row. */
    val Tight = 2.dp
    /** Rounded corners of a row, and of what's in it. */
    val Corner = 10.dp
    val InnerCorner = 8.dp
    /** A glyph in a row. */
    val Glyph = 16.dp
    /** A picture's box in a [PictureRow]. */
    val PictureWidth = 76.dp
    val PictureHeight = 52.dp
    /** A bar in a [CargoRow] and its percent beside it. */
    val BarWidth = 48.dp
    val PercentWidth = 36.dp
}

/** [label] with [value] right-aligned, and [glyph] before it; [valueColour] for a value to stand out, like a warning. */
@Composable
fun InfoRow(label: String, value: String, glyph: Glyph? = null, valueColour: Color? = null, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KitSpace.Gap),
    ) {
        glyph?.let { GlyphIcon(it, c.textDim, Modifier.size(KitSpace.Glyph)) }
        Text(label, color = c.textDim, style = KitType.Body, modifier = Modifier.weight(1f))
        Text(value, color = valueColour ?: c.text, style = KitType.Value, textAlign = TextAlign.End)
    }
}

/**
 * [stats] side by side, each a label over its value, in [columns] equal shares so rows of them line up; blank shares
 * fill out a short row.
 */
@Composable
fun StatColumns(stats: List<Pair<String, String>>, columns: Int = stats.size, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KitSpace.Gap)) {
        for ((label, value) in stats) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(label, color = c.textDim, style = KitType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, color = c.text, style = KitType.Value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        repeat(columns - stats.size) { Box(Modifier.weight(1f)) }
    }
}

/** Each cargo's colour, by its number, for its swatch. */
private val CARGO_COLOURS = listOf(
    Color(0xFF6FA8DC), // passengers
    Color(0xFFE8E6E1), // mail
    Color(0xFF8B5A2B), // timber
    Color(0xFFD9B26F), // lumber
    Color(0xFFE6C84F), // grain
    Color(0xFF3A3A3A), // coal
    Color(0xFFA0522D), // iron ore
    Color(0xFF8A9BA8), // steel
    Color(0xFFC77DBB), // goods
    Color(0xFF222831), // oil
    Color(0xFFE07B39), // fuel
    Color(0xFFB5835A), // livestock
    Color(0xFF7CC36E), // food
    Color(0xFF5FB3B3), // fish
    Color(0xFFF2EEDC), // paper
    Color(0xFFD96C6C), // potash
    Color(0xFF7D8F69), // nickel ore
    Color(0xFFB8C2C8), // nickel
    Color(0xFFC2603A), // bauxite
    Color(0xFFD5DDE3), // aluminium
)

/** [cargo]'s colour, for a swatch or a bar. */
fun cargoColour(cargo: Int): Color = CARGO_COLOURS.getOrElse(cargo) { Color(0xFF9AA0A6) }

/** A small square in [cargo]'s colour, edged so dark ones show on the panel. */
@Composable
fun CargoSwatch(cargo: Int, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(3.dp)
    Box(modifier.size(12.dp).clip(shape).background(cargoColour(cargo)).border(1.dp, c.chromeEdge, shape))
}

/**
 * A cargo's swatch and name, with [amount] right-aligned, and when [share] is given a bar filled to it with [percent]
 * beside. [name] is the cargo's own unless given.
 */
@Composable
fun CargoRow(cargo: Int, amount: String, share: Float? = null, percent: String? = null, name: String? = null, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val names = stringArrayResource(Res.array.cargoes)
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KitSpace.Gap),
    ) {
        CargoSwatch(cargo)
        Text(name ?: names.getOrElse(cargo) { "" }, color = c.text, style = KitType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(amount, color = c.text, style = KitType.Value, maxLines = 1, textAlign = TextAlign.End)
        if (share != null) {
            FillBar(share, Modifier.width(KitSpace.BarWidth))
            Text(percent ?: stringResource(Res.string.percent, (share.coerceIn(0f, 1f) * 100).roundToInt()), color = c.textDim, style = KitType.Small, textAlign = TextAlign.End, modifier = Modifier.width(KitSpace.PercentWidth))
        }
    }
}

/** A bar filled to [share], 0 to 1, red when low, amber in the middle and green when high, unless [colour] is given. */
@Composable
fun FillBar(share: Float, modifier: Modifier = Modifier, colour: Color? = null) {
    val c = LocalColors.current
    val s = share.coerceIn(0f, 1f)
    val fill = colour ?: when {
        s < 1f / 3 -> c.bad
        s < 2f / 3 -> c.warn
        else -> c.good
    }
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.button)) {
        if (s > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(s).clip(RoundedCornerShape(3.dp)).background(fill))
    }
}

/** [label] over a bar filled to [share], with [text] (the share as a percent unless given) right-aligned beside the label. */
@Composable
fun FillBarRow(label: String, share: Float, text: String? = null, glyph: Glyph? = null, colour: Color? = null) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        InfoRow(label, text ?: stringResource(Res.string.percent, (share.coerceIn(0f, 1f) * 100).roundToInt()), glyph)
        FillBar(share, Modifier.fillMaxWidth(), colour)
    }
}

/**
 * A row for a list with a picture: [picture] in a box, [title] with [titleEnd] (a price, say) at its end, a dim [line]
 * with [lineEnd], a [stats] line, [end] at the far right, and [below] under it all. Lit when [selected]; tapping does
 * [onClick], as a choice of one when [role] is a radio button.
 */
@Composable
fun PictureRow(
    title: String,
    modifier: Modifier = Modifier,
    picture: (@Composable (Modifier) -> Unit)? = null,
    titleEnd: String? = null,
    line: String? = null,
    lineEnd: String? = null,
    stats: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    role: Role = Role.Button,
    end: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(KitSpace.Corner)
    val tap = when {
        onClick == null -> Modifier
        role == Role.RadioButton -> Modifier.selectable(selected = selected, role = role, onClick = onClick)
        else -> Modifier.clickable(role = role, onClick = onClick)
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) c.accent.copy(alpha = 0.16f) else c.button)
            .border(2.dp, if (selected) c.accent else c.button, shape)
            .then(tap)
            .padding(horizontal = KitSpace.RowAcross, vertical = KitSpace.RowDown),
        verticalArrangement = Arrangement.spacedBy(KitSpace.Gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitSpace.Inline)) {
            if (picture != null) PictureBox { picture(Modifier.fillMaxSize()) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = c.text, style = KitType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (titleEnd != null) Text(titleEnd, color = c.text, style = KitType.Value, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                }
                if (line != null || lineEnd != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line ?: "", color = c.textDim, style = KitType.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (lineEnd != null) Text(lineEnd, color = c.textDim, style = KitType.Dim, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                }
                if (!stats.isNullOrEmpty()) Text(stats, color = c.text, style = KitType.Dim, maxLines = 2)
            }
            end?.invoke()
        }
        below?.invoke()
    }
}

/** The box a picture sits in, on the page's colour. */
@Composable
fun PictureBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalColors.current
    Box(
        modifier.size(KitSpace.PictureWidth, KitSpace.PictureHeight).clip(RoundedCornerShape(KitSpace.InnerCorner)).background(c.page).padding(2.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * One of [options] across the width, each a glyph with its short [label] under it, the [selected] one lit. With
 * [labels] off only the glyphs show, the label read out instead.
 */
@Composable
fun <T> OptionGroup(options: List<T>, selected: T, glyph: (T) -> Glyph, label: (T) -> String, labels: Boolean = true, onSelect: (T) -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(KitSpace.Gap)) {
        for (o in options) {
            val on = o == selected
            val tint = if (on) c.onAccent else c.text
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 34.dp)
                    .clip(RoundedCornerShape(KitSpace.InnerCorner))
                    .background(if (on) c.accent else c.button)
                    .then(if (labels) Modifier else Modifier.semantics { contentDescription = label(o) })
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(o) }
                    .padding(horizontal = 4.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                GlyphIcon(glyph(o), tint, Modifier.size(if (labels) 20.dp else 24.dp))
                if (labels) Text(label(o), color = tint, style = KitType.Small, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

/** One of [options] as segments of one size, in as few even rows of up to [perRow] as they need, the [selected] one lit. */
@Composable
fun <T> SegmentedChoice(options: List<T>, selected: T, label: (T) -> String, perRow: Int = 4, onSelect: (T) -> Unit) {
    val c = LocalColors.current
    if (options.isEmpty()) return
    val rows = (options.size + perRow - 1) / perRow
    val across = (options.size + rows - 1) / rows
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(KitSpace.InnerCorner)).background(c.page).padding(2.dp).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (row in options.chunked(across)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (o in row) {
                    val on = o == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (on) c.accent else c.page)
                            .selectable(selected = on, role = Role.RadioButton) { onSelect(o) }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label(o), color = if (on) c.onAccent else c.text, fontSize = 13.sp, maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
                // Blanks keep a short last row's segments the size of the rest.
                repeat(across - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** The game's sheets, for drawing pictures in windows; null outside a game. */
val LocalSheets = staticCompositionLocalOf<Sheets?> { null }

/** The drawing of each vehicle shown in a picture: facing down and to the left, three quarters on. */
private const val PICTURE_CELL = 2

/** The smallest sheet level, a halving each, still at least as big as a sprite [w0] by [h0] at level 0 is drawn. */
private fun DrawScope.levelFor(w0: Int, h0: Int, least: Int = 0): Pair<Int, Float> {
    val scale = min(size.width / w0, size.height / h0)
    var level = least
    while (level + 1 < Atlas.LEVELS && scale * (1 shl (level + 1)) <= 1f) level++
    return level to scale
}

/** The part of [image] at ([x], [y]) sized [w0] by [h0] at level 0, drawn at [level] scaled by [scale] and centred. */
private fun DrawScope.drawSprite(image: ImageBitmap, x: Int, y: Int, w0: Int, h0: Int, level: Int, scale: Float) {
    val shrink = 1 shl level
    val sw = w0 / shrink
    val sh = h0 / shrink
    if (sw == 0 || sh == 0) return
    val dw = (w0 * scale).toInt()
    val dh = (h0 * scale).toInt()
    drawImage(
        image, IntOffset(x / shrink, y / shrink), IntSize(sw, sh),
        IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()), IntSize(dw, dh),
        filterQuality = FilterQuality.Medium,
    )
}

/** [e]'s drawing from the map's sheets, scaled to fit the box and centred; blank until its sheet is unpacked. */
@Composable
fun VehiclePicture(e: Engine, modifier: Modifier) {
    val sheets = LocalSheets.current
    Canvas(modifier) {
        sheets ?: return@Canvas
        // Read here so the picture draws again once the sheet below is unpacked.
        sheets.revision.intValue
        val rects = sheets.vehicleRects(e.sprite) ?: return@Canvas
        val r = PICTURE_CELL * 6
        val w0 = rects[r + 2]
        val h0 = rects[r + 3]
        if (w0 == 0 || h0 == 0) return@Canvas
        val (level, scale) = levelFor(w0, h0)
        val image = sheets.vehicle(e.sprite, level) ?: return@Canvas
        drawSprite(image, rects[r], rects[r + 1], w0, h0, level, scale)
    }
}

/** Static sprite [sprite] (a place in [Atlas.STATIC_RECTS]), scaled to fit the box and centred; blank until its sheet is unpacked. */
@Composable
fun StaticPicture(sprite: Int, modifier: Modifier) {
    val sheets = LocalSheets.current
    Canvas(modifier) {
        sheets ?: return@Canvas
        sheets.revision.intValue
        if (sprite < 0 || sprite * 6 + 3 >= Atlas.STATIC_RECTS.size) return@Canvas
        val r = sprite * 6
        val w0 = Atlas.STATIC_RECTS[r + 2]
        val h0 = Atlas.STATIC_RECTS[r + 3]
        if (w0 == 0 || h0 == 0) return@Canvas
        // Level 2 at the sharpest, as Sheets keeps it for pictures.
        val (level, scale) = levelFor(w0, h0, least = 2)
        val image = sheets.staticImage(level) ?: return@Canvas
        drawSprite(image, Atlas.STATIC_RECTS[r], Atlas.STATIC_RECTS[r + 1], w0, h0, level, scale)
    }
}

/** The view edge pictures of buildings face, from [Atlas.PART] and [Atlas.HOUSE]'s four. */
private const val PICTURE_FACING = 3

/** An industry of [kind], as the biggest drawn piece of its layout; blank if none of its pieces are drawn. */
@Composable
fun IndustryPicture(kind: IndustryKind, modifier: Modifier) {
    val sprite = remember(kind) {
        kind.layout.flatten().distinct()
            .map { Atlas.PART_KEYS.indexOf(it) }
            .filter { it >= 0 }
            .map { Atlas.PART[it * 4 + PICTURE_FACING] }
            .maxByOrNull { Atlas.STATIC_RECTS[it * 6 + 2] * Atlas.STATIC_RECTS[it * 6 + 3] } ?: -1
    }
    StaticPicture(sprite, modifier)
}

/** A town building, by its key in the base pack's houses.json; blank for one the game has no drawing of. */
@Composable
fun HousePicture(key: String, modifier: Modifier) {
    val at = Atlas.HOUSE_KEYS.indexOf(key.removePrefix("base:"))
    StaticPicture(if (at < 0) -1 else Atlas.HOUSE[at * 4 + PICTURE_FACING], modifier)
}
