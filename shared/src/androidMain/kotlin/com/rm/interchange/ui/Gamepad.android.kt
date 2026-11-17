package com.rm.interchange.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs
import kotlinx.coroutines.delay
import android.view.KeyEvent as AndroidKeyEvent

actual fun KeyEvent.fromGamepad(): Boolean {
    val s = nativeKeyEvent.source
    return s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
        s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
}

/**
 * Reads the sticks, triggers and hat from the joystick motions sent to the Compose view. While the map cursor is out
 * they're all taken here, the hat as d-pad keys that repeat while it's held; otherwise Android turns the left stick
 * and hat into d-pad keys, which move focus.
 */
@Composable
actual fun GamepadSticks() {
    val view = LocalView.current
    val hat = remember { Hat() }
    DisposableEffect(view) {
        view.setOnGenericMotionListener { v, e -> motion(v, e, hat) }
        val press: (Boolean) -> Unit = { down -> key(view, AndroidKeyEvent.KEYCODE_DPAD_CENTER, down) }
        Gamepad.pressFocused = press
        onDispose {
            view.setOnGenericMotionListener(null)
            if (Gamepad.pressFocused === press) Gamepad.pressFocused = null
            hat.at[0] = 0
            hat.at[1] = 0
            hat.send(view, SystemClock.uptimeMillis())
            Gamepad.release()
        }
    }
    // Repeats a held hat each frame, and lets go of it when the cursor goes; otherwise it only looks now and then.
    LaunchedEffect(view) {
        while (true) {
            if (hat.held) withFrameMillis { hat.send(view, SystemClock.uptimeMillis()) } else delay(100)
        }
    }
}

/** The hat across and down, as -1, 0 or 1: where it's [at], the d-pad keys [sent] down for it, and their repeats. */
private class Hat {
    val at = IntArray(2)
    val sent = IntArray(2)
    val repeat = Array(2) { KeyRepeat(ViewConfiguration.getKeyRepeatTimeout().toLong(), ViewConfiguration.getKeyRepeatDelay().toLong()) }
    /** For each pad, whether its right stick's across is on AXIS_Z. */
    val stickOnZ = HashMap<Int, Boolean>()

    val held get() = at[0] != 0 || at[1] != 0 || sent[0] != 0 || sent[1] != 0

    /** Sends the d-pad keys for where the hat is while the cursor's out, the keys up otherwise, and any repeats due. */
    fun send(view: View, now: Long) {
        for (a in 0..1) {
            val want = if (KeyInput.cursorMode) at[a] else 0
            if (want != sent[a]) {
                if (sent[a] != 0) key(view, code(a, sent[a]), false)
                sent[a] = want
                if (want != 0) {
                    key(view, code(a, want), true)
                    repeat[a].start(now)
                } else repeat[a].stop()
            } else if (want != 0 && repeat[a].due(now)) key(view, code(a, want), true, repeat = 1)
        }
    }

    private fun code(axis: Int, dir: Int) =
        if (axis == 0) (if (dir < 0) AndroidKeyEvent.KEYCODE_DPAD_LEFT else AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        else (if (dir < 0) AndroidKeyEvent.KEYCODE_DPAD_UP else AndroidKeyEvent.KEYCODE_DPAD_DOWN)
}

private fun motion(view: View, e: MotionEvent, hat: Hat): Boolean {
    if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.actionMasked != MotionEvent.ACTION_MOVE) return false
    val device = e.device
    fun has(axis: Int) = device?.getMotionRange(axis, e.source) != null
    val onZ = hat.stickOnZ.getOrPut(e.deviceId) {
        val z = device?.getMotionRange(MotionEvent.AXIS_Z, e.source)
        stickOnZ(z != null, has(MotionEvent.AXIS_RX), z?.min ?: 0f, e.getAxisValue(MotionEvent.AXIS_Z))
    }
    val rx = e.getAxisValue(if (onZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX)
    val lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
    val rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
    Gamepad.sticks(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y), rx, lt, rt)
    hat.at[0] = hatStep(e.getAxisValue(MotionEvent.AXIS_HAT_X))
    hat.at[1] = hatStep(e.getAxisValue(MotionEvent.AXIS_HAT_Y))
    val cursor = KeyInput.cursorMode
    hat.send(view, SystemClock.uptimeMillis())
    return cursor
}

private fun hatStep(v: Float) = if (abs(v) < 0.5f) 0 else if (v < 0f) -1 else 1

/** Sends [code] down or up to [view] as a gamepad's key, the [repeat]th time if it's held. */
private fun key(view: View, code: Int, down: Boolean, repeat: Int = 0) {
    val now = SystemClock.uptimeMillis()
    view.dispatchKeyEvent(
        AndroidKeyEvent(
            now, now, if (down) AndroidKeyEvent.ACTION_DOWN else AndroidKeyEvent.ACTION_UP, code, repeat, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_GAMEPAD,
        ),
    )
}
