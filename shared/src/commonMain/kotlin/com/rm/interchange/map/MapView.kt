package com.rm.interchange.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.rm.interchange.map.gl.GlMapSurface
import com.rm.interchange.map.gl.GlScene
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.rememberTextMeasurer
import com.rm.interchange.res.Res
import com.rm.interchange.res.loading_drawing
import com.rm.interchange.res.loading_failed
import com.rm.interchange.res.loading_sending
import com.rm.interchange.res.loading_trains
import com.rm.interchange.res.loading_unpacking
import com.rm.interchange.sim.World
import org.jetbrains.compose.resources.stringResource
import kotlin.time.TimeSource

/** What the map does with a finger, for the tool in use. */
interface MapInput {
    /** Whether a one finger drag uses the tool. Otherwise it moves the map. */
    val drags: Boolean
    fun tap(at: Pick) {}
    fun dragStart(at: Pick) {}
    fun dragTo(at: Pick) {}
    fun dragEnd() {}
    fun dragCancel() {}
    /** Goes up with each build or remove command a tap or drag sends, so the map can hold its preview till it's drawn. */
    val builds: Int get() = 0
    /**
     * The world tiles (x0, y0, x1, y1) a tap or drag from [from] to [to] would build on where they reach past the box
     * of the two, as a diagonal line, an industry or a tunnel's far end do; null for that box. Asked before it's sent.
     */
    fun buildArea(from: Pick, to: Pick): IntArray? = null
}

/**
 * The map: drawn on the GPU by a [GlScene] on a surface under the Compose map,
 * which draws what moves into it and whatever the tool in use draws over it
 * ([overlay]). One finger uses the tool, or moves the map if the tool doesn't
 * drag. Two fingers move and pinch to zoom; the strip's buttons turn the view.
 *
 * [frame] is read while drawing, so the canvas draws again when it changes
 * without the screen around it composing again.
 */
