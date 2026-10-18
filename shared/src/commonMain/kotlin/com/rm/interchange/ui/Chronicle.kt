package com.rm.interchange.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.interchange.res.*
import com.rm.interchange.sim.News
import com.rm.interchange.sim.Story
import com.rm.interchange.sim.VehicleKind
import com.rm.interchange.sim.World
import com.rm.interchange.sim.crashFor
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** The news that makes the headlines; the rest is only in the full chronicle. */
private val HEADLINES = setOf(
    News.INDUSTRY_OPENS, News.INDUSTRY_CLOSES, News.WASHOUT, News.FIRE, News.AVALANCHE, News.SUBSIDY_AWARDED,
    News.COMPANY_FOR_SALE, News.COMPANY_BOUGHT, News.COMPANY_BANKRUPT, News.COMPANY_RESTARTED, News.NEW_VEHICLE,
    News.GOAL_MET, News.GOAL_FAILED, News.SCENARIO_WON, News.SCENARIO_LOST, News.CRASH,
)

/** The most months the chronicle shows at once, newest first. */
private const val MONTHS_SHOWN = 120

/** The years the papers change: a broadsheet gazette, then a tabloid, then a feed. */
private const val STAR_FROM = 1900
private const val FEED_FROM = 1970

/**
 * The story of the game, newest first, a month at a time under the masthead of the paper of its day, named after the
 * biggest town. A story with a place goes there.
 */
@Composable
fun ChronicleWindow(world: World, onGo: (Float, Float) -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    var everything by remember { mutableStateOf(false) }
    val months = stringArrayResource(Res.array.months)
    val town = world.towns.maxByOrNull { it.population }?.name ?: ""
    Window(stringResource(Res.string.chronicle), onClose, Glyph.Newspaper) {
        FillChips(listOf(false, true), everything, { stringResource(if (it) Res.string.chronicle_all else Res.string.chronicle_headlines) }) { everything = it }
        // The chronicle is replaced whole when the game adds to it, so this is a steady copy.
        val all = world.chronicle
        val told = remember(all, everything) {
            all.filter { everything || it.kind in HEADLINES }
                .groupBy { it.year * 12 + it.month }.entries.sortedByDescending { it.key }.take(MONTHS_SHOWN)
        }
        if (told.isEmpty()) Text(stringResource(Res.string.chronicle_empty), color = c.textDim, style = KitType.Body)
        for ((at, stories) in told) {
            // Some news is only told to the company it's about, so a month can have none to show.
            val lines = stories.asReversed().mapNotNull { t -> newsText(world, News(t.kind, t.about, 0)).takeIf { it.isNotEmpty() }?.let { t to it } }
            if (lines.isEmpty()) continue
            val year = at / 12
            val gazette = year < STAR_FROM
            val star = !gazette && year < FEED_FROM
            val paper = if (gazette) Res.string.paper_gazette else if (star) Res.string.paper_star else Res.string.paper_feed
            val family = if (gazette) FontFamily.Serif else FontFamily.SansSerif
            Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(paper, town).let { if (star) it.uppercase() else it },
                        color = c.text, style = KitType.ListLine, fontFamily = family,
                        fontWeight = if (gazette) FontWeight.Normal else FontWeight.Black,
                        fontStyle = if (gazette) FontStyle.Italic else FontStyle.Normal,
                    )
                    Text(stringResource(Res.string.month_year, months.getOrElse(at % 12) { "" }, year), color = c.textDim, style = KitType.Dim)
                }
                HorizontalDivider(color = c.textDim.copy(alpha = 0.4f))
                for ((t, text) in lines) {
                    val place = storyPlace(world, t)
                    val about = if (t.kind in COMPANY_NEWS) newsCompany(world, News(t.kind, t.about, 0)) else null
                    BulletRow(
                        if (star) text.uppercase() else text,
                        glyph = storyGlyph(world, t),
                        lead = about?.let { co -> { CompanyInsignia(co, 22.dp) } },
                        onClick = place?.let { (x, y) -> { onGo(x, y) } },
                        style = TextStyle(
                            fontFamily = family, fontSize = if (star) 13.sp else 14.sp,
                            fontWeight = if (star) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}

/** News about a company itself, shown with its insignia for a bullet. */
private val COMPANY_NEWS = setOf(
    News.COMPANY_TROUBLE, News.COMPANY_FOR_SALE, News.COMPANY_BOUGHT, News.COMPANY_BANKRUPT, News.COMPANY_RESTARTED, News.COMPANY_STARTED,
)

/** The bullet for story [t], by its kind. */
private fun storyGlyph(world: World, t: Story): Glyph = when (t.kind) {
    News.INDUSTRY_OPENS, News.INDUSTRY_CLOSES, News.PROSPECTING_FAILED -> Glyph.Factory
    News.PRODUCTION_UP -> Glyph.Up
    News.PRODUCTION_DOWN -> Glyph.Down
    News.SNOWBOUND -> Glyph.Snow
    News.TRAIN_OLD -> Glyph.Calendar
    News.TRAIN_STUCK -> Glyph.Rail
    News.WASHOUT -> Glyph.Water
    News.FIRE -> Glyph.Fire
    News.AVALANCHE -> Glyph.Heights
    News.SLID -> Glyph.Road
    News.DIVERTED -> Glyph.Air
    News.SUBSIDY_OFFERED, News.SUBSIDY_AWARDED -> Glyph.Money
    News.NEW_VEHICLE -> when (world.roster.getOrNull(t.about)?.kind) {
        VehicleKind.ROAD -> Glyph.Road
        VehicleKind.SHIP -> Glyph.Water
        VehicleKind.AIRCRAFT -> Glyph.Air
        null -> Glyph.Plus
        else -> Glyph.Rail
    }
    News.GOAL_MET, News.GOAL_FAILED -> world.goals.getOrNull(t.about)?.let { goalGlyph(it.kind) } ?: Glyph.Flag
    News.SCENARIO_WON -> Glyph.Trophy
    News.SCENARIO_LOST -> Glyph.Flag
    News.CRASH -> Glyph.Alert
    News.RENEW_FAILED -> Glyph.Renew
    else -> Glyph.Briefcase
}

/** Where on the map story [t] happened, or null if it's not about a place. */
private fun storyPlace(world: World, t: Story): Pair<Float, Float>? = when (t.kind) {
    News.INDUSTRY_OPENS, News.INDUSTRY_CLOSES, News.PRODUCTION_UP, News.PRODUCTION_DOWN ->
        world.industries.getOrNull(t.about)?.let { it.x + 1f to it.y + 1f }
    News.WASHOUT, News.FIRE, News.AVALANCHE -> t.about % world.width + 0.5f to t.about / world.width + 0.5f
    News.SUBSIDY_OFFERED, News.SUBSIDY_AWARDED -> subsidyStart(world, t.about)
    News.GOAL_MET, News.GOAL_FAILED -> goalPlace(world, t.about)
    News.CRASH -> world.crashFor(t.about)?.let { it.tile % world.width + 0.5f to it.tile / world.width + 0.5f }
    else -> null
}
