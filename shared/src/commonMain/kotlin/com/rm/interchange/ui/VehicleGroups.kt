package com.rm.interchange.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rm.interchange.res.*
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.WorldView
import com.rm.interchange.sim.capGroupName
import com.rm.interchange.sim.cleanGroupName
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun VehicleGroupControls(view: WorldView, kind: Int, vehicle: Int, group: Int, send: (Command) -> Unit, join: (Int) -> Unit) {
    val me = LocalCompany.current
    val groups = view.groups.getOrNull(me)?.getOrNull(kind).orEmpty().sortedBy { it.id }
    FillChips(listOf(-1) + groups.map { it.id }, group, { id ->
        if (id == -1) stringResource(Res.string.no_group) else groups.first { it.id == id }.name
    }, join)
    var text by remember(me, kind, vehicle) { mutableStateOf("") }
    val clean = cleanGroupName(text)
    val ok = clean.isNotEmpty() && groups.none { it.name.equals(clean, ignoreCase = true) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            Field(text, stringResource(Res.string.vehicle_group), { text = capGroupName(it) })
        }
        ActionButton(stringResource(Res.string.new_group), enabled = ok, strong = true) {
            send(Command.CreateGroup(me, kind, vehicle, clean))
            text = ""
        }
    }
}
