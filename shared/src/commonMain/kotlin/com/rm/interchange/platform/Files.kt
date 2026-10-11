package com.rm.interchange.platform

/**
 * The game's own files: saved games and settings, by name. Saves are written
 * whole to a new file and then moved into place, so a crash mid-write can't
 * spoil the last good one.
 */
interface Files {
    fun write(name: String, data: ByteArray)
    fun read(name: String): ByteArray?
    fun delete(name: String)
    /** The names of the files whose names start with [prefix], newest first. */
    fun list(prefix: String): List<String>
    /** When a file was last written, in milliseconds since 1970, or 0 if there's none. */
    fun modified(name: String): Long
}

/** This platform's files. Set up by the app before the screen starts. */
lateinit var files: Files
