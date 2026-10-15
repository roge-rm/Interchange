package com.rm.interchange.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.interchange.res.*
import com.rm.interchange.sim.News
import com.rm.interchange.sim.Story
import com.rm.interchange.sim.World
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** The news that makes the headlines; the rest is only in the full chronicle. */
private val HEADLINES = setOf(
    News.INDUSTRY_OPENS, News.INDUSTRY_CLOSES, News.WASHOUT, News.FIRE, News.AVALANCHE, News.SUBSIDY_AWARDED,
    News.COMPANY_FOR_SALE, News.COMPANY_BOUGHT, News.COMPANY_BANKRUPT, News.COMPANY_RESTARTED, News.NEW_VEHICLE,
    News.GOAL_MET, News.GOAL_FAILED,
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
    Window(stringResource(Res.string.chronicle), onClose, Glyph.Folder) {
        FillChips(listOf(false, true), everything, { stringResource(if (it) Res.string.chronicle_all else Res.string.chronicle_headlines) }) { everything = it }
        // The chronicle is replaced whole when the game adds to it, so this is a steady copy.
        val all = world.chronicle
        val told = remember(all, everything) {
            all.filter { everything || it.kind in HEADLINES }
                .groupBy { it.year * 12 + it.month }.entries.sortedByDescending { it.key }.take(MONTHS_SHOWN)
        }
        if (told.isEmpty()) Text(stringResource(Res.string.chronicle_empty), color = c.textDim, fontSize = 14.sp)
        for ((at, stories) in told) {
            val year = at / 12
            val gazette = year < STAR_FROM
            val star = !gazette && year < FEED_FROM
            val paper = if (gazette) Res.string.paper_gazette else if (star) Res.string.paper_star else Res.string.paper_feed
            val family = if (gazette) FontFamily.Serif else FontFamily.SansSerif
            Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(paper, town).let { if (star) it.uppercase() else it },
                        color = c.text, fontSize = 13.sp, fontFamily = family,
                        fontWeight = if (gazette) FontWeight.Normal else FontWeight.Black,
                        fontStyle = if (gazette) FontStyle.Italic else FontStyle.Normal,
                    )
                    Text(stringResource(Res.string.month_year, months.getOrElse(at % 12) { "" }, year), color = c.textDim, fontSize = 12.sp)
                }
                HorizontalDivider(color = c.textDim.copy(alpha = 0.4f))
                for (t in stories.asReversed()) {
                    val text = newsText(world, News(t.kind, t.about, 0))
                    if (text.isEmpty()) continue
                    val place = storyPlace(world, t)
                    val about = newsCompany(world, News(t.kind, t.about, 0))
                    if (about != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompanyInsignia(about, 24.dp)
                        Text(
                            when {
                                gazette -> text
                                star -> text.uppercase()
                                else -> text
                            },
                            color = c.text, fontFamily = family,
                            fontSize = if (star) 13.sp else 14.sp,
                            fontWeight = if (star) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                        )
                    } else Text(
                        when {
                            gazette -> text
                            star -> text.uppercase()
                            else -> "• $text"
                        },
                        color = c.text, fontFamily = family,
                        fontSize = if (star) 13.sp else 14.sp,
                        fontWeight = if (star) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (place != null) Modifier.clickable(role = Role.Button) { onGo(place.first, place.second) } else Modifier)
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** Where on the map story [t] happened, or null if it's not about a place. */
private fun storyPlace(world: World, t: Story): Pair<Float, Float>? = when (t.kind) {
    News.INDUSTRY_OPENS, News.INDUSTRY_CLOSES, News.PRODUCTION_UP, News.PRODUCTION_DOWN ->
        world.industries.getOrNull(t.about)?.let { it.x + 1f to it.y + 1f }
    News.WASHOUT, News.FIRE, News.AVALANCHE -> t.about % world.width + 0.5f to t.about / world.width + 0.5f
    News.SUBSIDY_OFFERED, News.SUBSIDY_AWARDED -> subsidyStart(world, t.about)
    News.GOAL_MET, News.GOAL_FAILED -> goalPlace(world, t.about)
    else -> null
}
