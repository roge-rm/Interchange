package com.rm.interchange.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs
import android.view.KeyEvent as AndroidKeyEvent

actual fun KeyEvent.fromGamepad(): Boolean {
    val s = nativeKeyEvent.source
    return s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
        s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
}

/**
 * Reads the sticks, triggers and hat from the joystick motions sent to the Compose view. While the map cursor is out
 * they're all taken here, the hat as d-pad keys; otherwise Android turns the left stick and hat into d-pad keys, which
 * move focus.
 */
@Composable
actual fun GamepadSticks() {
    val view = LocalView.current
    DisposableEffect(view) {
        val hat = IntArray(2)
        view.setOnGenericMotionListener { v, e -> motion(v, e, hat) }
        val press: (Boolean) -> Unit = { down -> key(view, AndroidKeyEvent.KEYCODE_DPAD_CENTER, down) }
        Gamepad.pressFocused = press
        onDispose {
            view.setOnGenericMotionListener(null)
            if (Gamepad.pressFocused === press) Gamepad.pressFocused = null
            Gamepad.release()
        }
    }
}

private fun motion(view: View, e: MotionEvent, hat: IntArray): Boolean {
    if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.actionMasked != MotionEvent.ACTION_MOVE) return false
    val device = e.device
    fun has(axis: Int) = device?.getMotionRange(axis, e.source) != null
    // Most pads put the right stick on Z and RZ, some on RX and RY.
    val rx = e.getAxisValue(if (has(MotionEvent.AXIS_Z) || !has(MotionEvent.AXIS_RX)) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX)
    val lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
    val rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
    Gamepad.sticks(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y), rx, lt, rt)
    val hx = hatStep(e.getAxisValue(MotionEvent.AXIS_HAT_X))
    val hy = hatStep(e.getAxisValue(MotionEvent.AXIS_HAT_Y))
    if (!KeyInput.cursorMode) {
        hat[0] = hx
        hat[1] = hy
        return false
    }
    if (hx != hat[0]) {
        if (hat[0] != 0) key(view, if (hat[0] < 0) AndroidKeyEvent.KEYCODE_DPAD_LEFT else AndroidKeyEvent.KEYCODE_DPAD_RIGHT, false)
        if (hx != 0) key(view, if (hx < 0) AndroidKeyEvent.KEYCODE_DPAD_LEFT else AndroidKeyEvent.KEYCODE_DPAD_RIGHT, true)
        hat[0] = hx
    }
    if (hy != hat[1]) {
        if (hat[1] != 0) key(view, if (hat[1] < 0) AndroidKeyEvent.KEYCODE_DPAD_UP else AndroidKeyEvent.KEYCODE_DPAD_DOWN, false)
        if (hy != 0) key(view, if (hy < 0) AndroidKeyEvent.KEYCODE_DPAD_UP else AndroidKeyEvent.KEYCODE_DPAD_DOWN, true)
        hat[1] = hy
    }
    return true
}

private fun hatStep(v: Float) = if (abs(v) < 0.5f) 0 else if (v < 0f) -1 else 1

/** Sends [code] down or up to [view] as a gamepad's key. */
private fun key(view: View, code: Int, down: Boolean) {
    val now = SystemClock.uptimeMillis()
    view.dispatchKeyEvent(
        AndroidKeyEvent(
            now, now, if (down) AndroidKeyEvent.ACTION_DOWN else AndroidKeyEvent.ACTION_UP, code, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_GAMEPAD,
        ),
    )
}
