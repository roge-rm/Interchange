package com.rm.interchange.platform

import java.io.File

/** The game's files in the app's own folder. */
class AndroidFiles(root: File) : Files {
    private val dir = File(root, "game").apply { mkdirs() }

    override fun write(name: String, data: ByteArray) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(data)
        tmp.renameTo(File(dir, name))
    }

    override fun read(name: String): ByteArray? = File(dir, name).takeIf { it.isFile }?.readBytes()

    override fun delete(name: String) {
        File(dir, name).delete()
    }

    override fun list(prefix: String): List<String> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.startsWith(prefix) && !it.name.endsWith(".tmp") }
            .sortedByDescending { it.lastModified() }.map { it.name }

    override fun modified(name: String): Long = File(dir, name).lastModified()
}
