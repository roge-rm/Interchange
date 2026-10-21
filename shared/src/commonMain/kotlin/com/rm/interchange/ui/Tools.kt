package com.rm.interchange.ui

import com.rm.interchange.res.*
import org.jetbrains.compose.resources.StringResource

/** The buttons on the tool bar. Each holds a few tools, shown as tabs over its tray. [editorOnly] groups are only in the scenario editor. */
enum class ToolGroup(val title: StringResource, val glyph: Glyph, val editorOnly: Boolean = false) {
    Inspect(Res.string.group_inspect, Glyph.Inspect),
    Rail(Res.string.group_rail, Glyph.Rail),
    Road(Res.string.group_road, Glyph.Road),
    Water(Res.string.group_water, Glyph.Water),
    Air(Res.string.group_air, Glyph.Air),
    Land(Res.string.group_land, Glyph.Land),
    Towns(Res.string.group_towns, Glyph.Home, editorOnly = true),
    Industry(Res.string.group_industry, Glyph.Factory, editorOnly = true),
}

/**
 * What a tap or a drag on the map does. [ready] is false for tools still to come, shown dimmed; [editorReady] is the
 * same in the scenario editor, and [editorOnly] tools are only there.
 */
enum class Tool(
    val group: ToolGroup,
    val title: StringResource,
    val glyph: Glyph,
    val ready: Boolean = false,
    val editorReady: Boolean = ready,
    val editorOnly: Boolean = false,
) {
    Inspect(ToolGroup.Inspect, Res.string.group_inspect, Glyph.Inspect, ready = true),

    Track(ToolGroup.Rail, Res.string.tool_track, Glyph.Rail, ready = true),
    Signals(ToolGroup.Rail, Res.string.tool_signals, Glyph.Signal, ready = true),
    Station(ToolGroup.Rail, Res.string.tool_station, Glyph.Station, ready = true),
    Depot(ToolGroup.Rail, Res.string.tool_depot, Glyph.Depot, ready = true),
    Waypoint(ToolGroup.Rail, Res.string.tool_waypoint, Glyph.Waypoint, ready = true),
    Wires(ToolGroup.Rail, Res.string.tool_wires, Glyph.Wires, ready = true),
    SnowShed(ToolGroup.Rail, Res.string.tool_snow_shed, Glyph.Shed, ready = true),
    SnowFence(ToolGroup.Rail, Res.string.tool_snow_fence, Glyph.Fence, ready = true),
    Bridge(ToolGroup.Rail, Res.string.tool_bridge, Glyph.Bridge, ready = true),
    Tunnel(ToolGroup.Rail, Res.string.tool_tunnel, Glyph.Tunnel, ready = true),

    Road(ToolGroup.Road, Res.string.tool_road, Glyph.Road, ready = true),
    RoadStop(ToolGroup.Road, Res.string.tool_stops, Glyph.RoadStop, ready = true),
    Garage(ToolGroup.Road, Res.string.tool_garage, Glyph.Depot, ready = true),
    OneWay(ToolGroup.Road, Res.string.tool_one_way, Glyph.OneWay, ready = true),
    TramRails(ToolGroup.Road, Res.string.tool_tram, Glyph.Rail, ready = true),
    SaltDepot(ToolGroup.Road, Res.string.tool_salt, Glyph.Dome, ready = true),
    RoadBridge(ToolGroup.Road, Res.string.tool_bridge, Glyph.Bridge),
    RoadTunnel(ToolGroup.Road, Res.string.tool_tunnel, Glyph.Tunnel),

    Dock(ToolGroup.Water, Res.string.tool_dock, Glyph.Dock, ready = true),
    Shipyard(ToolGroup.Water, Res.string.tool_shipyard, Glyph.Depot, ready = true),
    Buoy(ToolGroup.Water, Res.string.tool_buoy, Glyph.Waypoint, ready = true),
    Canal(ToolGroup.Water, Res.string.tool_canal, Glyph.Canal, ready = true),
    Lock(ToolGroup.Water, Res.string.tool_lock, Glyph.Lock, ready = true),

    Airport(ToolGroup.Air, Res.string.tool_airport, Glyph.Airport, ready = true),

    Raise(ToolGroup.Land, Res.string.tool_raise, Glyph.Raise, ready = true),
    Lower(ToolGroup.Land, Res.string.tool_lower, Glyph.Lower, ready = true),
    Level(ToolGroup.Land, Res.string.tool_level, Glyph.Level, editorReady = true),
    Trees(ToolGroup.Land, Res.string.tool_trees, Glyph.Tree, editorReady = true),
    ClearTrees(ToolGroup.Land, Res.string.tool_clear_trees, Glyph.Remove, editorReady = true, editorOnly = true),
    Clear(ToolGroup.Land, Res.string.tool_clear, Glyph.Clear, ready = true),
    Remove(ToolGroup.Land, Res.string.tool_remove, Glyph.Remove, ready = true),

    PlaceTown(ToolGroup.Towns, Res.string.tool_place_town, Glyph.Home, editorReady = true, editorOnly = true),
    GrowTown(ToolGroup.Towns, Res.string.tool_grow_town, Glyph.Plus, editorReady = true, editorOnly = true),

    PlaceIndustry(ToolGroup.Industry, Res.string.tool_place_industry, Glyph.Factory, editorReady = true, editorOnly = true),
    RemoveIndustry(ToolGroup.Industry, Res.string.tool_remove_industry, Glyph.Remove, editorReady = true, editorOnly = true);

    fun ready(editor: Boolean) = if (editor) editorReady else ready

    companion object {
        /** The group's tabs. Remove is a button on the tool bar, so it stays out of the land strip. */
        fun of(group: ToolGroup, editor: Boolean = false) = entries.filter { it.group == group && it != Remove && (editor || !it.editorOnly) }
    }
}
