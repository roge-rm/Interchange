package com.rm.interchange.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.rm.interchange.res.Res
import com.rm.interchange.res.cargo_amounts
import com.rm.interchange.res.cargoes
import com.rm.interchange.sim.Cargo
import com.rm.interchange.sim.CargoTable
import org.jetbrains.compose.resources.stringArrayResource

/** The cargo of the game being shown. The table is built once with the game's roster, so it's safe to read anywhere. */
val LocalCargo = staticCompositionLocalOf { CargoTable.BASE }

/**
 * The names, amounts and colours of a game's cargo. The base pack's own cargo use the app's strings, so they stay
 * translated; a pack's, and a base one a pack renames, use the pack's names in [language], else English.
 */
class CargoText(private val table: CargoTable, private val language: String, private val names: List<String>, private val amounts: List<String>) {
    val size get() = table.size

    private fun own(c: Int): Boolean {
        val k = table.getOrNull(c) ?: return false
        return c < Cargo.COUNT && k.names == Cargo.BASE[c].names
    }

    /** [c]'s name. */
    operator fun get(c: Int): String =
        if (own(c)) names.getOrElse(c) { "" } else table.getOrNull(c)?.name(language) ?: ""

    fun getOrElse(c: Int, default: (Int) -> String): String = if (c in 0 until table.size) get(c) else default(c)

    /** How much of [c] [n] is, as "31 passengers" or "20 casks". */
    fun amount(c: Int, n: Int): String {
        val grouped = Numbers.grouped(n.toLong())
        val k = table.getOrNull(c) ?: return grouped
        if (own(c) && k.units == Cargo.BASE[c].units) return amounts.getOrElse(c) { "%d" }.replace("%d", grouped)
        return "$grouped ${k.unit(language)}".trim()
    }

    /** [c]'s colour, for a swatch or a bar. */
    fun colour(c: Int): Color = table.getOrNull(c)?.let { Color(0xFF000000.toInt() or it.colour) } ?: Color(0xFF9AA0A6)
}

@Composable
fun cargoNames(): CargoText {
    val table = LocalCargo.current
    val names = stringArrayResource(Res.array.cargoes)
    val amounts = stringArrayResource(Res.array.cargo_amounts)
    val lang = languageCode()
    return remember(table, names, amounts, lang) { CargoText(table, lang, names, amounts) }
}

/** [c]'s colour in the game being shown. */
@Composable
fun cargoColour(c: Int): Color = cargoNames().colour(c)

/** How much of [cargo] [n] is, as "31 passengers" or "20 t coal". */
@Composable
fun cargoAmount(cargo: Int, n: Int): String = cargoNames().amount(cargo, n)
