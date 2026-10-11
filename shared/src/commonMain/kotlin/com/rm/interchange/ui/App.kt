package com.rm.interchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rm.interchange.map.MapView
import com.rm.interchange.map.View
import com.rm.interchange.server.Game
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.World

@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val game = remember { Game(World(64, 64, 1)) }
        val view = remember { View(game.world.width, game.world.height) }
        var frame by remember { mutableLongStateOf(0L) }
        var raising by remember { mutableStateOf(true) }

        // Runs the game at its tick rate off the display's frames.
        LaunchedEffect(game) {
            val tickNanos = 1_000_000_000L / Game.TICKS_PER_SECOND
            var last = withFrameNanos { it }
            var owed = 0L
            while (true) {
                withFrameNanos { now ->
                    owed = (owed + now - last).coerceAtMost(tickNanos * 10)
                    last = now
                    while (owed >= tickNanos) {
                        game.step()
                        owed -= tickNanos
                    }
                    frame++
                }
            }
        }

        Box(Modifier.fillMaxSize().background(Color(0xFF121417))) {
            MapView(game.world, view, frame, onTap = { x, y ->
                game.request(if (raising) Command.RaiseLand(0, x, y) else Command.LowerLand(0, x, y))
            }, modifier = Modifier.fillMaxSize())
            Row(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { view.turnBy(-1) }) { Text("Turn left") }
                Button(onClick = { raising = !raising }) { Text(if (raising) "Raise" else "Lower") }
                Button(onClick = { view.turnBy(1) }) { Text("Turn right") }
            }
        }
    }
}
