package com.rm.interchange.dedicated

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The last few hundred log lines, kept for the admin page. Every line also
 * goes to stdout, for the container's log.
 */
class LogRing(private val capacity: Int = 400) {
    class Line(val epochMillis: Long, val level: String, val text: String) {
        val timestamp: String get() = FORMAT.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC))

        override fun toString() = "$timestamp  ${level.padEnd(5)} $text"
    }

    private val lines = ArrayDeque<Line>(capacity)

    fun info(text: String) = add("INFO", text)
    fun warn(text: String) = add("WARN", text)

    private fun add(level: String, text: String) {
        val line = Line(System.currentTimeMillis(), level, text)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > capacity) lines.removeFirst()
        }
        println(line)
    }

    /** The last [count] lines, oldest first. */
    fun recent(count: Int = 100): List<Line> = synchronized(lines) { lines.takeLast(count.coerceIn(1, capacity)) }

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
