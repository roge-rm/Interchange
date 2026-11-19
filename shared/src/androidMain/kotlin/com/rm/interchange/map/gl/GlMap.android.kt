package com.rm.interchange.map.gl

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.SyncFence
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.AttachedSurfaceControl
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.ViewTreeObserver
import android.view.animation.AnimationUtils
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executor

/**
 * A GLSurfaceView: its own surface behind the window, which the compositor takes as it is, where a TextureView would
 * be drawn once more into the window, a full screen of fill the Fire can't spare. Compose draws over it through the
 * hole the view leaves in the window. It draws only when asked to, so it's idle while the map stands still.
 */
@Composable
internal actual fun GlMapSurface(scene: GlScene, page: Int, modifier: Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val surface = remember { arrayOfNulls<GLSurfaceView>(1) }
    AndroidView(
        factory = { context ->
            MapSurfaceView(context, scene).apply {
                setEGLContextClientVersion(3)
                // A depth buffer for cutting what moves where buildings in front stand.
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                preserveEGLContextOnPause = true
                val renderer = GlMapRenderer(scene, page)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                tag = renderer
                scene.requestRender = { requestRender() }
                surface[0] = this
            }
        },
        modifier = modifier,
        onRelease = { view ->
            scene.requestRender = {}
            surface[0] = null
            // Textures and buffers go with the context when the view leaves the window; this frees them first.
            (view.tag as? GlMapRenderer)?.let { r -> view.queueEvent { r.release() } }
        },
    )
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // The GL thread mustn't sit waiting on a frame while the main thread waits for it to pause.
                    scene.wake()
                    surface[0]?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> surface[0]?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/**
 * Shows each frame handed over on the same display frame as the Compose frame that handed it (API 29 and up). From API
 * 33 it's given that Compose frame's display time, from the Choreographer, as the system shows a GL frame without a
 * time as soon as it's in. A frame without a time, below 33 or before the first vsync data, is held by the GL thread
 * till that Compose frame has gone out: the commit callback is added before the window draws, as one added while it
 * draws waits for the next frame. From API 34 the display time takes in how late the window's frames have been
 * reaching the screen, read from their present fences, as a window frame that misses its time or queues behind another
 * shows a frame or two after it. The listener goes on and off with the window, while the window's tree observer is
 * still the view's.
 */
private class MapSurfaceView(context: Context, private val scene: GlScene) : GLSurfaceView(context) {
    // This window frame: the seq handed over in its draw, 0 if none, and its planned display time, 0 if not known.
    private class Frame {
        var seq = 0L
        var planned = 0L
    }
    private var frame = Frame()
    private var tree: ViewTreeObserver? = null
    private val timed = if (Build.VERSION.SDK_INT >= 33) PresentTime() else null
    private val onHanded: (Long) -> Long = { seq ->
        frame.seq = seq
        val t = timed
        if (t != null && Build.VERSION.SDK_INT >= 33) {
            frame.planned = t.planned
            t.next()
        } else 0L
    }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val t = tree
        if (t != null && t.isAlive && Build.VERSION.SDK_INT >= 29) {
            val last = frame
            val f = Frame()
            frame = f
            t.registerFrameCommitCallback { if (f.seq > 0L) scene.committed(f.seq) }
            // Only while frames are being handed over, so a still map adds nothing to its window frames.
            val p = timed
            if (Build.VERSION.SDK_INT >= 34 && p != null && p.fenced && last.seq > 0L) {
                val root = rootSurfaceControl
                if (root != null) p.askFence(root, context.mainExecutor) { f.planned }
            }
        }
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 29) {
            scene.onHanded = onHanded
            tree = viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
        }
        if (Build.VERSION.SDK_INT >= 33) timed?.open()
    }

    override fun onDetachedFromWindow() {
        tree?.let { if (it.isAlive) it.removeOnPreDrawListener(preDraw) }
        tree = null
        if (scene.onHanded === onHanded) scene.onHanded = null
        if (Build.VERSION.SDK_INT >= 33) timed?.close()
        // The GL thread mustn't sit waiting on a frame while detaching waits for it to stop.
        scene.wake()
        super.onDetachedFromWindow()
    }

    // These wait on the GL thread from the main thread, so it mustn't sit holding a frame for the main thread meanwhile.
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        scene.wake()
        super.surfaceChanged(holder, format, w, h)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        scene.wake()
        super.surfaceDestroyed(holder)
    }

    @Deprecated("Deprecated in Java")
    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        scene.wake()
        @Suppress("DEPRECATION")
        super.surfaceRedrawNeeded(holder)
    }
}

/** How long without frames handed over before the window's frames are taken to be on time again: 100 ms. */
private const val IDLE_NANOS = 100_000_000L

/**
 * The display time of the Compose frame drawing now: each frame that hands one over asks the Choreographer for the next
 * frame's times, and how far a frame's display time is past its frame time is kept for the first frame of the next pan.
 * From API 34 it's moved on by however late the window's frames have lately reached the screen past their planned
 * time, as read from their present fences. Main thread.
 */
