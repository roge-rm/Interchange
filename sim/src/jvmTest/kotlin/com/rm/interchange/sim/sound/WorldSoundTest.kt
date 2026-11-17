package com.rm.interchange.sim.sound

import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.FIRE_DAYS
import com.rm.interchange.sim.Front
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Runways
import com.rm.interchange.sim.News
import com.rm.interchange.sim.Traction
import com.rm.interchange.sim.Trees
import com.rm.interchange.sim.World
import com.rm.interchange.sim.skyAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldSoundTest {
    private fun world(year: Int = 1900): World {
        val w = World(NewGame(width = 64, height = 64, seed = 1, startYear = year))
        w.weather.fronts.clear()
        w.weather.anomaly = 0
        w.weather.wind = 0
        for (i in w.corners.indices) w.corners[i] = 4
        w.trees.fill(0)
        return w
    }

    private fun at(x: Float = 32.5f, y: Float = 32.5f, half: Float = 8f, turn: Int = 0) =
        WorldSound.Listener(x, y, half, half, turn)

    private fun frame(
        sound: WorldSound,
        at: WorldSound.Listener,
        hour: Float = 12f,
        dt: Float = 0.2f,
        movers: List<WorldSound.Mover> = emptyList(),
        crossings: IntArray = IntArray(0),
        stations: List<WorldSound.StationSpot> = emptyList(),
    ): WorldSound.Frame {
        val out = WorldSound.Frame()
        sound.frame(at, hour, dt, out, movers, crossings, stations)
        return out
    }

    private fun mover(
        kind: Int,
        id: Int,
        x: Float = 34f,
        y: Float = 32.5f,
        speed: Float = 0.5f,
        traction: Int = Traction.DIESEL,
        jet: Boolean = false,
        inDepot: Boolean = false,
        wrecked: Boolean = false,
        waiting: Boolean = false,
        sound: Int = 0,
    ) = WorldSound.Mover(kind, id, x, y, speed, traction, jet, inDepot, wrecked, waiting, sound)

    private fun front(kind: Int, warmth: Int, strength: Int = 8000) = Front(
        kind, 32 * 256 + 128, 32 * 256 + 128, 256, 0, 40, strength, warmth, 4, false,
    )

    @Test
    fun dryHasNoRain() {
        val w = world()
        val out = frame(WorldSound(w), at())
        assertEquals(0, w.skyAt(32, 32).precipitation)
        assertEquals(0f, out.loudness(WorldSound.KEY_RAIN))
        assertEquals(0, out.shots.size)
    }

    @Test
    fun rainWhenWetAndAboveFreezing() {
        val w = world()
        w.weather.fronts += front(Front.LOW, warmth = 250)
        val sky = w.skyAt(32, 32)
        assertTrue(sky.precipitation > 0 && sky.temperature > 0, "sky ${sky.temperature} ${sky.precipitation}")
        val out = frame(WorldSound(w), at())
        assertTrue(out.loudness(WorldSound.KEY_RAIN) > 0f, "rain ${out.loudness(WorldSound.KEY_RAIN)}")
        assertEquals(0, out.shots.size)
    }

    @Test
    fun snowHasNoRainAndDullsTheWind() {
        val w = world()
        w.weather.wind = 40
        val sound = WorldSound(w)
        val mild = frame(sound, at())
        w.weather.fronts += front(Front.LOW, warmth = -400)
        val sky = w.skyAt(32, 32)
        assertTrue(sky.precipitation > 0 && sky.temperature <= 0, "sky ${sky.temperature} ${sky.precipitation}")
        val snow = frame(sound, at())
        assertEquals(0f, snow.loudness(WorldSound.KEY_RAIN))
        assertTrue(snow.loudness(WorldSound.KEY_WIND) < mild.loudness(WorldSound.KEY_WIND), "snow ${snow.loudness(WorldSound.KEY_WIND)} dry ${mild.loudness(WorldSound.KEY_WIND)}")
        assertEquals(0f, mild.param(WorldSound.KEY_WIND, SharedParams.LOWPASS))
        val cut = snow.param(WorldSound.KEY_WIND, SharedParams.LOWPASS)
        assertTrue(cut in 2000f..5000f, "cutoff $cut")
    }

    @Test
    fun harderWindIsLouder() {
        val w = world()
        val sound = WorldSound(w)
        val calm = frame(sound, at())
        w.weather.wind = 40
        val blow = frame(sound, at())
        assertTrue(calm.loudness(WorldSound.KEY_WIND) > 0f)
        assertTrue(calm.loudness(WorldSound.KEY_WIND) < 0.12f, "calm ${calm.loudness(WorldSound.KEY_WIND)}")
        assertTrue(blow.loudness(WorldSound.KEY_WIND) > calm.loudness(WorldSound.KEY_WIND))
    }

    @Test
    fun birdsByDayPanWithTheView() {
        val w = world()
        w.trees[w.index(34, 32)] = Trees.pack(Trees.SPRUCE, 2).toByte()
        w.trees[w.index(35, 32)] = Trees.pack(Trees.SPRUCE, 2).toByte()
        val sound = WorldSound(w)
        sound.survey()
        val noon = frame(sound, at(turn = 0), hour = 12f)
        assertTrue(noon.loudness(WorldSound.KEY_BIRDS) > 0f, "birds ${noon.loudness(WorldSound.KEY_BIRDS)}")
        assertTrue(noon.pan(WorldSound.KEY_BIRDS) > 0f, "pan ${noon.pan(WorldSound.KEY_BIRDS)}")
        val turned = frame(sound, at(turn = 2), hour = 12f)
        assertTrue(turned.pan(WorldSound.KEY_BIRDS) < 0f, "pan ${turned.pan(WorldSound.KEY_BIRDS)}")
        assertEquals(0f, frame(sound, at(), hour = 0f).loudness(WorldSound.KEY_BIRDS))
    }

    @Test
    fun shoreIsHeardNearbyOnly() {
        val w = world()
        val x = 8
        val y = 8
        w.corners[w.corner(x, y)] = 0
        w.corners[w.corner(x + 1, y)] = 0
        w.corners[w.corner(x + 1, y + 1)] = 0
        w.corners[w.corner(x, y + 1)] = 0
        assertTrue(w.isWater(x, y))
        assertTrue(!w.isWater(x + 1, y))
        val sound = WorldSound(w)
        sound.survey()
        val near = frame(sound, at(x + 1.5f, y + 0.5f, half = 6f))
        val far = frame(sound, at(60.5f, 60.5f, half = 6f))
        assertTrue(near.loudness(WorldSound.KEY_SHORE) > 0f, "near ${near.loudness(WorldSound.KEY_SHORE)}")
        assertEquals(0f, far.loudness(WorldSound.KEY_SHORE))
    }

    @Test
    fun thunderUnderAStorm() {
        val w = world()
        w.weather.fronts += front(Front.STORM, warmth = 100)
        assertTrue(w.weather.fronts[0].reach(32, 32) > 0)
        val sound = WorldSound(w)
        val out = WorldSound.Frame()
        var claps = 0
        repeat(16) {
            sound.frame(at(), 12f, 1f, out)
            assertTrue((0 until out.count).none { out.recipes[it] == Recipes.THUNDER })
            for (shot in out.shots) {
                assertEquals(Recipes.THUNDER, shot.recipe)
                claps++
            }
        }
        assertTrue(claps > 0, "no thunder")
        w.weather.fronts.clear()
        sound.frame(at(), 12f, 1f, out)
        assertEquals(0, out.shots.size)
    }

    @Test
    fun trainPansWithTheView() {
        val w = world()
        val sound = WorldSound(w)
        val train = mover(WorldSound.TRAIN, 3, x = 40f, y = 32.5f, speed = 0.6f, traction = Traction.STEAM)
        val east = frame(sound, at(turn = 0), movers = listOf(train))
        val key = WorldSound.voiceKey(WorldSound.TRAIN, 3)
        assertEquals(Recipes.TRAIN, east.recipeOf(key))
        assertTrue(east.pan(key) > 0f, "pan ${east.pan(key)}")
        assertTrue(east.param(key, SharedParams.PITCH) > 0f, "pitch ${east.param(key, SharedParams.PITCH)}")
        val turned = frame(sound, at(turn = 2), movers = listOf(train))
        assertTrue(turned.pan(key) < 0f, "pan ${turned.pan(key)}")
    }

    @Test
    fun trainUnderTheCameraStaysAudibleZoomedOut() {
        val sound = WorldSound(world())
        val train = mover(WorldSound.TRAIN, 7, x = 32.5f, y = 32.5f)
        val out = frame(sound, at(half = 800f), movers = listOf(train))
        assertTrue(out.loudness(WorldSound.voiceKey(WorldSound.TRAIN, 7)) > 0f)
    }

    @Test
    fun fourNearestTrains() {
        val sound = WorldSound(world())
        val trains = (0 until 6).map { mover(WorldSound.TRAIN, it, x = 34f + it * 2f) }
        val out = frame(sound, at(), movers = trains)
        assertEquals(4, out.voices(Recipes.TRAIN))
        for (id in 0 until 4) assertTrue(out.loudness(WorldSound.voiceKey(WorldSound.TRAIN, id)) > 0f, "train $id")
        assertEquals(0f, out.loudness(WorldSound.voiceKey(WorldSound.TRAIN, 4)))
        assertEquals(0f, out.loudness(WorldSound.voiceKey(WorldSound.TRAIN, 5)))
    }

    @Test
    fun depotAndWreckStayQuiet() {
        val sound = WorldSound(world())
        val out = frame(sound, at(), movers = listOf(
            mover(WorldSound.TRAIN, 1, inDepot = true),
            mover(WorldSound.TRAIN, 2, wrecked = true),
        ))
        assertEquals(0, out.voices(Recipes.TRAIN))
    }

    @Test
    fun trafficMotorsFollowTheEngines() {
        val sound = WorldSound(world())
        val horses = frame(sound, at(), movers = listOf(
            mover(WorldSound.ROAD, 1, traction = Traction.HORSE),
            mover(WorldSound.ROAD, 2, x = 36f, traction = Traction.HORSE),
        ))
        assertEquals(0f, horses.param(WorldSound.KEY_TRAFFIC, 1), 0.05f)
        val diesel = frame(sound, at(), movers = listOf(
            mover(WorldSound.ROAD, 1, traction = Traction.DIESEL),
            mover(WorldSound.ROAD, 2, x = 36f, traction = Traction.DIESEL),
        ))
        assertEquals(1f, diesel.param(WorldSound.KEY_TRAFFIC, 1), 0.05f)
        assertEquals(0f, frame(sound, at()).loudness(WorldSound.KEY_TRAFFIC))
    }

    @Test
    fun tramIsNotTraffic() {
        val sound = WorldSound(world())
        val out = frame(sound, at(), movers = listOf(mover(WorldSound.TRAM, 1, traction = Traction.ELECTRIC)))
        assertEquals(Recipes.TRAM, out.recipeOf(WorldSound.KEY_TRAM))
        assertTrue(out.loudness(WorldSound.KEY_TRAM) > 0f)
        assertEquals(0f, out.loudness(WorldSound.KEY_TRAFFIC))
    }

    @Test
    fun crossingBellWhileTheLightsAreOn() {
        val w = world()
        val sound = WorldSound(w)
        val on = frame(sound, at(), crossings = intArrayOf(w.index(33, 32)))
        assertEquals(Recipes.SIREN, on.recipeOf(WorldSound.KEY_SIREN))
        assertTrue(on.loudness(WorldSound.KEY_SIREN) > 0f)
        assertEquals(0f, on.param(WorldSound.KEY_SIREN, 1))
        assertEquals(0f, frame(sound, at(), crossings = IntArray(0)).loudness(WorldSound.KEY_SIREN))
    }

    @Test
    fun whistleOnceWhenAWaitingTrainSetsOff() {
        val sound = WorldSound(world())
        val held = mover(WorldSound.TRAIN, 1, x = 34.5f, y = 32.5f, speed = 0f, traction = Traction.STEAM, waiting = true)
        assertEquals(0, frame(sound, at(), movers = listOf(held)).shots.size)
        val going = mover(WorldSound.TRAIN, 1, x = 34.5f, y = 32.5f, speed = 0.4f, traction = Traction.STEAM)
        val left = frame(sound, at(), movers = listOf(going))
        assertEquals(1, left.shots.size)
        assertEquals(Recipes.WHISTLE, left.shots[0].recipe)
        assertEquals(0f, left.shots[0].params[0])
        assertEquals(0.6f, left.shots[0].params[1])
        assertTrue(left.shots[0].params[SharedParams.PAN] > 0f)
        assertEquals(0, frame(sound, at(), movers = listOf(going)).shots.size)
    }

    @Test
    fun whistleOnceAfterTheStandstill() {
        val sound = WorldSound(world())
        val waiting = mover(WorldSound.TRAIN, 1, x = 34.5f, y = 32.5f, speed = 0f, traction = Traction.STEAM, waiting = true)
        assertEquals(0, frame(sound, at(), movers = listOf(waiting)).shots.size)
        val standing = mover(WorldSound.TRAIN, 1, x = 34.5f, y = 32.5f, speed = 0f, traction = Traction.STEAM)
        assertEquals(0, frame(sound, at(), movers = listOf(standing)).shots.size)
        val rolling = mover(WorldSound.TRAIN, 1, x = 34.5f, y = 32.5f, speed = 0.01f, traction = Traction.STEAM)
        val left = frame(sound, at(), movers = listOf(rolling))
        assertEquals(1, left.shots.size)
        assertEquals(Recipes.WHISTLE, left.shots[0].recipe)
        assertEquals(0, frame(sound, at(), movers = listOf(rolling)).shots.size)
    }

    @Test
    fun whistleOnceAtACrossing() {
        val w = world()
        val sound = WorldSound(w)
        val train = mover(WorldSound.TRAIN, 4, x = 34.5f, y = 32.5f, speed = 0.5f, traction = Traction.DIESEL)
        assertEquals(0, frame(sound, at(), movers = listOf(train)).shots.size)
        val beside = frame(sound, at(), movers = listOf(train), crossings = intArrayOf(w.index(36, 32)))
        assertEquals(0, beside.shots.size)
        val on = frame(sound, at(), movers = listOf(train), crossings = intArrayOf(w.index(34, 32)))
        assertEquals(1, on.shots.size)
        assertEquals(Recipes.WHISTLE, on.shots[0].recipe)
        assertEquals(1f, on.shots[0].params[0])
        assertEquals(0, frame(sound, at(), movers = listOf(train), crossings = intArrayOf(w.index(34, 32))).shots.size)
    }

    @Test
    fun whistleFollowsTractionAndAShipCastingOff() {
        val electric = leave(WorldSound.TRAIN, Traction.ELECTRIC)
        assertEquals(Recipes.HORN, electric.recipe)
        assertEquals(2f, electric.params[0])
        val ship = leave(WorldSound.SHIP, Traction.DIESEL)
        assertEquals(Recipes.WHISTLE, ship.recipe)
        assertEquals(2f, ship.params[0])
        assertEquals(0.6f, ship.params[1])
    }

    private fun leave(kind: Int, traction: Int): WorldSound.Shot {
        val sound = WorldSound(world())
        frame(sound, at(), movers = listOf(mover(kind, 2, speed = 0f, traction = traction, waiting = true)))
        val out = frame(sound, at(), movers = listOf(mover(kind, 2, speed = 0.4f, traction = traction)))
        assertEquals(1, out.shots.size)
        return out.shots[0]
    }

    @Test
    fun newsShotPicksCrunchChimeOrNothing() {
        val crash = newsShot(News.CRASH, true)
        assertEquals(Recipes.CRUNCH, crash?.recipe)
        assertEquals(VoiceFlags.TOWN, crash?.flags)
        assertEquals(null, newsShot(News.CRASH, false))
        assertTrue(crash?.recipe != Recipes.CHIME)
        val goal = newsShot(News.GOAL_MET, false)
        assertEquals(Recipes.CHIME, goal?.recipe)
        assertEquals(0, goal?.flags)
        assertEquals(1f, goal?.params?.get(0))
        assertEquals(Recipes.CHIME, newsShot(News.SCENARIO_WON, false)?.recipe)
        assertEquals(0f, newsShot(News.SUBSIDY_AWARDED, false)?.params?.get(0))
        assertEquals(0f, newsShot(News.NEW_VEHICLE, false)?.params?.get(0))
        assertEquals(2f, newsShot(News.COMPANY_BANKRUPT, false)?.params?.get(0))
        assertEquals(2f, newsShot(News.GOAL_FAILED, false)?.params?.get(0))
        assertEquals(2f, newsShot(News.SCENARIO_LOST, false)?.params?.get(0))
        assertEquals(null, newsShot(News.SLID, false))
        assertEquals(Recipes.IMPACT, newsShot(News.SLID, true)?.recipe)
        assertEquals(VoiceFlags.TOWN, newsShot(News.SLID, true)?.flags)
        assertEquals(Materials.SNOW.toFloat(), newsShot(News.SLID, true)?.params?.get(1))
        assertEquals(null, newsShot(News.WASHOUT, false))
        assertEquals(Recipes.SPLASH, newsShot(News.WASHOUT, true)?.recipe)
        assertEquals(VoiceFlags.TOWN, newsShot(News.WASHOUT, true)?.flags)
        assertEquals(null, newsShot(News.FIRE, false))
        assertEquals(Recipes.IMPACT, newsShot(News.FIRE, true)?.recipe)
        assertEquals(VoiceFlags.TOWN, newsShot(News.FIRE, true)?.flags)
        assertEquals(Materials.WOOD.toFloat(), newsShot(News.FIRE, true)?.params?.get(1))
        assertEquals(null, newsShot(News.AVALANCHE, false))
        assertEquals(Recipes.CRUNCH, newsShot(News.AVALANCHE, true)?.recipe)
        assertEquals(VoiceFlags.TOWN, newsShot(News.AVALANCHE, true)?.flags)
        assertEquals(null, newsShot(News.INDUSTRY_OPENS, true))
    }

    @Test
    fun crowdAtANearbyStation() {
        val sound = WorldSound(world())
        val near = frame(sound, at(), stations = listOf(WorldSound.StationSpot(33f, 32.5f, false)))
        assertTrue(near.loudness(WorldSound.KEY_CROWD) > 0f, "crowd ${near.loudness(WorldSound.KEY_CROWD)}")
        val waypoint = frame(sound, at(), stations = listOf(WorldSound.StationSpot(33f, 32.5f, true)))
        assertEquals(0f, waypoint.loudness(WorldSound.KEY_CROWD))
        val far = frame(sound, at(), stations = listOf(WorldSound.StationSpot(2f, 2f, false)))
        assertEquals(0f, far.loudness(WorldSound.KEY_CROWD))
    }

    @Test
    fun newTractionsHaveTheirOwnTrainSound() {
        val sound = WorldSound(world())
        val key = WorldSound.voiceKey(WorldSound.TRAIN, 1)
        for ((traction, kind) in listOf(Traction.BATTERY to 3f, Traction.HYDROGEN to 3f, Traction.MAGLEV to 4f, Traction.HYPERLOOP to 5f, Traction.DUAL to 1f)) {
            val out = frame(sound, at(), movers = listOf(mover(WorldSound.TRAIN, 1, traction = traction)))
            assertEquals(Recipes.TRAIN, out.recipeOf(key))
            assertEquals(kind, out.param(key, 1), "traction $traction")
        }
    }

    @Test
    fun batteryHornsAndFloatingTrainsLeaveQuietly() {
        val battery = leave(WorldSound.TRAIN, Traction.HYDROGEN)
        assertEquals(Recipes.HORN, battery.recipe)
        assertEquals(2f, battery.params[0])
        for (traction in listOf(Traction.MAGLEV, Traction.HYPERLOOP)) {
            val sound = WorldSound(world())
            frame(sound, at(), movers = listOf(mover(WorldSound.TRAIN, 2, speed = 0f, traction = traction, waiting = true)))
            assertEquals(0, frame(sound, at(), movers = listOf(mover(WorldSound.TRAIN, 2, speed = 0.4f, traction = traction))).shots.size)
        }
        val sound = WorldSound(world())
        val sail = WorldSound.SHIP_SAIL
        frame(sound, at(), movers = listOf(mover(WorldSound.SHIP, 2, speed = 0f, traction = Traction.STEAM, waiting = true, sound = sail)))
        assertEquals(0, frame(sound, at(), movers = listOf(mover(WorldSound.SHIP, 2, speed = 0.4f, traction = Traction.STEAM, sound = sail))).shots.size)
    }

    @Test
    fun stationChimesAsATrainLeavesFrom1950() {
        val station = listOf(WorldSound.StationSpot(34.5f, 32.5f, false))
        fun leaving(year: Int): List<WorldSound.Shot> {
            val sound = WorldSound(world(year))
            frame(sound, at(), movers = listOf(mover(WorldSound.TRAIN, 2, speed = 0f, traction = Traction.ELECTRIC, waiting = true)), stations = station)
            return frame(sound, at(), movers = listOf(mover(WorldSound.TRAIN, 2, speed = 0.3f, traction = Traction.ELECTRIC)), stations = station).shots
        }
        val modern = leaving(1980)
        val chime = modern.single { it.recipe == Recipes.CHIME }
        assertEquals(4f, chime.params[0])
        assertEquals(VoiceFlags.TOWN, chime.flags)
        assertTrue(leaving(1920).none { it.recipe == Recipes.CHIME })
    }

    @Test
    fun batteryAndHydrogenCarsCountAsElectric() {
        val sound = WorldSound(world())
        val cars = listOf(mover(WorldSound.ROAD, 1, traction = Traction.BATTERY), mover(WorldSound.ROAD, 2, traction = Traction.HYDROGEN))
        val out = frame(sound, at(), movers = cars)
        assertEquals(1f, out.param(WorldSound.KEY_TRAFFIC, 1))
        assertEquals(1f, out.param(WorldSound.KEY_TRAFFIC, 2))
    }

    @Test
    fun aHorseTramGoesWithTheHorses() {
        val sound = WorldSound(world())
        val out = frame(sound, at(), movers = listOf(mover(WorldSound.TRAM, 1, traction = Traction.HORSE)))
        assertEquals(0f, out.loudness(WorldSound.KEY_TRAM))
        assertTrue(out.loudness(WorldSound.KEY_TRAFFIC) > 0f)
        assertEquals(0f, out.param(WorldSound.KEY_TRAFFIC, 1))
    }

    @Test
    fun hornsInTrafficAndGongsOnTramsNowAndThen() {
        val sound = WorldSound(world(1960))
        val cars = (0 until 6).map { mover(WorldSound.ROAD, it, x = 33f + it) }
        val trams = listOf(mover(WorldSound.TRAM, 9, traction = Traction.ELECTRIC))
        var horns = 0
        var gongs = 0
        repeat(600) {
            val out = frame(sound, at(), dt = 0.2f, movers = cars + trams)
            horns += out.shots.count { it.recipe == Recipes.HORN }
            gongs += out.shots.count { it.recipe == Recipes.BELL }
        }
        // Two minutes: a handful of each, nothing like a din.
        assertTrue(horns in 2..20, "horns $horns")
        assertTrue(gongs in 1..12, "gongs $gongs")
    }

    @Test
    fun shipsAndAircraftByTheirSound() {
        assertEquals(WorldSound.SHIP_SAIL, WorldSound.shipSound(Traction.STEAM, false, 50, 1836))
        assertEquals(WorldSound.SHIP_STEAM, WorldSound.shipSound(Traction.STEAM, false, 200, 1836))
        assertEquals(WorldSound.SHIP_MOTOR, WorldSound.shipSound(Traction.STEAM, false, 9000, 1959))
        assertEquals(WorldSound.SHIP_ELECTRIC, WorldSound.shipSound(Traction.BATTERY, false, 4000, 2020))
        assertEquals(WorldSound.SHIP_HOVER, WorldSound.shipSound(Traction.STEAM, true, 1000, 1968))
        assertEquals(WorldSound.AIR_HELI, WorldSound.airSound(Runways.HELI))
        assertEquals(WorldSound.AIR_DRONE, WorldSound.airSound(Runways.DRONE))
        assertEquals(WorldSound.AIR_PLANE, WorldSound.airSound(Runways.CITY))
        val sound = WorldSound(world())
        val out = frame(sound, at(), movers = listOf(
            mover(WorldSound.SHIP, 1, sound = WorldSound.SHIP_STEAM),
            mover(WorldSound.AIRCRAFT, 2, sound = WorldSound.AIR_HELI),
            mover(WorldSound.AIRCRAFT, 3, x = 31f, sound = WorldSound.AIR_DRONE),
        ))
        val ship = WorldSound.voiceKey(WorldSound.SHIP, 1)
        assertEquals(Recipes.OUTBOARD, out.recipeOf(ship))
        assertEquals(WorldSound.SHIP_STEAM.toFloat(), out.param(ship, 2))
        assertEquals(Recipes.HELI, out.recipeOf(WorldSound.voiceKey(WorldSound.AIRCRAFT, 2)))
        assertEquals(Recipes.DRONE, out.recipeOf(WorldSound.voiceKey(WorldSound.AIRCRAFT, 3)))
    }

    @Test
    fun dronesNearbyDontCrowdOutAJet() {
        val sound = WorldSound(world())
        val drones = (0 until 3).map { mover(WorldSound.AIRCRAFT, it, x = 33f + it * 0.5f, sound = WorldSound.AIR_DRONE) }
        val jet = mover(WorldSound.AIRCRAFT, 9, x = 37f, speed = 0.9f, jet = true)
        val out = frame(sound, at(), movers = drones + jet)
        assertEquals(Recipes.JET, out.recipeOf(WorldSound.voiceKey(WorldSound.AIRCRAFT, 9)))
        assertEquals(2, out.voices(Recipes.DRONE))
    }

    @Test
    fun townHumDarkensWithZoomAndFollowsTheEra() {
        fun hum(year: Int, half: Float): Pair<Float, WorldSound.Frame> {
            val w = world(year)
            for (y in 28 until 38) for (x in 28 until 38) w.house[w.index(x, y)] = 1
            val sound = WorldSound(w)
            sound.survey()
            val out = frame(sound, at(half = half))
            return out.loudness(WorldSound.KEY_CITY) to out
        }
        val (near, closeUp) = hum(1960, 6f)
        val (far, highUp) = hum(1960, 60f)
        assertTrue(near > 0f && far > 0f, "near $near far $far")
        assertEquals(0f, closeUp.param(WorldSound.KEY_CITY, 2))
        assertEquals(1f, highUp.param(WorldSound.KEY_CITY, 2))
        assertEquals(1f, closeUp.param(WorldSound.KEY_CITY, 1))
        assertEquals(0f, hum(1900, 6f).second.param(WorldSound.KEY_CITY, 1))
        assertEquals(0f, frame(WorldSound(world()).also { it.survey() }, at()).loudness(WorldSound.KEY_CITY))
    }

    @Test
    fun industryAndFireAreHeard() {
        val w = world(1960)
        for (y in 32 until 35) for (x in 33 until 36) w.industryOf[w.index(x, y)] = 1
        w.fire[w.index(30, 30)] = FIRE_DAYS.toByte()
        val sound = WorldSound(w)
        sound.survey()
        val out = frame(sound, at())
        assertTrue(out.loudness(WorldSound.KEY_INDUSTRY) > 0f, "industry ${out.loudness(WorldSound.KEY_INDUSTRY)}")
        assertTrue(out.param(WorldSound.KEY_INDUSTRY, 1) in 0.7f..0.8f, "era ${out.param(WorldSound.KEY_INDUSTRY, 1)}")
        assertTrue(out.loudness(WorldSound.KEY_FIRE) > 0f, "fire ${out.loudness(WorldSound.KEY_FIRE)}")
    }

    @Test
    fun cricketsOnSummerNightsOnly() {
        val w = world()
        w.trees[w.index(34, 32)] = Trees.pack(Trees.SPRUCE, 2).toByte()
        w.tick = Calendar.TICKS_PER_DAY * 200L
        assertTrue(w.lookToday() == Climate.SUMMER || w.lookToday() == Climate.LATE_SUMMER, "look ${w.lookToday()}")
        val sound = WorldSound(w)
        sound.survey()
        assertTrue(frame(sound, at(), hour = 23f).loudness(WorldSound.KEY_CRICKETS) > 0f)
        assertEquals(0f, frame(sound, at(), hour = 12f).loudness(WorldSound.KEY_CRICKETS))
        w.tick = Calendar.TICKS_PER_DAY * 20L
        assertEquals(0f, frame(sound, at(), hour = 23f).loudness(WorldSound.KEY_CRICKETS))
    }

    @Test
    fun musicGetsTheYearAndSeason() {
        val w = world(1965)
        val sound = WorldSound(w)
        sound.survey()
        w.tick = Calendar.TICKS_PER_DAY * 200L
        val summer = frame(sound, at())
        assertEquals(1965, summer.year)
        assertEquals(1, summer.season, "look ${w.lookToday()}")
        w.tick = Calendar.TICKS_PER_DAY * 20L
        assertEquals(3, frame(sound, at()).season, "look ${w.lookToday()}")
        assertEquals(0, WorldSound.musicSeason(Climate.EARLY_SPRING))
        assertEquals(2, WorldSound.musicSeason(Climate.LATE_AUTUMN))
    }

    @Test
    fun lookTodayAndMusicSeasonFollowTheDate() {
        val w = world(1965)
        val sound = WorldSound(w)
        sound.survey()
        for (d in listOf(20, 200, 21, 20, 300, 300)) {
            w.tick = w.dayTicks.toLong() * d
            val year = Calendar.year(w.startYear, w.day)
            val look = Climate.look(w.settings.region, year, Calendar.dayOfYear(w.startYear, w.day))
            assertEquals(WorldSound.musicSeason(look), frame(sound, at()).season)
            assertEquals(look, w.lookToday(), "day $d")
        }
    }

    private fun WorldSound.Frame.param(key: Int, p: Int): Float {
        for (k in 0 until count) if (keys[k] == key) return params[k * SharedParams.COUNT + p]
        error("no sound $key")
    }

    private fun WorldSound.Frame.recipeOf(key: Int): Int {
        for (k in 0 until count) if (keys[k] == key) return recipes[k]
        return -1
    }

    private fun WorldSound.Frame.voices(recipe: Int): Int {
        var n = 0
        for (k in 0 until count) if (recipes[k] == recipe) n++
        return n
    }
}
