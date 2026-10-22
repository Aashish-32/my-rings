package com.aashish.myrings.data

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Spots when the body has moved away from the person's own normal. Three quick, calm rules (none needs days of waiting):
 *
 *  - **Not recovering:** heart rate rises naturally with effort, so after the last hard activity it gets a recovery
 *    window; if it is *still* well above the resting level once the person has been still for a while, that is flagged.
 *  - **Last night's lowest heart rate** well above the usual for the previous nights.
 *  - **Last night's skin temperature** well off the person's own baseline.
 *
 * Pure functions over already-computed data, so they can be unit-tested without a ring.
 */
object Drift {
    enum class Kind(val key: String, val title: String) {
        RESTING("resting", "resting heart rate is up"),
        TEMP("temp", "skin temperature has shifted"),
        NOT_RECOVERING("recovering", "heart rate hasn't settled"),
    }

    data class Finding(val kind: Kind, val text: String)

    // ---- thresholds (kept together so they are easy to tune) ----
    const val RESTING_BPM = 5.0              // last night's lowest HR above the usual
    const val TEMP_C = 0.5                   // last night's skin temperature off the baseline
    const val STILL_MET = 1.5                // below this the person is resting
    const val ACTIVE_MET = 3.0               // at or above this counts as real exertion
    const val RECOVERY_GRACE_S = 45 * 60L    // time allowed after exertion for heart rate to come back down
    const val MIN_STILL_SPAN_S = 20 * 60L    // elevated while still for at least this long
    const val ELEVATED_BPM = 12.0            // above the resting level by at least this
    private const val MIN_PRIOR_NIGHTS = 3
    private const val FRESH_NIGHT_S = 36 * 3600L

    private fun median(a: List<Double>): Double {
        val s = a.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** [lowestHr] is the lowest sleeping heart rate per night, oldest to newest (null where unknown). */
    fun restingNote(lowestHr: List<Double?>): String? {
        val known = lowestHr.filterNotNull()
        if (known.size < MIN_PRIOR_NIGHTS + 1) return null
        val latest = known.last()
        val usual = median(known.dropLast(1).takeLast(7))
        val diff = latest - usual
        if (diff < RESTING_BPM) return null // only "higher than usual" matters here; a lower resting rate is good news
        return "Last night's lowest heart rate was ${diff.roundToInt()} bpm above your usual (about ${usual.roundToInt()} bpm). " +
            "Worth watching if you feel run down."
    }

    /** [deviation] is last night's skin temperature relative to the person's own baseline, in °C. */
    fun tempNote(deviation: Double?): String? {
        if (deviation == null || abs(deviation) < TEMP_C) return null
        val dir = if (deviation > 0) "above" else "below"
        val tail = if (deviation > 0) " A raised temperature can come before an illness or follow a late night." else ""
        return "Your skin temperature last night was ${"%.1f".format(abs(deviation))} °C $dir your normal.$tail"
    }

    /** The heart-rate samples (t, bpm) taken while the person was still: the surrounding minutes' average MET is low. */
    fun stillHeartRate(hr: List<Pair<Long, Double>>, met: List<Pair<Long, Double>>): List<Pair<Long, Double>> =
        hr.filter { (t, _) ->
            val near = met.filter { abs(it.first - t) <= 150 }
            near.isNotEmpty() && near.map { it.second }.average() < STILL_MET
        }

    /** The person's resting level while awake and still, from previous days; null until there is enough to know it. */
    fun stillBaseline(previousDays: List<Intraday>): Double? {
        val samples = previousDays.flatMap { stillHeartRate(it.hr, it.met) }.map { it.second }
        return if (samples.size >= 12) median(samples) else null
    }

    /**
     * Heart rate that has not come back down: after the last hard activity (plus a recovery window) the person has been still for
     * at least 20 minutes and their heart rate is still at least 12 bpm above their usual still level. [now] is unix seconds.
     */
    fun notRecovering(today: Intraday, baseline: Double?, now: Long): String? {
        if (baseline == null) return null
        val recent = today.hr.filter { it.first <= now }
        val lastActive = today.met.filter { it.first <= now && it.second >= ACTIVE_MET }.maxOfOrNull { it.first }
        val from = lastActive?.plus(RECOVERY_GRACE_S) ?: (now - 90 * 60)
        val still = stillHeartRate(recent.filter { it.first >= from }, today.met)
        if (still.size < 4) return null
        if (still.last().first - still.first().first < MIN_STILL_SPAN_S) return null
        if (now - still.last().first > 30 * 60) return null // only while it is still true
        val level = median(still.map { it.second })
        if (level - baseline < ELEVATED_BPM) return null
        val minutes = ((still.last().first - still.first().first) / 60).toInt()
        return "Your heart rate has stayed around ${level.roundToInt()} bpm for ${minutes} min while you've been still, about " +
            "${(level - baseline).roundToInt()} bpm above your usual resting level. After exertion it normally settles within about " +
            "45 minutes. Stress, illness, caffeine or dehydration can keep it up."
    }

    /** Everything worth flagging right now, newest information first. */
    fun findings(a: Analysis, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Finding> {
        val out = ArrayList<Finding>()
        val nights = a.nights.sortedBy { it.end }
        val latest = nights.lastOrNull()
        if (latest != null && now - latest.end < FRESH_NIGHT_S) {
            restingNote(nights.map { it.lowestHr })?.let { out += Finding(Kind.RESTING, it) }
            tempNote(latest.tempDeviation)?.let { out += Finding(Kind.TEMP, it) }
        }
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate().toString()
        val intra = a.intraday[today]
        if (intra != null) {
            val earlier = a.intraday.filterKeys { it < today }.entries.sortedBy { it.key }.takeLast(3).map { it.value }
            notRecovering(intra, stillBaseline(earlier), now)?.let { out += Finding(Kind.NOT_RECOVERING, it) }
        }
        return out
    }
}
