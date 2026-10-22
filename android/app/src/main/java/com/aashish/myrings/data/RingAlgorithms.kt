package com.aashish.myrings.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ports of Oura's on-device `ecore` routines, as recovered by open_oura (each cites the ecore function address).
 * Kept free of Android types so they are unit-tested against the Rust reference values.
 */

// ---------------------------------------------------------------- personal baseline

/**
 * `baseline_update_lt_mean_and_dev @ 0x1dad04`: an asymmetric moving average of a rolling mean and mean absolute deviation,
 * stored as real × 8 fixed point. The step size anneals with the baseline's age (under 4 days, 4–14 days, over 14 days):
 * warm-up is quick (gain 1/2) and settles to about 1/32, so a baseline is usable within the first days instead of after two weeks.
 */
class EmaBaseline(var meanX8: Int = 0, var devX8: Int = 0) {
    private fun ashr(t: Int, shift: Int) = (if (t < 0) t + ((1 shl shift) - 1) else t) shr shift

    fun update(sample: Int, ageDays: Int) {
        val x8 = sample shl 3
        val delta = x8 - meanX8
        meanX8 += when {
            ageDays > 14 -> ashr(delta + (if (delta != 0 && meanX8 <= x8) 16 else -16), 5)
            ageDays >= 4 -> ashr(delta + (if (delta > 0) 4 else -4), 3)
            else -> ashr(if (delta > 0) delta + 1 else delta - 1, 1)
        }
        val absd = abs(x8 - meanX8)
        val (mag, shift) = when { ageDays > 14 -> 32 to 6; ageDays >= 4 -> 8 to 4; else -> 4 to 3 }
        val bias = if (absd != devX8 && devX8 <= absd) mag else -mag
        devX8 += ashr((absd - devX8) + bias, shift)
    }

    val mean get() = meanX8 / 8.0
    val deviation get() = devX8 / 8.0

    companion object {
        /**
         * The baseline of a series of nightly values (oldest first, null where unknown). ecore starts from an empty state; here the
         * first real value seeds the mean, so the baseline isn't dragged toward zero. [scale] keeps decimals (use 100 for °C).
         * Null until [minSamples] values exist, so one odd night can't define "normal".
         */
        fun of(values: List<Double?>, scale: Double = 1.0, minSamples: Int = 2): Double? {
            val known = values.filterNotNull()
            if (known.size < minSamples) return null
            val b = EmaBaseline()
            b.meanX8 = (known[0] * scale).roundToInt() shl 3
            for (i in 1 until known.size) b.update((known[i] * scale).roundToInt(), ageDays = i)
            return b.mean / scale
        }
    }
}

// ---------------------------------------------------------------- sleep debt

/**
 * `sleep_debt_calculate @ 0x215658`: a linearly decaying weighted sum of each night's shortfall (need − actual), floored at 0,
 * capped at 10 h and rounded to 45 minutes. Index 0 is the most recent night; the newest weighs 1.0, the oldest about 0.25.
 */
object SleepDebt {
    data class Result(val debtS: Int, val recentShortfallS: Int?, val valid: Boolean) {
        val debtHours get() = debtS / 3600.0
    }

    private const val MIN_VALID_DAYS = 5
    private const val MAX_DEBT_S = 36_000
    private const val ROUND_S = 2700

    fun compute(actualS: List<Int>, needS: List<Int>): Result {
        val n = min(actualS.size, needS.size)
        if (n == 0) return Result(0, null, false)
        fun shortfall(d: Int): Double? = if (actualS[d] <= 0 || needS[d] <= 0) null else (needS[d] - actualS[d]).toDouble()
        val decay = if (n == 1) 0.0 else 0.75 / (n - 1.0)
        var debt = 0.0
        var valid = 0
        for (d in 0 until n) shortfall(d)?.let { valid++; debt += (1.0 - decay * d) * it }
        val recent = shortfall(0) ?: return Result(0, null, false)
        debt = debt.coerceIn(0.0, MAX_DEBT_S.toDouble())
        debt = (debt / ROUND_S).roundToInt() * ROUND_S.toDouble()
        return Result(debt.toInt(), recent.toInt(), valid >= MIN_VALID_DAYS)
    }

    /** How the debt feeds the Readiness "sleep balance" contributor (our curve; Oura's isn't recoverable). */
    val BALANCE_CURVE = listOf(0.0 to 100.0, 0.75 to 95.0, 1.5 to 85.0, 3.0 to 65.0, 5.0 to 40.0, 8.0 to 15.0, 10.0 to 5.0)
}

// ---------------------------------------------------------------- nightly skin temperature

/**
 * `nightly_temperature_calculate @ 0x203520`: a 7-sample sliding median, then 30-sample windows; each window contributes its
 * maximum when its range is under 2.5 °C, and the night's value is the smallest of those maxima (needs at least 4 windows).
 * This ignores the warm-up and the brief spikes at the edges of a night that a plain average would include.
 */
object NightlyTemperature {
    private const val WINDOW = 30
    private const val RANGE_CENTI = 250
    private const val MIN_WINDOWS = 4

    /** [samplesC] in °C, in time order (0 or negative = invalid). Returns °C, or null with fewer than 4 valid windows. */
    fun compute(samplesC: List<Double>): Double? {
        val ring = IntArray(7)
        var idx = 0
        var winMin = Int.MAX_VALUE
        var winMax = 0
        val maxima = ArrayList<Int>()
        samplesC.forEachIndexed { i, c ->
            ring[idx] = if (c > 0) (c * 100).roundToInt() else 0
            idx = if (idx == 6) 0 else idx + 1
            val m = ring.sorted()[3]
            if (m != 0) { winMin = min(winMin, m); winMax = max(winMax, m) }
            if ((i + 1) % WINDOW == 0) {
                if (winMax >= winMin && winMax != 0 && winMax - winMin < RANGE_CENTI) maxima += winMax
                winMin = Int.MAX_VALUE; winMax = 0
            }
        }
        return if (maxima.size < MIN_WINDOWS) null else maxima.min() / 100.0
    }
}
