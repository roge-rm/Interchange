package com.rm.interchange.map

import com.rm.interchange.map.gl.GroundRecord
import com.rm.interchange.map.gl.SpriteRecorder
import com.rm.interchange.sim.CORNER_E
import com.rm.interchange.sim.CORNER_N
import com.rm.interchange.sim.CORNER_S
import com.rm.interchange.sim.CORNER_W
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.Airport
import com.rm.interchange.sim.Airports
import com.rm.interchange.sim.Taxiways
import com.rm.interchange.sim.Depots
import com.rm.interchange.sim.PAVED
import com.rm.interchange.sim.Trees
import com.rm.interchange.sim.World
import com.rm.interchange.sim.Zones
import com.rm.interchange.sim.zoneAt
import com.rm.interchange.sim.houseFacing
import com.rm.interchange.sim.industryAt
import com.rm.interchange.sim.part
import com.rm.interchange.sim.houseOf
import com.rm.interchange.sim.townBuilt
import com.rm.interchange.sim.stationBuildingAt
import com.rm.interchange.sim.stationAt
import com.rm.interchange.sim.stationEra
import com.rm.interchange.sim.hasFoundation
import com.rm.interchange.sim.rampEdge
import com.rm.interchange.sim.surfaceHeight
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.isPlatform
import com.rm.interchange.sim.Surface
import com.rm.interchange.sim.TRAM
import com.rm.interchange.sim.oneWayOf
import com.rm.interchange.sim.straightRoad
import com.rm.interchange.sim.surfaceOf
import com.rm.interchange.sim.ICE_ROAD_CM
import com.rm.interchange.sim.BURNT
import com.rm.interchange.sim.BURNT_FROM
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Lays the map down for the GPU map, tile by tile from the back, into a [SpriteRecorder]: each tile's ground as a
 * [GroundRecord] (its kind, the kinds round it that spread in, its slope light, ridges and hollows, road, airport
 * paving and burns), then foundations, track, buildings, trees and the odd boulder or shrub as sprites from the
 * static sheet, with shadows. The ground shader paints the ground from the records with the weather and the time of
 * year.
 *
 * Positions are those of the map drawn at [level] with view point 0, 0 at height 0 at ([originX], [originY]).
 *
 * Reads the world as it is, from a recording thread.
 */
