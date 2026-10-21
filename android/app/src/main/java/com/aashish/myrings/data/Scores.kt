package com.aashish.myrings.data

import kotlin.math.abs
import kotlin.math.roundToInt

/** One 0–100 contributor of a score. [value] is null when there is not enough data for it yet. */
data class Contributor(val name: String, val weight: Int, val value: Int?, val detail: String)

/** A 0–100 score built like Oura's: a weighted average of contributor sub-scores. */
data class Score(val value: Int?, val contributors: List<Contributor>, val provisional: Boolean, val note: String?) {
    val label: String
        get() = when {
            value == null -> "Not enough data"
            value >= 85 -> "Optimal"
            value >= 70 -> "Good"
            value >= 60 -> "Fair"
            else -> "Pay attention"
        }
}

/**
 * Sleep / Readiness / Stress scoring.
 *
 * What is Oura's and what is ours:
 *  - Contributor sets and combiner weights are Oura's, recovered by open_oura by regressing Oura's own scores on
 *    their contributors (`docs/algorithms/score-weights.md`: Sleep R²=0.999, Readiness R²=0.97). The combiner is
 *    `round(Σ wᵢ·cᵢ / Σ wᵢ)`.
 *  - The curves that turn a raw metric into a contributor (e.g. hours slept → 0–100) are NOT recoverable from the
 *    app; open_oura calibrates them per person from an Oura trends export. Without one, the curves below follow
 *    Oura's published guidance (7–9 h sleep, ~03:00 midpoint, 10–20 min latency, HRV/RHR versus your own
 *    baseline, ±0.5 °C temperature). Expect the right direction and a similar range, not Oura's exact number.
 *  - Contributors with no data are left out and the remaining weights are renormalised, so a score still appears
 *    after the first night; baseline-relative ones are flagged provisional until ~14 nights exist.
 *  - Daytime stress follows the structure of Oura's `stress_daytime_sensing` model (open_oura
 *    `tools/run_stress_model.py`): awake, sedentary (MET ≤ 1.8) 5-minute HRV compared with your daytime HRV
 *    baseline, with a ±2/3/4 ms neutral zone chosen from your night HRV baseline.
 */
object Scores {
    const val BASELINE_NIGHTS = 14

    /** Piecewise-linear interpolation through (x, y) knots, clamped at both ends. */
    fun interp(x: Double, knots: List<Pair<Double, Double>>): Double {
        if (x <= knots.first().first) return knots.first().second
        for (i in 1 until knots.size) {
            val (x1, y1) = knots[i]
            if (x <= x1) {
                val (x0, y0) = knots[i - 1]
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            }
        }
        return knots.last().second
    }

    private fun c(name: String, weight: Int, raw: Double?, knots: List<Pair<Double, Double>>, detail: String) =
        Contributor(name, weight, raw?.let { interp(it, knots).roundToInt().coerceIn(0, 100) }, detail)

    fun combine(contributors: List<Contributor>, provisional: Boolean, note: String? = null): Score {
        val have = contributors.filter { it.value != null }
        val w = have.sumOf { it.weight }
        val v = if (w == 0 || have.size < 2) null else (have.sumOf { it.weight * it.value!! }.toDouble() / w).roundToInt()
        return Score(v, contributors, provisional || have.size < contributors.size, note)
    }

    // ---------------- sleep ----------------

    /** Inputs for one night. Stage minutes are null when the ring sent no hypnogram (movement-based estimate). */
    data class SleepInput(
        val totalSleepMin: Double, val inBedMin: Double, val latencyMin: Double?, val wasoFraction: Double,
        val remMin: Double?, val deepMin: Double?, val midpointHour: Double,
        /** Stages came from our own stager rather than the ring. */
        val stagesEstimated: Boolean = false,
    )

