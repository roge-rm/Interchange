package com.rm.interchange.sim

/**
 * Plain bytes for saves, commands and the network: numbers as variable-length
 * integers, strings as UTF-8 with their length first. Everything the game
 * writes goes through these two, so a save reads back the same on any device.
 */
class ByteWriter(initial: Int = 1024) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    private fun room(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun byte(v: Int) {
        room(1)
        buf[size++] = v.toByte()
    }

    /** An unsigned number, seven bits a byte. */
    fun uvar(v: Long) {
        var x = v
        while (x and 0x7fL.inv() != 0L) {
            byte(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
        byte(x.toInt())
    }

    /** A signed number, zigzagged so small negatives stay small. */
    fun svar(v: Long) = uvar((v shl 1) xor (v shr 63))

    fun int(v: Int) = svar(v.toLong())
    fun long(v: Long) = svar(v)
    fun bool(v: Boolean) = byte(if (v) 1 else 0)

    fun bytes(b: ByteArray) {
        uvar(b.size.toLong())
        room(b.size)
        b.copyInto(buf, size)
        size += b.size
    }

    fun string(s: String) = bytes(s.encodeToByteArray())

    fun ints(a: IntArray) {
        uvar(a.size.toLong())
        for (v in a) int(v)
    }

    /** Each short as two bytes, low first, as one block. */
    fun shorts(a: ShortArray) = bytes(ByteArray(a.size * 2) { k -> (a[k / 2].toInt() shr (k % 2 * 8)).toByte() })

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

class ByteReader(private val buf: ByteArray, private var at: Int = 0) {
    val done get() = at >= buf.size

    fun byte(): Int {
        if (at >= buf.size) throw BadData("ran out of data")
        return buf[at++].toInt() and 0xff
    }

    fun uvar(): Long {
        var shift = 0
        var out = 0L
        while (true) {
            val b = byte()
            out = out or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return out
            shift += 7
            if (shift > 63) throw BadData("number too long")
        }
    }

    fun svar(): Long {
        val u = uvar()
        return (u ushr 1) xor -(u and 1)
    }

    fun int(): Int = svar().toInt()
    fun long(): Long = svar()
    fun bool(): Boolean = byte() != 0

    fun bytes(): ByteArray {
        val n = uvar().toInt()
        if (n < 0 || at + n > buf.size) throw BadData("a block runs past the end")
        val out = buf.copyOfRange(at, at + n)
        at += n
        return out
    }

    fun string(): String = bytes().decodeToString()

    fun shorts(): ShortArray {
        val b = bytes()
        return ShortArray(b.size / 2) { k -> ((b[k * 2].toInt() and 0xff) or (b[k * 2 + 1].toInt() shl 8)).toShort() }
    }

    fun ints(): IntArray {
        val n = uvar().toInt()
        if (n < 0 || n > buf.size) throw BadData("too many numbers")
        return IntArray(n) { int() }
    }
}

/** Data that isn't what it should be: a damaged save, or a message from a broken client. */
open class BadData(message: String) : Exception(message)

/** A save that needs packs this game hasn't got, or not at the version it was made with. */
class MissingPacks(val packs: List<PackRef>) : BadData("needs " + packs.joinToString { "${it.id} ${it.version}" })

/** Squeezes bytes smaller and back, for saves; zlib on the JVM. */
expect object Squeeze {
    fun pack(data: ByteArray): ByteArray
    fun unpack(data: ByteArray): ByteArray
}
