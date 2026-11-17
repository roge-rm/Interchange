package com.rm.interchange.sim

/*
 * Future industries, with [NewGame.future] on: lithium mines, battery plants and hydrogen plants open, more by the
 * decade from [FUTURE_RISE_FROM], and kinds that fade (coal mines and steel mills in the base pack) open less and
 * close more from their [IndustryKind.fades] year. A fading industry's closing is told [CLOSING_NOTICE] months ahead.
 * With future off none of this runs and nothing new opens.
 */

/** Months between the warning and the closing. */
const val CLOSING_NOTICE = 6

/** From this year future kinds open more, by the decade. */
const val FUTURE_RISE_FROM = 2030

/** The chance a month a fading industry is told it'll close, in thousandths a decade past its fading year, and the most. */
const val FADE_CLOSE = 3
const val FADE_CLOSE_MOST = 15

/** Decades of fading [kind] has had by [year] with future on, 1 in its fading year's decade; 0 if it doesn't fade yet. */
fun World.fadeDecades(kind: IndustryKind, year: Int) =
    if (!settings.future || kind.fades == 0 || year < kind.fades) 0 else (year - kind.fades) / 10 + 1

/**
 * How often [kind] opens in [year], by weight: its region's, with future off nothing for a future kind. With future on,
 * from [FUTURE_RISE_FROM] every weight is [FUTURE_SHARES] times that, so a faded one halves a few times before it's 0,
 * and a future kind gets one more share a decade, at most 4. Halved for each decade a kind has faded.
 */
fun World.openWeight(kind: IndustryKind, year: Int): Int {
    val w = kind.weight(settings.region)
    if (!settings.future) return if (kind.future) 0 else w
    val rise = if (year < FUTURE_RISE_FROM) 0 else minOf(3, (year - FUTURE_RISE_FROM) / 10 + 1)
    val shares = if (rise == 0) w else w * FUTURE_SHARES
    if (kind.future) return shares * (1 + rise)
    return shares shr minOf(30, fadeDecades(kind, year))
}

/** Weights with future on from [FUTURE_RISE_FROM] are this many times the pack's. */
const val FUTURE_SHARES = 4

/** A month of fading for [ind]: counts down a told closing, or now and then tells one. Not one an open delivery goal is for. */
internal fun World.fade(ind: Industry, kind: IndustryKind, year: Int) {
    if (ind.closing > 0) {
        if (--ind.closing == 0) closeIndustry(ind)
        return
    }
    val decades = fadeDecades(kind, year)
    if (decades == 0) return
    if (rng.below(1000) < minOf(FADE_CLOSE_MOST, FADE_CLOSE * decades) && !goalFor(ind)) {
        ind.closing = CLOSING_NOTICE
        tell(News.INDUSTRY_CLOSING, ind.id)
    }
}

/** Closings told ahead in the checksum, only with future transport on and one told, so older games keep theirs. */
internal fun World.futureIndustryChecksum(mix: (Long) -> Unit) {
    if (!settings.future) return
    var any = false
    for (ind in industries) if (ind.closing > 0) {
        if (!any) mix(950L)
        any = true
        mix(ind.id.toLong() * 47 + ind.closing)
    }
}
