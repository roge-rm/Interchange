package com.rm.interchange.sim

/**
 * A weather system crossing the map: a low bringing rain or snow, a storm, a
 * patch of summer showers, or a fog bank. Where it is and how far it moves a
 * day are in 256ths of a tile; the game moves it once a day and the screen
 * slides it on between.
 *
 * Ahead of a low the air is [warmth] warmer, behind it colder, so rain can
 * turn to snow as one goes over. A [freezing] one brings freezing rain where
 * it's just below zero.
 */
class Front(
    val kind: Int,
    var x: Int,
    var y: Int,
    val dx: Int,
    val dy: Int,
    /** In tiles. */
    val radius: Int,
    /** Precipitation under its middle, in hundredths of a mm a day. */
    val strength: Int,
    /** In tenths of a degree. */
    val warmth: Int,
    var life: Int,
    val freezing: Boolean,
) {
    fun copy() = Front(kind, x, y, dx, dy, radius, strength, warmth, life, freezing)

    /** How much of its strength reaches the middle of tile ([tx], [ty]), in 256ths, falling off to none at its edge. */
    fun reach(tx: Int, ty: Int): Int {
        val ex = (tx * 256L + 128 - x)
        val ey = (ty * 256L + 128 - y)
        val r = radius * 256L
        val d2 = ex * ex + ey * ey
        if (d2 >= r * r) return 0
        return (256 - d2 * 256 / (r * r)).toInt()
    }

    /** 1 if tile ([tx], [ty]) is ahead of it, the way it's going, else -1. */
    fun side(tx: Int, ty: Int): Int = if ((tx * 256L + 128 - x) * dx + (ty * 256L + 128 - y) * dy >= 0) 1 else -1

    companion object {
        const val LOW = 0
        const val STORM = 1
        const val SHOWERS = 2
        const val FOG = 3
    }
}

/**
 * The weather on every tile, kept as bytes: snow depth in cm; ice, as a glaze
 * in mm on land and as its thickness in cm on water; how wet the ground is,
 * in mm; and how deep the frost goes, in cm. Mud is where wet ground is
 * thawing or soaked.
 *
 * Also the fronts crossing the map, the air's warmth against the normals in
 * tenths ([anomaly]), and the wind at the middle of the map.
 */
class Weather(size: Int) {
    val snow = ByteArray(size)
    val ice = ByteArray(size)
    val wet = ByteArray(size)
    val frost = ByteArray(size)
    val fronts = ArrayList<Front>()
    var anomaly = 0
    /** The map's precipitation over the last month or so, in hundredths of a mm a day, to keep it near the normals. */
    var recent = -1
    /** Where the wind blows from, in eighths round from the west towards the north, and how hard, in km/h. */
    var windFrom = 0
    var wind = 0

    /** How far each water tile is from the shore, up to [FAR], worked out on first use. Not saved. */
    internal var shore: ByteArray? = null

    companion object {
        const val FAR = 12
        /** Snow on a track, in cm, that slows trains, and that stops them till they've pushed through. */
        const val DEEP = 25
        const val BLOCKING = 80
    }
}

/** The weather on one tile today, worked out from the fronts and the climate. */
class Sky(val temperature: Int, val precipitation: Int, val freezing: Boolean, val fog: Boolean) {
    val snowing get() = precipitation > 0 && temperature <= 0
}

/**
 * Today's air temperature, precipitation and fog on tile ([x], [y]) at height
 * [h] in steps, from the climate and [fronts], on a map [mapHeight] tall.
 * The game and the screen both use it, the screen with copies of the fronts.
 */
