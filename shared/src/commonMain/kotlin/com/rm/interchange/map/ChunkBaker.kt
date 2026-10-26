package com.rm.interchange.map

import com.rm.interchange.sim.CORNER_E
import com.rm.interchange.sim.CORNER_N
import com.rm.interchange.sim.CORNER_S
import com.rm.interchange.sim.CORNER_W
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.Airport
import com.rm.interchange.sim.Airports
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
import kotlin.math.abs
import com.rm.interchange.sim.hasFoundation
import com.rm.interchange.sim.surfaceHeight
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.mud
import com.rm.interchange.sim.isPlatform
import com.rm.interchange.sim.Surface
import com.rm.interchange.sim.TRAM
import com.rm.interchange.sim.oneWayOf
import com.rm.interchange.sim.straightRoad
import com.rm.interchange.sim.surfaceOf
import com.rm.interchange.sim.ICE_ROAD_CM
import com.rm.interchange.sim.BURNT
import com.rm.interchange.sim.BURNT_FROM
import com.rm.interchange.sim.VERY_WORN
import com.rm.interchange.sim.WORN
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Bakes a chunk of the map into pixels, tile by tile from the back: the
 * ground painted from its kind's texture, ragged where one kind meets another
 * and foamy at the shore, with town roads painted on it, then foundations,
 * track, buildings, trees and the odd boulder or shrub blitted from the static
 * sheet.
 *
 * So the lie of the land reads at a glance, the ground is lit by its slope,
 * ridges are lighter and hollows darker than the land round them, and bare
 * earth shows through on slopes. [overlays] adds a tile grid or bands of
 * height with contour lines, as [GRID] and [HEIGHTS] bits.
 *
 * The weather shows too: snow by depth on the ground and on whatever faces
 * the sky (from the low bits of each sprite pixel's blue), ice on the water,
 * mud, and the grass and leaves by [look], one of [Climate]'s looks of the year.
 *
 * A tile owns the pixels whose middles fall in it, so neighbours share their
 * edges exactly and no seam shows. Textures are laid in each tile's view
 * coordinates and repeat every [Atlas.PERIOD] tiles, so neighbours join up.
 *
 * Reads the world as it is, from a baking thread; see [Renderer].
 */
internal class ChunkBaker(
    private val world: World,
    private val view: View,
    private val level: Int,
    private val sheets: LevelSheets,
    private val width: Int,
    private val height: Int,
    private val overlays: Int = 0,
    private val look: Int = Climate.SUMMER,
    /** Where to draw, width by height, clear to start with. */
    private val pixels: IntArray = IntArray(width * height),
    /** The snow, ice and mud to show. */
    private val shown: WeatherCopy,
    /** The game's year, for industry pieces drawn another way before a year ([PART_ERAS]). */
    private val year: Int = Int.MAX_VALUE,
) {
    private val hw = Renderer.LEVELS[level]
    private val hh = hw / 2
    private val step = hw / 4
    private val shrink = 1 shl level
    private val texels = Atlas.TEXELS shr level
    private val size = texels * Atlas.PERIOD

    /** Where view point 0, 0 at height 0 lands. */
    var originX = 0f
    var originY = 0f

    fun x(vx: Float, vy: Float) = originX + (vx - vy) * hw
    fun y(vx: Float, vy: Float, h: Float) = originY + (vx + vy) * hh - h * step

    fun finish(): IntArray = pixels

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
    }

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

    /** The snow on view tile ([vx], [vy]) in cm, or on the nearest tile on the map. */
    private fun snowAt(vx: Int, vy: Int): Int {
        val x = vx.coerceIn(0, view.viewWidth - 1)
        val y = vy.coerceIn(0, view.viewHeight - 1)
        return shown.snow[world.index(view.worldX(x, y), view.worldY(x, y))].toInt() and 255
    }

    /** The snow at view corner ([vx], [vy]): the mean of the four tiles round it. */
    private fun cornerSnow(vx: Int, vy: Int): Float = (snowAt(vx - 1, vy - 1) + snowAt(vx, vy - 1) + snowAt(vx - 1, vy) + snowAt(vx, vy)) / 4f

    /** The ice at view corner ([vx], [vy]) in cm: the mean of the tiles round it, the shore as thick as the thickest ice there. */
    private fun cornerIce(vx: Int, vy: Int): Float {
        var sum = 0
        var most = 0
        var shore = 0
        for (dy in -1..0) for (dx in -1..0) {
            val x = (vx + dx).coerceIn(0, view.viewWidth - 1)
            val y = (vy + dy).coerceIn(0, view.viewHeight - 1)
            val wx = view.worldX(x, y)
            val wy = view.worldY(x, y)
            if (world.isWater(wx, wy) || world.canals.contains(world.index(wx, wy))) {
                val ice = shown.ice[world.index(wx, wy)].toInt() and 255
                sum += ice
                most = maxOf(most, ice)
            } else shore++
        }
        return (sum + shore * most) / 4f
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
        if (vx < 0 || vy < 0 || vx >= view.viewWidth || vy >= view.viewHeight) return -1
        val t = cornerHeight(vx, vy)
        val r = cornerHeight(vx + 1, vy)
        val b = cornerHeight(vx + 1, vy + 1)
        val l = cornerHeight(vx, vy + 1)
        val low = minOf(minOf(t, r), minOf(b, l))
        if (low > 0) return world.ground[world.index(view.worldX(vx, vy), view.worldY(vx, vy))].toInt()
        return if (t == 0 && r == 0 && b == 0 && l == 0) Atlas.WATER else Atlas.SAND
    }

    /** Whether anything of view tile ([vx], [vy]) can land in the picture: its ground and the earth under it, or what stands on it. */
    fun reaches(vx: Int, vy: Int): Boolean {
        val cx = originX + (vx - vy) * hw
        if (cx + 3 * hw < 0f || cx - 3 * hw > width) return false
        val cy = originY + (vx + vy) * hh
        val high = maxOf(maxOf(cornerHeight(vx, vy), cornerHeight(vx + 1, vy)), maxOf(cornerHeight(vx + 1, vy + 1), cornerHeight(vx, vy + 1)))
        return cy + 2 * hh + 3 * step + 2 >= 0f && cy - high * step - 3 * hw - 2 <= height
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
        val edges = intArrayOf(kindOf(vx, vy - 1), kindOf(vx + 1, vy), kindOf(vx, vy + 1), kindOf(vx - 1, vy))
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
            ground(vx, vy, t, r, b, l, Atlas.WATER, intArrayOf(-1, -1, -1, -1), weather = true, banks = banks)
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
            ground(vx, vy, st, sr, sb, sl, kind, intArrayOf(-1, -1, -1, -1), road, weather = true)
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
                ground(vx, vy, top, top, top, top, kind, intArrayOf(-1, -1, -1, -1))
            }
            return
        }
        if (pieces == 0) return
        world.depotTiles[tile]?.let {
            sprite(Atlas.TRACK[viewPieceIndex(Track.index(pieces)) * 16], vx, vy, base)
            sprite(Atlas.RAILS[viewPieceIndex(Track.index(pieces)) * 16], vx, vy, base)
            return
        }
        val st = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx, vy))
        val sr = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx + 1, vy))
        val sb = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx + 1, vy + 1))
        val sl = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx, vy + 1))
        val sBase = minOf(minOf(st, sr), minOf(sb, sl))
        val sShape = shapeOf(st - sBase, sr - sBase, sb - sBase, sl - sBase)
        if (world.hasFoundation(wx, wy)) {
            val shape = shapeOf(t - base, r - base, b - base, l - base)
            sprite(Atlas.WALLS[shape * 16 + shapeOf(st - base, sr - base, sb - base, sl - base)], vx, vy, base)
            ground(vx, vy, st, sr, sb, sl, kind, intArrayOf(-1, -1, -1, -1))
        }
        // Platforms bring their own track, drawn with them.
        if (world.isPlatform(tile)) return
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
            val art = partArt(kind.part(wx - ind.x, wy - ind.y, ind.turn))
            val base = maxOf(maxOf(cornerHeight(vx, vy), cornerHeight(vx + 1, vy)), maxOf(cornerHeight(vx + 1, vy + 1), cornerHeight(vx, vy + 1)))
            return if (art >= 0) art((if (shadow) Atlas.PART_SHADOW else Atlas.PART)[art * 4 + viewEdge(INDUSTRY_FACING[ind.turn])], base) else NO_ART
        }
        world.stationBuildingAt(tile)?.let { st ->
            val e = viewEdge(st.buildingFacing)
            return art((if (shadow) Atlas.STATION_HOUSE_SHADOW else Atlas.STATION_HOUSE)[stationEra(st.buildingYear) * 4 + e], base)
        }
        val built = world.house[tile].toInt()
        if (built != 0) {
            val art = houseArt(houseOf(built))
            val base = maxOf(maxOf(cornerHeight(vx, vy), cornerHeight(vx + 1, vy)), maxOf(cornerHeight(vx + 1, vy + 1), cornerHeight(vx, vy + 1)))
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
        val base = minOf(minOf(cornerHeight(vx, vy), cornerHeight(vx + 1, vy)), minOf(cornerHeight(vx + 1, vy + 1), cornerHeight(vx, vy + 1)))
        world.depotTiles[tile]?.let { id ->
            val d = world.depots.getOrNull(id) ?: return@let
            val e = viewEdge(d.edge)
            sprite((if (shadow) Atlas.DEPOT_SHADOW else Atlas.DEPOT)[depotEra(d.built.takeIf { it > 0 } ?: year) * 4 + e], vx, vy, base, shadow)
            return
        }
        if (world.isPlatform(tile)) {
            val st = world.surfaceHeight(wx, wy, pieces, worldCorner(wx, wy, vx, vy))
            val a = viewPieceIndex(Track.index(pieces))
            val bare = buildingBehind(vx, vy, a)
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
        // Split along whichever diagonal has its ends level, as the slopes are drawn. Each half is a plane
        // h = h0 + gu * u + gv * v over the tile's own u and v.
        val alongRL = r == l
        val p1 = if (alongRL) floatArrayOf(t.toFloat(), (r - t).toFloat(), (l - t).toFloat()) else floatArrayOf(t.toFloat(), (r - t).toFloat(), (b - r).toFloat())
        val p2 = if (alongRL) floatArrayOf((r + l - b).toFloat(), (b - l).toFloat(), (b - r).toFloat()) else floatArrayOf(t.toFloat(), (b - l).toFloat(), (l - t).toFloat())
        val f1 = light(p1[1], p1[2])
        val f2 = light(p2[1], p2[2])
        val sloped1 = p1[1] != 0f || p1[2] != 0f
        val sloped2 = p2[1] != 0f || p2[2] != 0f
        // Ridges and hollows, from each corner, spread across the tile; none on the water.
        val water = kind == Atlas.WATER
        // Light at each corner, from the slope round it, for halves no lighter than flat ground.
        val shaded = soft && !water && (f1 <= 256 || f2 <= 256)
        val lt = if (shaded) cornerLight(vx, vy) else 256f
        val lr = if (shaded) cornerLight(vx + 1, vy) else 256f
        val lb = if (shaded) cornerLight(vx + 1, vy + 1) else 256f
        val ll = if (shaded) cornerLight(vx, vy + 1) else 256f
        val smooth = shaded && (lt != 256f || lr != 256f || lb != 256f || ll != 256f || f1 != 256 || f2 != 256)
        val rt = if (water) 0f else relief(vx, vy)
        val rr = if (water) 0f else relief(vx + 1, vy)
        val rb = if (water) 0f else relief(vx + 1, vy + 1)
        val rl = if (water) 0f else relief(vx, vy + 1)
        // The land's own slopes darken a little along their low edge, so each step down reads.
        val low = minOf(minOf(t, r), minOf(b, l))
        val rise = (maxOf(maxOf(t, r), maxOf(b, l)) - low).toFloat()
        val foot = soft && !water && rise > 0f
        // What shows through on a slope: earth under plants, rock under snow.
        val bare = when (kind) {
            Atlas.MEADOW, Atlas.PRAIRIE, Atlas.FOREST, Atlas.TUNDRA -> Atlas.EARTH
            Atlas.SNOW -> Atlas.ROCK
            else -> -1
        }
        // Snow and ice at each corner, spread across the tile, so their edges don't follow the tiles.
        val tile = world.index(view.worldX(vx, vy), view.worldY(vx, vy))
        val st = cornerSnow(vx, vy)
        val sr = cornerSnow(vx + 1, vy)
        val sb = cornerSnow(vx + 1, vy + 1)
        val sl = cornerSnow(vx, vy + 1)
        val snowy = st + sr + sb + sl > 0f
        val iT = if (water) cornerIce(vx, vy) else 0f
        val ir = if (water) cornerIce(vx + 1, vy) else 0f
        val ib = if (water) cornerIce(vx + 1, vy + 1) else 0f
        val il = if (water) cornerIce(vx, vy + 1) else 0f
        val icy = water && iT + ir + ib + il > 0f
        val muddy = if (weather && !water && kind != Atlas.SAND) (shown.mud[tile].toInt() and 255) / 255f * MUD_MOST else 0f
        // Burnt black, fading as the ground grows back.
        val fireAt = world.fire[tile].toInt() and 255
        val charred = if (fireAt >= BURNT_FROM) 0.25f + (fireAt - BURNT_FROM) / (BURNT - BURNT_FROM).toFloat() * 0.7f else if (fireAt > 0) 0.7f else 0f
        val worn = if (overlays and CONDITION != 0 && world.track[tile].toInt() != 0) world.condition[tile].toInt() and 255 else -1
        val grade = GRADES[look].getOrNull(kind) ?: 0
        val gradeBy = (grade ushr 24) / 255f
        val grid = overlays and GRID != 0 && hw >= 16
        val heights = overlays and HEIGHTS != 0 && !water
        val fx = vx.toFloat()
        val fy = vy.toFloat()
        val left = x(fx, fy + 1).toInt() - 1
        val right = x(fx + 1, fy).toInt() + 1
        val top = minOf(minOf(y(fx, fy, t.toFloat()), y(fx + 1, fy, r.toFloat())), minOf(y(fx, fy + 1, l.toFloat()), y(fx + 1, fy + 1, b.toFloat()))).toInt() - 1
        val bottom = maxOf(maxOf(y(fx, fy, t.toFloat()), y(fx + 1, fy, r.toFloat())), maxOf(y(fx, fy + 1, l.toFloat()), y(fx + 1, fy + 1, b.toFloat()))).toInt() + 1
        val x0 = maxOf(0, left)
        val x1 = minOf(width - 1, right)
        val y0 = maxOf(0, top)
        val y1 = minOf(height - 1, bottom)
        if (x0 > x1 || y0 > y1) return
        val rankHere = rank(kind)
        val txBase = (vx % Atlas.PERIOD) * texels
        val tyBase = (vy % Atlas.PERIOD) * texels
        val kindBase = kind * size * size
        val ragBase = Atlas.RAG * size * size
        val baseY = originY + (vx + vy) * hh
        val baseX = originX + (vx - vy) * hw
        // The neighbours whose ground spreads in across each edge, -1 for none.
        val spread = IntArray(4) { e -> edges[e].let { if (it < 0 || it == kind || rank(it) <= rankHere) -1 else it } }
        val spreads = spread.any { it >= 0 }
        // Which pixels the tile covers and where each falls on it, the same for every tile of its shape.
        val base = minOf(minOf(t, r), minOf(b, l))
        val cover = coverOf(t - base, r - base, b - base, l - base)
        val bx = baseX.toInt()
        val by = (baseY - base * step).toInt()
        for (k in 0 until cover.size) {
            val px = bx + cover.dx[k]
            val py = by + cover.dy[k]
            if (px < x0 || px > x1 || py < y0 || py > y1) continue
            val u = cover.u[k]
            val v = cover.v[k]
            val second = cover.second[k]
            val f = if (second) f2 else f1
            val p = if (second) p2 else p1
            val sloped = if (second) sloped2 else sloped1
            val tx = txBase + (u * texels).toInt()
            val ty = tyBase + (v * texels).toInt()
            val ti = ty * size + tx
            var texel = sheets.ground[kindBase + ti]
            val rag = (sheets.ground[ragBase + ti] and 255) / 255f
            if (sloped && bare >= 0) {
                // Patches of it, from the same noise read somewhere else so they don't follow the kinds' edges.
                val patch = (sheets.ground[ragBase + ((ty + size / 2) % size) * size + (tx + size / 3) % size] and 255) / 255f
                if (patch > BARE_FROM) texel = sheets.ground[bare * size * size + ti]
            }
            // Higher ranked ground next door spreads in across the edge with a ragged line; water gets foam at the shore.
            if (spreads) for (e in 0 until 4) {
                val other = spread[e]
                if (other < 0) continue
                val dist = when (e) { 0 -> v; 1 -> 1f - u; 2 -> 1f - v; else -> u }
                val reach = 0.08f + rag * 0.26f
                if (dist < reach) {
                    texel = sheets.ground[other * size * size + ti]
                } else if (kind == Atlas.WATER && dist < reach + 0.07f && !icy) {
                    texel = FOAM
                }
            }
            if (gradeBy > 0f) texel = mix(texel, grade, gradeBy)
            if (muddy > 0f) texel = mix(texel, MUD, muddy)
            if (charred > 0f) texel = mix(texel, CHARCOAL, charred * (0.85f + rag * 0.15f))
            if (road != 0) texel = roadTexel(u, v, road, ti, texel)
            if (aero != 0) texel = airportTexel(u, v, aero, texel)
            if (icy) {
                val ice = iT * (1f - u) * (1f - v) + ir * u * (1f - v) + ib * u * v + il * (1f - u) * v
                if (ice >= ICE_FROM) texel = mix(texel, mix(ICE, ICE_DARK, rag * 0.6f), (0.5f + ice / 60f).coerceAtMost(0.92f))
            }
            if (banks != 0) for (k in 0 until 4) {
                if (banks and (1 shl k) == 0) continue
                val dist = when (k) { 0 -> v; 1 -> 1f - u; 2 -> 1f - v; else -> u }
                if (dist < BANK) texel = mix(STONE, sheets.ground[Atlas.ROCK * size * size + ti], 0.3f)
            }
            if (snowy && (!water || icy || banks != 0)) {
                val depth = st * (1f - u) * (1f - v) + sr * u * (1f - v) + sb * u * v + sl * (1f - u) * v
                // Thin snow lies in patches, from noise read somewhere else again; deeper, it covers everything.
                val patch = (sheets.ground[ragBase + ((ty + size / 3) % size) * size + (tx + size / 2) % size] and 255) / 255f
                val cover = ((depth - (0.5f + patch * 14f)) / 3f).coerceIn(0f, 1f)
                if (cover > 0f) {
                    // Traffic packs the snow on a road down to a greyer track, so the road still shows through.
                    val onRoad = (road != 0 && roadTexel(u, v, road, ti, NOT_ROAD) != NOT_ROAD) || (aero != 0 && airportTexel(u, v, aero, NOT_ROAD) != NOT_ROAD)
                    texel = mix(texel, sheets.ground[Atlas.SNOW * size * size + ti], if (onRoad) cover * 0.8f else cover)
                    if (onRoad) texel = mix(texel, PACKED_SNOW, cover * 0.45f)
                }
            }
            val rel = rt * (1f - u) * (1f - v) + rr * u * (1f - v) + rb * u * v + rl * (1f - u) * v
            val sun = if (smooth && f <= 256) minOf(256f, lt * (1f - u) * (1f - v) + lr * u * (1f - v) + lb * u * v + ll * (1f - u) * v) else f.toFloat()
            // Hollows darken less on slopes already in shadow.
            val dark = RELIEF_DARK - (256f - sun).coerceAtLeast(0f) * RELIEF_DARK_SLOPE
            var shade = sun * (1f + (rel * RELIEF_GAIN).coerceIn(-dark, RELIEF_LIGHT))
            if (foot) {
                // From the corners rather than the half's plane, so it doesn't break along the split.
                val up = (t * (1f - u) * (1f - v) + r * u * (1f - v) + b * u * v + l * (1f - u) * v - low) / rise
                if (up < FOOT_BAND && (road == 0 || roadTexel(u, v, road, ti, NOT_ROAD) == NOT_ROAD)) {
                    val s = 1f - up / FOOT_BAND
                    shade *= 1f - FOOT_SHADE * s * s
                }
            }
            if (worn >= 0) texel = mix(texel, if (worn < VERY_WORN) 0xffd03a2a.toInt() else if (worn < WORN) 0xffe8b030.toInt() else 0xff3aa048.toInt(), 0.6f)
            if (overlays and SNOW != 0) {
                val depth = st * (1f - u) * (1f - v) + sr * u * (1f - v) + sb * u * v + sl * (1f - u) * v
                val band = when {
                    icy -> ICE_BAND
                    water || depth < 1f -> 0
                    depth < 10f -> SNOW_BANDS[0]
                    depth < 30f -> SNOW_BANDS[1]
                    depth < 60f -> SNOW_BANDS[2]
                    else -> SNOW_BANDS[3]
                }
                if (band != 0) texel = mix(texel, band, 0.6f)
            }
            if (heights) {
                val h = p[0] + p[1] * u + p[2] * v
                texel = mix(texel, HEIGHT_COLOURS[(h / HEIGHT_BAND).toInt().coerceIn(0, HEIGHT_COLOURS.size - 1)], 0.55f)
                // A contour where a slope crosses a band's edge, about a pixel wide.
                if (sloped && h % HEIGHT_BAND < 1.4f / hw) shade *= 0.55f
            }
            var out = lit(texel, shade.toInt())
            // Grid lines light on dark ground and dark on light, so they show on both.
            if (grid && (u < 1f / hw || v < 1f / hw)) {
                val bright = ((out shr 16) and 255) + ((out shr 8) and 255) + (out and 255)
                out = if (bright > 380) lit(out, 150) else mix(out, GRID_LIGHT, 0.4f)
            }
            pixels[py * width + px] = out
        }
    }

    /** The pixels a tile covers, as offsets from its left corner's column and its top at height 0, with where each falls on the tile. */
    private class Cover(val dx: IntArray, val dy: IntArray, val u: FloatArray, val v: FloatArray, val second: BooleanArray) {
        val size get() = dx.size
    }

    /** The [Cover] of a tile with corner heights [t], [r], [b], [l] above its lowest, made the first time a shape's asked for. */
    private fun coverOf(t: Int, r: Int, b: Int, l: Int): Cover {
        val key = t or (r shl 8) or (b shl 16) or (l shl 24)
        val made = COVERS[level]
        synchronized(made) { made[key]?.let { return it } }
        // A pixel is the tile's if its middle falls in it, on whichever half's plane, as the tile is split.
        val alongRL = r == l
        val p1 = if (alongRL) floatArrayOf(t.toFloat(), (r - t).toFloat(), (l - t).toFloat()) else floatArrayOf(t.toFloat(), (r - t).toFloat(), (b - r).toFloat())
        val p2 = if (alongRL) floatArrayOf((r + l - b).toFloat(), (b - l).toFloat(), (b - r).toFloat()) else floatArrayOf(t.toFloat(), (b - l).toFloat(), (l - t).toFloat())
        val top = minOf(minOf(-t * step, hh - r * step), minOf(hh - l * step, 2 * hh - b * step)).toInt() - 1
        val bottom = maxOf(maxOf(-t * step, hh - r * step), maxOf(hh - l * step, 2 * hh - b * step)).toInt() + 1
        val dx = ArrayList<Int>()
        val dy = ArrayList<Int>()
        val us = ArrayList<Float>()
        val vs = ArrayList<Float>()
        val second = ArrayList<Boolean>()
        for (py in top..bottom) {
            val yRel = py + 0.5f
            for (px in -hw.toInt() - 1..hw.toInt() + 1) {
                val d = (px + 0.5f) / hw
                var s = (yRel + step * (p1[0] + (p1[1] - p1[2]) / 2 * d)) / (hh - step * (p1[1] + p1[2]) / 2)
                var u = (s + d) / 2
                var v = (s - d) / 2
                var inSecond = false
                if (!(if (alongRL) u + v <= 1f else u >= v)) {
                    s = (yRel + step * (p2[0] + (p2[1] - p2[2]) / 2 * d)) / (hh - step * (p2[1] + p2[2]) / 2)
                    u = (s + d) / 2
                    v = (s - d) / 2
                    inSecond = true
                    if (if (alongRL) u + v < 1f else u > v) continue
                }
                if (u < 0f || v < 0f || u >= 1f || v >= 1f) continue
                dx += px
                dy += py
                us += u
                vs += v
                second += inSecond
            }
        }
        val cover = Cover(dx.toIntArray(), dy.toIntArray(), us.toFloatArray(), vs.toFloatArray(), second.toBooleanArray())
        synchronized(made) { made[key] = cover }
        return cover
    }

    /** Which side of a tile view edge [edge] is, as the ground's neighbours count them: up, right, down, left. */
    private fun sideOf(edge: Int) = when (edge) { Track.YM -> 0; Track.XP -> 1; Track.YP -> 2; else -> 3 }

    /**
     * How airport [a]'s tile ([wx], [wy]) is paved, packed for [airportTexel]: an [AERO_RUNWAY], [AERO_STRIP],
     * [AERO_TAXI], [AERO_APRON] or [AERO_PAD] in the low bits, [AERO_ALONG_U] if its runway runs along view u, and
     * the sides of the tile at a runway's ends from bit 8.
     */
    private fun airportGround(a: Airport, wx: Int, wy: Int): Int {
        val lx = if (a.axis == Track.X) wx - a.x else wy - a.y
        val ly = if (a.axis == Track.X) wy - a.y else wx - a.x
        val len = Airports.LENGTH[a.kind]
        val role = when (a.kind) {
            Airports.SEAPLANE -> return 0
            Airports.HELIPORT -> AERO_PAD
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
        return role or (if (alongU) AERO_ALONG_U else 0) or (ends shl 8)
    }

    /** What airport paving [aero] shows at ([u], [v]): runway markings, gravel, a taxiway's line, apron slabs or a helipad's H. [ground] is what's there otherwise. */
    private fun airportTexel(u: Float, v: Float, aero: Int, ground: Int): Int {
        val along = aero and AERO_ALONG_U != 0
        val c = kotlin.math.abs((if (along) v else u) - 0.5f)
        val l = if (along) u else v
        val shade = if (ground == NOT_ROAD) 0f else 0.08f
        fun base(col: Int) = if (shade > 0f) mix(col, ground, shade) else col
        return when (aero and 15) {
            AERO_RUNWAY -> {
                if (c > 0.42f) return ground
                val ends = aero shr 8
                for (k in 0 until 4) {
                    if (ends and (1 shl k) == 0) continue
                    val dist = when (k) { 0 -> v; 1 -> 1f - u; 2 -> 1f - v; else -> u }
                    if (dist < 0.16f && c < 0.33f && ((if (along) v else u) * 12f) % 1f < 0.5f) return MARKING
                }
                when {
                    c < 0.018f && (l * 3f) % 1f < 0.55f -> MARKING
                    c in 0.37f..0.395f -> MARKING
                    else -> base(ASPHALT)
                }
            }
            AERO_STRIP -> if (c > 0.3f) ground else base(STRIP_GRAVEL)
            AERO_TAXI -> when {
                c > 0.28f -> ground
                c < 0.014f -> TAXI_LINE
                else -> base(ASPHALT)
            }
            AERO_APRON -> if ((l * 2f) % 1f < 0.015f || ((if (along) v else u) * 2f) % 1f < 0.015f) base(APRON_JOINT) else base(APRON)
            AERO_PAD -> {
                val du = u - 0.5f
                val dv = v - 0.5f
                val rr = kotlin.math.sqrt(du * du + dv * dv)
                when {
                    kotlin.math.abs(du) > 0.42f || kotlin.math.abs(dv) > 0.42f -> ground
                    rr in 0.28f..0.31f -> TAXI_LINE
                    (kotlin.math.abs(du) in 0.1f..0.14f && kotlin.math.abs(dv) < 0.16f) || (kotlin.math.abs(du) < 0.12f && kotlin.math.abs(dv) < 0.02f) -> MARKING
                    else -> base(APRON)
                }
            }
            else -> ground
        }
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

    /**
     * What a town road shows at ([u], [v]) on its tile, for road [bits] in view edges, [PAVED] and
     * [DOWNTOWN], by how far the point is from the road's middle line: the carriageway, then on a paved
     * road a kerb, a grass boulevard and a sidewalk out to the lot line, or downtown sidewalk all the way
     * to the shopfronts. A paved straight has a dashed line down it. [ground] is what's there otherwise.
     */
    private fun roadTexel(u: Float, v: Float, bits: Int, ti: Int, ground: Int): Int {
        val paved = bits and PAVED != 0
        val highway = bits and HIGHWAY != 0
        val d = roadDistance(u, v, bits)
        val half = if (highway) HIGHWAY_HALF else CARRIAGEWAY
        if (d < half) {
            val straightX = bits and 15 == (1 shl Track.XM) or (1 shl Track.XP)
            val straightY = bits and 15 == (1 shl Track.YM) or (1 shl Track.YP)
            // Streetcar rails, a pair either side of the middle along each arm.
            if (bits and TRAM_RAILS != 0 && tramRail(u, v, bits)) return TRAM_RAIL
            if (bits and ONE_WAY != 0 && oneWayArrow(u, v, (bits shr 10) and 3)) return ROAD_ARROW
            if (!paved) {
                val dirt = sheets.ground[Atlas.DIRT * size * size + ti]
                return if (bits and GRAVEL != 0) mix(dirt, GRAVEL_GREY, 0.45f) else dirt
            }
            if (highway && d > half - EDGE_LINE * 2 && d < half - EDGE_LINE) return EDGE_WHITE
            if ((straightX && abs(v - 0.5f) < LINE && (u * 5f) % 1f < 0.55f) || (straightY && abs(u - 0.5f) < LINE && (v * 5f) % 1f < 0.55f)) return ROAD_LINE
            // Round a corner the dashes follow the curve.
            cornerOf(bits)?.let { (cu, cv) ->
                if (d < LINE && (kotlin.math.atan2(abs(v - cv), abs(u - cu)) / (kotlin.math.PI.toFloat() / 2) * 4f) % 1f < 0.55f) return ROAD_LINE
            }
            return sheets.ground[Atlas.ASPHALT * size * size + ti]
        }
        // Sidewalks and boulevards only in towns; a company's road out in the country has a gravel shoulder.
        if (!paved || bits and TOWN_ROAD == 0) {
            return if (paved && d < half + SHOULDER) mix(ground, GRAVEL_GREY, 0.5f) else ground
        }
        val walk = sheets.ground[Atlas.PAVEMENT * size * size + ti]
        return when {
            d < CARRIAGEWAY + KERB -> lit(walk, 190)
            bits and DOWNTOWN != 0 -> walk
            d < BOULEVARD -> ground
            d < SIDEWALK -> walk
            else -> ground
        }
    }

    /** Whether ([u], [v]) is on a streetcar rail: a pair a little either side of the middle of each arm of the road. */
    /** The tile corner a road that only turns a corner turns round, as (u, v), or null for any other road. */
    private fun cornerOf(bits: Int): Pair<Float, Float>? {
        val e = bits and 15
        val x = e and ((1 shl Track.XM) or (1 shl Track.XP))
        val y = e and ((1 shl Track.YM) or (1 shl Track.YP))
        if (x.countOneBits() != 1 || y.countOneBits() != 1) return null
        return (if (x == 1 shl Track.XM) 0f else 1f) to (if (y == 1 shl Track.YM) 0f else 1f)
    }

    private fun tramRail(u: Float, v: Float, bits: Int): Boolean {
        if (cornerOf(bits) != null) return abs(roadDistance(u, v, bits) - TRAM_GAUGE) < TRAM_WIDTH
        val du = abs(u - 0.5f)
        val dv = abs(v - 0.5f)
        val alongU = (u < 0.5f && bits and (1 shl Track.XM) != 0) || (u >= 0.5f && bits and (1 shl Track.XP) != 0)
        val alongV = (v < 0.5f && bits and (1 shl Track.YM) != 0) || (v >= 0.5f && bits and (1 shl Track.YP) != 0)
        return (alongU && abs(dv - TRAM_GAUGE) < TRAM_WIDTH) || (alongV && abs(du - TRAM_GAUGE) < TRAM_WIDTH)
    }

    /** Whether ([u], [v]) is on a white arrow in the middle of the tile pointing out through view edge [edge]. */
    private fun oneWayArrow(u: Float, v: Float, edge: Int): Boolean {
        // Into the arrow's own terms: along it towards its tip, and across.
        val (along, across) = when (edge) {
            Track.XP -> (u - 0.5f) to (v - 0.5f)
            Track.XM -> (0.5f - u) to (v - 0.5f)
            Track.YP -> (v - 0.5f) to (u - 0.5f)
            else -> (0.5f - v) to (u - 0.5f)
        }
        if (along < -0.18f || along > 0.18f) return false
        // A shaft, then a head narrowing to the tip.
        return if (along < 0.04f) abs(across) < 0.025f else abs(across) < (0.18f - along) * 0.6f
    }

    /** How far ([u], [v]) is from the middle line of a road reaching the view edges in [bits], squarely round its middle. */
    private fun roadDistance(u: Float, v: Float, bits: Int): Float {
        // A road turning a corner sweeps round it: a quarter circle about the corner its two edges share, through their middles.
        cornerOf(bits)?.let { (cu, cv) -> return abs(sqrt((u - cu) * (u - cu) + (v - cv) * (v - cv)) - 0.5f) }
        val du = abs(u - 0.5f)
        val dv = abs(v - 0.5f)
        var d = maxOf(du, dv)
        if ((u < 0.5f && bits and (1 shl Track.XM) != 0) || (u >= 0.5f && bits and (1 shl Track.XP) != 0)) d = minOf(d, dv)
        if ((v < 0.5f && bits and (1 shl Track.YM) != 0) || (v >= 0.5f && bits and (1 shl Track.YP) != 0)) d = minOf(d, du)
        return d
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

    /** Whether ([u], [v]) is within [w] of the middle line of a road reaching the view edges in [bits]. */
    private fun inRoad(u: Float, v: Float, bits: Int, w: Float): Boolean {
        val du = abs(u - 0.5f)
        val dv = abs(v - 0.5f)
        if (du < w && dv < w) return true
        if (dv < w && ((u < 0.5f && bits and (1 shl Track.XM) != 0) || (u >= 0.5f && bits and (1 shl Track.XP) != 0))) return true
        if (du < w && ((v < 0.5f && bits and (1 shl Track.YM) != 0) || (v >= 0.5f && bits and (1 shl Track.YP) != 0))) return true
        return false
    }

    /** [a] moved [t] of the way to [b], colour by colour. */
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 255) * (1f - t) + ((b shr shift) and 255) * t).toInt() shl shift
        return (0xff shl 24) or ch(16) or ch(8) or ch(0)
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

    /** The [light] at view corner ([vx], [vy]), from the slope across the corners either side of it. */
    private fun cornerLight(vx: Int, vy: Int): Float {
        val x0 = maxOf(0, vx - 1)
        val x1 = minOf(view.viewWidth, vx + 1)
        val y0 = maxOf(0, vy - 1)
        val y1 = minOf(view.viewHeight, vy + 1)
        val gu = (cornerHeight(x1, vy) - cornerHeight(x0, vy)).toFloat() / maxOf(1, x1 - x0)
        val gv = (cornerHeight(vx, y1) - cornerHeight(vx, y0)).toFloat() / maxOf(1, y1 - y0)
        return if (gu == 0f && gv == 0f) 256f else light(gu, gv).toFloat()
    }

    private fun lit(texel: Int, f: Int): Int {
        val r = minOf(255, ((texel shr 16) and 255) * f shr 8)
        val g = minOf(255, ((texel shr 8) and 255) * f shr 8)
        val b = minOf(255, (texel and 255) * f shr 8)
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** The earth under the map's front edges, down past sea level. */
    private fun earthFaces(vx: Int, vy: Int, r: Int, b: Int, l: Int) {
        val fx = vx.toFloat()
        val fy = vy.toFloat()
        if (vx == view.viewWidth - 1) {
            face(x(fx + 1, fy), y(fx + 1, fy, r.toFloat()), y(fx + 1, fy, -2f), x(fx + 1, fy + 1), y(fx + 1, fy + 1, b.toFloat()), y(fx + 1, fy + 1, -2f), EARTH_DARK)
        }
        if (vy == view.viewHeight - 1) {
            face(x(fx, fy + 1), y(fx, fy + 1, l.toFloat()), y(fx, fy + 1, -2f), x(fx + 1, fy + 1), y(fx + 1, fy + 1, b.toFloat()), y(fx + 1, fy + 1, -2f), EARTH_LIGHT)
        }
    }

    /** An upright face between screen columns [ax] and [bx], each end running from its top y down to its bottom y. */
    private fun face(ax: Float, aTop: Float, aBottom: Float, bx: Float, bTop: Float, bBottom: Float, colour: Int) {
        for (px in maxOf(0, minOf(ax, bx).toInt())..minOf(width - 1, maxOf(ax, bx).toInt())) {
            val t = ((px + 0.5f - ax) / (bx - ax)).coerceIn(0f, 1f)
            val top = aTop + (bTop - aTop) * t
            val bottom = aBottom + (bBottom - aBottom) * t
            for (py in maxOf(0, top.toInt())..minOf(height - 1, bottom.toInt())) {
                if (py + 0.5f >= top) pixels[py * width + px] = colour
            }
        }
    }

    // ---- sprites -------------------------------------------------------------------------

    /** Blits static sprite [cell] with its anchor at ([ax], [ay]), blending by its alpha. */
    /** Shadow [cell] from the shadows sheet laid at ([ax], [ay]): what's under it darkened by how dark the shadow is there. */
    private fun shade(cell: Int, ax: Float, ay: Float) {
        if (cell < 0) return
        val r = cell * 6
        val sx = Atlas.SHADOW_RECTS[r] / shrink
        val sy = Atlas.SHADOW_RECTS[r + 1] / shrink
        val w = Atlas.SHADOW_RECTS[r + 2] / shrink
        val h = Atlas.SHADOW_RECTS[r + 3] / shrink
        val left = (ax + Atlas.SHADOW_RECTS[r + 4] / shrink).toInt()
        val top = (ay + Atlas.SHADOW_RECTS[r + 5] / shrink).toInt()
        val src = sheets.shadows
        val sw = sheets.shadowWidth
        for (j in 0 until h) {
            val py = top + j
            if (py < 0 || py >= height) continue
            val srcRow = (sy + j) * sw + sx
            val dstRow = py * width
            for (i in 0 until w) {
                val px = left + i
                if (px < 0 || px >= width) continue
                val a = src[srcRow + i].toInt() and 255
                if (a == 0) continue
                val d = pixels[dstRow + px]
                val keep = 255 - a
                pixels[dstRow + px] = (d and 0xff000000.toInt()) or ((((d shr 16) and 255) * keep / 255) shl 16) or ((((d shr 8) and 255) * keep / 255) shl 8) or ((d and 255) * keep / 255)
            }
        }
    }

    private fun blit(cell: Int, ax: Float, ay: Float, snow: Int = 0) {
        if (cell < 0) return
        val r = cell * 6
        val sx = Atlas.STATIC_RECTS[r] / shrink
        val sy = Atlas.STATIC_RECTS[r + 1] / shrink
        val w = Atlas.STATIC_RECTS[r + 2] / shrink
        val h = Atlas.STATIC_RECTS[r + 3] / shrink
        val left = (ax + Atlas.STATIC_RECTS[r + 4] / shrink).toInt()
        val top = (ay + Atlas.STATIC_RECTS[r + 5] / shrink).toInt()
        val src = sheets.static
        val sw = sheets.staticWidth
        for (j in 0 until h) {
            val py = top + j
            if (py < 0 || py >= height) continue
            val srcRow = (sy + j) * sw + sx
            val dstRow = py * width
            for (i in 0 until w) {
                val px = left + i
                if (px < 0 || px >= width) continue
                var c = src[srcRow + i]
                val a = c ushr 24
                if (a == 0) continue
                if (snow > 0 && c and 3 != 0) {
                    // Snow on what faces the sky, a little greyer where it's steeper.
                    val up = c and 3
                    val k = snow * up / 3
                    val white = SNOW_TOP - (3 - up) * 0x0a0a0a
                    c = (c and 0xff000000.toInt()) or (mix(c, white, k / 256f) and 0xffffff)
                }
                if (a == 255) {
                    pixels[dstRow + px] = c
                    continue
                }
                val d = pixels[dstRow + px]
                val da = d ushr 24
                val inv = 255 - a
                val rr = (((c shr 16) and 255) * a + ((d shr 16) and 255) * inv) / 255
                val gg = (((c shr 8) and 255) * a + ((d shr 8) and 255) * inv) / 255
                val bb = ((c and 255) * a + (d and 255) * inv) / 255
                val oa = a + da * inv / 255
                pixels[dstRow + px] = (oa shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
    }

    /** A static sprite anchored on view tile ([vx], [vy])'s top corner at height [h]. */
    private fun sprite(cell: Int, vx: Int, vy: Int, h: Int, shadow: Boolean = false) =
        if (shadow) shade(cell, x(vx.toFloat(), vy.toFloat()), y(vx.toFloat(), vy.toFloat(), h.toFloat()))
        else blit(cell, x(vx.toFloat(), vy.toFloat()), y(vx.toFloat(), vy.toFloat(), h.toFloat()), snowCover(world.index(view.worldX(vx, vy), view.worldY(vx, vy))))

    /** A sprite standing at world point ([tx], [ty]), drawn as if on a tile whose middle is there. */
    private fun standing(cell: Int, tx: Float, ty: Float, shadow: Boolean = false) {
        val vx = view.toViewX(tx, ty)
        val vy = view.toViewY(tx, ty)
        val ax = x(vx - 0.5f, vy - 0.5f)
        val ay = y(vx - 0.5f, vy - 0.5f, groundHeight(world, tx, ty))
        if (shadow) shade(cell, ax, ay)
        else blit(cell, ax, ay, snowCover(world.index(tx.toInt().coerceIn(0, world.width - 1), ty.toInt().coerceIn(0, world.height - 1))))
    }

    /**
     * The trees on a tile, or their shadows, each where the tile's own dice put it, the furthest first. Deep in a
     * forest they close up and are older; on its edges and in thin woods they stand in a clump with open ground
     * round it and are younger. Some take the kind of a wooded neighbour, so stands mix where they meet.
     */
    private fun trees(wx: Int, wy: Int, shadow: Boolean) {
        val packed = world.trees[world.index(wx, wy)].toInt()
        // Each of the sim's trees (0 to 3) is drawn as two, so a full tile reads as forest.
        val n = Trees.count(packed) * TREE_SPRITES
        if (n == 0) return
        val kind = Trees.kind(packed)
        val wooded = IntArray(8)
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
        val spots = ArrayList<FloatArray>(n)
        for (k in 0 until n) {
            val d = LandPainter.dice(wx, wy, k)
            val jx = (d and 255) / 255f
            val jy = ((d shr 8) and 255) / 255f
            // Kept off the tile's edges so they don't crowd onto the track next door.
            val (ux, uy) = if (deep) {
                // Close canopy: a loose three by two grid, each tree in the middle part of its cell so gaps stay small.
                0.14f + (k % 3 + 0.15f + jx * 0.7f) * 0.24f to 0.14f + (k / 3 % 2 + 0.15f + jy * 0.7f) * 0.36f
            } else {
                (cx + (jx - 0.5f) * 0.5f).coerceIn(0.12f, 0.88f) to (cy + (jy - 0.5f) * 0.5f).coerceIn(0.12f, 0.88f)
            }
            val tx = wx + ux
            val ty = wy + uy
            val mixed = ((d shr 24) and 3) == 0 && around > 0
            val own = if (mixed) wooded[((d shr 26) and 7) % around] else kind
            var look = (((d shr 16) and 255) % Atlas.TREE_LOOKS + age).coerceIn(0, Atlas.TREE_LOOKS - 1)
            // A pine's oldest look is the big white pine; only one in four of those, so it stands out among jack pines.
            if (own == Trees.PINE && look == Atlas.TREE_LOOKS - 1 && (d ushr 30) != 0) look--
            spots += floatArrayOf(view.toViewX(tx, ty) + view.toViewY(tx, ty), tx, ty, look.toFloat(), own.toFloat())
        }
        spots.sortBy { it[0] }
        val cells = treeCells(shadow)
        for (sp in spots) standing(cells[sp[4].toInt() * Atlas.TREE_LOOKS + sp[3].toInt()], sp[1], sp[2], shadow)
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
        /** What [building] gives for no building, and for one with no drawing. */
        const val NOTHING = -1L
        const val NO_ART = -2L
        /** Each level's tile [Cover]s by shape, shared by every baker. */
        private val COVERS = Array(Renderer.LEVELS.size) { HashMap<Int, Cover>() }
        private const val RELIEF_GAIN = 0.1f
        private const val RELIEF_DARK = 0.24f
        /** How much less [RELIEF_DARK] darkens for each 256th a slope is in shadow. */
        private const val RELIEF_DARK_SLOPE = 0.4f / 256f
        private const val RELIEF_LIGHT = 0.18f
        /** How far up a slope tile, as a share of its rise, the shade along its low edge reaches, and how dark it is there. */
        private const val FOOT_BAND = 0.4f
        private const val FOOT_SHADE = 0.1f

        /** Where the noise has to be past for bare ground to show on a slope: about a quarter of a slope shows it. */
        private const val BARE_FROM = 0.64f

        private const val GRID_LIGHT = 0xfff2efe6.toInt()

        /**
         * Across a road from its middle line, as shares of a tile: the carriageway's half width, its kerb,
         * then out to the boulevard's far side and the sidewalk's, the lot line being at a half.
         */
        private const val CARRIAGEWAY = 0.2f
        private const val KERB = 0.015f
        private const val BOULEVARD = 0.33f
        private const val SIDEWALK = 0.47f
        /** Added to a tile's road bits for the baker: downtown, a gravel or highway surface, tram rails, one-way with the view edge it's
         * towards in bits 10 and 11, and a town's road. */
        private const val DOWNTOWN = 32
        private const val GRAVEL = 64
        private const val HIGHWAY = 128
        private const val TRAM_RAILS = 256
        private const val ONE_WAY = 512
        private const val TOWN_ROAD = 4096
        private const val HIGHWAY_HALF = 0.3f
        private const val SHOULDER = 0.05f
        private const val EDGE_LINE = 0.012f
        private const val TRAM_GAUGE = 0.06f
        private const val TRAM_WIDTH = 0.008f
        private const val GRAVEL_GREY = 0xff9a948a.toInt()
        private const val EDGE_WHITE = 0xffe8e8e2.toInt()
        private const val TRAM_RAIL = 0xff4a4846.toInt()
        private const val ROAD_ARROW = 0xffeaeae4.toInt()
        /** Street trees are maples. */
        private const val STREET_TREE = 3
        /** The dashed line down a paved straight: its half width and colour. */
        private const val LINE = 0.012f
        private const val ROAD_LINE = 0xffd6c46a.toInt()

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

        /** Overlays, as bits. */
        const val GRID = 1
        const val HEIGHTS = 2
        const val SNOW = 4
        const val CONDITION = 8
        private const val CHARCOAL = 0xff2e2a26.toInt()

        /** The snow overlay's bands: under 10 cm, under 30, under 60, deeper; then ice on the water. */
        private val SNOW_BANDS = intArrayOf(0xff9fd0f0.toInt(), 0xff5a9ae0.toInt(), 0xff3a52c8.toInt(), 0xff6a2ab0.toInt())
        private const val ICE_BAND = 0xff40e0e0.toInt()
        /** Steps of height to each band of the heights overlay, and the bands' colours from sea level up. */
        private const val HEIGHT_BAND = 2f
        private val HEIGHT_COLOURS = intArrayOf(
            0xff2f7a3a.toInt(), 0xff4f9440.toInt(), 0xff7fac4a.toInt(), 0xffb0bc58.toInt(), 0xffd4c062.toInt(), 0xffd8a456.toInt(),
            0xffc8844a.toInt(), 0xffa86a44.toInt(), 0xff8a5a46.toInt(), 0xff9a8478.toInt(), 0xffc8c0bc.toInt(), 0xfff2f2f6.toInt(),
        )

        private const val FOAM = 0xffe8f0f4.toInt()
        private const val ICE = 0xffd4e2ea.toInt()
        private const val ICE_DARK = 0xffa8bccb.toInt()
        /** Ice thinner than this, in cm, doesn't show. */
        private const val ICE_FROM = 4f
        private const val SNOW_TOP = 0xfff4f7fa.toInt()
        private const val MUD = 0xff5a4630.toInt()
        private const val PACKED_SNOW = 0xff9c9a94.toInt()
        /** A canal's stone banks, and how far in from the edge they reach. */
        private const val STONE = 0xff8e8a82.toInt()
        private const val BANK = 0.11f
        /** Airport paving, as [airportGround] packs it, and its colours. */
        private const val AERO_RUNWAY = 1
        private const val AERO_STRIP = 2
        private const val AERO_TAXI = 3
        private const val AERO_APRON = 4
        private const val AERO_PAD = 5
        private const val AERO_ALONG_U = 16
        private const val ASPHALT = 0xff4a4c4e.toInt()
        private const val STRIP_GRAVEL = 0xffa8987c.toInt()
        private const val APRON = 0xffb4b0a8.toInt()
        private const val APRON_JOINT = 0xff96928a.toInt()
        private const val MARKING = 0xffeeeeea.toInt()
        private const val TAXI_LINE = 0xffe8c030.toInt()
        /** Passed to roadTexel as the ground, to tell where a road shows. */
        private const val NOT_ROAD = 0x00010203
        private const val MUD_MOST = 0.45f

        /**
         * The grass through the year, by [Climate] look then ground kind: a colour to mix in, how much in its
         * top byte. Dead grass in early spring, fresh in spring, prairie gold by late summer, red tundra and
         * brown meadows in autumn.
         */
        private val GRADES = arrayOf(
            intArrayOf(0x598a7a58, 0x598a7a58, 0x26705a40, 0, 0x4d8a7a58),
            intArrayOf(0x1a7aa648, 0x1a8ab050, 0, 0, 0x1a7aa648),
            intArrayOf(0, 0, 0, 0, 0),
            intArrayOf(0x26a89a5a, 0x5ac8a860, 0, 0, 0x1aa89a5a),
            intArrayOf(0x40a08a50, 0x66b89858, 0x1a806040, 0, 0x4da0583a),
            intArrayOf(0x66857455, 0x73a89068, 0x26705a40, 0, 0x4d806048),
            intArrayOf(0x73807462, 0x73a08c6c, 0x26705a40, 0, 0x4d806048),
        )
        private const val EARTH_LIGHT = 0xff7a6248.toInt()
        private const val EARTH_DARK = 0xff5a4634.toInt()

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
