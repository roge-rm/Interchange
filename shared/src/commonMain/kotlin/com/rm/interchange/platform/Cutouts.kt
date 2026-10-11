package com.rm.interchange.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect

/** Where the cameras cut into the screen, in window pixels; empty where there are none. */
@Composable
expect fun cameraCutouts(): List<Rect>
