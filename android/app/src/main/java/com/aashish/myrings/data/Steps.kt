package com.aashish.myrings.data

import kotlin.math.roundToInt

/**
 * Step ESTIMATE from the ring's per-minute MET values. This is not Oura's step count.
 *
 * open_oura has no plain step counter: the ring only reports gait features (`real_steps_features`, tags 0x7e/0x7f, behind a server
 * flag) that Oura's own model turns into steps, and we don't use that model. What it does provide is the MET per minute
 * (`activity_information`, 0x50) and the fixed 0.762 m stride (`actinfo_steps_to_meters`). So we map each minute's MET to a typical
 * walking or running cadence (steps per minute, in line with published MET and cadence tables) and add the minutes up.
 * A walk you count by hand sets a scale so it fits how you move; until then the number is a rough guide, off by 20 % or more.
 */
object Steps {
    const val SCALE_KEY = "steps_scale"
    const val PENDING_KEY = "steps_calib_pending"   // "startSec|endSec|counted"
    const val RESULT_KEY = "steps_calib_result"

    /** MET to steps per minute: nothing below 2 MET (sitting, fidgeting), then a typical walk-to-run cadence curve. */
    private val CADENCE = listOf(2.0 to 0.0, 2.5 to 70.0, 3.3 to 100.0, 4.3 to 115.0, 6.0 to 140.0, 8.0 to 160.0, 12.0 to 175.0)

    fun cadence(met: Double): Double {
        if (met <= CADENCE.first().first) return 0.0
        if (met >= CADENCE.last().first) return CADENCE.last().second
        val i = CADENCE.indexOfLast { it.first <= met }
        val (m0, c0) = CADENCE[i]; val (m1, c1) = CADENCE[i + 1]
        return c0 + (c1 - c0) * (met - m0) / (m1 - m0)
    }

    fun raw(mets: Collection<Double>): Double = mets.sumOf { cadence(it) }

    fun estimate(mets: Collection<Double>, scale: Double = 1.0): Int = (raw(mets) * scale).roundToInt()

    /** counted / estimated for a walk, or null when either is too small to trust. Limited to 0.5 to 2 so one odd walk can't wreck the numbers. */
    fun calibrationScale(counted: Int, estimatedRaw: Double): Double? =
        if (counted < 30 || estimatedRaw < 30.0) null else (counted / estimatedRaw).coerceIn(0.5, 2.0)

    private fun key(base: String, serial: String) = "${base}_$serial"

    /**
     * If a walk is waiting to be calibrated and the ring's minutes now cover it, work out the scale and store it.
     * Returns true when a scale was stored. The result text is kept for the app to show.
     */
    fun applyPending(store: Store, serial: String, metByMinute: Map<Long, Double>): Boolean {
        val pendingText = store.meta(key(PENDING_KEY, serial))?.takeIf { it.isNotEmpty() } ?: return false
        val p = pendingText.split("|").mapNotNull { it.toLongOrNull() }
        if (p.size != 3) { store.setMeta(key(PENDING_KEY, serial), ""); return false }
        val (start, end, counted) = p
        val newest = metByMinute.keys.maxOrNull() ?: return false
        if (newest < end) return false // the ring hasn't sent those minutes yet: wait for the next sync
        val estimated = raw(metByMinute.filterKeys { it in (start / 60 * 60) until end }.values)
        val scale = calibrationScale(counted.toInt(), estimated)
        store.setMeta(key(PENDING_KEY, serial), "")
        if (scale == null) {
            store.setMeta(key(RESULT_KEY, serial), "That walk was too short or too gentle to calibrate from. Try 10 minutes of steady walking.")
            return false
        }
        store.setMeta(key(SCALE_KEY, serial), scale.toString())
        store.setMeta(key(RESULT_KEY, serial), "Calibrated: the estimate was ${estimated.roundToInt()} steps for the $counted you counted, so steps are now scaled by ${"%.2f".format(scale)}.")
        return true
    }

    fun scale(store: Store, serial: String): Double = store.meta(key(SCALE_KEY, serial))?.toDoubleOrNull() ?: 1.0
}
