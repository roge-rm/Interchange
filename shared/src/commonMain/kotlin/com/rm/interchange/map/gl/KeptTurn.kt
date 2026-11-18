package com.rm.interchange.map.gl

import kotlinx.coroutines.Job

/**
 * One view direction's regions on the GPU map, seen from [turn] over a view [viewWidth] by [viewHeight], and which
 * of them are kept: those round the screen ([keep]), recorded only there. Main thread only.
 *
 * [pending] holds the version each region is being recorded at, or -1. [due] marks regions found changed while
 * the turn was hidden and [jobs] is what's recording for it. [published] is what's drawn, recorded with the look
 * [shownLook] where it can be ([publishRegions]); the look moves on once the screen's regions all have the new one.
 */
internal class KeptTurn(val turn: Int, val viewWidth: Int, val viewHeight: Int) {
    val regions = arrayOfNulls<RegionSprites>((viewHeight + REGION_ROWS - 1) / REGION_ROWS)
    val pending = LongArray(regions.size) { -1L }
    val due = BooleanArray(regions.size)
    val jobs = ArrayList<Job>()
    /** What's recording each region, so one started again lets the last go. */
    val regionJobs = arrayOfNulls<Job>(regions.size)
    val published = arrayOfNulls<RegionSprites>(regions.size)
    var shownLook = -1
    /**
     * The view tiles kept recorded, columns by view row as [visibleColumns] gives them, round the screen
     * ([keepFrame]); regions outside are let go of, those inside recorded only there. Null till first set.
     */
    var keep: IntArray? = null
        private set
    private var scratch = IntArray(0)

    /**
     * Keeps the regions round [frame] ([keepFrame]), or the whole map if it's small ([keepsWholeMap]), once what's
     * kept no longer holds the screen with some to spare: those outside are let go of and their jobs cancelled, the
     * rest are recorded there as they come up short ([short]), the old ones showing till then. Returns whether it
     * kept them again.
     */
    fun keepRound(frame: GlFrame): Boolean {
        val k = keep
        if (scratch.size < 2 * frame.viewHeight) scratch = IntArray(2 * frame.viewHeight)
        if (k != null && keepHolds(k, frame, scratch)) return false
        val next = if (keepsWholeMap(frame)) wholeColumns(frame) else visibleColumns(keepFrame(frame))
        keep = next
        for (i in regions.indices) {
            if (regionKept(next, i, viewHeight)) continue
            if (pending[i] >= 0L) regionJobs[i]?.cancel()
            pending[i] = -1L
            due[i] = false
            regions[i] = null
            published[i] = null
        }
        jobs.removeAll { it.isCompleted || it.isCancelled }
        return true
    }

    /** Whether region [i] has tiles kept. */
    fun kept(i: Int): Boolean = keep?.let { regionKept(it, i, viewHeight) } ?: true

    /** What's kept of region [i], for [recordRegion]: null for the whole of it. */
    fun slice(i: Int): IntArray? = keep?.let { keepSlice(it, i, viewWidth, viewHeight) }

    /**
     * Whether region [i] is kept but isn't recorded there, nor recording. One still recording an old slice when the
     * kept area moved finishes, then comes up short and has the rest added ([base]).
     */
    fun short(i: Int): Boolean {
        if (pending[i] >= 0L || !kept(i)) return false
        val r = regions[i] ?: return true
        val k = keep ?: return r.cols != null
        return !holdsKept(r, k)
    }

    /** Whether every region holds what's kept of it and none is recording. */
    fun whole() = pending.all { it < 0L } && regions.indices.none { short(it) }

    /** Region [i] as it is, to add the tiles it's short of to ([extendRegion]), if it's at [version], [look] and [year]. */
    fun base(i: Int, version: Long, look: Int, year: Int): RegionSprites? =
        regions[i]?.takeIf { it.version == version && it.look == look && it.year == year }

    /** Region [i] starts recording at [version]; one recording already is let go of. */
    fun begin(i: Int, version: Long) {
        if (pending[i] >= 0L) regionJobs[i]?.cancel()
        pending[i] = version
        due[i] = false
        jobs.removeAll { it.isCompleted }
    }

    /**
     * Puts in [r], recorded for region [i] at [version] from [base] if given, unless it's been let go of or started
     * again since, or [base] isn't what's there now. Returns whether it went in.
     *
     * One recorded for a kept area that's moved since may lack tiles of it the region there holds, which the screen
     * may be on ([holdsKeptOf]); it's left out and the region there stays, short, to be recorded again for what's
     * kept now.
     */
    fun put(i: Int, version: Long, r: RegionSprites, base: RegionSprites?): Boolean {
        if (pending[i] != version) return false
        pending[i] = -1L
        if (base != null && regions[i] !== base) return false
        val old = regions[i]
        val k = keep
        if (old != null && k != null && !holdsKeptOf(r, old, k)) return false
        regions[i] = r
        return true
    }

    /**
     * The version of each region drawn, for [EditHold.current]: one kept but not drawn yet is behind any, and one let
     * go of, off round the screen, has nothing to show an edit in, so it's taken as current.
     */
    fun drawnVersions() = LongArray(published.size) { published[it]?.version ?: if (kept(it)) Long.MIN_VALUE else Long.MAX_VALUE }
}
