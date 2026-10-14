package com.rm.interchange.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Every colour the interface uses, by role, from Infill's palette. Screens
 * read these through [LocalColors] and never write a colour of their own.
 */
@Immutable
data class Colors(
    /** Behind the map, past its edges. */
    val page: Color,
    /** The bars and panels over the map. */
    val chrome: Color,
    val chromeEdge: Color,
    val button: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val onAccent: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
)

val DarkColors = Colors(
    page = Color(0xFF121417),
    chrome = Color(0xEB1C1F24),
    chromeEdge = Color(0xFF30353D),
    button = Color(0xFF2A2E35),
    text = Color(0xFFE8E6E1),
    textDim = Color(0xFF9AA0A6),
    accent = Color(0xFFF0B429),
    onAccent = Color(0xFF1C1F24),
    good = Color(0xFF4CB86A),
    warn = Color(0xFFE8A33A),
    bad = Color(0xFFE05A4E),
)

val LocalColors = staticCompositionLocalOf { DarkColors }

/** The company played on this screen, whose orders the cards give; -1 watching someone else's game. */
val LocalCompany = staticCompositionLocalOf { 0 }
