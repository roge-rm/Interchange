package com.rm.interchange.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import com.rm.interchange.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * Where key presses go, on the UI thread. Esc closes the top window; with a window open only pause and speed work,
 * and the rest go to the game screen's [game], which says whether it used them.
 */
object KeyInput {
    /** Each open window's close, the top one last. */
    private val windows = ArrayList<State<() -> Unit>>()
    /** The game screen's handler, while it shows. */
    var game: ((KeyAction) -> Boolean)? = null
    /** The [KeyAction.held] actions whose keys are down now. */
    val held = HashSet<KeyAction>()
    /** The keys down now, to tell a key held from one pressed again, and those whose press was used. */
    private val down = HashSet<String>()
    private val taken = HashSet<String>()
    /** Text fields with focus. Keys do nothing while there's one. */
    internal var typing = 0

    val windowOpen get() = windows.isNotEmpty()

    /** Whether the app's outermost box has focus itself, not a button or field in it. */
    internal var rootFocused = false
    /** Gives focus back to the outermost box. */
    internal var focusRoot: (() -> Unit)? = null
    /** Whether the gamepad's A went to the map when pressed. */
    private var tapOnMap = false

    /** Whether a gamepad's d-pad and A work the map cursor, rather than moving focus and pressing. */
    val cursorMode get() = rootFocused && windows.isEmpty() && game != null && typing == 0

    fun handle(event: KeyEvent): Boolean {
        var name = KEY_NAMES[event.key] ?: return false
        if (name in ARROWS && event.fromGamepad()) name = KeyMap.PAD + name
        val action = KeyMap.action(name) ?: return false
        val pad = KeyMap.isPad(name)
        if (pad) Gamepad.used()
        // The d-pad moves focus, as Compose does with it, unless the map cursor's out.
        val step = action == KeyAction.CursorUp || action == KeyAction.CursorDown || action == KeyAction.CursorLeft || action == KeyAction.CursorRight
        if (step && !cursorMode) return false
        if (action == KeyAction.Tap) return tap(event)
        if (event.type == KeyEventType.KeyUp) {
            down.remove(name)
            held.remove(action)
            // A key let go of is taken if its press was, so the system doesn't act on it as well, as Esc for back.
            return taken.remove(name)
        }
        if (event.type != KeyEventType.KeyDown) return false
        val again = !down.add(name)
        // A key held down repeats only for zooming and stepping the cursor.
        if (again && action != KeyAction.ZoomIn && action != KeyAction.ZoomOut && !step) return name in taken
        if (!again && (name == "pad_l2" || name == "pad_r2") && !Gamepad.triggerTurn(name == "pad_r2")) {
            taken.add(name)
            return true
        }
        val used = if (pad && action == KeyAction.Close && windows.isEmpty() && !rootFocused) {
            // B out of the tool bar gives the map back.
            focusRoot?.invoke()
            true
        } else !(event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) && press(action)
        if (used) taken.add(name) else taken.remove(name)
        return used
    }

    /** A gamepad's A: a tap at the cursor while it's out, otherwise a press of what has focus. */
    private fun tap(event: KeyEvent): Boolean {
        val name = "pad_a"
        if (event.type == KeyEventType.KeyDown) {
            if (!down.add(name)) return true
            tapOnMap = cursorMode
            if (tapOnMap) Gamepad.map?.press() else Gamepad.pressFocused?.invoke(true)
            return true
        }
        if (event.type != KeyEventType.KeyUp) return false
        if (!down.remove(name)) return true
        if (tapOnMap) Gamepad.map?.release() else Gamepad.pressFocused?.invoke(false)
        tapOnMap = false
        return true
    }

    /** An action from a gamepad's stick or trigger. */
    fun pad(action: KeyAction): Boolean = press(action)

    private fun press(action: KeyAction): Boolean {
        if (typing > 0) return false
        if (action == KeyAction.Close && windows.isNotEmpty()) {
            windows.last().value()
            return true
        }
        if (windows.isNotEmpty() && !action.overWindows) return false
        val g = game ?: return false
        if (action.held) {
            held.add(action)
            return true
        }
        return g(action)
    }

    /** Lets go of every key, as when focus goes elsewhere. */
    fun release() {
        held.clear()
        down.clear()
        taken.clear()
        if (tapOnMap) Gamepad.map?.cancel()
        tapOnMap = false
    }

    internal fun opened(close: State<() -> Unit>) { windows.add(close) }
    internal fun closed(close: State<() -> Unit>) { windows.remove(close) }
}

/** Closes the window with Esc, while it shows. Called by [Window]. */
@Composable
internal fun ClosesWithEsc(onClose: () -> Unit) {
    val close = rememberUpdatedState(onClose)
    DisposableEffect(close) {
        KeyInput.opened(close)
        onDispose { KeyInput.closed(close) }
    }
}