fun skyAt(region: Region, year: Int, doy: Int, anomaly: Int, fronts: List<Front>, mapHeight: Int, x: Int, y: Int, h: Int): Sky {
    var t = Climate.normal(region, year, doy) + anomaly
    t += (y - mapHeight / 2) * Climate.NORTH_SOUTH / mapHeight.coerceAtLeast(1)
    t -= (h - Climate.BASE_HEIGHT) * Climate.LAPSE
    var p = 0
    var freezingShare = 0
    var fog = false
    for (f in fronts) {
        val r = f.reach(x, y)
        if (r == 0) continue
        if (f.kind == Front.FOG) {
            if (r > 64) fog = true
            continue
        }
        t += f.warmth * f.side(x, y) * r / 256
        var add = f.strength * r / 256
        // Showers fall in cells, some places soaked and others missed.
        if (f.kind == Front.SHOWERS) add = if (cellDice(x / 3, y / 3, f.x xor f.y) < 100) add * 5 / 2 else 0
        p += add
        if (f.freezing) freezingShare += add
    }
    // Hills wring more out of the air.
    if (p > 0) p = p * (16 + h.coerceAtLeast(0)) / 16
    return Sky(t, p, freezingShare * 2 > p && t in -40..5, fog)
}

/** A number from 0 to 255 for cell ([x], [y]) and [salt], the same every time. */
internal fun cellDice(x: Int, y: Int, salt: Int): Int {
    var h = x * 374761393 + y * 668265263 + salt * 1442695041
    h = (h xor (h ushr 13)) * 1274126177
    return (h xor (h ushr 16)) and 255
}

/** Rounds [hundredths] to whole units, up or down by tile ([x], [y])'s dice for the day [salt] stands for, so small daily amounts still add up. */
private fun ditherBy(hundredths: Int, x: Int, y: Int, salt: Int): Int {
    if (hundredths <= 0) return 0
    val whole = hundredths / 100
    return whole + if (cellDice(x, y, salt) * 100 / 256 < hundredths % 100) 1 else 0
}

/** The mean height of tile ([x], [y])'s corners, in steps. */
internal fun World.tileHeight(x: Int, y: Int): Int =
    (heightAt(x, y) + heightAt(x + 1, y) + heightAt(x + 1, y + 1) + heightAt(x, y + 1)) / 4

/** Today's weather on tile ([x], [y]). */
fun World.skyAt(x: Int, y: Int): Sky {
    val year = Calendar.year(startYear, day)
    return skyAt(settings.region, year, Calendar.dayOfYear(startYear, day), weather.anomaly, weather.fronts, height, x, y, tileHeight(x, y))
}

private fun u(b: Byte) = b.toInt() and 255

/** How muddy tile [i] is, 0 to 255: wet ground that's thawing out, or soaked. */
fun World.mud(i: Int): Int {
    val g = ground[i].toInt()
    if (g == Ground.ROCK || g == Ground.SNOW || u(weather.snow[i]) > 2) return 0
    val wet = u(weather.wet[i])
    val thawing = u(weather.frost[i]) in 1..60
    return when {
        thawing && wet > 30 -> minOf(255, wet * 3)
        wet > 90 -> minOf(255, (wet - 90) * 3)
        else -> 0
    }
}

/** Starts the weather off on a new map, a couple of months before the first day, so it opens with the season's snow and ice. */
internal fun World.startWeather() {
    for (d in -WARM_UP_DAYS until 0) weatherDay(d)
}

