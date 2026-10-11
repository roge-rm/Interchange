package com.rm.interchange.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import com.rm.interchange.sim.World
import kotlin.math.PI
import kotlin.math.atan2

/** What the map does with a finger, for the tool in use. */
interface MapInput {
    /** Whether a one finger drag uses the tool. Otherwise it moves the map. */
    val drags: Boolean
    fun tap(at: Pick) {}
    fun dragStart(at: Pick) {}
    fun dragTo(at: Pick) {}
    fun dragEnd() {}
    fun dragCancel() {}
}

/**
 * The map: chunks from the [Renderer], then whatever the tool in use draws
 * over them ([overlay]). One finger uses the tool, or moves the map if the
 * tool doesn't drag. Two fingers move, pinch to zoom and twist to turn the view.
 *
 * [frame] is read while drawing, so the canvas draws again when it changes
 * without the screen around it composing again.
 */
@Composable
fun MapView(
    world: World,
    sheets: List<ImageBitmap>,
    view: View,
    frame: () -> Long,
    input: MapInput,
    moving: () -> Moving?,
    modifier: Modifier = Modifier,
    overlay: (DrawScope.(LandPainter) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val renderer = remember(world) { Renderer(world, sheets, scope) }
    val currentInput by rememberUpdatedState(input)
    Canvas(
        modifier.pointerInput(world, view) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = down.position
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                var mode = Mode.Undecided
                var lastCentre = Offset.Unspecified
                var lastSpan = 0f
                var lastAngle = 0f
                var twist = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        if (mode == Mode.Using) currentInput.dragCancel()
                        val a = pressed[0].position
                        val b = pressed[1].position
                        val centre = (a + b) / 2f
                        val span = (a - b).getDistance()
                        val angle = atan2(b.y - a.y, b.x - a.x)
                        if (mode == Mode.Two && lastCentre != Offset.Unspecified) {
                            view.pan(centre.x - lastCentre.x, centre.y - lastCentre.y)
                            if (lastSpan > 0f && span > 0f) view.zoomAt(span / lastSpan, centre.x, centre.y, w, h)
                            var turned = angle - lastAngle
                            if (turned > PI) turned -= (2 * PI).toFloat()
                            if (turned < -PI) turned += (2 * PI).toFloat()
                            twist += turned
                            // A twist past a third of a right angle turns the view a quarter.
                            if (twist > TWIST) { view.turnBy(-1); twist = 0f }
                            if (twist < -TWIST) { view.turnBy(1); twist = 0f }
                        }
                        mode = Mode.Two
                        lastCentre = centre
                        lastSpan = span
                        lastAngle = angle
                    } else {
                        val p = pressed[0]
                        if (mode == Mode.Two) {
                            // Down to one finger after two: it moves the map until it lifts.
                            mode = Mode.Moving
                        } else if (mode == Mode.Undecided && (p.position - start).getDistance() > viewConfiguration.touchSlop) {
                            if (currentInput.drags) {
                                val at = view.pick(world, start.x, start.y, w, h)
                                if (at != null) {
                                    mode = Mode.Using
                                    currentInput.dragStart(at)
                                } else mode = Mode.Moving
                            } else mode = Mode.Moving
                        }
                        when (mode) {
                            Mode.Using -> view.pick(world, p.position.x, p.position.y, w, h)?.let { currentInput.dragTo(it) }
                            Mode.Moving -> p.positionChange().let { view.pan(it.x, it.y) }
                            else -> {}
                        }
                    }
                    event.changes.forEach { it.consume() }
                }
                when (mode) {
                    Mode.Undecided -> view.pick(world, start.x, start.y, w, h)?.let { currentInput.tap(it) }
                    Mode.Using -> currentInput.dragEnd()
                    else -> {}
                }
            }
        },
    ) {
        renderer.revision.intValue
        frame()
        with(renderer) { draw(view) }
        moving()?.let { drawMoving(world, view, sheets, it) }
        overlay?.let { draw ->
            val painter = LandPainter(world, view, view.halfWidth)
            painter.originX = view.screenX(0f, 0f, size.width)
            painter.originY = view.screenY(0f, 0f, 0f, size.height)
            draw(painter)
        }
    }
}

private enum class Mode { Undecided, Using, Moving, Two }

private const val TWIST = (PI / 6).toFloat()
