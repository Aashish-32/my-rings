package com.aashish.myrings.data

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One heartbeat seen during a session: when it arrived (ms since the session's first beat) and the beat-to-beat interval. */
data class CalmBeat(val tMs: Long, val ibiMs: Int)

/** Before-and-after numbers for a slow-breathing session; any value is null when there weren't enough beats to say. */
data class CalmResult(val hrStart: Int?, val hrEnd: Int?, val hrvStart: Int?, val hrvEnd: Int?, val beats: Int) {
    val hrvChangePct: Int? get() = if (hrvStart != null && hrvEnd != null && hrvStart > 0) ((hrvEnd - hrvStart) * 100.0 / hrvStart).roundToInt() else null
}

/**
 * A guided slow-breathing session read through the live heart-rate stream.
 *
 * open_oura has no guided-breathing feature, so the pace and the comparison are ours, not Oura's: breathing around six breaths a minute is a
 * widely used slow pace for this, and the session compares your heart rate and HRV (RMSSD, the same measure the repo's HRV port uses) over the
 * first and last 20 seconds. It describes what happened; it is not a medical measurement.
 */
object CalmSession {
    const val BREATH_IN_S = 5.5          // seconds breathing in
    const val BREATH_OUT_S = 5.5         // seconds breathing out
    const val SESSION_S = 60             // length of the breathing, counted from the first locked beat
    const val WINDOW_S = 20              // first and last window compared
    private const val MIN_BEATS = 8

    /** RMSSD (ms) of beat-to-beat differences, skipping jumps over 25 % (missed or doubled beats), as the live HRV readout does. */
    fun rmssd(ibis: List<Int>): Double? {
        if (ibis.size < MIN_BEATS) return null
        val d = ibis.zipWithNext().filter { (a, b) -> abs(b - a) <= 0.25 * a }.map { (a, b) -> (b - a).toDouble() }
        return if (d.size < MIN_BEATS - 2) null else sqrt(d.sumOf { it * it } / d.size)
    }

    private fun hr(ibis: List<Int>): Int? = if (ibis.size < MIN_BEATS) null else (60000.0 / ibis.average()).roundToInt()

    fun summarize(beats: List<CalmBeat>): CalmResult {
        val first = beats.filter { it.tMs < WINDOW_S * 1000L }.map { it.ibiMs }
        val last = beats.filter { it.tMs >= (SESSION_S - WINDOW_S) * 1000L }.map { it.ibiMs }
        return CalmResult(hr(first), hr(last), rmssd(first)?.roundToInt(), rmssd(last)?.roundToInt(), beats.size)
    }

    /** A plain, non-clinical description of the result. */
    fun message(r: CalmResult): String {
        val pct = r.hrvChangePct
        val hrDrop = if (r.hrStart != null && r.hrEnd != null) r.hrStart - r.hrEnd else null
        return when {
            pct == null -> "Not enough clean beats to compare. Sit still with the ring snug, and try again."
            pct >= 10 && (hrDrop ?: 0) >= 2 -> "Your heart slowed and its rhythm became more varied: a sign your body settled toward calm."
            pct >= 10 -> "Your heart rate variability rose by about $pct%: your body shifted toward calm."
            pct <= -10 -> "Your HRV was lower at the end. That's normal: it varies a lot from one session to the next, and stress or movement can lower it."
            else -> "Little change this time. It often takes a few sessions, and comparing the same time of day helps."
        }
    }

    /** 0 = fully breathed out, 1 = fully breathed in, for [tSec] seconds into the breathing (smooth, easing at each turn). */
    fun breathFraction(tSec: Double): Double {
        val cycle = BREATH_IN_S + BREATH_OUT_S
        val t = ((tSec % cycle) + cycle) % cycle
        val x = if (t < BREATH_IN_S) t / BREATH_IN_S else 1 - (t - BREATH_IN_S) / BREATH_OUT_S
        return 0.5 - 0.5 * Math.cos(Math.PI * x) // ease in and out like a real breath
    }

    fun phase(tSec: Double): String = if (((tSec % (BREATH_IN_S + BREATH_OUT_S)) + BREATH_IN_S + BREATH_OUT_S) % (BREATH_IN_S + BREATH_OUT_S) < BREATH_IN_S) "Breathe in" else "Breathe out"
}
