package com.rm.interchange.platform

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun deviceMemory(): DeviceMemory? {
    val context = LocalContext.current
    return remember(context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@remember null
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        DeviceMemory(
            heap = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt(),
            normalHeap = am.memoryClass,
            largeHeap = am.largeMemoryClass,
            total = (info.totalMem / (1024 * 1024)).toInt(),
        )
    }
}
