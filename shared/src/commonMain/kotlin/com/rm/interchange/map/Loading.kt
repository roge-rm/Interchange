package com.rm.interchange.map

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The steps the loading cover names, in the order they usually come, then [FAILED] if the GPU can't take the map. */
object LoadStep {
    const val UNPACKING = 0
    const val DRAWING = 1
    const val SENDING = 2
    const val TRAINS = 3
    const val FAILED = 4
}

/** The loading cover's colours, from the kit: [rail] dim, [train] the accent, [trim] the text colour, and the step's [text] and [style]. */
class LoadingLook(val rail: Color, val train: Color, val trim: Color, val text: Color, val style: TextStyle)

/**
 * Where the loading cover's train is along its track, 0 to 1: eased towards the work's progress, never back. Starts
 * again from 0 each time the cover comes up. Main thread only.
 */
class LoadingTrain {
    var shown = 0f
        private set
    private var target = 0f
    private var lastMillis = 0L
    private var up = false

    /** Moves on to [millis] towards [progress] while [cover] is over the map. */
    fun advance(progress: Float, cover: Float, millis: Long) {
        if (cover <= 0f) {
            up = false
            return
        }
        if (!up) {
            up = true
            shown = 0f
            target = 0f
            lastMillis = millis
        }
        // The work started over while the cover was still fading out.
        if (target >= 1f && progress < 1f) {
            shown = 0f
            target = 0f
        }
        target = max(target, progress.coerceIn(0f, 1f))
        val dt = (millis - lastMillis).coerceIn(0L, 100L).toFloat()
        lastMillis = millis
        // Most of the way in about a quarter second, and never slower than a fifth of the run a second, so it arrives.
        val step = max((target - shown) * (1f - exp(-dt / EASE_MILLIS)), dt * MIN_SPEED)
        shown = min(target, shown + step)
    }

    private companion object {
        const val EASE_MILLIS = 250f
        const val MIN_SPEED = 0.2f / 1000f
    }
}

/**
 * The loading cover: [page] over the map at [cover], and on it a train along a track as far as [train] has got, with
 * [label] under it. Faded as a whole while it lifts. [millis] runs the wheels and steam.
 */
fun DrawScope.drawLoadingCover(cover: Float, page: Color, look: LoadingLook, train: LoadingTrain, measurer: TextMeasurer, label: String, millis: Long) {
    if (cover <= 0f) return
    val fading = cover < 1f
    if (fading) drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = cover }) }
    drawRect(page)
    val u = 1.dp.toPx()
    val trackWidth = min(size.width * 0.7f, 300 * u)
    val left = (size.width - trackWidth) / 2
    val right = left + trackWidth
    val railY = size.height / 2 + 8 * u
    // The train's front, from the track's start with the whole train on it to its end.
    val length = TRAIN_LENGTH * TRAIN_SCALE * u
    val front = left + length + train.shown * (trackWidth - length)
    // Sleepers, then the rail, the run so far in the train's colour.
    val sleeper = look.rail.copy(alpha = look.rail.alpha * 0.5f)
    var x = left + 2 * u
    while (x < right - 3 * u) {
        drawRoundRect(sleeper, Offset(x, railY + 1.2f * u), Size(5 * u, 2.2f * u), CornerRadius(0.8f * u))
        x += 8 * u
    }
    drawLine(look.rail, Offset(front, railY), Offset(right, railY), 1.5f * u, StrokeCap.Round)
    drawLine(look.train, Offset(left, railY), Offset(front, railY), 1.5f * u, StrokeCap.Round)
    val t = millis / 1000f
    // The train a little bigger than the track's dp, so it reads at phone size.
    withTransform({
        translate(front, railY - 0.75f * u)
        scale(u * TRAIN_SCALE, u * TRAIN_SCALE, Offset.Zero)
    }) { drawTrain(look, page, t) }
    val text = measurer.measure(label, look.style)
    drawText(text, look.text, Offset((size.width - text.size.width) / 2, railY + 12 * u))
    if (fading) drawIntoCanvas { it.restore() }
}

private const val TRAIN_LENGTH = 60f
private const val TRAIN_SCALE = 1.4f

/**
 * A steam locomotive and one coach facing right, in dp with the front at 0 and the rail's top at 0, at [t] seconds:
 * wheels turning, rods going round with them, a little bob and puffs of steam trailing back.
 */
