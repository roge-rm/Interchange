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
        r.close(check = true)
    }

    @Test
    fun cutShortSaveFails() {
        val world = World(NewGame(64, 64, 3))
        val data = Save.write(world)
        assertFailsWith<BadData> { Save.read(data.copyOf(data.size - 40)) }
        // The packed body cut short inside, its length before it right.
        val packed = Squeeze.pack(body(data))
        assertFailsWith<BadData> { Save.read(save(packed.copyOf(packed.size * 2 / 3))) }
        assertFailsWith<BadData> { Save.read(save(packed.copyOf(packed.size - 2))) }
        assertEquals(world.checksum(), Save.read(save(packed)).checksum())
        assertEquals(world.checksum(), Save.read(data).checksum())
    }

    @Test
    fun damagedChecksumFails() {
        val data = Save.write(World(NewGame(64, 64, 3)))
        // The adler32 is the packed body's last four bytes, the save's too.
        val bad = data.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<BadData> { Save.read(bad) }
        // Only the packs are read, so the end isn't checked.
        assertEquals(Save.packs(data), Save.packs(bad))
    }

    @Test
    @Suppress("DEPRECATION")
    fun corruptCountFailsWithoutAHugeArray() {
        val world = World(NewGame(64, 64, 3))
        val body = body(Save.write(world))
        // The base pack's id is the first block in the body, its length one byte; made 60 million.
        val id = byteArrayOf(Packs.BASE_ID.length.toByte()) + Packs.BASE_ID.encodeToByteArray()
        val at = (0..body.size - id.size).first { k -> id.indices.all { body[k + it] == id[it] } }
        val count = ByteWriter().also { it.uvar(60_000_000) }.toByteArray()
        val bad = body.copyOfRange(0, at) + count + body.copyOfRange(at + 1, body.size)
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = threads.getThreadAllocatedBytes(Thread.currentThread().id)
        assertFailsWith<BadData> { Save.read(save(Squeeze.pack(bad))) }
        val allocated = threads.getThreadAllocatedBytes(Thread.currentThread().id) - before
        assertTrue(allocated < 16_000_000, "allocated $allocated bytes")
        // The same for numbers.
        val packer = Squeeze.packer()
        ByteWriter(4096) { b, n -> packer.add(b, n) }.also { it.uvar(60_000_000); repeat(1000) { k -> it.int(k) } }.flush()
        val r = ByteReader(Squeeze.unpacker(packer.finish()))
        assertFailsWith<BadData> { r.ints() }
        r.close()
    }

    /** The unpacked body of save [data], which has no scenario heading. */
    private fun body(data: ByteArray): ByteArray {
        val r = ByteReader(data)
        repeat(4) { r.byte() }
        assertEquals(Save.VERSION, r.int())
        assertEquals(false, r.bool())
        return Squeeze.unpack(r.bytes())
    }

    /** A save around [packed], as [Save.write] makes. */
    private fun save(packed: ByteArray): ByteArray {
        val w = ByteWriter()
        for (b in "ICHG".encodeToByteArray()) w.byte(b.toInt())
        w.int(Save.VERSION)
        w.bool(false)
        w.bytes(packed)
        return w.toByteArray()
    }
}
