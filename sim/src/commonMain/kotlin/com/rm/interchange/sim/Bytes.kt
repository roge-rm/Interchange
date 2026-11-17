package com.rm.interchange.sim

/**
 * Plain bytes for saves, commands and the network: numbers as variable-length
 * integers, strings as UTF-8 with their length first. Everything the game
 * writes goes through these two, so a save reads back the same on any device.
 */
class ByteWriter(initial: Int = 1024, private val drain: ((ByteArray, Int) -> Unit)? = null) {
    private var buf = ByteArray(initial)
    /** Bytes held; with [drain], only those not drained yet. */
    var size = 0
        private set

    private fun room(n: Int) {
        if (size + n <= buf.size) return
        if (drain != null) {
            flush()
            if (n <= buf.size) return
        }
        buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    /** Hands what's held to [drain], for a writer that passes its bytes on as it goes instead of holding them all. */
    fun flush() {
        if (drain != null && size > 0) drain(buf, size)
        size = 0
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
        if (drain != null && b.size > buf.size) {
            flush()
            drain(b, b.size)
            return
        }
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

/**
 * Reads what [ByteWriter] wrote, from a whole buffer, or from a [source] that fills a buffer bit by bit, for a save too
 * big to hold whole before reading. [close] lets go of the source.
 */
class ByteReader private constructor(private var buf: ByteArray, private var at: Int, private var end: Int, private val source: Unpacker?) {
    constructor(buf: ByteArray, at: Int = 0) : this(buf, at, buf.size, null)
    constructor(source: Unpacker) : this(ByteArray(1 shl 16), 0, 0, source)

    val done get() = at >= end && !refill()
    /** Bytes not read yet, for bounding counts read from the data; a [source]'s aren't known ahead, so the most a block may be. */
    val left get() = if (source == null) end - at else MOST_BLOCK

    private fun refill(): Boolean {
        if (source == null) return false
        val n = source.read(buf, 0, buf.size)
        if (n <= 0) return false
        at = 0
        end = n
        return true
    }

    fun byte(): Int {
        if (at >= end && !refill()) throw BadData("ran out of data")
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
        if (source == null) {
            if (n < 0 || at + n > end) throw BadData("a block runs past the end")
            val out = buf.copyOfRange(at, at + n)
            at += n
            return out
        }
        if (n < 0 || n > MOST_BLOCK) throw BadData("a block too big")
        // Grown as the bytes come, so a wrong count ends in BadData instead of a huge array.
        var out = ByteArray(minOf(n, GROW_FROM))
        var k = 0
        while (k < n) {
            if (at >= end && !refill()) throw BadData("a block runs past the end")
            if (k == out.size) out = out.copyOf(minOf(n, out.size * 2))
            val take = minOf(end - at, out.size - k)
            buf.copyInto(out, k, at, at + take)
            at += take
            k += take
        }
        return out
    }

    /** Where in the whole buffer the next block is, for reading it in place; moves past it. */
    fun blockRange(): IntRange {
        check(source == null)
        val n = uvar()
        if (n < 0 || at + n > end) throw BadData("a block runs past the end")
        return (at until at + n.toInt()).also { at += n.toInt() }
    }

    fun string(): String = bytes().decodeToString()

    fun shorts(): ShortArray {
        val b = bytes()
        return ShortArray(b.size / 2) { k -> ((b[k * 2].toInt() and 0xff) or (b[k * 2 + 1].toInt() shl 8)).toShort() }
    }

    fun ints(): IntArray = ints(if (source == null) end.toLong() else MOST_BLOCK.toLong())

    /** A count of numbers, up to [most], then the numbers; streamed, the array grows as they come, as in [bytes]. */
    fun ints(most: Long): IntArray {
        val n = uvar()
        if (n !in 0..most) throw BadData("too many numbers")
        if (source == null) return IntArray(n.toInt()) { int() }
        var out = IntArray(minOf(n, GROW_FROM.toLong()).toInt())
        for (k in 0 until n.toInt()) {
            if (k == out.size) out = out.copyOf(minOf(n, out.size * 2L).toInt())
            out[k] = int()
        }
        return out
    }

    /** Lets go of the source; with [check], first reads it to the end and checks it came through whole, or BadData. */
    fun close(check: Boolean = false) {
        source?.close(check)
    }
}

/** What a streamed [ByteReader] starts a block or numbers at before growing it, in items. */
private const val GROW_FROM = 1 shl 16

/** The biggest block a streamed [ByteReader] reads: twice a 4096 map's two-byte layer. */
private const val MOST_BLOCK = 1 shl 26

/** Data that isn't what it should be: a damaged save, or a message from a broken client. */
open class BadData(message: String) : Exception(message)

/** A save that needs packs this game hasn't got, or not at the version it was made with. */
class MissingPacks(val packs: List<PackRef>) : BadData("needs " + packs.joinToString { "${it.id} ${it.version}" })

/** Squeezes bytes smaller and back, for saves; zlib on the JVM. */
expect object Squeeze {
    fun pack(data: ByteArray): ByteArray
    fun unpack(data: ByteArray): ByteArray
    /** A packer that takes the bytes bit by bit, so a big save needn't be held whole before packing. */
    fun packer(): Packer
    /** [data] from [from] until [until] unpacked bit by bit, as a [ByteReader] source. */
    fun unpacker(data: ByteArray, from: Int = 0, until: Int = data.size): Unpacker
}

/** Packs what it's [add]ed, the first [add] n bytes of each array, all of each taken before [add] returns. */
interface Packer {
    fun add(b: ByteArray, n: Int)
    fun finish(): ByteArray
}

/** Unpacks bit by bit, for a streamed [ByteReader]. */
interface Unpacker {
    /** Up to [len] bytes into [buf] at [off]: how many, or -1 at the end. BadData if they're cut short or damaged. */
    fun read(buf: ByteArray, off: Int, len: Int): Int
    /** Lets go of what it holds, once; with [check], first reads to the end and checks the data came through whole, or BadData. */
    fun close(check: Boolean)
}