@Composable
fun MapView(
    world: World,
    sheets: Sheets,
    view: View,
    frame: () -> Long,
    input: MapInput,
    moving: () -> Moving?,
    modifier: Modifier = Modifier,
    overlay: (DrawScope.(LandPainter) -> Unit)? = null,
    /** The overlays drawn into the ground, as [MapOverlays] bits. */
    overlays: Int = 0,
    /** Drawn over everything else: town names. */
    labels: (DrawScope.() -> Unit)? = null,
    /** Over even those: what the drag would cost, by the finger. */
    label: (DrawScope.() -> Unit)? = null,
    /** What shows while the map first loads. */
    page: Color = Color.Black,
    /** The train and step name drawn on the page while the map first loads; the page alone if null. */
    loading: LoadingLook? = null,
    /** Called once, when the first screen has everything it needs and the cover starts to lift. */
    onSharp: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // The lines drawn over the map keep their world, so let go of it when the map shows another.
    DisposableEffect(world) { onDispose { DrawLines.drop(world) } }
    val currentInput by rememberUpdatedState(input)
    val gl = remember(world) { GlScene(world, scope) }
    DisposableEffect(gl) { onDispose { gl.close() } }
    gl.onSharp = onSharp
    gl.overlays = overlays
    val measurer = rememberTextMeasurer()
    val steps = arrayOf(
        stringResource(Res.string.loading_unpacking), stringResource(Res.string.loading_drawing),
        stringResource(Res.string.loading_sending), stringResource(Res.string.loading_trains),
        stringResource(Res.string.loading_failed),
    )
    val train = remember(world) { LoadingTrain() }
    val clock = remember(world) { TimeSource.Monotonic.markNow() }
    // When the GPU map last had everything for the screen, to fade the cover from; -1 while it waits.
    val glReadyAt = remember(world) { longArrayOf(-1L) }
    // The tool's drawing over the map is kept in a layer, so the last of a drag can stay on screen as it was till the
    // GPU map draws what it built. Where it was drawn: the screen place of world point 0, 0, the zoom and the turn.
    val plan = rememberGraphicsLayer()
    val planAt = remember(world) { FloatArray(4) }
    val planHeld = remember(world) { booleanArrayOf(false, false) }
    val touch = Modifier.pointerInput(world, view) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = down.position
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                var mode = Mode.Undecided
                var lastCentre = Offset.Unspecified
                var lastSpan = 0f
                // The drag's first and last tiles.
                var from: Pick? = null
                var to: Pick? = null
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
                        if (mode == Mode.Two && lastCentre != Offset.Unspecified) {
                            view.pan(centre.x - lastCentre.x, centre.y - lastCentre.y)
                            if (lastSpan > 0f && span > 0f) view.zoomAt(span / lastSpan, centre.x, centre.y, w, h)
                        }
                        mode = Mode.Two
                        lastCentre = centre
                        lastSpan = span
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
                                    from = at
                                    to = at
                                    currentInput.dragStart(at)
                                } else mode = Mode.Moving
                            } else mode = Mode.Moving
                        }
                        when (mode) {
                            Mode.Using -> view.pick(world, p.position.x, p.position.y, w, h)?.let { gl.lastEdit = it.tileX to it.tileY; to = it; currentInput.dragTo(it) }
                            Mode.Moving -> p.positionChange().let { view.pan(it.x, it.y) }
                            else -> {}
                        }
                    }
                    event.changes.forEach { it.consume() }
                }
                when (mode) {
                    Mode.Undecided -> view.pick(world, start.x, start.y, w, h)?.let {
                        gl.lastEdit = it.tileX to it.tileY
                        planHeld[0] = false
                        val mark = gl.editMark(view.turn)
                        val builds = currentInput.builds
                        val area = editArea(currentInput, it, it)
                        currentInput.tap(it)
                        if (currentInput.builds != builds) gl.edited(view.turn, area[0], area[1], area[2], area[3], mark)
                    }
                    Mode.Using -> {
                        val mark = gl.editMark(view.turn)
                        val builds = currentInput.builds
                        val drawn = planHeld[1]
                        val a = from
                        val b = to
                        val area = if (a != null && b != null) editArea(currentInput, a, b) else null
                        currentInput.dragEnd()
                        // What the drag showed stays till what it built is drawn ([planHeld]: held, drawn last frame).
                        planHeld[0] = drawn && currentInput.builds != builds && area != null
                        if (planHeld[0] && area != null) gl.edited(view.turn, area[0], area[1], area[2], area[3], mark)
                    }
                    else -> {}
                }
            }
        }
    val paint: DrawScope.() -> Unit = {
        sheets.revision.intValue
        frame()
        gl.revision.intValue
        gl.show(view, sheets, size.width.toInt(), size.height.toInt())
        if (gl.wantsMoving()) moving()?.let {
            drawMoving(world, view, sheets, it, gl.live, gl.editWaiting)
            drawSky(world, view, it)
        }
        val now = clock.elapsedNow().inWholeMilliseconds
        // The GPU map takes the frame with what moves in it, so they show together, then the cover fades.
        if (!gl.finish()) glReadyAt[0] = -1L
        else if (glReadyAt[0] < 0L) glReadyAt[0] = now
        val cover = if (glReadyAt[0] < 0L) 1f else (1f - (now - glReadyAt[0]) / FADE_MILLIS.toFloat()).coerceIn(0f, 1f)
        val ox = view.screenX(0f, 0f, size.width)
        val oy = view.screenY(0f, 0f, 0f, size.height)
        val draw = overlay
        if (draw != null) {
            val painter = LandPainter(world, view, view.halfWidth)
            painter.originX = ox
            painter.originY = oy
            plan.record { draw(painter) }
            drawLayer(plan)
            planAt[0] = ox
            planAt[1] = oy
            planAt[2] = view.halfWidth
            planAt[3] = view.turn.toFloat()
            planHeld[0] = false
            planHeld[1] = true
        } else {
            planHeld[1] = false
            // Held as it was, moved with a pan; a zoom or turn lets it go.
            if (planHeld[0] && gl.holdingEdit && planAt[2] == view.halfWidth && planAt[3] == view.turn.toFloat()) {
                translate(ox - planAt[0], oy - planAt[1]) { drawLayer(plan) }
            } else planHeld[0] = false
        }
        labels?.invoke(this)
        label?.invoke(this)
        // The train stands still if the map can't be drawn.
        val failed = gl.step == LoadStep.FAILED
        if (!failed) train.advance(gl.progress, cover, now)
        if (cover > 0f) {
            if (loading != null) drawLoadingCover(cover, page, loading, train, measurer, steps[gl.step], if (failed) 0L else now)
            else drawRect(page.copy(alpha = cover))
        }
    }
    Box(modifier) {
        GlMapSurface(gl, page.toArgb(), Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize().then(touch), onDraw = paint)
    }
}

/** The box round [from], [to] and what [input] says it'd build past them, as x0, y0, x1, y1. */
private fun editArea(input: MapInput, from: Pick, to: Pick): IntArray {
    val box = intArrayOf(minOf(from.tileX, to.tileX), minOf(from.tileY, to.tileY), maxOf(from.tileX, to.tileX), maxOf(from.tileY, to.tileY))
    val more = input.buildArea(from, to) ?: return box
    return intArrayOf(
        minOf(box[0], more[0], more[2]), minOf(box[1], more[1], more[3]),
        maxOf(box[2], more[0], more[2]), maxOf(box[3], more[1], more[3]),
    )
}

/** The overlays the GPU map draws into the ground, as bits. */
object MapOverlays {
    /** A line along each tile's top two edges, at the closer zooms. */
    const val GRID = 1
    /** Bands of colour by height, with a contour line where a slope crosses one. */
    const val HEIGHTS = 2
    /** Bands of colour by snow depth, and ice on the water. */
    const val SNOW = 4
    /** Track tiles coloured by how worn they are. */
    const val CONDITION = 8
}

/** How long the cover takes to fade once the map's ready, in milliseconds. */
private const val FADE_MILLIS = 180

private enum class Mode { Undecided, Using, Moving, Two }

