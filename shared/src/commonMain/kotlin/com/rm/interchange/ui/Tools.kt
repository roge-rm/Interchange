package com.rm.interchange.ui

/** The buttons on the tool bar. Each holds a few tools, shown as tabs over its tray. */
enum class ToolGroup(val title: String, val glyph: Glyph) {
    Inspect("Inspect", Glyph.Inspect),
    Rail("Rail", Glyph.Rail),
    Road("Road", Glyph.Road),
    Water("Water", Glyph.Water),
    Air("Air", Glyph.Air),
    Land("Land", Glyph.Land),
}

/** What a tap or a drag on the map does. [ready] is false for tools still to come, shown dimmed. */
enum class Tool(val group: ToolGroup, val title: String, val glyph: Glyph, val ready: Boolean = false) {
    Inspect(ToolGroup.Inspect, "Inspect", Glyph.Inspect, ready = true),

    Track(ToolGroup.Rail, "Track", Glyph.Rail, ready = true),
    Signals(ToolGroup.Rail, "Signals", Glyph.Signal),
    Station(ToolGroup.Rail, "Station", Glyph.Station),
    Depot(ToolGroup.Rail, "Depot", Glyph.Depot),
    Bridge(ToolGroup.Rail, "Bridge", Glyph.Bridge),
    Tunnel(ToolGroup.Rail, "Tunnel", Glyph.Tunnel),

    Road(ToolGroup.Road, "Road", Glyph.Road),
    RoadStop(ToolGroup.Road, "Stops", Glyph.RoadStop),
    Garage(ToolGroup.Road, "Garage", Glyph.Depot),
    RoadBridge(ToolGroup.Road, "Bridge", Glyph.Bridge),
    RoadTunnel(ToolGroup.Road, "Tunnel", Glyph.Tunnel),

    Canal(ToolGroup.Water, "Canal", Glyph.Canal),
    Dock(ToolGroup.Water, "Dock", Glyph.Dock),
    Shipyard(ToolGroup.Water, "Shipyard", Glyph.Depot),
    Lock(ToolGroup.Water, "Lock", Glyph.Lock),

    Airport(ToolGroup.Air, "Airport", Glyph.Airport),
    Hangar(ToolGroup.Air, "Hangar", Glyph.Depot),

    Raise(ToolGroup.Land, "Raise", Glyph.Raise, ready = true),
    Lower(ToolGroup.Land, "Lower", Glyph.Lower, ready = true),
    Level(ToolGroup.Land, "Level", Glyph.Level),
    Trees(ToolGroup.Land, "Trees", Glyph.Tree),
    Clear(ToolGroup.Land, "Clear", Glyph.Clear);

    companion object {
        fun of(group: ToolGroup) = entries.filter { it.group == group }
    }
}