/** The weather's day: the air, the fronts moved on and new ones, then every tile across the cores. */
internal fun World.weatherDay(today: Int = day) {
    if (!settings.weather) return
    val w = weather
    val region = settings.region
    val year = Calendar.year(startYear, today)
    val doy = Calendar.dayOfYear(startYear, today)
    val normal = Climate.normal(region, year, doy)
    val winter = normal < 0

    // The air: warm and cold spells come and go, and now and then a deep cold snap in winter.
    w.anomaly = w.anomaly * 7 / 8 + rng.below(61) - 30
    if (winter && rng.below(45) == 0) w.anomaly -= 80 + rng.below(80)
    if (!winter && rng.below(60) == 0) w.anomaly += 40 + rng.below(40)

    for (f in w.fronts) {
        f.x += f.dx
        f.y += f.dy
        f.life--
    }
    w.fronts.removeAll { f ->
        val r = f.radius * 256
        f.life <= 0 || f.x > width * 256 + r || f.x < -2 * r || f.y > height * 256 + r || f.y < -r
    }

    // Keep the map's precipitation near the normals: sample it on a coarse grid and bring in another front when it's been dry.
    val target = Climate.precipitation(region, year, doy)
    var sampled = 0L
    val grid = 8
    for (gy in 0 until grid) for (gx in 0 until grid) {
        val x = width * (2 * gx + 1) / (2 * grid)
        val y = height * (2 * gy + 1) / (2 * grid)
        sampled += skyAt(region, year, doy, w.anomaly, w.fronts, height, x, y, Climate.BASE_HEIGHT).precipitation
    }
    val today = (sampled / (grid * grid)).toInt()
    w.recent = if (w.recent < 0) target else (w.recent * 29 + today) / 30
    if (w.recent < target) {
        val short = (target - w.recent) * 100 / target.coerceAtLeast(1)
        if (rng.below(100) < 50 + short) newFront(normal, winter, target)
        if (rng.below(100) < short) newFront(normal, winter, target)
    }
    if (rng.below(100) < fogChance(region, doy)) newFog()

    // The wind at the middle of the map: the region's own, stronger round a storm, from the south ahead of a low and the north-west behind.
    val mx = width / 2
    val my = height / 2
    var wind = when (region) { Region.PRAIRIES -> 20; Region.MARITIMES -> 18; Region.NORTH -> 16; else -> 12 } + rng.below(8)
    var from = 0
    var most = 0
    for (f in w.fronts) {
        if (f.kind == Front.FOG) continue
        val r = f.reach(mx, my)
        wind += r * (if (f.kind == Front.STORM) 50 else 15) / 256
        if (r > most) {
            most = r
            from = if (f.side(mx, my) > 0) 6 else 1
        }
    }
    w.wind = wind
    w.windFrom = from

    tileWeather(year, doy, today)
}

/** A new low, storm or patch of showers, by the season. */
private fun World.newFront(normal: Int, winter: Boolean, target: Int) {
    val side = maxOf(width, height)
    val showers = normal > 120 && rng.below(100) < 45
    if (showers) {
        // Summer thunderstorms pop up anywhere and drift a little.
        weather.fronts += Front(
            Front.SHOWERS, rng.below(width) * 256, rng.below(height) * 256, (rng.below(9) - 2) * 256, (rng.below(7) - 3) * 256,
            6 + rng.below(9) + side / 128, target * (6 + rng.below(10)), 0, 1 + rng.below(2), false,
        )
        return
    }
    val storm = rng.below(8) == 0
    val radius = 14 + rng.below(26) + side / 48
    val speed = 10 + rng.below(22)
    // Mostly in from the west, on the prevailing westerlies, now and then up from the south.
    val fromSouth = rng.below(6) == 0
    val x: Int
    val y: Int
    val dx: Int
    val dy: Int
    if (fromSouth) {
        x = rng.below(width) * 256; y = (height + radius) * 256; dx = (rng.below(9) - 2) * 256; dy = -speed * 256
    } else {
        x = (rng.below(width / 3 + radius) - radius) * 256; y = rng.below(height) * 256; dx = speed * 256; dy = (rng.below(13) - 6) * 256
    }
    val days = (side + 2 * radius) / speed + 2
    weather.fronts += Front(
        if (storm) Front.STORM else Front.LOW, x, y, dx, dy, radius,
        target * (3 + rng.below(7)) * (if (storm) 2 else 1), 15 + rng.below(40), days, winter && rng.below(4) == 0,
    )
}

/** Chances in 100 a day of a fog bank: the Maritimes' summer fogs, and spring and autumn mornings elsewhere. */
private fun fogChance(region: Region, doy: Int): Int {
    val spring = doy in 75..150
    val summer = doy in 151..243
    val autumn = doy in 244..320
    return when {
        region == Region.MARITIMES && (spring || summer) -> 30
        spring || autumn -> 10
        else -> 3
    }
}