private fun DrawScope.drawTrain(look: LoadingLook, page: Color, t: Float) {
    val body = look.train
    val trim = look.trim
    val coach = lerp(body, page, 0.25f)
    val angle = t * 2f * PI.toFloat() * WHEEL_TURNS
    val bob = sin(t * 14f) * 0.25f

    // Steam from the chimney, drifting back and up as it grows and fades.
    for (i in 0 until PUFFS) {
        val a = (t * PUFF_RATE + i.toFloat() / PUFFS) % 1f
        val alpha = 0.55f * (1f - a) * min(1f, a * 6f)
        drawCircle(look.trim.copy(alpha = alpha * 0.7f), 1.6f + a * 3.6f, Offset(-5.3f - a * 18f, -26f + bob - a * 8f - sin(a * 5f) * 0.8f))
    }

    // The coach, with its rounded roof and windows, on two bogies.
    withTransform({ translate(0f, bob * 0.6f) }) {
        drawRoundRect(coach, Offset(-58f, -20.5f), Size(22f, 14f), CornerRadius(2.2f))
        drawRoundRect(trim, Offset(-59f, -22.5f), Size(24f, 3.2f), CornerRadius(1.6f))
        for (w in 0 until 4) drawRoundRect(page, Offset(-56f + w * 5f, -17.5f), Size(3.4f, 4f), CornerRadius(0.8f))
        drawLine(trim, Offset(-36f, -6.5f), Offset(-33f, -6.5f), 1f)
    }
    for (cx in floatArrayOf(-54.5f, -50.5f, -43.5f, -39.5f)) wheel(trim, page, cx, 2.1f, angle * 4.2f / 2.1f)

    withTransform({ translate(0f, bob) }) {
        // Running board and buffer beam under the boiler and cab.
        drawRoundRect(trim, Offset(-33f, -10.5f), Size(32f, 2f), CornerRadius(0.6f))
        // Boiler, round at the front, into the cab.
        val boiler = Path().apply {
            addRoundRect(RoundRect(Rect(-25f, -19.5f, -1f, -10f), CornerRadius.Zero, CornerRadius(4.6f), CornerRadius(4.6f), CornerRadius.Zero))
        }
        drawPath(boiler, body)
        // Smokebox at the front, darker, with a band behind it.
        drawPath(Path().apply {
            addRoundRect(RoundRect(Rect(-6.5f, -19.5f, -1f, -10f), CornerRadius.Zero, CornerRadius(4.6f), CornerRadius(4.6f), CornerRadius.Zero))
        }, trim)
        drawLine(lerp(body, page, 0.35f), Offset(-15.5f, -19.3f), Offset(-15.5f, -10.2f), 0.9f)
        // Chimney, flared at the top.
        drawPath(Path().apply {
            moveTo(-6.6f, -19.4f); lineTo(-6.0f, -24f); lineTo(-4.4f, -24f); lineTo(-3.8f, -19.4f); close()
        }, trim)
        drawRoundRect(trim, Offset(-7.3f, -25.6f), Size(4.2f, 1.8f), CornerRadius(0.7f))
        // Steam dome.
        drawPath(Path().apply {
            moveTo(-18.5f, -19.3f)
            cubicTo(-18.3f, -23.2f, -13.7f, -23.2f, -13.5f, -19.3f)
            close()
        }, lerp(body, trim, 0.35f))
        // Cab with a window and a roof that overhangs.
        drawRoundRect(body, Offset(-33f, -23.5f), Size(9f, 14f), CornerRadius(1f))
        drawRoundRect(page, Offset(-31.2f, -21.6f), Size(5.4f, 4.6f), CornerRadius(1f))
        drawRoundRect(trim, Offset(-34.5f, -25.5f), Size(12f, 2.4f), CornerRadius(1.2f))
        // Cylinder by the front wheels.
        drawRoundRect(trim, Offset(-10f, -9.6f), Size(5f, 3.6f), CornerRadius(1f))
        // Pilot sloping down to the rail at the front.
        drawPath(Path().apply {
            moveTo(-3.2f, -8.5f); lineTo(-0.5f, -8.5f); lineTo(2.2f, -1.2f); lineTo(-3.2f, -1.2f); close()
        }, trim)
    }

    // A small leading wheel and two coupled drivers, with the rod between their crank pins and to the cylinder.
    wheel(trim, page, -5.6f, 2.3f, angle * 4.2f / 2.3f)
    val r = 4.2f
    val d1 = -13f
    val d2 = -22f
    wheel(trim, page, d1, r, angle)
    wheel(trim, page, d2, r, angle)
    val px = cos(angle) * 2.2f
    val py = sin(angle) * 2.2f
    drawLine(body, Offset(d2 + px, -r + py), Offset(d1 + px, -r + py), 1.1f, StrokeCap.Round)
    drawLine(body, Offset(d1 + px, -r + py), Offset(-8f, -7.8f + bob), 0.9f, StrokeCap.Round)
}

/** A spoked wheel of radius [r] at [cx], standing on the rail, turned by [angle]. */
private fun DrawScope.wheel(trim: Color, page: Color, cx: Float, r: Float, angle: Float) {
    val c = Offset(cx, -r)
    drawCircle(trim, r, c)
    drawCircle(page, r - 0.8f, c)
    val spokes = if (r > 3f) 6 else 4
    for (i in 0 until spokes) {
        val a = angle + i * PI.toFloat() * 2f / spokes
        drawLine(trim, c, Offset(cx + cos(a) * (r - 0.6f), -r + sin(a) * (r - 0.6f)), 0.55f)
    }
    drawCircle(trim, r * 0.28f, c)
}

/** Turns a second of the drivers. */
private const val WHEEL_TURNS = 1.1f
private const val PUFFS = 4
private const val PUFF_RATE = 0.9f