    fun sleepScore(s: SleepInput): Score {
        val eff = if (s.inBedMin > 0) 100 * s.totalSleepMin / s.inBedMin else null
        val d = ((s.midpointHour - 3.0) % 24 + 24) % 24
        val midDist = minOf(d, 24 - d) // circular distance (hours) from a 03:00 midpoint
        return combine(listOf(
            c("Total sleep", 35, s.totalSleepMin / 60, listOf(0.0 to 0.0, 4.0 to 20.0, 5.0 to 40.0, 6.0 to 65.0, 7.0 to 85.0, 7.5 to 95.0, 8.0 to 100.0, 10.0 to 100.0, 11.5 to 85.0),
                "%.1f h asleep (7–9 h is best)".format(s.totalSleepMin / 60)),
            c("Restfulness", 15, s.wasoFraction, listOf(0.0 to 100.0, 0.05 to 95.0, 0.1 to 80.0, 0.2 to 55.0, 0.35 to 20.0, 0.5 to 0.0),
                "${(s.wasoFraction * 100).roundToInt()}% of the night awake or restless"),
            c("Efficiency", 10, eff, listOf(50.0 to 0.0, 65.0 to 30.0, 75.0 to 60.0, 85.0 to 90.0, 90.0 to 100.0),
                eff?.let { "${it.roundToInt()}% of time in bed asleep" } ?: "—"),
            c("REM sleep", 10, s.remMin, listOf(0.0 to 0.0, 30.0 to 35.0, 60.0 to 65.0, 90.0 to 90.0, 105.0 to 100.0),
                s.remMin?.let { "${it.roundToInt()} min" } ?: "Needs the ring's sleep stages"),
            c("Deep sleep", 10, s.deepMin, listOf(0.0 to 0.0, 20.0 to 30.0, 40.0 to 60.0, 60.0 to 85.0, 75.0 to 100.0),
                s.deepMin?.let { "${it.roundToInt()} min" } ?: "Needs the ring's sleep stages"),
            c("Latency", 10, s.latencyMin, listOf(0.0 to 60.0, 5.0 to 85.0, 10.0 to 100.0, 20.0 to 100.0, 30.0 to 85.0, 45.0 to 60.0, 60.0 to 40.0, 90.0 to 20.0),
                s.latencyMin?.let { "${it.roundToInt()} min to fall asleep (10–20 is best)" } ?: "—"),
            c("Timing", 10, midDist, listOf(0.0 to 100.0, 1.0 to 95.0, 2.0 to 80.0, 3.0 to 55.0, 4.0 to 30.0, 6.0 to 0.0),
                "Midpoint %d:%02d (around 3:00 is best)".format(s.midpointHour.toInt() % 24, ((s.midpointHour % 1) * 60).toInt())),
        ), provisional = s.remMin == null || s.stagesEstimated, note = when {
            s.remMin == null -> "Estimated from movement only: not enough heart data to stage this night."
            s.stagesEstimated -> "Sleep stages estimated by My Rings from heart rate, HRV and movement (not Oura's model)."
            else -> null
        })
    }

    // ---------------- readiness ----------------

    data class ReadinessInput(
        val lowestHr: Double?, val rhrBaseline: Double?,
        val sleepScore: Int?,
        /** Mean night HRV of the last 3 nights vs the baseline from the nights before. */
        val hrvRecent: Double?, val hrvBaseline: Double?,
        val tempDeviation: Double?,
        /** Mean total sleep (hours) over up to the last 14 nights. */
        val sleepBalanceH: Double?,
        /** Medium + high activity minutes the day before. */
        val prevDayActiveMin: Double?,
        val recoveryIndexH: Double?,
        /** Mean daily medium + high activity minutes over the last 14 days. */
        val activityBalanceMin: Double?,
        val nightsOfHistory: Int,
    )

