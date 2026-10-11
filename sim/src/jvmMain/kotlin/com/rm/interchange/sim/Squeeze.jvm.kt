package com.rm.interchange.sim

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

actual object Squeeze {
    actual fun pack(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_SPEED)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream(data.size / 4 + 64)
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    actual fun unpack(data: ByteArray): ByteArray {
        val i = Inflater()
        i.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buf = ByteArray(64 * 1024)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) throw BadData("save is cut short")
            out.write(buf, 0, n)
        }
        i.end()
        return out.toByteArray()
    }
}
