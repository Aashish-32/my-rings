package com.aashish.myrings.data

import kotlin.math.roundToInt

/** One resilience reading: a 0-100 number, a level word, and the parts it came from. */
data class Resilience(val score: Int, val level: String, val nights: Int, val provisional: Boolean, val parts: List<Pair<String, Int?>>)

/**
 * Resilience ESTIMATE. Oura's is a proprietary model (`stress_resilience`, which open_oura lists as needing about 14 days of history
 * and doesn't ship); only its inputs are public: sleep recovery and daytime recovery over two weeks. This is our own simple blend of
 * those, plus HRV against your own average, and the level cut-offs are ours too. It is a guide to your trend, not Oura's number.
 */
object ResilienceEstimate {
    const val WINDOW = 14
    const val MIN_NIGHTS = 3
    // our weights: sleep recovery counts most, then how much of the day was calm, then HRV
    private val WEIGHTS = listOf(0.5, 0.3, 0.2)

    fun level(score: Int) = when {
        score < 40 -> "Limited"
        score < 55 -> "Adequate"
        score < 70 -> "Solid"
        score < 85 -> "Strong"
        else -> "Exceptional"
    }

    /** Share of classified daytime minutes that were relaxed or restored, mapped so that a calm 60 % of the day scores 100. */
    fun daytimeRecovery(days: Collection<StressDay>): Int? {
        val calm = days.sumOf { it.relaxedMin + it.restoredMin }
        val total = days.sumOf { it.stressedMin + it.engagedMin + it.relaxedMin + it.restoredMin }
        if (total < 120) return null
        return (100.0 * (calm.toDouble() / total) / 0.6).coerceIn(0.0, 100.0).roundToInt()
    }

    /** Recent HRV against the fortnight's average: equal to average is 70, and every 10 % above or below moves it 20 points. */
    fun hrvBalance(hrvs: List<Double>): Int? {
        if (hrvs.size < MIN_NIGHTS) return null
        val recent = hrvs.takeLast(3).average(); val all = hrvs.average()
        return (70 + 200 * (recent / all - 1)).coerceIn(0.0, 100.0).roundToInt()
    }

    fun estimate(nights: List<Night>, stress: Map<String, StressDay>): Resilience? {
        val last = nights.takeLast(WINDOW)
        if (last.size < MIN_NIGHTS) return null
        val sleep = last.mapNotNull { it.sleepScore.value }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        val day = daytimeRecovery(last.mapNotNull { stress[it.date] })
        val hrv = hrvBalance(last.mapNotNull { it.hrv })
        val parts = listOf("Sleep recovery" to sleep, "Daytime recovery" to day, "HRV balance" to hrv)
        val have = parts.indices.filter { parts[it].second != null }
        if (have.isEmpty()) return null
        val score = (have.sumOf { parts[it].second!! * WEIGHTS[it] } / have.sumOf { WEIGHTS[it] }).roundToInt()
        return Resilience(score, level(score), last.size, last.size < WINDOW, parts)
    }
}
