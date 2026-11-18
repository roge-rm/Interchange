package com.rm.interchange.map.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditLookTest {
    private fun region(index: Int, look: Int) =
        RegionSprites(0, index, index * REGION_ROWS, (index + 1) * REGION_ROWS, 1, IntArray(0), 0, IntArray(PASSES * REGION_ROWS + 1), look = look)

    @Test
    fun regionsRecordedWithANewLookWaitTillTheTurnShowsIt() {
        val old = Array<RegionSprites?>(3) { region(it, 0) }
        val published = old.copyOf()
        val regions = old.copyOf()
        regions[1] = region(1, 1)
        regions[2] = null
        publishRegions(regions, published, 0)
        assertSame(old[0], published[0])
        assertSame(old[1], published[1], "the old look stays till the turn's look moves on")
        assertNull(published[2])
        publishRegions(regions, published, 1)
        assertSame(regions[1], published[1])
        assertSame(old[0], published[0], "one not recorded again yet is drawn as it is")
        // An edit recorded with the same look shows at once.
        val edited = region(0, 1)
        regions[0] = edited
        publishRegions(regions, published, 1)
        assertSame(edited, published[0])
        // With nothing of the look shown kept, the new one's drawn.
        val fresh = arrayOfNulls<RegionSprites>(3)
        publishRegions(regions, fresh, 0)
        assertSame(regions[1], fresh[1])
    }

    @Test
    fun aSquareStandsOnlyWhileItsRegionsChangedForTheLook() {
        val old = Array<RegionSprites?>(3) { region(it, 0) }
        val square = arrayOf<Any?>(old[1]!!.content, old[2]!!.content)
        val looks = intArrayOf(0, 0)
        assertTrue(squareRegionsStand(0, square, looks, 1, old))
        assertTrue(squareRegionsSame(square, 1, old))
        val drawn = old.copyOf()
        drawn[2] = region(2, 1)
        assertTrue(squareRegionsStand(0, square, looks, 1, drawn), "recorded again for the new look")
        assertFalse(squareRegionsSame(square, 1, drawn))
        drawn[1] = region(1, 0)
        assertFalse(squareRegionsStand(0, square, looks, 1, drawn), "recorded again with the same look, an edit")
        drawn[1] = null
        assertFalse(squareRegionsStand(0, square, looks, 1, drawn))
    }

    /** A [regionVersion] with [changes] world changes, the [look] and no eras. */
    private fun version(changes: Long, look: Int = 0) = (changes * 8 + look) * com.rm.interchange.map.TileRecorder.PART_ERA_COUNT
    private fun vs(vararg changes: Long) = LongArray(changes.size) { version(changes[it]) }

    @Test
    fun anEditIsHeldTillTheGpuDrawsItsRegions() {
        val hold = EditHold()
        val base = vs(1, 2, 3)
        hold.start(1000, 0, 1..1, base)
        assertTrue(hold.holding)
        assertFalse(hold.changed(base.copyOf()))
        assertFalse(hold.changed(vs(9, 2, 9)), "a change away from the edit's regions")
        assertTrue(hold.changed(vs(1, 5, 3)))
        assertFalse(hold.current(vs(1, 5, 3), vs(1, 2, 3)))
        assertTrue(hold.current(vs(1, 5, 3), vs(1, 5, 3)))
        assertTrue(hold.current(vs(9, 5, 3), vs(1, 5, 3)), "only the edit's regions count")
        // A new look alone isn't a change, and an old recording with a later look isn't newer.
        assertFalse(hold.changed(longArrayOf(vs(1)[0], version(2, look = 1), vs(3)[0])))
        assertFalse(hold.current(vs(1, 5, 3), longArrayOf(vs(1)[0], version(2, look = 6), vs(3)[0])))
        // The game took the command, the regions are recording.
        assertTrue(hold.step(1050, changed = true, current = false, shownSeq = 10, drawnSeq = 9))
        assertEquals(0L, hold.waitSeq)
        // Recorded and handed on in seq 12, not drawn yet.
        assertTrue(hold.step(1100, changed = true, current = true, shownSeq = 12, drawnSeq = 11))
        assertEquals(12L, hold.waitSeq)
        assertTrue(hold.step(1110, changed = true, current = true, shownSeq = 13, drawnSeq = 11))
        assertEquals(12L, hold.waitSeq, "later frames don't put it off")
        // More of the command comes in before it's drawn: it waits for that too.
        assertTrue(hold.step(1112, changed = true, current = false, shownSeq = 13, drawnSeq = 12))
        assertEquals(0L, hold.waitSeq)
        assertTrue(hold.step(1115, changed = true, current = true, shownSeq = 14, drawnSeq = 12))
        assertFalse(hold.step(1120, changed = true, current = true, shownSeq = 15, drawnSeq = 14))
        assertFalse(hold.holding)
    }

    @Test
    fun anEditThatChangesNothingIsLetGo() {
        val hold = EditHold()
        hold.start(0, 0, 0..0, longArrayOf(1))
        assertTrue(hold.step(EditHold.NO_CHANGE_MILLIS, changed = false, current = true, shownSeq = 1, drawnSeq = 1))
        assertFalse(hold.step(EditHold.NO_CHANGE_MILLIS + 1, changed = false, current = true, shownSeq = 1, drawnSeq = 1))
        hold.start(0, 0, 0..0, longArrayOf(1))
        assertFalse(hold.expired(EditHold.MAX_MILLIS))
        assertTrue(hold.expired(EditHold.MAX_MILLIS + 1), "runs out with no draws")
        assertTrue(hold.step(EditHold.MAX_MILLIS, changed = true, current = false, shownSeq = 1, drawnSeq = 1))
        assertFalse(hold.step(EditHold.MAX_MILLIS + 1, changed = true, current = false, shownSeq = 1, drawnSeq = 1))
    }

    @Test
    fun anEditTouchesTheRegionsWhoseVersionsItMoves() {
        val world = com.rm.interchange.sim.World(com.rm.interchange.sim.NewGame(64, 64, 7, 1930, com.rm.interchange.sim.Region.MIXED, towns = 1, industries = 1, rivals = 0))
        for (turn in 0 until 4) {
            val x = 20
            val y = 37
            val near = regionsTouching(world, turn, x, y, x + 2, y)
            val before = LongArray(regionCount(world, turn)) { regionVersion(world, turn, it, 0, 1930) }
            world.touched(x + 1, y)
            for (i in before.indices) {
                val moved = regionVersion(world, turn, i, 0, 1930) != before[i]
                if (moved) assertTrue(i in near, "turn $turn region $i moved outside $near")
            }
            assertTrue(near.any { regionVersion(world, turn, it, 0, 1930) != before[it] })
        }
    }
}
