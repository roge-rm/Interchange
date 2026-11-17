package com.rm.interchange.ui

import com.rm.interchange.platform.DeviceMemory
import com.rm.interchange.platform.MapLimits
import com.rm.interchange.platform.MapTooBig
import com.rm.interchange.sim.ByteWriter
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.Squeeze
import com.rm.interchange.sim.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MapLimitsTest {
    private val sizes = NewGame.SIZES.toList()

    private fun memory(heap: Int, total: Int) = DeviceMemory(heap, heap / 2, heap, total)

    @Test
    fun unknownMemoryAllowsEverySize() {
        assertEquals(sizes, MapLimits.sides(sizes, 4096, null))
    }

    @Test
    fun smallHeapStopsAt1024() {
        assertEquals(1024L * 1024, MapLimits.mostTiles(memory(192, 2048)))
        assertEquals(sizes.filter { it <= 1024 }, MapLimits.sides(sizes, 1024, memory(192, 2048)))
        // A narrow map can be longer, as it's the tiles that count.
        assertEquals(sizes.filter { it <= 4096 }, MapLimits.sides(sizes, 256, memory(192, 2048)))
    }

    @Test
    fun eachStepNeedsBothHeapAndRam() {
        for ((i, s) in MapLimits.STEPS.withIndex()) {
            assertEquals(s.tiles, MapLimits.mostTiles(memory(s.heap, s.total)))
            if (i == 0) continue
            val below = MapLimits.STEPS[i - 1].tiles
            assertEquals(below, MapLimits.mostTiles(memory(s.heap - 1, s.total)))
            assertEquals(below, MapLimits.mostTiles(memory(s.heap, s.total - 1)))
        }
    }

    @Test
    fun theSmallestSizeIsAlwaysOffered() {
        assertEquals(listOf(64), MapLimits.sides(listOf(64, 128), 1 shl 20, memory(0, 0)))
    }

    /** A save that's only its header and the start of its body, with the map's sides, as [Save.mapSize] reads. */
    private fun saveStart(width: Int, height: Int): ByteArray {
        val w = ByteWriter()
        for (b in "ICHG".encodeToByteArray()) w.byte(b.toInt())
        w.int(Save.VERSION)
        w.bool(false)
        w.bytes(Squeeze.pack(ByteWriter().also { it.int(width); it.int(height); it.long(1) }.toByteArray()))
        return w.toByteArray()
    }

    @Test
    fun aSaveTooBigIsRefusedBeforeReading() {
        val e = assertFailsWith<MapTooBig> { MapLimits.read(saveStart(2048, 1024), memory(192, 2048), "%1 × %2") { error("read") } }
        assertEquals("2048 × 1024", e.message)
        assertEquals(1, MapLimits.read(saveStart(2048, 512), memory(192, 2048), "") { 1 })
        assertEquals(1, MapLimits.read(saveStart(4096, 4096), null, "") { 1 })
    }

    @Test
    fun runningOutOfMemoryIsRefusedToo() {
        val e = assertFailsWith<MapTooBig> { MapLimits.read(saveStart(256, 256), null, "%1 × %2") { throw OutOfMemoryError() } }
        assertEquals("256 × 256", e.message)
    }

    @Test
    fun aRealSaveReadsThrough() {
        val w = World(NewGame(64, 128, 2))
        val data = Save.write(w)
        assertEquals(64 to 128, Save.mapSize(data))
        assertEquals(w.checksum(), MapLimits.read(data, memory(0, 0), "") { Save.read(it) }.checksum())
    }
}
