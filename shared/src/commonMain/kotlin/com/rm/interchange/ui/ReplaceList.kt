package com.rm.interchange.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.rm.interchange.res.*
import com.rm.interchange.sim.Engine
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ReplaceList(models: List<Engine>, funds: Long, costOf: (Engine) -> Long, blocked: (Engine) -> Boolean = { false }, replace: (Int) -> Unit) {
    val lang = languageCode()
    for (e in models) {
        val cost = costOf(e)
        val short = funds < cost
        val enabled = !short && !blocked(e)
        PictureRow(
            e.name(lang), modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
            picture = { VehiclePicture(e, it) }, titleEnd = money(cost),
            onClick = if (enabled) { { replace(e.id) } } else null,
            below = if (short) {
                { Text(stringResource(Res.string.not_enough_money), color = LocalColors.current.warn, style = KitType.Dim) }
            } else null,
        )
    }
}
