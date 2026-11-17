package com.rm.interchange.sim

/*
 * The hyperloop, with [NewGame.future] on, from [HYPERLOOP_FROM]. Tube is the third kind of track, beside rail and
 * maglev guideway, on a tile marked in [World.tube]: the same pieces, signals, junctions, platforms (portals), depots
 * and spans as rail, kept apart from the other two kinds as guideway is. Tube on the ground stands on pylons and
 * crosses nothing on the level; it goes over on a concrete span and under anything in a bore, a tunnel between two
 * flat heads at the same height of any length up to [Hyperloop.BORE_LONGEST].
 *
 * Pods are engines and wagons with traction hyperloop, carrying only passengers and mail, bought only in a depot on
 * tube. In the tube they take no grip or snow from the weather and aren't held to a span's speed; they brake
 * [Hyperloop.BRAKE_TIMES] as hard as trains and take bends at [Hyperloop]'s caps. Nothing wears tube.
 */

/** The year tube can first be built. */
const val HYPERLOOP_FROM = 2060

object Hyperloop {
    /** A piece of tube on pylons, lifting one, a portal platform and a depot on it, in dollars before inflation. */
    const val TRACK = 8_000L
    const val TRACK_REMOVE = 400L
    const val PLATFORM = 10_000L
    const val DEPOT = 24_000L
    /** A concrete span for tube costs this many times rail's for each tile between its heads. */
    const val SPAN_TIMES = 4
    /** Each tile of a bore between its heads, and the longest bore. */
    const val BORE = 40_000L
    const val BORE_LONGEST = 60
    /** What a tile of tube counts as in its company's track upkeep, against 1 for a tile of rail. */
    const val UPKEEP_TILES = 6
    /** How fast a pod takes a tight bend and a gentle one, in km/h. */
    const val TIGHT_KMH = 150
    const val GENTLE_KMH = 300
    /** How many times as hard as a train a pod brakes. */
    const val BRAKE_TIMES = 2
}

/** The kinds of track a tile can carry; a tile is all one kind. */
object TrackKind {
    const val RAIL = 0
    const val GUIDEWAY = 1
    const val TUBE = 2

    /** The kind a command's [maglev] and [tube] flags ask for. */
    fun of(maglev: Boolean, tube: Boolean) = if (tube) TUBE else if (maglev) GUIDEWAY else RAIL
}

/** The kind of track on tile [i]: rail where there's none. */
fun World.kindAt(i: Int) = if (tube[i]) TrackKind.TUBE else if (guideway[i]) TrackKind.GUIDEWAY else TrackKind.RAIL

/** Marks tile [i]'s track as of [kind]. */
internal fun World.setKind(i: Int, kind: Int) {
    guideway[i] = kind == TrackKind.GUIDEWAY
    tube[i] = kind == TrackKind.TUBE
}

/** Whether track of [kind] can be built now. */
fun World.kindOpen(kind: Int) = when (kind) {
    TrackKind.GUIDEWAY -> maglevOpen()
    TrackKind.TUBE -> hyperloopOpen()
    else -> true
}

/** Whether tube can be built now: future transport on and [HYPERLOOP_FROM] come. */
fun World.hyperloopOpen() = settings.future && Calendar.year(startYear, day) >= HYPERLOOP_FROM

/** A hyperloop pod, which runs only in tube. */
val Engine.hyperloop get() = traction == Traction.HYPERLOOP

/** The kind of track an engine or wagon runs on. */
val Engine.trackKind get() = when (traction) {
    Traction.MAGLEV -> TrackKind.GUIDEWAY
    Traction.HYPERLOOP -> TrackKind.TUBE
    else -> TrackKind.RAIL
}

/** Whether an engine or wagon belongs to future transport, so is on sale only with [NewGame.future]. */
val Engine.future get() = traction == Traction.MAGLEV || traction == Traction.HYPERLOOP || autonomyFuture || futureCargo

/** The kind of track a train of [cars] runs on, by its first car; [validTrainFormation] keeps them all one kind. */
fun carsKind(roster: Roster, cars: List<TrainCar>) = if (cars.isEmpty()) TrackKind.RAIL else roster[cars.first().model].trackKind

/** The kind of track depot [d] stands on, so the kind of train it buys and takes. */
fun World.depotKind(d: Depot) = kindAt(d.tile)

/** The kind of track span [s] carries, as its heads do. */
fun World.spanKind(s: Span) = kindAt(index(s.x0, s.y0))

/** Whether span [s] is a bore: every tunnel for tube is one. */
fun World.bore(s: Span) = s.tunnel && tube[index(s.x0, s.y0)]

/** The kind of track [t] runs on. */
val Train.kind get() = if (hyperloop) TrackKind.TUBE else if (maglev) TrackKind.GUIDEWAY else TrackKind.RAIL

/** How hard [t] brakes in good grip, in speed units a tick. */
val Train.brakeRate get() = if (hyperloop) BRAKE * Hyperloop.BRAKE_TIMES else BRAKE

/** A piece of track of [kind], its mix, and lifting one, in dollars before inflation. */
internal fun trackPrice(kind: Int) = when (kind) {
    TrackKind.GUIDEWAY -> Maglev.TRACK
    TrackKind.TUBE -> Hyperloop.TRACK
    else -> Prices.TRACK
}
internal fun trackMix(kind: Int) = when (kind) {
    TrackKind.GUIDEWAY -> Mix.GUIDEWAY
    TrackKind.TUBE -> Mix.TUBE
    else -> Mix.TRACK
}
internal fun liftPrice(kind: Int) = when (kind) {
    TrackKind.GUIDEWAY -> Maglev.TRACK_REMOVE
    TrackKind.TUBE -> Hyperloop.TRACK_REMOVE
    else -> Prices.TRACK_REMOVE
}

/** A platform and a depot on track of [kind]. */
internal fun platformPrice(kind: Int) = when (kind) {
    TrackKind.GUIDEWAY -> Maglev.PLATFORM
    TrackKind.TUBE -> Hyperloop.PLATFORM
    else -> Prices.PLATFORM
}
internal fun depotPrice(kind: Int) = when (kind) {
    TrackKind.GUIDEWAY -> Maglev.DEPOT
    TrackKind.TUBE -> Hyperloop.DEPOT
    else -> Prices.DEPOT
}

/** How many times rail's a span's tiles between its heads cost for track of [kind]. */
internal fun spanTimes(kind: Int) = when (kind) {
    TrackKind.GUIDEWAY -> Maglev.SPAN_TIMES
    TrackKind.TUBE -> Hyperloop.SPAN_TIMES
    else -> 1
}

/** What a tile of track of [kind] counts as in its company's upkeep, [wired] with era costs counting twice. */
internal fun upkeepTiles(kind: Int, wired: Boolean) = when (kind) {
    TrackKind.GUIDEWAY -> Maglev.UPKEEP_TILES
    TrackKind.TUBE -> Hyperloop.UPKEEP_TILES
    else -> if (wired) 2 else 1
}

/** Tube tiles in the checksum, only with future transport on and some tube built, so older games keep theirs. */
internal fun World.hyperloopChecksum(mix: (Long) -> Unit) {
    if (!settings.future) return
    var any = false
    for (i in tube.indices) if (tube[i] && track[i].toInt() != 0) {
        if (!any) mix(930L)
        any = true
        mix(i.toLong() * 41)
    }
}
