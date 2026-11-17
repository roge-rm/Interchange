package com.rm.interchange.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import com.rm.interchange.map.View
import com.rm.interchange.map.groundHeight
import com.rm.interchange.sim.World
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * A gamepad's sticks and the map cursor they move, on the UI thread. The platform feeds [sticks] with each stick
 * motion; the buttons come as keys through [KeyInput]. The cursor shows from when a gamepad is used till the screen is
 * touched or a mouse moves.
 */
object Gamepad {
    /** Whether the cursor shows. */
    val shown = mutableStateOf(false)
    /** Where the cursor is on the map, in pixels; unspecified till it's first placed. */
    val cursor = mutableStateOf(Offset.Unspecified)
    /** The left stick, -1 to 1, y down. */
    var leftX = 0f
        private set
    var leftY = 0f
        private set
    /** What A at the cursor does on the map, while one shows. */
    var map: CursorTarget? = null
    /** Presses or lets go of whatever has focus, as the d-pad's centre does; set by the platform. */
    var pressFocused: ((down: Boolean) -> Unit)? = null

    private val rightStick = TurnLatch()
    private val leftTrigger = TurnLatch()
    private val rightTrigger = TurnLatch()
    private val clock = TimeSource.Monotonic.markNow()
    /** When a trigger last turned the view, left and right, in ms on [clock]; -1 for never. */
    private val triggerTurned = longArrayOf(-1L, -1L)

    fun used() {
        if (!shown.value) shown.value = true
    }

    fun hide() {
        if (shown.value) shown.value = false
        map?.cancel()
    }

    /**
     * Takes a motion of the sticks and triggers, each -1 to 1 (triggers 0 to 1). The right stick and the triggers
     * turn the view a quarter for each push.
     */
    fun sticks(lx: Float, ly: Float, rx: Float, lt: Float, rt: Float) {
        leftX = lx
        leftY = ly
        if (stickSpeed(lx, ly) > 0f) used()
        var turn = rightStick.update(rx)
        if (leftTrigger.update(lt) > 0 && triggerTurn(false)) turn -= 1
        if (rightTrigger.update(rt) > 0 && triggerTurn(true)) turn += 1
        if (turn != 0) {
            used()
            KeyInput.pad(if (turn < 0) KeyAction.TurnLeft else KeyAction.TurnRight)
        }
    }

    /**
     * Whether a pull of the left ([right] false) or right trigger turns the view. A trigger can come as an axis and a
     * button both, so only the first of the two in a short while does.
     */
    internal fun triggerTurn(right: Boolean): Boolean {
        val i = if (right) 1 else 0
        val now = clock.elapsedNow().inWholeMilliseconds
        if (triggerTurned[i] >= 0 && now - triggerTurned[i] < TRIGGER_BOTH_MILLIS) return false
        triggerTurned[i] = now
        return true
    }

    /** Lets go of the sticks, as when the gamepad goes. */
    fun release() {
        sticks(0f, 0f, 0f, 0f, 0f)
    }
}

/** What A does at the cursor: [press] and [release] as a finger's down and up, [moved] when the cursor or map moves. */
interface CursorTarget {
    fun press()
    fun release()
    fun moved()
    /** Drops a press without acting on it. */
    fun cancel()
}

/** Whether a key came from a gamepad, as its d-pad does, rather than a keyboard's arrows. */
expect fun KeyEvent.fromGamepad(): Boolean

/** Feeds [Gamepad] the sticks and sets [Gamepad.pressFocused], while it's in the composition. */
@Composable
expect fun GamepadSticks()

/**
 * A stick's push as a share of full speed, 0 to 1: nothing inside [STICK_DEAD] of the middle, as sticks don't come
 * back to quite 0, then rising with the square of the push past it, for fine moves near the middle.
 */
fun stickSpeed(x: Float, y: Float): Float {
    val d = sqrt(x * x + y * y)
    if (d <= STICK_DEAD) return 0f
    val s = ((d - STICK_DEAD) / (1f - STICK_DEAD)).coerceAtMost(1f)
    return s * s
}

/** How far the cursor goes for a stick at ([x], [y]) in [dt] seconds at [top] pixels a second, as x, y. */
fun stickMove(x: Float, y: Float, top: Float, dt: Float): Offset {
    val s = stickSpeed(x, y)
    if (s == 0f) return Offset.Zero
    val d = sqrt(x * x + y * y)
    return Offset(x / d * s * top * dt, y / d * s * top * dt)
}

/**
 * Moves a cursor at ([x], [y]) by ([dx], [dy]) on a map [w] by [h], keeping it [margin] in from the edges. Gives
 * x, y and how far to move the map, as [View.pan] takes it, for the part of the move past the margin.
 */
fun cursorMove(x: Float, y: Float, dx: Float, dy: Float, w: Float, h: Float, margin: Float): FloatArray {
    val m = margin.coerceAtMost(minOf(w, h) / 2)
    val nx = x + dx
    val ny = y + dy
    val cx = nx.coerceIn(m, w - m)
    val cy = ny.coerceIn(m, h - m)
    return floatArrayOf(cx, cy, cx - nx, cy - ny)
}

/**
 * Where the d-pad takes the cursor at [at]: the middle of the tile ([dx], [dy]) tiles across and down the screen from
 * the one under it. Tiles in a screen column are a tile's height apart, in a row its width.
 */
fun View.cursorStep(world: World, at: Offset, dx: Int, dy: Int, w: Float, h: Float): Offset {
    val p = pick(world, at.x, at.y, w, h)
        ?: return Offset(at.x + dx * 2 * halfWidth, at.y + dy * 2 * halfHeight)
    val wx = p.tileX + 0.5f
    val wy = p.tileY + 0.5f
    val vx = toViewX(wx, wy)
    val vy = toViewY(wx, wy)
    val sx = screenX(vx, vy, w)
    val sy = screenY(vx, vy, groundHeight(world, wx, wy), h)
    return Offset(sx + dx * 2 * halfWidth, sy + dy * 2 * halfHeight)
}

/**
 * Turns a stick or trigger's axis into pushes: +1 or -1 once it goes past [TURN_PUSH] either way, and again only after
 * it's come back inside [TURN_REST].
 */
class TurnLatch {
    private var armed = true

    fun update(v: Float): Int {
        if (abs(v) < TURN_REST) {
            armed = true
            return 0
        }
        if (!armed || abs(v) < TURN_PUSH) return 0
        armed = false
        return if (v > 0f) 1 else -1
    }
}

/** How far from the middle a stick sits still. */
const val STICK_DEAD = 0.2f

/** How far a stick or trigger goes to turn the view, and back to turn it again. */
const val TURN_PUSH = 0.6f
const val TURN_REST = 0.3f

/** A trigger's button within this long of its axis turning is the same pull, in milliseconds. */
private const val TRIGGER_BOTH_MILLIS = 250L
