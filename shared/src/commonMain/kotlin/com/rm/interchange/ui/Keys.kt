package com.rm.interchange.ui

import com.rm.interchange.res.*
import org.jetbrains.compose.resources.StringResource

/**
 * What a key does. Actions with the same [title] share a row in the key list. [group] is the tool group a key picks;
 * [held] actions keep going while the key's down.
 */
enum class KeyAction(val title: StringResource, val group: ToolGroup? = null, val held: Boolean = false, val speed: Int = -1) {
    Pause(Res.string.key_pause),
    Speed1(Res.string.speed, speed = 0),
    Speed2(Res.string.speed, speed = 1),
    Speed3(Res.string.speed, speed = 2),
    Speed4(Res.string.speed, speed = 3),
    Close(Res.string.key_close),
    ZoomIn(Res.string.map_zoom_in),
    ZoomOut(Res.string.map_zoom_out),
    TurnLeft(Res.string.turn_left),
    TurnRight(Res.string.turn_right),
    PanUp(Res.string.key_pan, held = true),
    PanDown(Res.string.key_pan, held = true),
    PanLeft(Res.string.key_pan, held = true),
    PanRight(Res.string.key_pan, held = true),
    Map(Res.string.overview_map),
    Finances(Res.string.finances),
    Overlays(Res.string.overlays),
    Inspect(Res.string.group_inspect, ToolGroup.Inspect),
    Rail(Res.string.group_rail, ToolGroup.Rail),
    Road(Res.string.group_road, ToolGroup.Road),
    Water(Res.string.group_water, ToolGroup.Water),
    Air(Res.string.group_air, ToolGroup.Air),
    Land(Res.string.group_land, ToolGroup.Land),
    Towns(Res.string.group_towns, ToolGroup.Towns),
    Industry(Res.string.group_industry, ToolGroup.Industry),
    Remove(Res.string.tool_remove),
    ;

    /** Whether it still works with a window open. */
    val overWindows get() = this == Pause || speed >= 0 || this == Close
}

/**
 * The keys, by name: lower case letters and digits, and space, esc, delete, backspace, plus, equals, minus, up, down,
 * left and right. A tool group's key picks the tool last used in it, and pressed again goes on to the group's next tool.
 */
object KeyMap {
    val DEFAULT: List<Pair<String, KeyAction>> = listOf(
        "space" to KeyAction.Pause,
        "1" to KeyAction.Speed1, "2" to KeyAction.Speed2, "3" to KeyAction.Speed3, "4" to KeyAction.Speed4,
        "esc" to KeyAction.Close,
        "plus" to KeyAction.ZoomIn, "equals" to KeyAction.ZoomIn, "minus" to KeyAction.ZoomOut,
        "q" to KeyAction.TurnLeft, "e" to KeyAction.TurnRight,
        "up" to KeyAction.PanUp, "down" to KeyAction.PanDown, "left" to KeyAction.PanLeft, "right" to KeyAction.PanRight,
        "m" to KeyAction.Map, "f" to KeyAction.Finances, "v" to KeyAction.Overlays,
        "i" to KeyAction.Inspect, "r" to KeyAction.Rail, "o" to KeyAction.Road, "w" to KeyAction.Water,
        "a" to KeyAction.Air, "l" to KeyAction.Land, "t" to KeyAction.Towns, "n" to KeyAction.Industry,
        "x" to KeyAction.Remove, "delete" to KeyAction.Remove, "backspace" to KeyAction.Remove,
    )

    private val byName = DEFAULT.toMap()

    fun action(name: String): KeyAction? = byName[name]

    /** Each action's keys, in the order of [DEFAULT]. */
    fun keysOf(action: KeyAction): List<String> = DEFAULT.filter { it.second == action }.map { it.first }
}

/**
 * The tool a group's key picks: the group's tool last used if it's not in use, or the next ready one after the one
 * in use. Null if the group isn't there, as the editor's groups outside it.
 */
fun keyTool(current: Tool, group: ToolGroup, editor: Boolean, last: Tool?): Tool? {
    if (group.editorOnly && !editor) return null
    val tools = Tool.of(group, editor).filter { it.ready(editor) }
    if (tools.isEmpty()) return null
    val at = tools.indexOf(current)
    if (at >= 0) return tools[(at + 1) % tools.size]
    return last?.takeIf { it in tools } ?: tools.first()
}
