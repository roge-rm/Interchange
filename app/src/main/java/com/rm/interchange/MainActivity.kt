package com.rm.interchange

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
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
import com.rm.interchange.platform.Network
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

    private var askedNotifications = false
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
        // Wi-Fi drops broadcasts unless something holds a multicast lock, so one is held while listening for games nearby.
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("interchange-discover")?.apply { setReferenceCounted(false) }
        Network.listening = { on -> lock?.let { if (on) it.acquire() else if (it.isHeld) it.release() } }
        // The game keeps hosting in the background through a foreground service. Notifications are asked for once, and hosting goes ahead if refused.
        Network.hosting = { name, players ->
            if (name == null) {
                HostingService.stop(this)
            } else {
                if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    askedNotifications = true
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                HostingService.start(this, name, players)
            }
        }
        setContent { App() }
    }

    /** Put away: the game saves itself. */
    override fun onStop() {
        super.onStop()
        Lifecycle.onHide?.invoke()
    }
}
