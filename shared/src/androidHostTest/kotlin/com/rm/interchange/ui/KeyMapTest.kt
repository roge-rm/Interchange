package com.rm.interchange.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyMapTest {
    @Test
    fun noKeyDoesTwoThings() {
        val names = KeyMap.DEFAULT.map { it.first }
        assertEquals(names.size, names.toSet().size, "keys used twice: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}")
    }

    @Test
    fun everyActionHasAKey() {
        for (a in KeyAction.entries) assertTrue(KeyMap.keysOf(a).isNotEmpty(), "$a has no key")
    }

    @Test
    fun everyGroupHasAKey() {
        for (g in ToolGroup.entries) assertNotNull(KeyAction.entries.firstOrNull { it.group == g }, "$g has no key")
    }

    @Test
    fun speedKeysCoverEverySpeed() {
        assertEquals(SPEEDS.indices.toSet(), KeyAction.entries.filter { it.speed >= 0 }.map { it.speed }.toSet())
    }

    @Test
    fun everyToolIsReachable() {
        for (editor in listOf(false, true)) {
            val reached = HashSet<Tool>()
            for (action in KeyAction.entries) {
                val g = action.group ?: continue
                // Pressed over and over from the inspect tool, as many times as there are tools.
                var tool = Tool.Inspect
                repeat(Tool.entries.size + 1) {
                    val next = keyTool(tool, g, editor, null) ?: return@repeat
                    tool = next
                    reached += tool
                }
            }
            if (KeyMap.keysOf(KeyAction.Remove).isNotEmpty()) reached += Tool.Remove
            val wanted = Tool.entries.filter { it.ready(editor) && (editor || !it.editorOnly) }
            for (t in wanted) assertTrue(t in reached, "$t can't be reached by keys, editor $editor")
        }
    }

    @Test
    fun editorGroupsAreOutsideTheEditor() {
        assertNull(keyTool(Tool.Inspect, ToolGroup.Towns, editor = false, last = null))
        assertEquals(Tool.PlaceTown, keyTool(Tool.Inspect, ToolGroup.Towns, editor = true, last = null))
    }

    @Test
    fun groupKeyOpensTheToolLastUsed() {
        assertEquals(Tool.Signals, keyTool(Tool.Inspect, ToolGroup.Rail, editor = false, last = Tool.Signals))
        // Pressed again it goes on to the next.
        assertEquals(Tool.Station, keyTool(Tool.Signals, ToolGroup.Rail, editor = false, last = Tool.Signals))
    }

    @Test
    fun keysThatWorkOverWindows() {
        assertTrue(KeyAction.Pause.overWindows)
        assertTrue(KeyAction.Close.overWindows)
        assertTrue(!KeyAction.Rail.overWindows)
        assertTrue(!KeyAction.PanUp.overWindows)
    }
}