/** A fog bank over some water, if a look round finds any. */
private fun World.newFog() {
    repeat(8) {
        val x = rng.below(width)
        val y = rng.below(height)
        if (isWater(x, y)) {
            weather.fronts += Front(Front.FOG, x * 256, y * 256, (rng.below(5) - 2) * 128, (rng.below(5) - 2) * 128, 8 + rng.below(14), 0, 0, 1 + rng.below(2), false)
            return
        }
    }
}

/** How far each water tile is from land, up to [Weather.FAR], spreading out from the shore a ring at a time. */
private fun World.shoreDistances(): ByteArray {
    weather.shore?.let { return it }
    val far = ByteArray(width * height) { Weather.FAR.toByte() }
    val first = ArrayList<Int>()
    for (y in 0 until height) for (x in 0 until width) if (!isWater(x, y)) {
        far[index(x, y)] = 0
        first += index(x, y)
    }
    var ring = first.toIntArray()
    for (d in 1 until Weather.FAR) {
        val next = ArrayList<Int>()
        for (i in ring) {
            val x = i % width
            val y = i / width
            for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (!inside(nx, ny)) continue
                val n = index(nx, ny)
                if (far[n].toInt() > d) {
                    far[n] = d.toByte()
                    next += n
                }
            }
        }
        ring = next.toIntArray()
    }
    weather.shore = far
    return far
}

/** Forgets the distances from shore, after the land has changed. */
internal fun World.shoreChanged() {
    weather.shore = null
}

/**
 * Every tile's day: snow falls, settles and melts, faster on slopes facing
 * south; rain and melt soak in and drain; frost goes deeper in the cold,
 * slower under snow; freezing rain glazes; lakes and the sea freeze from the
 * shore out and thaw in spring. Towns clear their roads a few days after it
 * stops snowing, or roll the snow flat before motor plows. Each band of rows
 * writes only its own tiles.
 */
private fun World.tileWeather(year: Int, doy: Int, today: Int) {
    val w = weather
    val shore = shoreDistances()
    val region = settings.region
    val fronts = w.fronts.toList()
    val plows = year >= PLOW_YEAR
    val spring = doy in 50..140
    val salt = today * 31 + seedBits
    fun dither(hundredths: Int, x: Int, y: Int) = ditherBy(hundredths, x, y, salt)
    parallelBands(height) { from, until ->
        for (y in from until until) {
            // Only the fronts that reach this row.
            val near = fronts.filter { f -> kotlin.math.abs(y * 256L + 128 - f.y) < f.radius * 256L }
            for (x in 0 until width) {
                val i = index(x, y)
                val h = tileHeight(x, y)
                val sky = skyAt(region, year, doy, w.anomaly, near, height, x, y, h)
                val t = sky.temperature
                val p = sky.precipitation
                var snow = u(w.snow[i])
                var ice = u(w.ice[i])
                var wet = u(w.wet[i])
                var frost = u(w.frost[i])
                if (isWater(x, y)) {
                    val far = shore[i].toInt()
                    if (t < 0) {
                        // Thickening slows as it gets thicker, under snow and out from the shore.
                        val grow = -t * 30 / (ice + 3) * 10 / (10 + snow) * 3 / (3 + far)
                        ice = minOf(250, ice + dither(minOf(grow, 400), x, y))
                    } else if (ice > 0) {
                        ice = maxOf(0, ice - dither(t * 15 + 100, x, y))
                    }
                    snow = if (ice >= 5) {
                        var s = snow + (if (t <= 0) dither(p, x, y) else 0)
                        if (t > 0) s -= dither(t * 15 + p / 2, x, y)
                        s.coerceIn(0, 250)
                    } else 0
                    w.ice[i] = ice.toByte(); w.snow[i] = snow.toByte()
                    continue
                }
                val melted: Int
                if (t <= 0) {
                    // Colder snow is fluffier.
                    snow += dither(if (t < -100) p * 3 / 2 else p, x, y)
                    // The spring sun melts it off slopes facing south a little even below zero.
                    melted = if (spring && t > -50 && facesSouth(x, y)) minOf(snow, dither(40, x, y)) else 0
                } else {
                    var m = t * 13 + p / 2
                    if (facesSouth(x, y)) m = m * 3 / 2 else if (facesNorth(x, y)) m = m * 3 / 5
                    melted = minOf(snow, dither(m, x, y))
                }
                snow -= melted
                // It settles a little each day.
                snow -= dither(snow * 5, x, y)
                if (t > 0) wet += dither(p, x, y)
                wet += melted * 3
                if (frost == 0) wet -= wet / 12
                if (t > 0) wet -= dither(t * 3 + 50, x, y)
                if (t < 0) {
                    frost += dither(-t * 120 / (frost + 10) * 20 / (20 + snow), x, y)
                } else if (frost > 0) {
                    frost -= dither(t * 20 + 50, x, y)
                }
                if (sky.freezing) ice += dither(p, x, y)
                else if (t in -60..-1 && wet > 80 && snow < 2) ice += 1
                if (t > 0) ice -= dither(t * 10 + 200, x, y) else ice -= dither(30, x, y)
                // Town roads cleared some days after the snow stops, by plow, or rolled flat before.
                if (road[i].toInt() != 0 && snow > 4 && p == 0) {
                    val town = towns.getOrNull(townOf[i].toInt())
                    val busy = if (town != null && town.population > 5000) 128 else 70
                    if (cellDice(x, y, salt + 7) < busy) snow = if (plows) 2 else snow * 2 / 3
                }
                w.snow[i] = snow.coerceIn(0, 250).toByte()
                w.ice[i] = ice.coerceIn(0, 250).toByte()
                w.wet[i] = wet.coerceIn(0, 250).toByte()
                w.frost[i] = frost.coerceIn(0, 250).toByte()
            }
        }
    }
    // Chunks are drawn again when their snow or ice crosses a step of how it looks.
    weatherLooks()
}

