package com.rm.interchange.map.gl

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// SystemProperties by reflection, so the first map frame doesn't wait on a getprop process.
private val wanted by lazy {
    try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "debug.interchange.gl") == "1"
    } catch (e: Exception) {
        false
    }
}

internal actual fun glMapWanted(): Boolean = wanted

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
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 0, 0)
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
                Lifecycle.Event.ON_PAUSE -> surface[0]?.onPause()
                Lifecycle.Event.ON_RESUME -> surface[0]?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