/** Takes the keys for [KeyInput], keeping focus here or in what's inside, for the app's outermost box. */
@Composable
fun Modifier.gameKeys(): Modifier {
    val focus = remember { FocusRequester() }
    var inside by remember { mutableStateOf(false) }
    LaunchedEffect(inside) {
        if (!inside) {
            KeyInput.release()
            runCatching { focus.requestFocus() }
        }
    }
    DisposableEffect(focus) {
        val back = { runCatching { focus.requestFocus() }; Unit }
        KeyInput.focusRoot = back
        onDispose { if (KeyInput.focusRoot === back) KeyInput.focusRoot = null }
    }
    GamepadSticks()
    return this
        .hidesCursor()
        .onKeyEvent { KeyInput.handle(it) }
        .focusRequester(focus)
        .onFocusChanged {
            inside = it.hasFocus
            KeyInput.rootFocused = it.isFocused
        }
        .focusable()
}

/** Hides the gamepad's cursor when the screen's touched or a mouse moves or presses. */
private fun Modifier.hidesCursor(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (!Gamepad.shown.value) continue
            if (e.type == PointerEventType.Press || e.type == PointerEventType.Scroll ||
                e.type == PointerEventType.Move && e.changes.any { it.type == PointerType.Mouse }
            ) Gamepad.hide()
        }
    }
}

/** Marks a text field, so keys do nothing while it has focus. */
@Composable
fun Modifier.typing(): Modifier {
    val focused = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            if (focused[0]) KeyInput.typing--
            focused[0] = false
        }
    }
    return onFocusChanged {
        if (it.isFocused != focused[0]) {
            focused[0] = it.isFocused
            KeyInput.typing += if (it.isFocused) 1 else -1
        }
    }
}

private val ARROWS = setOf("up", "down", "left", "right")

/** The name [KeyMap] knows a key by. */
private val KEY_NAMES: Map<Key, String> by lazy {
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    val digits = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    val pad = listOf(Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9)
    buildMap {
        letters.forEachIndexed { i, k -> put(k, ('a' + i).toString()) }
        digits.forEachIndexed { i, k -> put(k, i.toString()) }
        pad.forEachIndexed { i, k -> put(k, i.toString()) }
        put(Key.Spacebar, "space")
        put(Key.Escape, "esc")
        put(Key.Delete, "delete")
        put(Key.Backspace, "backspace")
        put(Key.Plus, "plus")
        put(Key.NumPadAdd, "plus")
        put(Key.Equals, "equals")
        put(Key.Minus, "minus")
        put(Key.NumPadSubtract, "minus")
        put(Key.DirectionUp, "up")
        put(Key.DirectionDown, "down")
        put(Key.DirectionLeft, "left")
        put(Key.DirectionRight, "right")
        put(Key.ButtonA, "pad_a")
        put(Key.ButtonB, "pad_b")
        put(Key.ButtonY, "pad_y")
        put(Key.ButtonStart, "pad_start")
        put(Key.ButtonSelect, "pad_select")
        put(Key.ButtonL1, "pad_l1")
        put(Key.ButtonR1, "pad_r1")
        put(Key.ButtonL2, "pad_l2")
        put(Key.ButtonR2, "pad_r2")
    }
}

/** A key's name as printed on it. */
@Composable
fun keyLabel(name: String): String = when (name) {
    "space" -> stringResource(Res.string.key_space)
    "esc" -> stringResource(Res.string.key_esc)
    "delete" -> stringResource(Res.string.key_delete)
    "backspace" -> stringResource(Res.string.key_backspace)
    "plus" -> "+"
    "equals" -> "="
    "minus" -> "-"
    "up" -> "↑"
    "down" -> "↓"
    "left" -> "←"
    "right" -> "→"
    "pad_start" -> stringResource(Res.string.pad_start)
    "pad_select" -> stringResource(Res.string.pad_select)
    "pad_up", "pad_down", "pad_left", "pad_right" -> stringResource(Res.string.pad_dpad)
    else -> name.removePrefix(KeyMap.PAD).uppercase()
}

/**
 * Every action and its keys, a row each, actions of the same title on one row: the keyboard's, or with [pad] the
 * gamepad's, with its sticks.
 */
@Composable
fun KeyList(pad: Boolean = false) {
    for ((title, actions) in KeyAction.entries.groupBy { it.title }) {
        val keys = actions.flatMap { KeyMap.keysOf(it) }.filter { KeyMap.isPad(it) == pad }.map { keyLabel(it) }.distinct()
        if (keys.isEmpty()) continue
        InfoRow(stringResource(title), keys.joinToString("  "))
    }
    if (pad) {
        InfoRow(stringResource(Res.string.key_cursor), stringResource(Res.string.pad_left_stick))
        InfoRow(stringResource(Res.string.key_turn), stringResource(Res.string.pad_right_stick))
    }
}
