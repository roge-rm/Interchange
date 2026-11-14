package com.rm.interchange.map.gl

/**
 * Follows a player's edit from the finger lifting till the GPU has drawn it, so the build preview can stay on screen
 * till then. Only the regions round the tiles the edit touches are followed. Times are in milliseconds from any fixed
 * point. Main thread only.
 */
internal class EditHold {
    /** When the edit was made, or -1 for none held. */
    var since = -1L
        private set
    /** The turn it was made in, the regions it touches and every region's version then. */
    var turn = -1
        private set
    var regions = IntRange.EMPTY
        private set
    private var base = LongArray(0)
    /** The [GlShown.seq] that holds the edit's regions once they're recorded, 0 till then. */
    var waitSeq = 0L
        private set

    val holding get() = since >= 0L

    /** Starts holding an edit made at [now] in [turn] touching [regions], whose regions were at [versions] before it. */
    fun start(now: Long, turn: Int, regions: IntRange, versions: LongArray) {
        since = now
        this.turn = turn
        this.regions = regions
        base = versions
        waitSeq = 0L
    }

    fun stop() {
        since = -1L
        waitSeq = 0L
    }

    /** Whether the hold has run past [MAX_MILLIS] at [now]. */
    fun expired(now: Long) = holding && now - since > MAX_MILLIS

    /** Region [i]'s version when the edit was made, or -1. */
    fun before(i: Int): Long = base.getOrElse(i) { -1L }

    /**
     * Whether the world under any of the edit's [regions] has moved on from where it was, by [versions] now; only the
     * changes count ([regionChanges]), not a new look or eras.
     */
    fun changed(versions: LongArray): Boolean = regions.any { moved(versions, it) }

    private fun moved(versions: LongArray, i: Int) = regionChanges(versions.getOrElse(i) { -1L }) != regionChanges(before(i))

    /**
     * Whether every one of the edit's [regions] that moved on, by [versions] now, is drawn from a recording made since,
     * [drawn] being the versions of the regions drawn, by their changes alone, which only go up.
     */
    fun current(versions: LongArray, drawn: LongArray): Boolean = regions.all {
        !moved(versions, it) || (drawn.getOrElse(it) { -1L } >= 0L && regionChanges(drawn[it]) > regionChanges(before(it)))
    }

    /**
     * Moves on to [now]: [changed] says if the world under the edit's regions has moved on, [current] if what moved
     * is drawn from recordings since, [shownSeq] is the newest [GlShown] and [drawnSeq] the one the GL thread last drew.
     * Lets go once the GPU has drawn the regions with the edit in, once nothing changed in [NO_CHANGE_MILLIS] (the edit
     * was turned down) or after [MAX_MILLIS] in any case. A change that comes while it's waiting puts it back to wait
     * for that too. Returns whether it still holds.
     */
    fun step(now: Long, changed: Boolean, current: Boolean, shownSeq: Long, drawnSeq: Long): Boolean {
        if (since < 0L) return false
        val age = now - since
        if (age > MAX_MILLIS || (!changed && age > NO_CHANGE_MILLIS)) {
            stop()
            return false
        }
        if (!changed || !current) waitSeq = 0L
        else if (waitSeq == 0L) waitSeq = shownSeq
        if (waitSeq > 0L && drawnSeq >= waitSeq) {
            stop()
            return false
        }
        return true
    }

    companion object {
        /** How long an edit that changes nothing is held: long enough for the game to take the command. */
        const val NO_CHANGE_MILLIS = 500L
        const val MAX_MILLIS = 2_000L
    }
}
