package com.aashish.myrings.data

import kotlin.math.abs
import kotlin.math.roundToInt

/** A stretch of sustained activity found in a day's MET and heart-rate data. Times are unix seconds. */
data class Workout(
    val start: Long, val end: Long, val durationMin: Int,
    val avgMet: Double, val peakMet: Double,
    val avgHr: Double?, val maxHr: Double?,
    val kcal: Int,
) {
    /** Plain-language intensity. The ring can't say *what* the activity was (Oura's own classifier runs in its cloud). */
    val intensity: String get() = when {
        avgMet >= 7.0 -> "Vigorous"
        avgMet >= 4.5 -> "Moderate"
        else -> "Light"
    }
}

/**
 * Finds activity sessions without guessing their type: runs of minutes at 3 MET or more (brief dips of up to 3 minutes are bridged),
 * at least 10 minutes long. Oura's automatic workout classification is a cloud model; the retired open_oura heuristic mislabelled
 * workouts from skin temperature, so this only reports effort, duration, heart rate and calories.
 */
object Workouts {
    const val ACTIVE_MET = 3.0
    const val MAX_GAP_MIN = 3
    const val MIN_DURATION_MIN = 10

    /** [met] is one value per minute (unix seconds, MET); [hr] are heart-rate samples (unix seconds, bpm). */
    fun detect(met: List<Pair<Long, Double>>, hr: List<Pair<Long, Double>>, weightKg: Double): List<Workout> {
        val minutes = met.sortedBy { it.first }
        val active = minutes.filter { it.second >= ACTIVE_MET }
        if (active.isEmpty()) return emptyList()
        // group active minutes into sessions, bridging short dips
        val groups = ArrayList<MutableList<Pair<Long, Double>>>()
        for (m in active) {
            val last = groups.lastOrNull()?.last()
            if (last != null && (m.first - last.first) / 60 <= MAX_GAP_MIN + 1) groups.last() += m else groups += mutableListOf(m)
        }
        return groups.mapNotNull { g ->
            val start = g.first().first; val end = g.last().first + 60
            val durationMin = ((end - start) / 60).toInt()
            if (durationMin < MIN_DURATION_MIN) return@mapNotNull null
            val inside = minutes.filter { it.first in start until end }
            val hrIn = hr.filter { it.first in (start - 60)..(end + 60) }.map { it.second }
            Workout(
                start, end, durationMin,
                avgMet = inside.map { it.second }.average(), peakMet = inside.maxOf { it.second },
                avgHr = hrIn.takeIf { it.isNotEmpty() }?.average(), maxHr = hrIn.maxOrNull(),
                kcal = (inside.sumOf { maxOf(0.0, it.second - 1.0) } * weightKg / 60).roundToInt(),
            )
        }
    }
}

// ---------------------------------------------------------------- activity score

/**
 * An *estimated* Activity score. Oura's weights are known (open_oura score-weights.md: Move Every Hour 33, Meet Daily Targets 24,
 * Stay Active 17, Training Volume 15, Training Frequency 12) but its per-person goals and curves are not, so every contributor
 * here uses defaults and the score is flagged provisional.
 */
object ActivityScore {
    private fun c(name: String, weight: Int, raw: Double?, knots: List<Pair<Double, Double>>, detail: String) =
        Contributor(name, weight, raw?.let { Scores.interp(it, knots).roundToInt().coerceIn(0, 100) }, detail)

    /** Share of waking hours (between the first and last worn minute) with at least [MOVE_MIN] minutes of movement. */
    private const val MOVE_MIN = 2
    private const val MOVE_MET = 1.8

    fun movingHoursFraction(met: List<Pair<Long, Double>>): Double? {
        if (met.size < 120) return null // under two hours of data says nothing about "every hour"
        val byHour = met.groupBy { it.first / 3600 }
        val hours = byHour.keys.sorted()
        val span = hours.last() - hours.first() + 1
        if (span < 4) return null
        val moving = byHour.count { (_, v) -> v.count { it.second >= MOVE_MET } >= MOVE_MIN }
        return moving.toDouble() / span
    }

    /**
     * @param activeKcal active calories today; @param targetKcal today's goal (default: 20 % of resting burn, 250–600)
     * @param inactiveMin worn minutes below 1.5 MET; @param weekWorkouts sessions in the last 7 days (incl. today)
     */
    fun score(met: List<Pair<Long, Double>>, activeKcal: Int?, targetKcal: Double, inactiveMin: Int, weekWorkouts: List<Workout>): Score {
        val moveFrac = movingHoursFraction(met)
        val trainingDays = weekWorkouts.map { it.start / 86400 }.distinct().size
        val weekMin = weekWorkouts.sumOf { it.durationMin }.toDouble()
        val contributors = listOf(
            c("Move every hour", 33, moveFrac, listOf(0.0 to 0.0, 0.4 to 20.0, 0.6 to 55.0, 0.8 to 85.0, 0.9 to 100.0),
                moveFrac?.let { "Moved in ${(it * 100).roundToInt()}% of waking hours" } ?: "Needs a few hours of wear"),
            c("Meet daily targets", 24, activeKcal?.let { it / targetKcal }, listOf(0.0 to 0.0, 0.5 to 50.0, 0.8 to 80.0, 1.0 to 100.0),
                activeKcal?.let { "$it of ${targetKcal.roundToInt()} active kcal (default goal)" } ?: "No activity data yet"),
            c("Stay active", 17, if (inactiveMin > 0) inactiveMin.toDouble() else null, listOf(300.0 to 100.0, 480.0 to 85.0, 600.0 to 60.0, 720.0 to 35.0, 900.0 to 10.0),
                "${inactiveMin / 60} h ${inactiveMin % 60} min sitting or lying still"),
            c("Training volume", 15, weekMin, listOf(0.0 to 20.0, 60.0 to 50.0, 120.0 to 80.0, 150.0 to 100.0),
                "${weekMin.roundToInt()} min of training this week"),
            c("Training frequency", 12, trainingDays.toDouble(), listOf(0.0 to 30.0, 1.0 to 55.0, 2.0 to 75.0, 3.0 to 95.0, 4.0 to 100.0),
                "$trainingDays training day${if (trainingDays == 1) "" else "s"} this week"),
        )
        return Scores.combine(contributors, provisional = true,
            note = "Estimated: Oura's activity goal and curves are personal to you, so this uses defaults.")
    }

    /** Default daily active-calorie goal: a fifth of resting burn, kept between 250 and 600. */
    fun defaultTargetKcal(bmrKcal: Double) = (bmrKcal * 0.2).coerceIn(250.0, 600.0)
}
