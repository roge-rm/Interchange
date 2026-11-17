package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BytesStreamTest {
    private fun write(w: ByteWriter) {
        w.int(-5); w.long(1L shl 40); w.string("Moose Jaw")
        w.bytes(ByteArray(300_000) { (it * 7).toByte() })
        w.shorts(ShortArray(100_000) { (it * 3).toShort() })
        w.ints(IntArray(50_000) { it * 11 - 9 })
        repeat(20_000) { w.uvar(it.toLong()) }
        w.bool(true)
    }

    @Test
    fun drainedWriterMakesTheSameBytes() {
        val whole = ByteWriter().also { write(it) }.toByteArray()
        val parts = java.io.ByteArrayOutputStream()
        val w = ByteWriter(4096) { b, n -> parts.write(b, 0, n) }
        write(w)
        w.flush()
        assertContentEquals(whole, parts.toByteArray())
    }

    @Test
    fun streamedReaderReadsWhatWasPacked() {
        val packer = Squeeze.packer()
        val w = ByteWriter(4096) { b, n -> packer.add(b, n) }
        write(w)
        w.flush()
        val r = ByteReader(Squeeze.unpacker(packer.finish()))
        assertEquals(-5, r.int()); assertEquals(1L shl 40, r.long()); assertEquals("Moose Jaw", r.string())
        assertContentEquals(ByteArray(300_000) { (it * 7).toByte() }, r.bytes())
        assertContentEquals(ShortArray(100_000) { (it * 3).toShort() }, r.shorts())
        assertContentEquals(IntArray(50_000) { it * 11 - 9 }, r.ints())
        // Counts bounded by what's left aren't cut to one buffer's worth.
        assertTrue(r.left > 1_000_000)
        repeat(20_000) { assertEquals(it.toLong(), r.uvar()) }
        assertTrue(r.bool())
        assertTrue(r.done)
        assertFailsWith<BadData> { r.byte() }
    }

    @Test
    fun cutShortSaveFails() {
        val world = World(NewGame(64, 64, 3))
        val data = Save.write(world)
        assertFailsWith<BadData> { Save.read(data.copyOf(data.size - 40)) }
        assertEquals(world.checksum(), Save.read(data).checksum())
    }
}