@RequiresApi(33)
private class PresentTime {
    private var period = 0L
    // How long after its frame time a frame's planned to be on screen, as the Choreographer last had it, -1 till known.
    private var ahead = -1L
    // How late the window's frames have been reaching the screen past their planned time, in whole display frames.
    private var lateNanos = 0L
    // The last fence reading in display frames, -1 if none: the late time only changes when two in a row agree.
    private var lastReading = -1L
    // Window frames' present fences not signalled yet, with the planned time of each.
    private val fences = ArrayList<Pair<Long, SyncFence>>()
    // When a frame last asked, on System.nanoTime's clock.
    private var lastAsked = 0L
    private var asked = false
    private var attached = false

    /** Whether to read window frames' present fences: from API 34, till a call to them fails. */
    var fenced = Build.VERSION.SDK_INT >= 34
        private set

    private val vsync = Choreographer.VsyncCallback { data ->
        asked = false
        val lines = data.frameTimelines
        period = if (lines.size > 1) lines[1].expectedPresentationTimeNanos - lines[0].expectedPresentationTimeNanos else 0L
        ahead = if (period > 0L) data.preferredFrameTimeline.expectedPresentationTimeNanos - data.frameTimeNanos else -1L
    }

    /**
     * The planned display time of the Compose frame drawing now, 0 if not known: its frame time, to the millisecond
     * as the animation clock has it, and how far ahead the last frame asked about was planned.
     */
    val planned: Long
        get() = if (ahead >= 0L) AnimationUtils.currentAnimationTimeMillis() * 1_000_000L + ahead else 0L

    /**
     * The time to show the frame handed over now at, 0 if not known: half a display frame before the window's frame
     * is expected on screen, as the system shows a frame once its time has passed, so a time just before it shows
     * with that display frame and not the one before.
     */
    fun next(): Long {
        if (!asked) {
            asked = true
            Choreographer.getInstance().postVsyncCallback(vsync)
        }
        // After a still spell the window's frames have caught up, so they start on time again.
        val now = System.nanoTime()
        if (now - lastAsked > IDLE_NANOS) {
            lateNanos = 0L
            lastReading = -1L
            closeFences()
        }
        lastAsked = now
        readFences()
        val at = planned
        return if (at > 0L) at + lateNanos - period / 2 else 0L
    }

    /**
     * Asks for the present fence of the window frame drawing now, planned for what [planned] returns by then. Any
     * failure turns fences off for the session, as the listener is hidden on API 34.
     */
    @RequiresApi(34)
    fun askFence(root: AttachedSurfaceControl, executor: Executor, planned: () -> Long) {
        try {
            PresentFences.ask(root, executor, { fence -> shown(planned(), fence) }, { fenced = false })
        } catch (e: LinkageError) {
            fenced = false
        } catch (e: SecurityException) {
            fenced = false
        }
    }

    /** A window frame planned for [planned], 0 if not known, has gone to the screen, which [fence] signals once it's shown. */
    private fun shown(planned: Long, fence: SyncFence) {
        if (!attached || planned <= 0L) {
            fence.close()
            return
        }
        fences.add(planned to fence)
        // Too many waiting means they've stopped signalling: drop the oldest.
        while (fences.size > 8) fences.removeAt(0).second.close()
        readFences()
    }

    fun open() {
        attached = true
    }

    /** Off the window: closes the fences still held and stops asking for vsync data. */
    fun close() {
        attached = false
        closeFences()
        if (asked) {
            Choreographer.getInstance().removeVsyncCallback(vsync)
            asked = false
        }
    }

    private fun closeFences() {
        for (f in fences) f.second.close()
        fences.clear()
    }

    private fun readFences() {
        val it = fences.iterator()
        while (it.hasNext()) {
            val (at, fence) = it.next()
            val t = fence.signalTime
            if (t == SyncFence.SIGNAL_TIME_PENDING) continue
            it.remove()
            fence.close()
            if (t == SyncFence.SIGNAL_TIME_INVALID || period <= 0L) continue
            // Rounded to whole display frames, up to three.
            val frames = ((t - at + period / 2) / period).coerceIn(0L, 3L)
            if (frames == lastReading) lateNanos = frames * period
            lastReading = frames
        }
    }
}

/**
 * The window frame present fence listener, in its own class so TransactionStats is only loaded inside the caller's try.
 * Public from API 35 and hidden on 34, where it works on the phones tried.
 */
@RequiresApi(34)
private object PresentFences {
    @SuppressLint("NewApi") // Hidden on API 34: the caller turns it off on any LinkageError or SecurityException.
    fun ask(root: AttachedSurfaceControl, executor: Executor, onFence: (SyncFence) -> Unit, onFail: () -> Unit) {
        val t = SurfaceControl.Transaction().addTransactionCompletedListener(executor) { stats ->
            try {
                onFence(stats.presentFence)
            } catch (e: LinkageError) {
                onFail()
            } catch (e: SecurityException) {
                onFail()
            }
        }
        root.applyTransactionOnDraw(t)
    }
}
