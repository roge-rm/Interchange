package com.rm.interchange.ui

import com.rm.interchange.res.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.stringArrayResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.Front
import com.rm.interchange.sim.Sky
import com.rm.interchange.sim.WeatherView
import com.rm.interchange.sim.World
import com.rm.interchange.sim.skyAt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** Today's weather on tile ([x], [y]), from the game's copy of the fronts. */
fun skyHere(world: World, wv: WeatherView, x: Int, y: Int): Sky {
    val h = (world.heightAt(x, y) + world.heightAt(x + 1, y) + world.heightAt(x + 1, y + 1) + world.heightAt(x, y + 1)) / 4
    return skyAt(world.settings.region, wv.year, wv.doy, wv.anomaly, wv.fronts, world.height, x, y, h)
}

/** What the sky's doing, as a place in the skies list: clear, cloudy, rain, snow, freezing rain, fog, a thunderstorm or a blizzard. */
fun skyKind(wv: WeatherView, x: Int, y: Int, sky: Sky): Int {
    val storm = wv.fronts.any { it.kind == Front.STORM && it.reach(x, y) > 64 }
    return when {
        sky.fog -> 5
        sky.precipitation > 0 && sky.freezing -> 4
        sky.precipitation > 0 && sky.snowing -> if (storm || wv.wind >= 40) 7 else 3
        sky.precipitation > 0 -> if (storm || wv.fronts.any { it.kind == Front.SHOWERS && it.reach(x, y) > 0 }) 6 else 2
        wv.fronts.any { it.kind != Front.FOG && it.reach(x, y) > 0 } -> 1
        else -> 0
    }
}

/** A temperature in tenths as the strip shows it, to the degree, with a real minus sign. */
fun degrees(tenths: Int): String {
    val d = (tenths / 10f).roundToInt()
    return (if (d < 0) "−" else "") + abs(d) + "°"
}

/** Hours of daylight, in minutes, on day [doy] at [latitude] degrees north. */
fun daylightMinutes(latitude: Int, doy: Int): Int {
    val decline = 23.44 * sin(2 * PI * (284 + doy + 1) / 365.0)
    val cos = -tan(latitude * PI / 180) * tan(decline * PI / 180)
    if (cos <= -1) return 24 * 60
    if (cos >= 1) return 0
    return (acos(cos) * 2 * 24 * 60 / (2 * PI)).roundToInt()
}

/** The weather where the map is looking: the sky, temperature, wind, what's on the ground, the season and the daylight. */
@Composable
fun WeatherCard(world: World, wv: WeatherView, x: Int, y: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val sky = skyHere(world, wv, x, y)
    val skies = stringArrayResource(Res.array.skies)
    val seasons = stringArrayResource(Res.array.seasons)
    val winds = stringArrayResource(Res.array.winds)
    val region = world.settings.region
    val i = world.index(x, y)
    val snow = world.weather.snow[i].toInt() and 255
    val ice = world.weather.ice[i].toInt() and 255
    val frost = world.weather.frost[i].toInt() and 255
    val water = world.isWater(x, y)
    val normal = Climate.normal(region, wv.year, wv.doy)
    val drift = Climate.drift(wv.year)
    val light = daylightMinutes(Climate.LATITUDE[region.ordinal], wv.doy)
    Card(skies.getOrElse(skyKind(wv, x, y, sky)) { "" }, onClose, modifier) {
        Head(stringResource(Res.string.weather_now), Glyph.Sun)
        InfoRow(stringResource(Res.string.temperature), degrees(sky.temperature), Glyph.Thermometer)
        InfoRow(stringResource(Res.string.normal_temperature), degrees(normal), Glyph.Thermometer)
        InfoRow(
            stringResource(Res.string.wind),
            if (wv.wind < 5) stringResource(Res.string.calm) else stringResource(Res.string.wind_speed, winds.getOrElse(wv.windFrom) { "" }, wv.wind),
            Glyph.Wind,
        )
        val showIce = water && ice >= 5
        val showFrost = !water && frost > 0
        if (showIce || snow > 0 || showFrost) {
            Head(stringResource(Res.string.weather_ground), Glyph.Snow)
            if (showIce) InfoRow(stringResource(Res.string.ice), stringResource(Res.string.centimetres, ice), Glyph.Water)
            if (snow > 0) InfoRow(stringResource(Res.string.snow_depth), stringResource(Res.string.centimetres, snow), Glyph.Snow)
            if (showFrost) InfoRow(stringResource(Res.string.frost), stringResource(Res.string.centimetres, frost), Glyph.Land)
        }
        Head(stringResource(Res.string.weather_year), Glyph.Calendar)
        InfoRow(stringResource(Res.string.season), seasons.getOrElse(Climate.season(region, wv.year, wv.doy)) { "" }, Glyph.Tree)
        InfoRow(stringResource(Res.string.daylight), stringResource(Res.string.hours_minutes, light / 60, light % 60), Glyph.Sun)
        if (drift >= 5) InfoRow(stringResource(Res.string.warmer_than_1900), degreesExact(drift), Glyph.Thermometer)
    }
}

/** A difference in tenths of a degree, to the tenth. */
private fun degreesExact(tenths: Int): String = "${tenths / 10}.${abs(tenths % 10)}°"
