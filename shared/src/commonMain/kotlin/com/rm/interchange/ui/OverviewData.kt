package com.rm.interchange.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import com.rm.interchange.map.imageBitmapOf
import com.rm.interchange.map.terrainColour
import com.rm.interchange.sim.*

internal data class MapPlace(val x: Float, val y: Float, val owner: Int = -1, val name: String = "", val population: Int = 0, val id: Int = -1)
internal class MapIndustry(val place: MapPlace, val kind: IndustryKind, val english: String, val french: String, val info: OverviewIndustry) {
    fun name(lang: String) = if (lang == "fr") french else english
}
internal data class MapSpan(val x: Int, val y: Int, val ex: Int, val ey: Int, val owner: Int)

/** Arrays and positions copied between ticks; rasterising and making paths run off the game thread. */
internal class MapSnapshot(w: World) {
    val info = w.overviewInfo()
    val width = w.width
    val height = w.height
    val corners = w.corners.copyOf()
    val ground = w.ground.copyOf()
    val trees = w.trees.copyOf()
    val rail = w.track.copyOf()
    val roads = w.road.copyOf()
    val owner = w.owner.copyOf()
    val roadOwner = w.roadOwner.copyOf()
    val canals = w.canals.toHashSet()
    val spans = w.spans.filterNotNull().map { MapSpan(it.x0, it.y0, it.x1, it.y1, it.owner) }
    val towns = w.towns.map { MapPlace(it.x + 0.5f, it.y + 0.5f, name = it.name, population = it.population, id = it.id) }.sortedByDescending { it.population }
    val stations = w.stations.filterNotNull().map { MapPlace(it.signX + 0.5f, it.signY + 0.5f, it.owner, id = it.id) }
    val industries = w.industries.filter { !it.closed }.map { ind ->
        MapIndustry(MapPlace(ind.x + 1f, ind.y + 1f, id = ind.id), w.roster.industries[ind.kind],
            industryName(w, ind, "en"), industryName(w, ind, "fr"), info.industries.first { it.id == ind.id })
    }
    val stationById = stations.associateBy { it.id }
    val companies = w.companies.filter { !it.gone }.map { it.id to it.name }
}

internal class MapImage(val data: MapSnapshot) {
    val terrain: ImageBitmap
    val rail = linkedMapOf<Int, Path>()
    val roads = linkedMapOf<Int, Path>()
    init {
        val w = data.width
        val pixels = IntArray(w * data.height)
        for (i in pixels.indices) {
            val corner = i / w * (w + 1) + i % w
            pixels[i] = if (i in data.canals) terrainColour(0, 0, 0, 0, 0, 0) else terrainColour(
                data.corners[corner].toInt(), data.corners[corner + 1].toInt(),
                data.corners[corner + w + 2].toInt(), data.corners[corner + w + 1].toInt(),
                data.ground[i].toInt(), Trees.count(data.trees[i].toInt()),
            )
            val x = i % w + 0.5f
            val y = i / w + 0.5f
            for (p in Track.PIECES) if (data.rail[i].toInt() and p != 0) {
                val edges = Track.edges(p)
                val a = edges and 3
                val b = edges shr 2
                rail.getOrPut(data.owner[i].toInt()) { Path() }.apply {
                    moveTo(x + Track.dx(a) * 0.5f, y + Track.dy(a) * 0.5f)
                    lineTo(x + Track.dx(b) * 0.5f, y + Track.dy(b) * 0.5f)
                }
            }
            for (e in 0 until 4) if (data.roads[i].toInt() and roadBit(e) != 0) {
                roads.getOrPut(data.roadOwner[i].toInt()) { Path() }.apply {
                    moveTo(x, y)
                    lineTo(x + Track.dx(e) * 0.5f, y + Track.dy(e) * 0.5f)
                }
            }
        }
        for (s in data.spans) rail.getOrPut(s.owner) { Path() }.apply {
            moveTo(s.x + 0.5f, s.y + 0.5f)
            lineTo(s.ex + 0.5f, s.ey + 0.5f)
        }
        terrain = imageBitmapOf(pixels, data.width, data.height)
    }
}
