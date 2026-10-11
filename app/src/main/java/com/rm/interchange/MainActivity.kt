package com.rm.interchange

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rm.interchange.platform.AndroidFiles
import com.rm.interchange.platform.AndroidPictures
import com.rm.interchange.platform.Lifecycle
import com.rm.interchange.platform.Picker
import com.rm.interchange.platform.files
import com.rm.interchange.platform.picker
import com.rm.interchange.platform.pictures
import com.rm.interchange.sim.Packs
import com.rm.interchange.ui.App

class MainActivity : ComponentActivity(), Picker {
    private var onPicked: ((ByteArray?) -> Unit)? = null

    // The system's file chooser. The file is read off the main thread, and turned away if it's bigger than a pack can be.
    private val chooser = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val done = onPicked ?: return@registerForActivityResult
        onPicked = null
        if (uri == null) { done(null); return@registerForActivityResult }
        Thread {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > Packs.MAX_TOTAL) return@use null
                    }
                    out.toByteArray()
                }
            }.getOrNull()
            runOnUiThread { done(bytes) }
        }.start()
    }

    override fun pickFile(onPicked: (ByteArray?) -> Unit) {
        this.onPicked = onPicked
        chooser.launch(arrayOf("*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The map fills the whole screen and the controls over it keep clear of the bars.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Into the camera cutout too: the bars over the map keep clear of it themselves.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        files = AndroidFiles(filesDir)
        picker = this
        pictures = AndroidPictures
        setContent { App() }
    }

    /** Put away: the game saves itself. */
    override fun onStop() {
        super.onStop()
        Lifecycle.onHide?.invoke()
    }
}
