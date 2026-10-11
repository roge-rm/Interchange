package com.rm.interchange.sim

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

actual object Zip {
    actual fun read(zip: ByteArray, maxFile: Int, maxTotal: Int): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                val buf = ByteArray(65536)
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.replace('\\', '/')
                    if (name.startsWith("/") || ".." in name.split('/')) throw BadPack(listOf("$name: a path outside the pack"))
                    val bytes = ByteArrayOutputStream()
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        bytes.write(buf, 0, n)
                        total += n
                        if (bytes.size() > maxFile) throw BadPack(listOf("$name is bigger than ${maxFile shr 20} MB"))
                        if (total > maxTotal) throw BadPack(listOf("the pack is bigger than ${maxTotal shr 20} MB unpacked"))
                    }
                    out[name] = bytes.toByteArray()
                }
            }
        } catch (e: ZipException) {
            throw BadPack(listOf("not a zip file: ${e.message}"))
        }
        if (out.isEmpty()) throw BadPack(listOf("not a zip file, or an empty one"))
        return out
    }
}
