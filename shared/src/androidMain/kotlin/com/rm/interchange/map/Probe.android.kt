package com.rm.interchange.map

internal actual fun probeNanos(): Long = System.nanoTime()

internal actual fun probeHashWanted(): Boolean = try {
    Runtime.getRuntime().exec(arrayOf("getprop", "debug.interchange.hash")).inputStream.bufferedReader().readText().trim() == "1"
} catch (e: Exception) {
    false
}

internal actual fun probeSaves(): Map<String, ByteArray> {
    val pkg = java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
    val files = java.io.File("/data/data/$pkg/files/game").listFiles() ?: return emptyMap()
    return files.filter { it.name.endsWith(".ichg") && !it.name.startsWith("autosave") }.sortedBy { it.name }.associate { it.name to it.readBytes() }
}
