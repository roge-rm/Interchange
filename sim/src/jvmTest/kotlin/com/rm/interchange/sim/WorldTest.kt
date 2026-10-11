package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WorldTest {
    @Test
    fun sameSeedAndCommandsGiveTheSameWorld() {
        val a = World(64, 64, 42)
        val b = World(64, 64, 42)
        for (w in listOf(a, b)) {
            w.apply(Command.RaiseLand(0, 10, 10))
            repeat(100) { w.step() }
            w.apply(Command.LowerLand(1, 20, 5))
        }
        assertEquals(a.checksum(), b.checksum())
    }

    @Test
    fun aDifferentCommandChangesTheChecksum() {
        val a = World(64, 64, 42)
        val b = World(64, 64, 42)
        b.apply(Command.RaiseLand(0, 3, 3))
        assertNotEquals(a.checksum(), b.checksum())
    }
}