    fun readiness(r: ReadinessInput): Score {
        val rhr = when {
            r.lowestHr == null -> c("Resting heart rate", 17, null, listOf(0.0 to 0.0), "—")
            r.rhrBaseline != null -> c("Resting heart rate", 17, r.lowestHr - r.rhrBaseline, listOf(-5.0 to 100.0, 0.0 to 95.0, 2.0 to 85.0, 4.0 to 70.0, 7.0 to 50.0, 10.0 to 30.0),
                "Lowest %.0f bpm vs your %.0f bpm baseline".format(r.lowestHr, r.rhrBaseline))
            else -> c("Resting heart rate", 17, r.lowestHr, listOf(40.0 to 100.0, 55.0 to 95.0, 65.0 to 80.0, 75.0 to 60.0, 90.0 to 30.0),
                "Lowest %.0f bpm (no baseline yet)".format(r.lowestHr))
        }
        val hrvRatio = if (r.hrvRecent != null && r.hrvBaseline != null && r.hrvBaseline > 0) r.hrvRecent / r.hrvBaseline else null
        return combine(listOf(
            rhr,
            Contributor("Previous night", 15, r.sleepScore, r.sleepScore?.let { "Sleep score $it" } ?: "No sleep recorded"),
            c("HRV balance", 15, hrvRatio, listOf(0.6 to 20.0, 0.75 to 45.0, 0.85 to 70.0, 0.95 to 90.0, 1.0 to 100.0),
                if (hrvRatio == null) "Needs a few nights to form your HRV baseline"
                else "%.0f ms recently vs %.0f ms baseline".format(r.hrvRecent, r.hrvBaseline)),
            c("Body temperature", 13, r.tempDeviation?.let { abs(it) }, listOf(0.0 to 100.0, 0.2 to 100.0, 0.4 to 85.0, 0.6 to 65.0, 1.0 to 35.0, 1.5 to 10.0),
                r.tempDeviation?.let { "%+.2f °C from your normal".format(it) } ?: "Needs a few nights to learn your normal"),
            c("Sleep balance", 12, r.sleepBalanceH, listOf(5.0 to 20.0, 6.0 to 50.0, 6.5 to 70.0, 7.0 to 85.0, 7.5 to 100.0),
                r.sleepBalanceH?.let { "%.1f h a night lately".format(it) } ?: "—"),
            c("Previous day activity", 10, r.prevDayActiveMin, listOf(0.0 to 40.0, 15.0 to 70.0, 30.0 to 90.0, 45.0 to 100.0, 120.0 to 100.0, 180.0 to 85.0, 300.0 to 60.0),
                r.prevDayActiveMin?.let { "${it.roundToInt()} active minutes yesterday" } ?: "No activity data for yesterday"),
            c("Recovery index", 10, r.recoveryIndexH, listOf(0.0 to 30.0, 2.0 to 55.0, 4.0 to 80.0, 5.5 to 95.0, 6.0 to 100.0),
                r.recoveryIndexH?.let { "Heart rate bottomed out %.1f h before waking".format(it) } ?: "—"),
            c("Activity balance", 7, r.activityBalanceMin, listOf(0.0 to 40.0, 10.0 to 65.0, 20.0 to 85.0, 30.0 to 100.0, 90.0 to 100.0, 150.0 to 85.0),
                r.activityBalanceMin?.let { "${it.roundToInt()} active min/day over 2 weeks" } ?: "Needs a few days of activity"),
        ), provisional = r.nightsOfHistory < BASELINE_NIGHTS,
            note = if (r.nightsOfHistory < BASELINE_NIGHTS) "Learning your baselines: ${r.nightsOfHistory} of $BASELINE_NIGHTS nights so far." else null)
    }

    // ---------------- daytime stress ----------------

    enum class Zone { STRESSED, ENGAGED, RELAXED, RESTORED }

    data class StressSample(val t: Long, val rmssd: Double, val intensity: Double, val zone: Zone)

    /** Neutral-zone half width (ms) from the night HRV baseline: the model picks 2, 3 or 4 ms. Cut points are ours. */
    fun neutralHalf(nightBaseline: Double): Double = when {
        nightBaseline < 40 -> 2.0
        nightBaseline < 70 -> 3.0
        else -> 4.0
    }

    fun zoneOf(intensity: Double, half: Double): Zone = when {
        intensity <= -half -> Zone.STRESSED
        intensity < 0 -> Zone.ENGAGED
        intensity < half -> Zone.RELAXED
        else -> Zone.RESTORED
    }

    /** Oura's day summary: mostly restored, mostly stressed, or balanced. */
    fun stressSummary(stressedMin: Int, restoredMin: Int): String = when {
        stressedMin + restoredMin < 30 -> "Not enough data"
        stressedMin >= 60 && stressedMin >= 2 * restoredMin -> "Stressful"
        restoredMin >= 60 && restoredMin >= 2 * stressedMin -> "Restored"
        else -> "Normal"
    }
}