internal class TileRecorder(
    private val world: World,
    private val view: View,
    private val level: Int,
    private val look: Int = Climate.SUMMER,
    /** The snow, ice and mud to show. */
    private val shown: WeatherCopy,
    /** The game's year, for industry pieces drawn another way before a year ([PART_ERAS]). */
    private val year: Int = Int.MAX_VALUE,
    /** The curved track's cells, this recorder's alone while it runs. Made on first use if not given. */
    curveArt: CurveArt? = null,
) {
    private val curveArt by lazy { curveArt ?: CurveArt(world).also { it.refresh() } }
    private val hw = Zoom.LEVELS[level]
    private val hh = hw / 2
    private val step = hw / 4
    private val shrink = 1 shl level

    /** Where view point 0, 0 at height 0 lands. */
    var originX = 0f
    var originY = 0f

    fun x(vx: Float, vy: Float) = originX + (vx - vy) * hw
    fun y(vx: Float, vy: Float, h: Float) = originY + (vx + vy) * hh - h * step

    /** Where the passes lay what they draw, in drawing order; nothing is laid down while it's null, for [building] alone. */
    var recorder: SpriteRecorder? = null

    /** Given each static sprite [objects] lays, as its cell and anchor, in place of recording it; for [Cover]. */
    var onStatic: ((cell: Int, ax: Float, ay: Float) -> Unit)? = null

    private fun cornerHeight(vx: Int, vy: Int): Int {
        val gx = vx - gx0
        val gy = vy - gy0
        if (gx in 0 until gw && gy in 0 until gh) return grid[gy * gw + gx]
        return world.heightAt(view.cornerX(vx, vy), view.cornerY(vx, vy))
    }

    // View corners gx0 until gx0 + gw across and gy0 until gy0 + gh down, their heights and running sums of them
    // (one row and column bigger), filled by [prepare] so a tile's corners and relief are a few array reads.
    private var gx0 = 0
    private var gy0 = 0
    private var gw = 0
    private var gh = 0
    private var grid = IntArray(0)
    private var sums = IntArray(0)

    /** Reads the corner heights for view tiles [vx0] until [vx1] across and [vy0] until [vy1] down, before baking them. */
    fun prepare(vx0: Int, vy0: Int, vx1: Int, vy1: Int) {
        gx0 = maxOf(0, vx0 - RELIEF - 2)
        gy0 = maxOf(0, vy0 - RELIEF - 2)
        gw = minOf(view.viewWidth, vx1 + RELIEF + 2) - gx0 + 1
        gh = minOf(view.viewHeight, vy1 + RELIEF + 2) - gy0 + 1
        if (gw <= 0 || gh <= 0) {
            gw = 0
            gh = 0
            return
        }
        val heights = IntArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) heights[y * gw + x] = world.heightAt(view.cornerX(gx0 + x, gy0 + y), view.cornerY(gx0 + x, gy0 + y))
        val w1 = gw + 1
        val s = IntArray(w1 * (gh + 1))
        for (y in 0 until gh) {
            var row = 0
            for (x in 0 until gw) {
                row += heights[y * gw + x]
                s[(y + 1) * w1 + x + 1] = s[y * w1 + x + 1] + row
            }
        }
        grid = heights
        sums = s
        kinds = IntArray(gw * gh) { UNKNOWN }
        snowSums = IntArray(gw * gh) { UNKNOWN }
        lights = FloatArray(gw * gh) { Float.NaN }
    }

    // By the same view corners as [grid], worked out when first asked for: the kind of the tile whose top corner it is
    // ([kindOf]), the snow on the four tiles round it ([cornerSnow]) and its [cornerLight]. UNKNOWN and NaN till then.
    private var kinds = IntArray(0)
    private var snowSums = IntArray(0)
    private var lights = FloatArray(0)

    private val reliefs = HashMap<Int, Float>()

    /** How far view corner ([vx], [vy]) stands above the land within [RELIEF] corners of it, in steps; below is negative. */
    private fun relief(vx: Int, vy: Int): Float {
        val x0 = maxOf(0, vx - RELIEF)
        val y0 = maxOf(0, vy - RELIEF)
        val x1 = minOf(view.viewWidth, vx + RELIEF)
        val y1 = minOf(view.viewHeight, vy + RELIEF)
        val ax = x0 - gx0
        val ay = y0 - gy0
        val bx = x1 - gx0 + 1
        val by = y1 - gy0 + 1
        if (ax >= 0 && ay >= 0 && bx <= gw && by <= gh) {
            val w1 = gw + 1
            val sum = sums[by * w1 + bx] - sums[ay * w1 + bx] - sums[by * w1 + ax] + sums[ay * w1 + ax]
            return cornerHeight(vx, vy) - sum.toFloat() / ((x1 - x0 + 1) * (y1 - y0 + 1))
        }
        return reliefs.getOrPut(vy * (view.viewWidth + 1) + vx) { slowRelief(vx, vy) }
    }

    private fun slowRelief(vx: Int, vy: Int): Float {
        var sum = 0
        var n = 0
        for (dy in -RELIEF..RELIEF) for (dx in -RELIEF..RELIEF) {
            val x = vx + dx
            val y = vy + dy
            if (x < 0 || y < 0 || x > view.viewWidth || y > view.viewHeight) continue
            sum += cornerHeight(x, y)
            n++
        }
        return cornerHeight(vx, vy) - sum.toFloat() / n
    }

    /** How much of what faces the sky on world tile [i] is under snow, in 256ths. */
    private fun snowCover(i: Int): Int {
        val d = shown.snow[i].toInt() and 255
        return when {
            d == 0 -> 0
            d < 3 -> 90
            d < 10 -> 170
            d < 30 -> 230
            else -> 256
        }
    }

    /** The ground kind of view tile ([vx], [vy]) as drawn: water, beach sand, or the land's own. -1 off the map. */
    private fun kindOf(vx: Int, vy: Int): Int {
        val gx = vx - gx0
        val gy = vy - gy0
        if (gx !in 0 until gw || gy !in 0 until gh) return kindAt(vx, vy)
        val i = gy * gw + gx
        if (kinds[i] == UNKNOWN) kinds[i] = kindAt(vx, vy)
        return kinds[i]
    }

    private fun kindAt(vx: Int, vy: Int): Int {
        if (vx < 0 || vy < 0 || vx >= view.viewWidth || vy >= view.viewHeight) return -1
        val t = cornerHeight(vx, vy)
        val r = cornerHeight(vx + 1, vy)
        val b = cornerHeight(vx + 1, vy + 1)
        val l = cornerHeight(vx, vy + 1)
        val low = minOf(minOf(t, r), minOf(b, l))
        if (low > 0) return world.ground[world.index(view.worldX(vx, vy), view.worldY(vx, vy))].toInt()
        return if (t == 0 && r == 0 && b == 0 && l == 0) Atlas.WATER else Atlas.SAND
    }

    /**
     * The first pass, for every tile from the back: the ground, the earth under the map's edges, foundations and
     * track. Shadows come next, over all of it, then what stands up ([objects]).
     */
    fun ground(vx: Int, vy: Int) {
        val t = cornerHeight(vx, vy)
        val r = cornerHeight(vx + 1, vy)
        val b = cornerHeight(vx + 1, vy + 1)
        val l = cornerHeight(vx, vy + 1)
        val wx = view.worldX(vx, vy)
        val wy = view.worldY(vx, vy)
        val kind = kindOf(vx, vy)
        val edges = edgeKinds
        edges[0] = kindOf(vx, vy - 1)
        edges[1] = kindOf(vx + 1, vy)
        edges[2] = kindOf(vx, vy + 1)
        edges[3] = kindOf(vx - 1, vy)
        // The town road here in view edges, and whether it's paved.
        val worldRoad = world.road[world.index(wx, wy)].toInt()
        val here = world.index(wx, wy)
        var road = worldRoad and PAVED
        // An ice road shows only while the ice will carry it.
        if (worldRoad != 0 && world.isWater(wx, wy) && (shown.ice[here].toInt() and 255) < ICE_ROAD_CM) road = -1
        if (road < 0) road = 0 else if (worldRoad != 0) {
            if (downtown(here)) road = road or DOWNTOWN
            if (world.townOf[here].toInt() >= 0) road = road or TOWN_ROAD
            val kindHere = world.roadKind[here].toInt()
            when (surfaceOf(kindHere)) {
                Surface.GRAVEL -> road = road or GRAVEL
                Surface.HIGHWAY -> road = road or HIGHWAY
            }
            if (kindHere and TRAM != 0) road = road or TRAM_RAILS
            val way = oneWayOf(kindHere)
            if (way != 0) {
                val straight = world.straightRoad(here)
                val exit = if (straight == Track.X) (if (way == 1) Track.XP else Track.XM) else (if (way == 1) Track.YP else Track.YM)
                road = road or ONE_WAY or (viewEdge(exit) shl 10)
            }
        }
        if (road != 0 || (worldRoad != 0 && !world.isWater(wx, wy))) for (e in 0 until 4) if (worldRoad and (1 shl e) != 0) road = road or (1 shl viewEdge(e))
        // A canal is water at its own height, with stone banks wherever it doesn't go on.
        if (world.canals.contains(here)) {
            var banks = 0
            for (e in 0 until 4) {
                val nx = wx + Track.dx(e)
                val ny = wy + Track.dy(e)
                val n = if (world.inside(nx, ny)) world.index(nx, ny) else -1
                if (n < 0 || !(world.canals.contains(n) || world.locks.containsKey(n) || world.isWater(nx, ny))) banks = banks or (1 shl sideOf(viewEdge(e)))
            }
            ground(vx, vy, t, r, b, l, Atlas.WATER, NO_EDGES, weather = true, banks = banks)
            earthFaces(vx, vy, r, b, l)
            return
        }
        val aero = world.airportTiles[here]?.let { id -> world.airports.getOrNull(id)?.let { airportGround(it, wx, wy) } } ?: 0
        // A road where the land doesn't suit it goes up on a foundation, as track does, the road on top.
        val raised = road != 0 && world.trackAt(wx, wy) == 0 && world.hasFoundation(wx, wy)
        ground(vx, vy, t, r, b, l, kind, edges, if (raised) 0 else road, weather = true, aero = aero, soft = true)
        earthFaces(vx, vy, r, b, l)
        if (raised) {
            val st = world.surfaceHeight(wx, wy, 0, worldCorner(wx, wy, vx, vy))
            val sr = world.surfaceHeight(wx, wy, 0, worldCorner(wx, wy, vx + 1, vy))
            val sb = world.surfaceHeight(wx, wy, 0, worldCorner(wx, wy, vx + 1, vy + 1))
            val sl = world.surfaceHeight(wx, wy, 0, worldCorner(wx, wy, vx, vy + 1))
            val low = minOf(minOf(t, r), minOf(b, l))
            sprite(Atlas.WALLS[shapeOf(t - low, r - low, b - low, l - low) * 16 + shapeOf(st - low, sr - low, sb - low, sl - low)], vx, vy, low)
            ground(vx, vy, st, sr, sb, sl, kind, NO_EDGES, road, weather = true)
            return
        }

        val tile = world.index(wx, wy)
        val pieces = world.trackAt(wx, wy)
        val base = minOf(minOf(t, r), minOf(b, l))
        // A building or an industry on a slope stands on a foundation level with its highest corner, its yard on top.
        if (world.house[tile].toInt() != 0 || world.industryOf[tile].toInt() != 0) {
            val top = maxOf(maxOf(t, r), maxOf(b, l))
            if (top != base) {
                sprite(Atlas.WALLS[shapeOf(t - base, r - base, b - base, l - base) * 16 + 15], vx, vy, base)
                ground(vx, vy, top, top, top, top, kind, NO_EDGES)
            }
            return
        }
        if (pieces == 0) return
        val guide = world.guideway[tile]
        val tube = world.tube[tile]
        world.depotTiles[tile]?.let {
            if (guide) sprite(Atlas.GUIDE[viewPieceIndex(Track.index(pieces)) * 16], vx, vy, base)
            else if (tube) sprite(Atlas.TUBE[viewPieceIndex(Track.index(pieces)) * 16], vx, vy, base)
            else {
                sprite(Atlas.TRACK[viewPieceIndex(Track.index(pieces)) * 16], vx, vy, base)
                sprite(Atlas.RAILS[viewPieceIndex(Track.index(pieces)) * 16], vx, vy, base)
            }
            return
        }
        // A bridge's ramp head brings its own track, drawn with the bridge.
        if (world.spanHeads.containsKey(tile) && world.rampEdge(wx, wy) >= 0) return
        val st = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx, vy))
        val sr = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx + 1, vy))
        val sb = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx + 1, vy + 1))
        val sl = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx, vy + 1))
        val sBase = minOf(minOf(st, sr), minOf(sb, sl))
        val sShape = shapeOf(st - sBase, sr - sBase, sb - sBase, sl - sBase)
        if (world.hasFoundation(wx, wy)) {
            val shape = shapeOf(t - base, r - base, b - base, l - base)
            sprite(Atlas.WALLS[shape * 16 + shapeOf(st - base, sr - base, sb - base, sl - base)], vx, vy, base)
            ground(vx, vy, st, sr, sb, sl, kind, NO_EDGES)
        }
        // Platforms bring their own track, drawn with them.
        if (world.isPlatform(tile)) return
        // A level tile draws its pieces on their curves, each layer of every piece in turn, where CurveTiles says so;
        // the cars and the lines drawn over the map follow the same rule.
        curveArt.cells(wx, wy, view.turn)?.let { cells ->
            for (c in cells) sprite(c, vx, vy, sBase)
            return
        }
        // The curve lookup that said no counts as curve time.
        if (guide || tube) {
            guideway(vx, vy, wx, wy, tile, pieces, sShape, sBase, tube)
            return
        }
        // Every piece's bed, then the rails over them all: a single piece's own, or for two or more, which always lie
        // level, the rails of them together, gapped where they cross.
        var view = 0
        for ((i, p) in Track.PIECES.withIndex()) {
            if (pieces and p == 0) continue
            sprite(Atlas.TRACK[viewPieceIndex(i) * 16 + sShape], vx, vy, sBase)
            view = view or (1 shl viewPieceIndex(i))
        }
        if (view.countOneBits() >= 2) sprite(Atlas.RAIL_SETS[view], vx, vy, sBase)
        else sprite(Atlas.RAILS[view.countTrailingZeroBits() * 16 + sShape], vx, vy, sBase)
    }

    /**
     * Maglev guideway, or hyperloop tube where [tube], on world tile ([wx], [wy]): one piece on the shape it lies on,
     * or two or more on a level tile together, and a buffer or cap on level ground wherever it stops with no more of
     * its kind going on.
     */
    private fun guideway(vx: Int, vy: Int, wx: Int, wy: Int, tile: Int, pieces: Int, sShape: Int, sBase: Int, tube: Boolean = false) {
        var view = 0
        for ((i, p) in Track.PIECES.withIndex()) if (pieces and p != 0) view = view or (1 shl viewPieceIndex(i))
        if (view.countOneBits() >= 2) sprite((if (tube) Atlas.TUBE_SETS else Atlas.GUIDE_SETS)[view], vx, vy, sBase)
        else sprite((if (tube) Atlas.TUBE else Atlas.GUIDE)[view.countTrailingZeroBits() * 16 + sShape], vx, vy, sBase)
        if (sShape != 0 || world.spanHeads.containsKey(tile)) return
        var ends = 0
        for (p in Track.PIECES) {
            if (pieces and p == 0) continue
            val e = Track.edges(p)
            for (edge in intArrayOf(e and 3, e shr 2)) if (!guidewayOn(wx, wy, edge, tube)) ends = ends or (1 shl edge)
        }
        for (edge in 0 until 4) if (ends and (1 shl edge) != 0) sprite((if (tube) Atlas.TUBE_END else Atlas.GUIDE_END)[viewEdge(edge)], vx, vy, sBase)
    }

    /** Whether guideway, or tube where [tube], goes on from world tile ([wx], [wy]) through [edge]: the tile there has a piece of it meeting it. */
    private fun guidewayOn(wx: Int, wy: Int, edge: Int, tube: Boolean = false): Boolean {
        val nx = wx + Track.dx(edge)
        val ny = wy + Track.dy(edge)
        if (!world.inside(nx, ny)) return false
        val n = world.index(nx, ny)
        if (!(if (tube) world.tube[n] else world.guideway[n])) return false
        val theirs = world.trackAt(nx, ny)
        val back = Track.opposite(edge)
        for (p in Track.PIECES) if (theirs and p != 0 && Track.has(p, back)) return true
        return false
    }

    /** The second pass: the shadows of what stands on a tile, laid over the ground of every tile before anything stands up. */
    fun shadows(vx: Int, vy: Int) = standing(vx, vy, shadow = true)

    /** The third pass, from the back: trees, boulders and shrubs, platforms and engine houses. */
    fun objects(vx: Int, vy: Int) = standing(vx, vy, shadow = false)

    /**
     * The building on view tile ([vx], [vy]) with no track: a house, an industry's piece, a dock, a lock, an airport's,
     * a garage, a shipyard, a salt dome, a road stop or a station building, as its sprite (or its shadow's) in the high half and the height
     * it stands at in the low; [NO_ART] for something there with no drawing, or [NOTHING].
     */
    fun building(vx: Int, vy: Int, shadow: Boolean = false): Long {
        val wx = view.worldX(vx, vy)
        val wy = view.worldY(vx, vy)
        val tile = world.index(wx, wy)
        val base = minOf(minOf(cornerHeight(vx, vy), cornerHeight(vx + 1, vy)), minOf(cornerHeight(vx + 1, vy + 1), cornerHeight(vx, vy + 1)))
        if (world.saltDepots.contains(tile)) {
            return art(if (shadow) Atlas.SALT_DOME_SHADOW else Atlas.SALT_DOME, base)
        }
        world.docks[tile]?.let {
            val e = viewEdge(world.dockFacing[tile] ?: 0)
            return art(if (shadow) Atlas.DOCK_SHADOW[e] else Atlas.DOCK[e], base)
        }
        world.locks[tile]?.let {
            // Drawn rising to its higher end, gates and house by its place in a flight of locks.
            val high = lockHigh(wx, wy)
            val e = (lockEra(year) * 4 + lockPlace(wx, wy, high)) * 4 + viewEdge(high)
            return art(if (shadow) Atlas.LOCK_SHADOW[e] else Atlas.LOCK[e], base)
        }
        if (world.canals.contains(tile)) return NO_ART
        world.airportTiles[tile]?.let { id ->
            val a = world.airports.getOrNull(id) ?: return NO_ART
            return art(airportArt(a, wx, wy, shadow), base)
        }
        if (world.roadStops.containsKey(tile)) {
            val a = if (viewEdge(if (world.straightRoad(tile) == Track.X) Track.XP else Track.YP) <= Track.XP) 0 else 1
            return art(if (shadow) Atlas.ROAD_STOP_SHADOW[a] else Atlas.ROAD_STOP[a], base)
        }
        world.depotTiles[tile]?.let { id ->
            val d = world.depots.getOrNull(id)
            if (d != null && d.road) {
                val e = viewEdge(d.edge)
                return art(if (shadow) Atlas.GARAGE_SHADOW[e] else Atlas.GARAGE[e], base)
            }
            if (d != null && d.kind == Depots.SHIPYARD) {
                val e = viewEdge(d.edge)
                return art(if (shadow) Atlas.SHIPYARD_SHADOW[e] else Atlas.SHIPYARD[e], base)
            }
        }
        world.industryAt(tile)?.let { ind ->
            val kind = world.roster.industries[ind.kind]
            val part = kind.part(wx - ind.x, wy - ind.y, ind.turn)
            val base = maxOf(maxOf(cornerHeight(vx, vy), cornerHeight(vx + 1, vy)), maxOf(cornerHeight(vx + 1, vy + 1), cornerHeight(vx, vy + 1)))
            val own = kind.parts[part]
            if (own != null) return if (shadow) NO_ART else art(packCell(own, viewEdge(INDUSTRY_FACING[ind.turn])), base)
            val art = partArt(part)
            return if (art >= 0) art((if (shadow) Atlas.PART_SHADOW else Atlas.PART)[art * 4 + viewEdge(INDUSTRY_FACING[ind.turn])], base) else NO_ART
        }
        world.stationBuildingAt(tile)?.let { st ->
            val e = viewEdge(st.buildingFacing)
            return art((if (shadow) Atlas.STATION_HOUSE_SHADOW else Atlas.STATION_HOUSE)[stationEra(st.buildingYear) * 4 + e], base)
        }
        val built = world.house[tile].toInt()
        if (built != 0) {
            val kind = houseOf(built)
            val art = houseArt(kind)
            val base = maxOf(maxOf(cornerHeight(vx, vy), cornerHeight(vx + 1, vy)), maxOf(cornerHeight(vx + 1, vy + 1), cornerHeight(vx, vy + 1)))
            val own = world.roster.houses.getOrNull(kind)?.sprite ?: -1
            if (own >= 0) return if (shadow) NO_ART else art(packCell(own, viewEdge(houseFacing(built))), base)
            return if (art >= 0) art((if (shadow) Atlas.HOUSE_SHADOW else Atlas.HOUSE)[art * 4 + viewEdge(houseFacing(built))], base) else NO_ART
        }
        return NOTHING
    }

    private fun art(cell: Int, base: Int): Long = if (cell < 0) NO_ART else (cell.toLong() shl 32) or base.toLong()

    private fun standing(vx: Int, vy: Int, shadow: Boolean) {
        val wx = view.worldX(vx, vy)
        val wy = view.worldY(vx, vy)
        val tile = world.index(wx, wy)
        val pieces = world.trackAt(wx, wy)
        val kind = kindOf(vx, vy)
        if (pieces == 0) {
            val b = building(vx, vy, shadow)
            if (b != NOTHING) {
                if (b >= 0) sprite((b shr 32).toInt(), vx, vy, b.toInt(), shadow)
                return
            }
            if (world.road[tile].toInt() != 0) {
                streetTrees(wx, wy, shadow)
                return
            }
            if (kind != Atlas.WATER && kind != Atlas.SAND && !world.townBuilt(tile)) {
                trees(wx, wy, shadow)
                decor(wx, wy, kind, shadow)
            }
            return
        }
        standingOnTrack(vx, vy, wx, wy, tile, pieces, shadow)
    }

    /** What stands on a tile with track: a depot, or a platform and its canopy. */
    private fun standingOnTrack(vx: Int, vy: Int, wx: Int, wy: Int, tile: Int, pieces: Int, shadow: Boolean) {
        val base = minOf(minOf(cornerHeight(vx, vy), cornerHeight(vx + 1, vy)), minOf(cornerHeight(vx + 1, vy + 1), cornerHeight(vx, vy + 1)))
        val guide = world.guideway[tile]
        val tube = world.tube[tile]
        world.depotTiles[tile]?.let { id ->
            val d = world.depots.getOrNull(id) ?: return@let
            val e = viewEdge(d.edge)
            if (guide) sprite((if (shadow) Atlas.GUIDE_DEPOT_SHADOW else Atlas.GUIDE_DEPOT)[e], vx, vy, base, shadow)
            else if (tube) sprite((if (shadow) Atlas.TUBE_DEPOT_SHADOW else Atlas.TUBE_DEPOT)[e], vx, vy, base, shadow)
            else sprite((if (shadow) Atlas.DEPOT_SHADOW else Atlas.DEPOT)[depotEra(d.built.takeIf { it > 0 } ?: year) * 4 + e], vx, vy, base, shadow)
            return
        }
        if (world.isPlatform(tile)) {
            val st = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx, vy))
            val a = viewPieceIndex(Track.index(pieces))
            val bare = buildingBehind(vx, vy, a)
            if (guide) {
                val cell = if (bare) (if (shadow) Atlas.GUIDE_STATION_BARE_SHADOW else Atlas.GUIDE_STATION_BARE)[a]
                else (if (shadow) Atlas.GUIDE_STATION_SHADOW else Atlas.GUIDE_STATION)[a]
                sprite(cell, vx, vy, st, shadow)
                return
            }
            if (tube) {
                val cell = if (bare) (if (shadow) Atlas.TUBE_STATION_BARE_SHADOW else Atlas.TUBE_STATION_BARE)[a]
                else (if (shadow) Atlas.TUBE_STATION_SHADOW else Atlas.TUBE_STATION)[a]
                sprite(cell, vx, vy, st, shadow)
                return
            }
            // The canopy goes by the year its station's building went up, or the game's year for a station without one.
            val built = world.stationAt(tile)?.takeIf { it.building >= 0 && it.buildingYear > 0 }?.buildingYear ?: year
            val cell = if (bare) (if (shadow) Atlas.STATION_BARE_SHADOW else Atlas.STATION_BARE)[a]
            else (if (shadow) Atlas.STATION_SHADOW else Atlas.STATION)[canopyEra(built) * 2 + a]
            sprite(cell, vx, vy, st, shadow)
        }
    }

    /**
     * Whether a station building stands on the canopy side of the platform at view tile ([vx], [vy]) along view axis
     * [a], facing it. The platform sprite's canopy is on the view's far side (v - 1 along u, u - 1 along v), so that
     * platform is drawn without one to leave the building's front in sight.
     */
    private fun buildingBehind(vx: Int, vy: Int, a: Int): Boolean {
        val bx = if (a == 0) vx else vx - 1
        val by = if (a == 0) vy - 1 else vy
        val wx = view.worldX(bx, by)
        val wy = view.worldY(bx, by)
        if (wx !in 0 until world.width || wy !in 0 until world.height) return false
        val st = world.stationBuildingAt(world.index(wx, wy)) ?: return false
        return viewEdge(st.buildingFacing) == (if (a == 0) Track.YP else Track.XP)
    }

    // ---- ground --------------------------------------------------------------------------

    /**
     * Paints a tile's top with corner heights [t], [r], [b], [l] (view order) in [kind]'s texture, letting the
     * kinds of its neighbours across each edge ([edges], -1 for none) spread in where they rank higher.
     * [soft] for the land's own corners: slopes no lighter than flat take their light from the corners, so it fades across the tile.
     */
    private fun ground(vx: Int, vy: Int, t: Int, r: Int, b: Int, l: Int, kind: Int, edges: IntArray, road: Int = 0, weather: Boolean = false, banks: Int = 0, aero: Int = 0, soft: Boolean = false) {
        recordGround(recorder ?: return, vx, vy, t, r, b, l, kind, edges, road, weather, banks, aero, soft)
    }

    /**
     * What [ground] would paint, for the GPU map to draw: everything that comes from the world, worked out as it
     * does. The weather and the time of year are left to the GPU's shader.
     */
    private fun recordGround(rec: SpriteRecorder, vx: Int, vy: Int, t: Int, r: Int, b: Int, l: Int, kind: Int, edges: IntArray, road: Int, weather: Boolean, banks: Int, aero: Int, soft: Boolean) {
        val alongRL = r == l
        val f1 = if (alongRL) planeLight((r - t).toFloat(), (l - t).toFloat()) else planeLight((r - t).toFloat(), (b - r).toFloat())
        val f2 = if (alongRL) planeLight((b - l).toFloat(), (b - r).toFloat()) else planeLight((b - l).toFloat(), (l - t).toFloat())
        val water = kind == Atlas.WATER
        val shaded = soft && !water && (f1 <= 256 || f2 <= 256)
        val lights = FloatArray(4) { 256f }
        if (shaded) {
            lights[0] = cornerLight(vx, vy)
            lights[1] = cornerLight(vx + 1, vy)
            lights[2] = cornerLight(vx + 1, vy + 1)
            lights[3] = cornerLight(vx, vy + 1)
        }
        val smooth = shaded && (lights.any { it != 256f } || f1 != 256 || f2 != 256)
        val reliefs = FloatArray(4)
        if (!water) {
            reliefs[0] = relief(vx, vy)
            reliefs[1] = relief(vx + 1, vy)
            reliefs[2] = relief(vx + 1, vy + 1)
            reliefs[3] = relief(vx, vy + 1)
        }
        val low = minOf(minOf(t, r), minOf(b, l))
        val foot = soft && !water && maxOf(maxOf(t, r), maxOf(b, l)) > low
        val rankHere = rank(kind)
        val spreads = IntArray(4) { e -> edges[e].let { if (it < 0 || it == kind || rank(it) <= rankHere) -1 else it } }
        val tile = world.index(view.worldX(vx, vy), view.worldY(vx, vy))
        val fireAt = world.fire[tile].toInt() and 255
        val charred = if (fireAt >= BURNT_FROM) 0.25f + (fireAt - BURNT_FROM) / (BURNT - BURNT_FROM).toFloat() * 0.7f else if (fireAt > 0) 0.7f else 0f
        rec.addGround(GroundRecord.ground(vx, vy, t, r, b, l, kind, spreads, weather, smooth, foot, banks, road, charred, aero, f1, f2, reliefs, lights))
    }

    /** The kinds across a tile's edges for [ground], and those that spread in, kept to save making them for every tile. */
    private val edgeKinds = IntArray(4)

    /** Which side of a tile view edge [edge] is, as the ground's neighbours count them: up, right, down, left. */
    private fun sideOf(edge: Int) = when (edge) { Track.YM -> 0; Track.XP -> 1; Track.YP -> 2; else -> 3 }

    /**
     * How airport [a]'s tile ([wx], [wy]) is paved, packed for [airportTexel]: an [AERO_RUNWAY], [AERO_STRIP],
     * [AERO_TAXI], [AERO_APRON] or [AERO_PAD] in the low bits, [AERO_ALONG_U] if its runway runs along view u, and
     * the sides of the tile at a runway's ends from bit 8. From bit 12, for [airportTexel] to find where on the airport
     * a texel is: the view sides world x and y count up toward, two bits each, the tile's place along and across
     * (4 and 3 bits), the kind (3 bits) and whether the runway runs along y.
     */
    private fun airportGround(a: Airport, wx: Int, wy: Int): Int {
        val lx = if (a.axis == Track.X) wx - a.x else wy - a.y
        val ly = if (a.axis == Track.X) wy - a.y else wx - a.x
        val len = Airports.LENGTH[a.kind]
        val role = when (a.kind) {
            Airports.SEAPLANE -> return 0
            Airports.HELIPORT, Airports.PAD -> AERO_PAD
            Airports.STRIP -> AERO_STRIP
            Airports.REGIONAL -> if (ly == 0) AERO_RUNWAY else AERO_APRON
            Airports.CITY -> when (ly) { 0 -> AERO_RUNWAY; 1 -> AERO_TAXI; else -> AERO_APRON }
            else -> when (ly) { 0 -> AERO_RUNWAY; 1 -> AERO_TAXI; else -> AERO_APRON }
        }
        val alongU = viewEdge(if (a.axis == Track.X) Track.XP else Track.YP) <= Track.XP
        var ends = 0
        if (role == AERO_RUNWAY) {
            val back = if (a.axis == Track.X) Track.XM else Track.YM
            if (lx == 0) ends = ends or (1 shl sideOf(viewEdge(back)))
            if (lx == len - 1) ends = ends or (1 shl sideOf(viewEdge(Track.opposite(back))))
        }
        val place = sideOf(viewEdge(Track.XP)) or (sideOf(viewEdge(Track.YP)) shl 2) or (lx shl 4) or (ly shl 8) or
            (a.kind shl 11) or (if (a.axis == Track.X) 0 else 1 shl 14)
        return role or (if (alongU) AERO_ALONG_U else 0) or (ends shl 8) or (place shl 12)
    }

    /** The edge the lock on world tile ([wx], [wy]) rises to. */
    private fun lockHigh(wx: Int, wy: Int): Int =
        if (world.locks[world.index(wx, wy)] == Track.X) (if (world.heightAt(wx + 1, wy) > world.heightAt(wx, wy)) Track.XP else Track.XM)
        else (if (world.heightAt(wx, wy + 1) > world.heightAt(wx, wy)) Track.YP else Track.YM)

    /**
     * Where the lock on world tile ([wx], [wy]), rising to [high], sits in a flight of locks, a lock tile next to it
     * along its length rising the same way: 0 on its own, 1 the low end, 2 in the middle, 3 the high end.
     */
    private fun lockPlace(wx: Int, wy: Int, high: Int): Int {
        fun flight(edge: Int): Boolean {
            val x = wx + Track.dx(edge)
            val y = wy + Track.dy(edge)
            return x in 0 until world.width && y in 0 until world.height && world.locks.containsKey(world.index(x, y)) && lockHigh(x, y) == high
        }
        val below = flight(Track.opposite(high))
        val above = flight(high)
        return when {
            below && above -> 2
            above -> 1
            below -> 3
            else -> 0
        }
    }

    /** The building on airport [a]'s tile ([wx], [wy]) and its shadow, facing the runway, or -1. */
    private fun airportArt(a: Airport, wx: Int, wy: Int, shadow: Boolean): Int {
        val lx = if (a.axis == Track.X) wx - a.x else wy - a.y
        val ly = if (a.axis == Track.X) wy - a.y else wx - a.x
        val e = viewEdge(if (a.axis == Track.X) Track.YM else Track.XM)
        // Hangars, terminals and towers look like the year the airport went up, by its hangar's build year.
        val built = world.depots.getOrNull(a.depot)?.built?.takeIf { it > 0 } ?: year
        val era = terminalEra(built)
        fun pick(art: IntArray, shade: IntArray, era: Int) = if (shadow) shade[era * 4 + e] else art[era * 4 + e]
        // The 1940s terminals of two and three tiles have the control cab on one of them, era 3.
        fun terminal(art: IntArray, shade: IntArray, cab: Boolean = false) = pick(art, shade, if (cab && era == 0) 3 else era)
        val tower = if (shadow) Atlas.TOWER_SHADOW[towerEra(built)] else Atlas.TOWER[towerEra(built)]
        val back = Airports.WIDTH[a.kind] - 1
        return when (a.kind) {
            Airports.SEAPLANE -> viewEdge(a.axis).let { if (shadow) Atlas.SEAPLANE_SHADOW[it] else Atlas.SEAPLANE[it] }
            Airports.STRIP -> if (lx == Airports.LENGTH[a.kind] - 1) (if (shadow) Atlas.STRIP_HUT_SHADOW else Atlas.STRIP_HUT) else -1
            Airports.REGIONAL -> if (ly != back) -1 else when (lx) {
                0 -> pick(Atlas.HANGAR_SMALL, Atlas.HANGAR_SMALL_SHADOW, smallHangarEra(built))
                2 -> terminal(Atlas.TERMINAL_SMALL, Atlas.TERMINAL_SMALL_SHADOW)
                4 -> tower
                else -> -1
            }
            Airports.CITY -> if (ly != back) -1 else when (lx) {
                0 -> pick(Atlas.HANGAR, Atlas.HANGAR_SHADOW, hangarEra(built))
                2, 3 -> terminal(Atlas.TERMINAL_MID, Atlas.TERMINAL_MID_SHADOW, lx == 2)
                5 -> tower
                else -> -1
            }
            Airports.INTERNATIONAL -> if (ly != back) -1 else when (lx) {
                0, 1 -> pick(Atlas.HANGAR, Atlas.HANGAR_SHADOW, hangarEra(built))
                3, 4, 5 -> terminal(Atlas.TERMINAL_BIG, Atlas.TERMINAL_BIG_SHADOW, lx == 4)
                7 -> tower
                else -> -1
            }
            else -> -1
        }
    }

    /** Whether tile [i] is in the inner part or the centre of its town, where streets have sidewalk up to the buildings. */
    private fun downtown(i: Int): Boolean {
        val t = world.towns.getOrNull(world.townOf[i].toInt()) ?: return false
        val z = t.zoneAt(i % world.width, i / world.width)
        return z == Zones.CENTRE || z == Zones.INNER
    }

    /** Street trees on the boulevards of a paved suburban road on world tile ([wx], [wy]), where the tile's dice put them. */
    private fun streetTrees(wx: Int, wy: Int, shadow: Boolean) {
        val bits = world.road[world.index(wx, wy)].toInt()
        if (bits and PAVED == 0 || downtown(world.index(wx, wy))) return
        val cells = treeCells(shadow)
        val spots = ArrayList<FloatArray>()
        val side = (BOULEVARD + CARRIAGEWAY + KERB) / 2
        for (e in 0 until 4) {
            if (bits and (1 shl e) == 0) continue
            val d = LandPainter.dice(wx, wy, 20 + e)
            for (s in listOf(-1f, 1f)) {
                if ((d shr (if (s < 0) 0 else 8)) and 255 > 110) continue
                val along = if (e == Track.XM || e == Track.YM) 0.22f else 0.78f
                val (tx, ty) = if (e == Track.XM || e == Track.XP) wx + along to wy + 0.5f + s * side else wx + 0.5f + s * side to wy + along
                spots += floatArrayOf(view.toViewX(tx, ty) + view.toViewY(tx, ty), tx, ty, ((d shr 16) and 255).toFloat())
            }
        }
        spots.sortBy { it[0] }
        for (sp in spots) standing(cells[STREET_TREE * Atlas.TREE_LOOKS + sp[3].toInt() % Atlas.TREE_LOOKS], sp[1], sp[2], shadow)
    }

    /**
     * How lit a plane with these slopes is, in 256ths, flat ground being 256. The light is low and off the
     * diagonal, so each of the four ways a slope can face gets its own shade.
     */
    private fun light(gu: Float, gv: Float): Int {
        // The normal of h = gu * u + gv * v, with a step half a tile high.
        val nx = -gu * 0.5f
        val ny = -gv * 0.5f
        val nz = 1f
        val n = sqrt(nx * nx + ny * ny + nz * nz)
        val d = (nx * LX + ny * LY + nz * LZ) / (n * LL)
        val flat = LZ / LL
        val raw = 1f + LIGHT_GAIN * (d / flat - 1f)
        // Slopes ease off towards LIGHT_HIGH and LIGHT_LOW rather than all stopping at them, so steeper still reads as steeper.
        val f = if (raw >= 1f) 1f + (LIGHT_HIGH - 1f) * tanh((raw - 1f) / (LIGHT_HIGH - 1f)) else 1f - (1f - LIGHT_LOW) * tanh((1f - raw) / (1f - LIGHT_LOW))
        return (f * 256).toInt()
    }

    /** [light] for a tile half's slopes, which are whole steps, from [PLANE_LIGHTS] for the usual ones. */
    private fun planeLight(gu: Float, gv: Float): Int {
        val iu = gu.toInt()
        val iv = gv.toInt()
        if (iu.toFloat() != gu || iv.toFloat() != gv || iu !in -PLANE_REACH..PLANE_REACH || iv !in -PLANE_REACH..PLANE_REACH) return light(gu, gv)
        val k = (iu + PLANE_REACH) * (2 * PLANE_REACH + 1) + iv + PLANE_REACH
        var f = PLANE_LIGHTS[k]
        if (f == UNKNOWN) {
            f = light(gu, gv)
            PLANE_LIGHTS[k] = f
        }
        return f
    }

    /** The [light] at view corner ([vx], [vy]), from the slope across the corners either side of it. */
    private fun cornerLight(vx: Int, vy: Int): Float {
        val gx = vx - gx0
        val gy = vy - gy0
        if (gx !in 0 until gw || gy !in 0 until gh) return lightAt(vx, vy)
        val i = gy * gw + gx
        if (lights[i].isNaN()) lights[i] = lightAt(vx, vy)
        return lights[i]
    }

    private fun lightAt(vx: Int, vy: Int): Float {
        val x0 = maxOf(0, vx - 1)
        val x1 = minOf(view.viewWidth, vx + 1)
        val y0 = maxOf(0, vy - 1)
        val y1 = minOf(view.viewHeight, vy + 1)
        val gu = (cornerHeight(x1, vy) - cornerHeight(x0, vy)).toFloat() / maxOf(1, x1 - x0)
        val gv = (cornerHeight(vx, y1) - cornerHeight(vx, y0)).toFloat() / maxOf(1, y1 - y0)
        return if (gu == 0f && gv == 0f) 256f else light(gu, gv).toFloat()
    }

    /** The earth under the map's front edges, down past sea level. */
    private fun earthFaces(vx: Int, vy: Int, r: Int, b: Int, l: Int) {
        val rec = recorder ?: return
        if (vx == view.viewWidth - 1) rec.addGround(GroundRecord.face(vx, vy, GroundRecord.FACE_RIGHT, r, b, l))
        if (vy == view.viewHeight - 1) rec.addGround(GroundRecord.face(vx, vy, GroundRecord.FACE_LEFT, r, b, l))
    }

    // ---- sprites -------------------------------------------------------------------------

    /** Shadow [cell] from the shadows sheet laid at ([ax], [ay]): what's under it darkened by how dark the shadow is there. */
    private fun shade(cell: Int, ax: Float, ay: Float) {
        if (cell < 0) return
        shadeOn(cell, ax, ay)
    }

    private fun shadeOn(cell: Int, ax: Float, ay: Float) {
        val r = cell * 6
        val sx = Atlas.SHADOW_RECTS[r] / shrink
        val sy = Atlas.SHADOW_RECTS[r + 1] / shrink
        val w = Atlas.SHADOW_RECTS[r + 2] / shrink
        val h = Atlas.SHADOW_RECTS[r + 3] / shrink
        val left = (ax + Atlas.SHADOW_RECTS[r + 4] / shrink).toInt()
        val top = (ay + Atlas.SHADOW_RECTS[r + 5] / shrink).toInt()
        recorder?.add(SpriteRecorder.SHADOW, left, top, w, h, sx, sy, 0, cell, anchor(ax - originX), anchor(ay - originY), -1)
    }

    /** Blits static sprite [cell] anchored at ([ax], [ay]), with the snow on world tile [snowTile] on what faces the sky (-1 for none). */
    private fun blit(cell: Int, ax: Float, ay: Float, snowTile: Int = -1) {
        if (cell < 0) return
        onStatic?.let { it(cell, ax, ay); return }
        blitOn(cell, ax, ay, if (snowTile < 0) 0 else snowCover(snowTile), snowTile)
    }

    /** An anchor [a] pixels from view point 0, 0 at this level as level 0 pixels, rounded down, for [SpriteRecorder]. */
    private fun anchor(a: Float): Int = kotlin.math.floor(a * shrink).toInt()

    private fun blitOn(cell: Int, ax: Float, ay: Float, snow: Int, snowTile: Int) {
        val r = cell * 6
        // The sprite as packed at this level, trimmed in from its box by dx and dy.
        val lr = (cell * Atlas.LEVELS + level) * 6
        val sx = StaticCells.levelRects[lr]
        val sy = StaticCells.levelRects[lr + 1]
        val w = StaticCells.levelRects[lr + 2]
        val h = StaticCells.levelRects[lr + 3]
        val left = (ax + StaticCells.rects[r + 4] / shrink).toInt() + StaticCells.levelRects[lr + 4]
        val top = (ay + StaticCells.rects[r + 5] / shrink).toInt() + StaticCells.levelRects[lr + 5]
        recorder?.add(SpriteRecorder.STATIC, left, top, w, h, sx, sy, snow, cell, anchor(ax - originX), anchor(ay - originY), if (snowTile < 0) -1 else (snowTile % world.width) or (snowTile / world.width shl 16))
    }

    /** A static sprite anchored on view tile ([vx], [vy])'s top corner at height [h]. */
    private fun sprite(cell: Int, vx: Int, vy: Int, h: Int, shadow: Boolean = false) =
        if (shadow) shade(cell, x(vx.toFloat(), vy.toFloat()), y(vx.toFloat(), vy.toFloat(), h.toFloat()))
        else blit(cell, x(vx.toFloat(), vy.toFloat()), y(vx.toFloat(), vy.toFloat(), h.toFloat()), world.index(view.worldX(vx, vy), view.worldY(vx, vy)))

    /** A sprite standing at world point ([tx], [ty]), drawn as if on a tile whose middle is there. */
    private fun standing(cell: Int, tx: Float, ty: Float, shadow: Boolean = false) {
        val vx = view.toViewX(tx, ty)
        val vy = view.toViewY(tx, ty)
        val ax = x(vx - 0.5f, vy - 0.5f)
        val ay = y(vx - 0.5f, vy - 0.5f, groundHeight(world, tx, ty))
        if (shadow) shade(cell, ax, ay)
        else blit(cell, ax, ay, world.index(tx.toInt().coerceIn(0, world.width - 1), ty.toInt().coerceIn(0, world.height - 1)))
    }

    /**
     * The trees on a tile, or their shadows, each where the tile's own dice put it, the furthest first. Deep in a
     * forest they close up and are older; on its edges and in thin woods they stand in a clump with open ground
     * round it and are younger. Some take the kind of a wooded neighbour, so stands mix where they meet.
     */
    private fun trees(wx: Int, wy: Int, shadow: Boolean) {
        val tile = world.index(wx, wy)
        val cells = treeCells(shadow)
        // The shadow pass works the spots out and keeps them; the object pass, coming to the same tiles in the same
        // order, draws the ones kept.
        val first: Int
        val until: Int
        if (!shadow && treeNext < treeTiles && treeTile[treeNext] == tile) {
            first = treeFirst[treeNext]
            until = treeFirst[treeNext + 1]
            treeNext++
        } else {
            first = treeFirst[treeTiles]
            until = treeSpots(wx, wy, first)
            if (shadow && until > first) {
                if (treeTiles + 1 >= treeTile.size) {
                    treeTile = treeTile.copyOf(treeTile.size * 2)
                    treeFirst = treeFirst.copyOf(treeFirst.size * 2)
                }
                treeTile[treeTiles++] = tile
                treeFirst[treeTiles] = until
            }
        }
        for (k in first until until) {
            if (shadow) shade(cells[spotCell[k]], spotAx[k], spotAy[k])
            else blit(cells[spotCell[k]], spotAx[k], spotAy[k], spotSnow[k])
        }
    }

    // Tree spots, kept from the shadow pass for the object pass: the tiles with any in the order they came and where
    // each one's spots start (one more, the end), then each spot's anchor, tree (kind * looks + look) and world tile.
    private var treeTile = IntArray(256)
    private var treeFirst = IntArray(257)
    private var treeTiles = 0
    private var treeNext = 0
    private var spotAx = FloatArray(1024)
    private var spotAy = FloatArray(1024)
    private var spotCell = IntArray(1024)
    private var spotSnow = IntArray(1024)
    private var spotKey = FloatArray(1024)
    private val wooded = IntArray(8)

    /** Works out the trees on world tile ([wx], [wy]) into the spots from [first], the furthest first, and returns where they end. */
    private fun treeSpots(wx: Int, wy: Int, first: Int): Int {
        val packed = world.trees[world.index(wx, wy)].toInt()
        // Each of the sim's trees (0 to 3) is drawn as two, so a full tile reads as forest.
        val n = Trees.count(packed) * TREE_SPRITES
        if (n == 0) return first
        if (first + n > spotAx.size) {
            val size = maxOf(spotAx.size * 2, first + n)
            spotAx = spotAx.copyOf(size)
            spotAy = spotAy.copyOf(size)
            spotCell = spotCell.copyOf(size)
            spotSnow = spotSnow.copyOf(size)
            spotKey = spotKey.copyOf(size)
        }
        val kind = Trees.kind(packed)
        var around = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val x = wx + dx
            val y = wy + dy
            if (x < 0 || y < 0 || x >= world.width || y >= world.height) continue
            val p = world.trees[world.index(x, y)].toInt()
            if (Trees.count(p) > 0) wooded[around++] = Trees.kind(p)
        }
        val deep = Trees.count(packed) == 3 && around >= 6
        val age = if (around >= 7) 1 else if (around <= 3) -1 else 0
        val c = LandPainter.dice(wx, wy, 97)
        val cx = 0.3f + (c and 255) / 255f * 0.4f
        val cy = 0.3f + ((c shr 8) and 255) / 255f * 0.4f
        for (k in 0 until n) {
            val d = LandPainter.dice(wx, wy, k)
            val jx = (d and 255) / 255f
            val jy = ((d shr 8) and 255) / 255f
            // Kept off the tile's edges so they don't crowd onto the track next door.
            val ux: Float
            val uy: Float
            if (deep) {
                // Close canopy: a loose three by two grid, each tree in the middle part of its cell so gaps stay small.
                ux = 0.14f + (k % 3 + 0.15f + jx * 0.7f) * 0.24f
                uy = 0.14f + (k / 3 % 2 + 0.15f + jy * 0.7f) * 0.36f
            } else {
                ux = (cx + (jx - 0.5f) * 0.5f).coerceIn(0.12f, 0.88f)
                uy = (cy + (jy - 0.5f) * 0.5f).coerceIn(0.12f, 0.88f)
            }
            val tx = wx + ux
            val ty = wy + uy
            val mixed = ((d shr 24) and 3) == 0 && around > 0
            val own = if (mixed) wooded[((d shr 26) and 7) % around] else kind
            var look = (((d shr 16) and 255) % Atlas.TREE_LOOKS + age).coerceIn(0, Atlas.TREE_LOOKS - 1)
            // A pine's oldest look is the big white pine; only one in four of those, so it stands out among jack pines.
            if (own == Trees.PINE && look == Atlas.TREE_LOOKS - 1 && (d ushr 30) != 0) look--
            // Where it stands, as [standing] works it out, sorted in by how far back it is; ties keep their order.
            val key = view.toViewX(tx, ty) + view.toViewY(tx, ty)
            val vx = view.toViewX(tx, ty)
            val vy = view.toViewY(tx, ty)
            val ax = x(vx - 0.5f, vy - 0.5f)
            val ay = y(vx - 0.5f, vy - 0.5f, groundHeight(world, tx, ty))
            var at = first + k
            while (at > first && spotKey[at - 1].compareTo(key) > 0) {
                spotKey[at] = spotKey[at - 1]
                spotAx[at] = spotAx[at - 1]
                spotAy[at] = spotAy[at - 1]
                spotCell[at] = spotCell[at - 1]
                spotSnow[at] = spotSnow[at - 1]
                at--
            }
            spotKey[at] = key
            spotAx[at] = ax
            spotAy[at] = ay
            spotCell[at] = own * Atlas.TREE_LOOKS + look
            spotSnow[at] = world.index(tx.toInt().coerceIn(0, world.width - 1), ty.toInt().coerceIn(0, world.height - 1))
        }
        return first + n
    }

    /** The trees for the time of year: leafy ones turning in autumn and bare from late autumn till spring. */
    private fun treeCells(shadow: Boolean): IntArray = when (look) {
        Climate.AUTUMN -> if (shadow) Atlas.TREE_SHADOW else Atlas.TREE_AUTUMN
        Climate.LATE_AUTUMN, Climate.WINTER, Climate.EARLY_SPRING -> if (shadow) Atlas.TREE_BARE_SHADOW else Atlas.TREE_BARE
        else -> if (shadow) Atlas.TREE_SHADOW else Atlas.TREE
    }

    /** Now and then a boulder, a shrub or a clump of tall grass, as suits the ground, or its shadow. Only for looks. */
    private fun decor(wx: Int, wy: Int, kind: Int, shadow: Boolean) {
        if (Trees.count(world.trees[world.index(wx, wy)].toInt()) > 0) return
        val d = LandPainter.dice(wx, wy, 11)
        val roll = d and 255
        val what = when (kind) {
            Atlas.ROCK -> if (roll < 80) 0 else -1
            Atlas.TUNDRA -> if (roll < 25) 0 else if (roll < 90) 1 else -1
            Atlas.MEADOW -> if (roll < 12) 1 else if (roll < 50) 2 else -1
            Atlas.PRAIRIE -> if (roll < 70) 2 else -1
            Atlas.FOREST -> if (roll < 60) 1 else -1
            else -> -1
        }
        if (what < 0) return
        val tx = wx + 0.25f + ((d shr 8) and 255) / 255f * 0.5f
        val ty = wy + 0.25f + ((d shr 16) and 255) / 255f * 0.5f
        val cells = if (shadow) Atlas.DECOR_SHADOW else Atlas.DECOR
        standing(cells[what * Atlas.DECOR_LOOKS + (d ushr 24) % Atlas.DECOR_LOOKS], tx, ty, shadow)
    }

    // ---- view helpers ---------------------------------------------------------------------

    private fun worldCorner(wx: Int, wy: Int, cvx: Int, cvy: Int): Int {
        val dx = view.cornerX(cvx, cvy) - wx
        val dy = view.cornerY(cvx, cvy) - wy
        return if (dy == 0) (if (dx == 0) CORNER_N else CORNER_E) else (if (dx == 0) CORNER_W else CORNER_S)
    }

    private fun viewPieceIndex(index: Int): Int {
        val e = Track.edges(Track.PIECES[index])
        return Track.PIECES.indexOf(Track.pieceOf(viewEdge(e and 3), viewEdge(e shr 2)))
    }

    /** Each building kind's place in [Atlas.HOUSE_KEYS], or -1 for one the game has no drawing of. */
    private val houseArts = IntArray(world.roster.houses.size) { h ->
        val key = world.roster.houses[h].key
        if (key.startsWith("base:")) Atlas.HOUSE_KEYS.indexOf(key.removePrefix("base:")) else -1
    }

    private fun houseArt(h: Int) = houseArts.getOrElse(h) { -1 }

    /** Each pack drawing's place among [com.rm.interchange.sim.Roster.buildingSprites]. */
    private val buildingPlace = HashMap<Int, Int>().also { m -> world.roster.buildingSprites.forEachIndexed { i, s -> m[s] = i } }

    /**
     * Pack drawing [sprite]'s cell for view edge [edge] on the static sheet, or -1 if it has none (the game's drawings
     * for its buildings aren't set, or there were too many).
     */
    private fun packCell(sprite: Int, edge: Int): Int {
        val at = buildingPlace[sprite] ?: return -1
        val cell = StaticCells.BASE + at * 4 + edge
        return if ((at + 1) * 4 <= StaticCells.ROOM && cell < StaticCells.count) cell else -1
    }

    /** An industry piece's place in [Atlas.PART_KEYS], or -1 for one the game has no drawing of. */
    private val partArts = HashMap<String, Int>()
    private fun partArt(key: String) = partArts.getOrPut(key) {
        val before = PART_ERAS.firstOrNull { it.first == key && year < it.second }
        Atlas.PART_KEYS.indexOf(before?.third ?: key)
    }

    private fun viewEdge(edge: Int): Int = when (view.turn) {
        0 -> edge
        1 -> when (edge) { Track.XM -> Track.YM; Track.XP -> Track.YP; Track.YM -> Track.XP; else -> Track.XM }
        2 -> Track.opposite(edge)
        else -> when (edge) { Track.YM -> Track.XM; Track.YP -> Track.XP; Track.XM -> Track.YP; else -> Track.YM }
    }

    companion object {
        /** Tree sprites drawn for each of the sim's trees on a tile. */
        private const val TREE_SPRITES = 2
        // Light from the top left of the screen and above, in (u, v, up), and how strongly slopes take it.
        private const val LX = -1f
        private const val LY = -0.55f
        private const val LZ = 1f
        private val LL = sqrt(LX * LX + LY * LY + LZ * LZ)
        private const val LIGHT_GAIN = 0.95f
        private const val LIGHT_LOW = 0.58f
        private const val LIGHT_HIGH = 1.3f

        /** Corners each way looked at for ridges and hollows, and how much lighter or darker a step above or below makes them. */
        private const val RELIEF = 3
        /** Not worked out yet, in [kinds], [snowSums] and [PLANE_LIGHTS]. */
        private const val UNKNOWN = Int.MIN_VALUE
        /** [light] for slopes of up to this many steps each way, filled as asked for; bakers on other threads may fill the same one, with the same value. */
        private const val PLANE_REACH = 4
        private val PLANE_LIGHTS = IntArray((2 * PLANE_REACH + 1) * (2 * PLANE_REACH + 1)) { UNKNOWN }
        /** No neighbours' ground spreading in. Never written. */
        private val NO_EDGES = intArrayOf(-1, -1, -1, -1)
        /** What [building] gives for no building, and for one with no drawing. */
        const val NOTHING = -1L
        const val NO_ART = -2L

        /**
         * Across a road from its middle line, as shares of a tile: the carriageway's half width, its kerb,
         * then out to the boulevard's far side and the sidewalk's, the lot line being at a half.
         */
        private const val CARRIAGEWAY = 0.2f
        private const val KERB = 0.015f
        private const val BOULEVARD = 0.33f
        /** Added to a tile's road bits for the baker: downtown, a gravel or highway surface, tram rails, one-way with the view edge it's
         * towards in bits 10 and 11, and a town's road. */
        private const val DOWNTOWN = 32
        private const val GRAVEL = 64
        private const val HIGHWAY = 128
        private const val TRAM_RAILS = 256
        private const val ONE_WAY = 512
        private const val TOWN_ROAD = 4096
        /** Street trees are maples. */
        private const val STREET_TREE = 3

        /** The world edge an industry's pieces face, by how its layout is turned. */
        private val INDUSTRY_FACING = intArrayOf(Track.XP, Track.YP, Track.XM, Track.YM)

        /** Industry pieces drawn another way before a year: the piece, the year its own drawing starts, and the drawing before it. */
        private val PART_ERAS = listOf(
            Triple("mill_yard", 1925, "mill_yard_team"),
            Triple("headframe", 1920, "headframe_wood"),
            Triple("headframe", 1960, "headframe_steel"),
            // Open pits loaded rail cars behind steam shovels until trucks took over in the 1930s, Euclid rear dumps to the 1960s.
            Triple("open_pit", 1935, "open_pit_steam"),
            Triple("open_pit_far", 1935, "open_pit_far_rail"),
            Triple("open_pit_far", 1965, "open_pit_far_euclid"),
            // Oxygen converters overtook open hearths in Canada about 1970: Dofasco first in 1954, the last open hearths gone by the late 1970s.
            Triple("stacks", 1970, "stacks_open_hearth"),
            Triple("factory_yard", 1950, "factory_yard_box"),
            Triple("factory_yard", 1985, "factory_yard_tractor"),
            Triple("superstack", 1972, "superstack_brick"),
            // Oil sands mines dug by bucketwheel and dragline onto conveyors until trucks and shovels took over about 1993;
            // the Cat 797 came in 1998, smaller rear dumps before it.
            Triple("sand_pit", 1993, "sand_pit_bucketwheel"),
            Triple("sand_pit_far", 1993, "sand_pit_far_bucketwheel"),
            Triple("sand_pit_far", 1998, "sand_pit_far_early"),
            Triple("sand_pit_corner", 1993, "sand_pit_corner_dragline"),
            Triple("sand_pit_corner", 1998, "sand_pit_corner_early"),
        )

        /** The years engine houses, platform canopies, locks and airport buildings change their look ([depotEra], [canopyEra], [lockEra], [terminalEra], [smallHangarEra], [hangarEra], [towerEra]). */
        private val SCENERY_YEARS = intArrayOf(1900, 1915, 1930, 1950, 1958, 1960, 1970, 1985)

        /** An engine house's look by the year it was built (the game's year for one from an older save): 0 wooden to 1900, 1 brick to 1960, 2 a steel diesel shop. */
        fun depotEra(year: Int): Int = when {
            year < 1900 -> 0
            year < 1960 -> 1
            else -> 2
        }

        /** A platform canopy's look by the year its station went up: 0 wooden to 1915, 1 the steel butterfly to 1970, 2 a modern shelter. */
        fun canopyEra(year: Int): Int = when {
            year < 1915 -> 0
            year < 1970 -> 1
            else -> 2
        }

        /** A lock's look by the game's year: 0 a Rideau lock in stone to 1930, 1 a Seaway lock in concrete (the Welland Ship Canal opened in 1932). */
        fun lockEra(year: Int): Int = if (year < 1930) 0 else 1

        /** A terminal's look by the year its airport went up: 0 a Department of Transport terminal to 1958, 1 a glass curtain terminal to 1985, 2 a glass hall. */
        fun terminalEra(year: Int): Int = when {
            year < 1958 -> 0
            year < 1985 -> 1
            else -> 2
        }

        /** A small hangar's look by the year its airport went up: 0 a wooden flying club hangar to 1950, 1 a Butler steel arch hangar. */
        fun smallHangarEra(year: Int): Int = if (year < 1950) 0 else 1

        /** A big hangar's look by the year its airport went up: 0 a wartime RCAF hangar to 1970, 1 a clad steel hangar. */
        fun hangarEra(year: Int): Int = if (year < 1970) 0 else 1

        /** A control tower's look by the year its airport went up: 0 a wooden tower on a timber frame to 1960, 1 a Transport Canada concrete tower. */
        fun towerEra(year: Int): Int = if (year < 1960) 0 else 1

        /** Every year in [PART_ERAS] and [SCENERY_YEARS], once each, in order. */
        private val ERA_YEARS = (PART_ERAS.map { it.second } + SCENERY_YEARS.toList()).distinct().sorted()

        /** How many of [PART_ERAS]' and [SCENERY_YEARS]' years have come by [year], so chunks bake again as each one comes. */
        fun partEras(year: Int): Int = ERA_YEARS.count { year >= it }

        /** How many values [partEras] can take. */
        val PART_ERA_COUNT = ERA_YEARS.size + 1

        const val SNOW = 4

        /** Airport paving, as [airportGround] packs it, and its colours. */
        private const val AERO_RUNWAY = 1
        private const val AERO_STRIP = 2
        private const val AERO_TAXI = 3
        private const val AERO_APRON = 4
        private const val AERO_PAD = 5
        private const val AERO_ALONG_U = 16
        /**
         * Each airport kind's taxi legs that leave the runway's middle line, from [Taxiways] so the map paves and lines
         * the way aircraft go: along0, across0, along1, across1 in tiles. Only city and international airports have them.
         */
        internal val TAXI_LEGS: Array<FloatArray> = Array(Airports.LENGTH.size) { kind ->
            val out = ArrayList<Float>()
            if (kind == Airports.CITY || kind == Airports.INTERNATIONAL) {
                val places = (0 until Taxiways.STANDS[kind].size / 4) + (Taxiways.EXIT0 until Taxiways.EXIT0 + Taxiways.EXITS[kind].size) +
                    listOf(Taxiways.DOOR, Taxiways.END0, Taxiways.END1)
                for (p in places) {
                    val sp = Taxiways.spur(kind, p)
                    for (i in 0 until sp.size / 2 - 1) {
                        if (sp[i * 2 + 1] == 8 && sp[i * 2 + 3] == 8) continue
                        for (k in 0 until 4) out += sp[i * 2 + k] / 16f
                    }
                }
            }
            out.toFloatArray()
        }

        /**
         * The grass through the year, by [Climate] look then ground kind: a colour to mix in, how much in its
         * top byte. Dead grass in early spring, fresh in spring, prairie gold by late summer, red tundra and
         * brown meadows in autumn.
         */
        internal val GRADES = arrayOf(
            intArrayOf(0x598a7a58, 0x598a7a58, 0x26705a40, 0, 0x4d8a7a58),
            intArrayOf(0x1a7aa648, 0x1a8ab050, 0, 0, 0x1a7aa648),
            intArrayOf(0, 0, 0, 0, 0),
            intArrayOf(0x26a89a5a, 0x5ac8a860, 0, 0, 0x1aa89a5a),
            intArrayOf(0x40a08a50, 0x66b89858, 0x1a806040, 0, 0x4da0583a),
            intArrayOf(0x66857455, 0x73a89068, 0x26705a40, 0, 0x4d806048),
            intArrayOf(0x73807462, 0x73a08c6c, 0x26705a40, 0, 0x4d806048),
        )

        fun shapeOf(t: Int, r: Int, b: Int, l: Int) = t or (r shl 1) or (b shl 2) or (l shl 3)

        /** Which ground wins where two meet, spreading in across the edge: water lowest, snow highest. */
        fun rank(kind: Int) = when (kind) {
            Atlas.WATER -> 0
            Atlas.SAND -> 1
            Atlas.PRAIRIE -> 2
            Atlas.MEADOW -> 3
            Atlas.TUNDRA -> 4
            Atlas.FOREST -> 5
            Atlas.ROCK -> 6
            else -> 7
        }
    }
}
