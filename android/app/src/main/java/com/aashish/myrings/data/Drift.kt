package com.aashish.myrings.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Spots a metric that has drifted away from the person's own normal for several days in a row.
 * Compares the last [recentDays] readings with the average of the readings before them. Returns calm plain-language notes;
 * it never alarms on a single odd day.
 */
object Drift {
    private const val RECENT_DAYS = 3
    private const val MIN_BASELINE_DAYS = 4

    data class Rule(val name: String, val unit: String, val threshold: Double, val decimals: Int)

    val RestingHr = Rule("resting heart rate", "bpm", 4.0, 0)
    val Temperature = Rule("skin temperature", "°C", 0.4, 1)

    /** [values] is oldest -> newest, null for days without a reading. */
    fun note(values: List<Double?>, rule: Rule): String? {
        val known = values.filterNotNull()
        if (known.size < RECENT_DAYS + MIN_BASELINE_DAYS) return null
        val recent = known.takeLast(RECENT_DAYS)
        val base = known.dropLast(RECENT_DAYS).takeLast(7)
        if (base.size < MIN_BASELINE_DAYS) return null
        val diff = recent.average() - base.average()
        if (abs(diff) < rule.threshold) return null
        // all recent days must lean the same way, so one outlier can't trigger it
        val sameWay = recent.count { (it - base.average()) * diff > 0 }
        if (sameWay < RECENT_DAYS) return null
        val amount = if (rule.decimals == 0) "${abs(diff).roundToInt()}" else "%.${rule.decimals}f".format(abs(diff))
        val dir = if (diff > 0) "above" else "below"
        val tail = when {
            rule === RestingHr && diff > 0 -> " Worth watching if you feel run down."
            rule === Temperature && diff > 0 -> " A raised temperature can come before an illness or follow a late night."
            else -> ""
        }
        return "Your ${rule.name} has run $amount ${rule.unit} $dir your usual for the last $RECENT_DAYS days.$tail"
    }

    fun notes(resting: List<Double?>, temp: List<Double?>): List<String> =
        listOfNotNull(note(resting, RestingHr), note(temp, Temperature))
}
