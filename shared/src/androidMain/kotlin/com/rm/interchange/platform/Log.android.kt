package com.rm.interchange.platform

import android.util.Log

actual fun logLine(text: String) {
    Log.i("Interchange", text)
}
