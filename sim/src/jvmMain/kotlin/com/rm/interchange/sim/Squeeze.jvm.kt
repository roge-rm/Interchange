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

    actual fun packer(): Packer = object : Packer {
        private val d = Deflater(Deflater.BEST_SPEED)
        private val out = ByteArrayOutputStream(1 shl 16)
        private val chunk = ByteArray(64 * 1024)

        override fun add(b: ByteArray, n: Int) {
            d.setInput(b, 0, n)
            while (!d.needsInput()) out.write(chunk, 0, d.deflate(chunk))
        }

        override fun finish(): ByteArray {
            d.finish()
            while (!d.finished()) out.write(chunk, 0, d.deflate(chunk))
            d.end()
            return out.toByteArray()
        }
    }

    actual fun unpacker(data: ByteArray): (ByteArray, Int, Int) -> Int {
        val i = Inflater()
        i.setInput(data)
        return fun(buf: ByteArray, off: Int, len: Int): Int {
            while (true) {
                if (i.finished()) {
                    i.end()
                    return -1
                }
                val n = i.inflate(buf, off, len)
                if (n > 0) return n
                if (i.needsInput() || i.needsDictionary()) throw BadData("save is cut short")
            }
        }
    }
}