/** Whether tile ([x], [y]) slopes down towards the south, the bottom of the map. */
private fun World.facesSouth(x: Int, y: Int) = heightAt(x, y) + heightAt(x + 1, y) > heightAt(x, y + 1) + heightAt(x + 1, y + 1)
private fun World.facesNorth(x: Int, y: Int) = heightAt(x, y) + heightAt(x + 1, y) < heightAt(x, y + 1) + heightAt(x + 1, y + 1)

/** How snow, ice and mud look on tile [i], in steps, for knowing when to draw a chunk again. */
fun World.weatherLook(i: Int): Int {
    val s = u(weather.snow[i])
    val snow = when {
        s == 0 -> 0
        s < 3 -> 1
        s < 10 -> 2
        s < 30 -> 3
        else -> 4
    }
    val ice = u(weather.ice[i])
    val frozen = if (isWater(i % width, i / width)) (if (ice >= 5) 1 + minOf(ice / 20, 2) else 0) else (if (ice > 3) 1 else 0)
    val mud = mud(i).let { if (it > 120) 2 else if (it > 0) 1 else 0 }
    return snow or (frozen shl 3) or (mud shl 5)
}

/** Notes each chunk with a tile whose look has changed, for the renderer, and the season's look once it turns. */
private fun World.weatherLooks() {
    val side = width / World.CHUNK
    val changed = BooleanArray(chunkVersions.size)
    val looks = weatherLooks ?: ByteArray(width * height).also { weatherLooks = it }
    parallelBands(height / World.CHUNK) { from, until ->
        for (cy in from until until) for (y in cy * World.CHUNK until (cy + 1) * World.CHUNK) for (x in 0 until width) {
            val i = index(x, y)
            val look = weatherLook(i).toByte()
            if (looks[i] != look) {
                looks[i] = look
                changed[cy * side + x / World.CHUNK] = true
            }
        }
    }
    for (c in changed.indices) if (changed[c]) chunkVersions[c]++
}

/** Days of weather run before a new game starts. */
const val WARM_UP_DAYS = 75
/** The year towns start plowing their streets. */
const val PLOW_YEAR = 1920
