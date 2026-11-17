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

/** The most terrain pixels the overview has on a side; a bigger map takes every second tile or so. */
internal const val TERRAIN_MOST = 2048

/**
 * What the overview draws, taken between ticks: what the terrain's colours are made from, a pixel for each
 * [terrainStep] tiles square, and the tiles with rail or road; colouring and making paths run off the game thread.
 */
internal class MapSnapshot(w: World) {
    val info = w.overviewInfo()
    val width = w.width
    val height = w.height
    val terrainStep = (maxOf(width, height) + TERRAIN_MOST - 1) / TERRAIN_MOST
    val terrainWidth = maxOf(1, width / terrainStep)
    val terrainHeight = maxOf(1, height / terrainStep)
    /** Each pixel's [terrainTile] until [terrainColours] colours them in place. */
    private val terrain = terrainTiles(w, terrainStep, terrainWidth, terrainHeight)
    private var coloured = false

    /** The terrain's colours, coloured the first time it's asked, off the game thread. */
    fun terrainColours(): IntArray {
        if (!coloured) {
            for (k in terrain.indices) terrain[k] = colourOf(terrain[k])
            coloured = true
        }
        return terrain
    }
    /** Tiles with track, their [Track] bits and owner; tiles with road, their road bits and builder. */
    val rail = SparseTiles.of(w.track, w.owner)
    val roads = SparseTiles.of(w.road, w.roadOwner)
    val spans = w.spans.filterNotNull().map { MapSpan(it.x0, it.y0, it.x1, it.y1, it.owner) }
    val towns = w.towns.map { MapPlace(it.x + 0.5f, it.y + 0.5f, name = it.name, population = it.population, id = it.id) }.sortedByDescending { it.population }
    val stations = w.stations.filterNotNull().map { MapPlace(it.signX + 0.5f, it.signY + 0.5f, it.owner, id = it.id) }
    val industries = run {
        val byId = info.industries.associateBy { it.id }
        // Each one's number among its kind by its town, counted in id order as [industryName] does.
        val seen = HashMap<Long, Int>()
        val nth = HashMap<Int, Int>()
        for (ind in w.industries.sortedBy { it.id }) {
            val key = ind.kind.toLong() shl 32 or (ind.town.toLong() and 0xffffffffL)
            val n = (seen[key] ?: 0) + 1
            seen[key] = n
            nth[ind.id] = n
        }
        w.industries.filter { !it.closed }.map { ind ->
            val n = nth.getValue(ind.id)
            MapIndustry(MapPlace(ind.x + 1f, ind.y + 1f, id = ind.id), w.roster.industries[ind.kind],
                industryName(w, ind, "en", n), industryName(w, ind, "fr", n), byId.getValue(ind.id))
        }
    }
    val stationById = stations.associateBy { it.id }
    val companies = w.companies.filter { !it.gone }.map { it.id to it.name }
}

/** The tiles where a layer isn't 0, with its value and owner on each. */
internal class SparseTiles(val tiles: IntArray, val bits: ByteArray, val owners: ByteArray) {
    companion object {
        fun of(layer: ByteArray, owner: ByteArray): SparseTiles {
            var n = 0
            for (v in layer) if (v.toInt() != 0) n++
            val out = SparseTiles(IntArray(n), ByteArray(n), ByteArray(n))
            n = 0
            for (i in layer.indices) if (layer[i].toInt() != 0) {
                out.tiles[n] = i
                out.bits[n] = layer[i]
                out.owners[n++] = owner[i]
            }
            return out
        }
    }
}

/**
 * What [w]'s terrain colour takes, a pixel for each [step] tiles square, [across] by [down], each as [terrainTile]:
 * only reads and shifts, so the game thread isn't held up by the colouring.
 */
private fun terrainTiles(w: World, step: Int, across: Int, down: Int): IntArray {
    val out = IntArray(across * down)
    val canals = w.canals
    val corners = w.corners
    val ground = w.ground
    val trees = w.trees
    val row = w.width + 1
    for (py in 0 until down) {
        val y = py * step
        var i = y * w.width
        var c = y * row
        var o = py * across
        for (px in 0 until across) {
            out[o++] = terrainTile(corners[c].toInt(), corners[c + 1].toInt(), corners[c + row + 1].toInt(), corners[c + row].toInt(),
                ground[i].toInt(), Trees.count(trees[i].toInt()))
            i += step
            c += step
        }
    }
    // A canal is water, as all four corners at 0 colour it.
    for (i in canals) {
        val x = i % w.width
        val y = i / w.width
        if (x % step == 0 && y % step == 0 && x / step < across && y / step < down) out[y / step * across + x / step] = 0
    }
    return out
}

/** A tile's corner heights, 6 bits each, its ground in 3 and its tree count in 2, packed in an Int for [colourOf]. */
private fun terrainTile(t: Int, r: Int, b: Int, l: Int, ground: Int, trees: Int): Int =
    minOf(t, 63) or (minOf(r, 63) shl 6) or (minOf(b, 63) shl 12) or (minOf(l, 63) shl 18) or (minOf(ground, 7) shl 24) or (trees shl 27)

private fun colourOf(tile: Int): Int = terrainColour(
    tile and 63, (tile shr 6) and 63, (tile shr 12) and 63, (tile shr 18) and 63, (tile shr 24) and 7, (tile shr 27) and 3,
)

internal class MapImage(val data: MapSnapshot) {
    val terrain: ImageBitmap = imageBitmapOf(data.terrainColours(), data.terrainWidth, data.terrainHeight)
    val rail = linkedMapOf<Int, Path>()
    val roads = linkedMapOf<Int, Path>()
    init {
        val w = data.width
        val rs = data.rail
        for (k in rs.tiles.indices) {
            val i = rs.tiles[k]
            val x = i % w + 0.5f
            val y = i / w + 0.5f
            for (p in Track.PIECES) if (rs.bits[k].toInt() and p != 0) {
                val edges = Track.edges(p)
                val a = edges and 3
                val b = edges shr 2
                rail.getOrPut(rs.owners[k].toInt()) { Path() }.apply {
                    moveTo(x + Track.dx(a) * 0.5f, y + Track.dy(a) * 0.5f)
                    lineTo(x + Track.dx(b) * 0.5f, y + Track.dy(b) * 0.5f)
                }
            }
        }
        val ds = data.roads
        for (k in ds.tiles.indices) {
            val i = ds.tiles[k]
            val x = i % w + 0.5f
            val y = i / w + 0.5f
            for (e in 0 until 4) if (ds.bits[k].toInt() and roadBit(e) != 0) {
                roads.getOrPut(ds.owners[k].toInt()) { Path() }.apply {
                    moveTo(x, y)
                    lineTo(x + Track.dx(e) * 0.5f, y + Track.dy(e) * 0.5f)
                }
            }
        }
        for (s in data.spans) rail.getOrPut(s.owner) { Path() }.apply {
            moveTo(s.x + 0.5f, s.y + 0.5f)
            lineTo(s.ex + 0.5f, s.ey + 0.5f)
        }
    }
}
