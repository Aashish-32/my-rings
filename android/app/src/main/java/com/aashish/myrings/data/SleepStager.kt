package com.aashish.myrings.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Our own 4-stage sleep stager (awake / light / deep / REM) on 30-second epochs, from the signals the ring
 * records at night. Oura stages sleep on the phone with an encrypted model (SleepNet) whose key is server-delivered,
 * so we don't use it; this is an independent, physiology-based reimplementation (open_oura sleepnet.md, route 3).
 *
 * What each stage looks like in ring data (standard sleep physiology, as used by consumer wearables):
 *  - Wake: movement, or a heart-rate surge with some movement.
 *  - Deep (N3): fully still; heart rate below its local level and steady over minutes; short-term HRV (5-min
 *    RMSSD) slightly LOWER than in light sleep (measured on ECG + polysomnography, see [Params]; the textbook
 *    "deep = high HRV" made staging worse); concentrated early in the night (homeostatic pressure decays roughly as exp(-2.2·fraction), the same
 *    weighting open_oura's Gen 4 N3 recovery uses).
 *  - REM: still (muscle atonia; small twitches allowed) but heart rate above its local level and irregular,
 *    HRV lower than light sleep; concentrated late; first REM rarely comes within ~60 min of falling asleep.
 *  - Light: everything else asleep.
 * Scores are compared with fixed thresholds, so stage amounts vary night to night, then held to physiological
 * bounds and cleaned up (2.5-minute smoothing; deep bouts ≥ 5 min, REM bouts ≥ 3 min).
 *
 * Validated on lab polysomnography (see [Params]), not against Oura: treat stage minutes as estimates.
 */
object SleepStager {
    const val EPOCH_S = 30L

    /**
     * Tunable constants; [DEFAULT] is what the app uses. Defaults were fitted (random search, 3000 trials) on half of
     * the 31 nights in Walch et al. 2019 (PhysioNet `sleep-accel`: wrist heart rate + motion with lab
     * polysomnography) and checked on the other half: 4-stage accuracy 63%, Cohen's κ 0.37, deep found 44%,
     * REM 51%, mean deep 56 vs 58 lab minutes (`StagerEvalTest`). Hand-set defaults scored κ 0.10. The motion
     * threshold was fitted on wrist movement (≥ 0.08 g range per second), which may differ from the ring's
     * motion_event seconds.
     *
     * The HRV weights ([deepRm], [remRm]) were fitted separately on ECG beat-to-beat data with polysomnography
     * (`StagerEvalTest.evaluateHrv`): 15 healthy controls from the CAP Sleep Database plus 18 MIT-BIH
     * Polysomnographic records, 33 nights, 5-fold cross-validated, staging within sleep (light / deep / REM):
     * HRV off κ 0.27 → HRV with these signs κ 0.32 (cross-validated; 0.33 with the chosen values). The first,
     * textbook signs (deep = high HRV) scored κ 0.20, i.e. worse than no HRV.
     */
    data class Params(
        val onsetEpochs: Int = 20, // 10 still minutes = asleep
        /**
         * Per-night motion baseline: this quantile of the night's motion counts is subtracted from every epoch, so a
         * sensor that reports a few "moving" seconds even at rest still yields still epochs. Most of a night is still
         * on any sensor, so on the validation data (where rest reads 0) it changes nothing.
         */
        val motionBaselineQ: Double = 0.4,
        val local: Int = 120, // ±60 min local baseline
        val remLatency: Int = 120, // no REM in the first 60 min after onset
        val minDeepBout: Int = 10,
        val minRemBout: Int = 4,
        val wakeMotion: Int = 3, // seconds of movement in an epoch that mean awake
        val wakeHrRise: Double = 8.0, // bpm above the local level that, with some movement, mean awake
        val deepT: Double = 0.89,
        val remT: Double = 0.78,
        val deepHr: Double = 0.36, val deepRm: Double = -0.5, val deepVar: Double = 0.83, val deepHomeo: Double = 1.71, val deepBias: Double = 0.46,
        val remHr: Double = 0.73, val remVar: Double = 0.92, val remRm: Double = 1.0, val remLate: Double = 1.16, val remBias: Double = 0.57,
        val deepLo: Double = 0.05, val deepHi: Double = 0.30,
        val remLo: Double = 0.08, val remHi: Double = 0.35,
    )

    val DEFAULT = Params()

    /**
     * @param hr heart rate per epoch (NaN where unknown)
     * @param rmssd HRV per epoch from a ~5-minute window (NaN where unknown)
     * @param motion seconds of movement per epoch (0–30), -1 where unknown
     * @return a stage per epoch ("awake", "light", "deep", "rem"), or null if there is too little heart data.
     */
    fun stage(hr: DoubleArray, rmssd: DoubleArray, motion: IntArray, p: Params = DEFAULT): List<String>? {
        val n = hr.size
        if (n < 60 || hr.count { !it.isNaN() } < n * 0.4) return null
        val h = fill(hr)
        val rm = if (rmssd.count { !it.isNaN() && it > 0 } >= n * 0.3) fill(rmssd.map { if (it > 0) it else Double.NaN }.toDoubleArray()) else null
        val known = motion.filter { it >= 0 }.sorted()
        val floor = if (known.isEmpty()) 0 else known[(p.motionBaselineQ * (known.size - 1)).toInt()]
        val mo = IntArray(n) { maxOf(0, motion[it] - floor) }

        // ---- wake ----
        val localHrAll = rollingMean(h, p.local)
        val wake = BooleanArray(n) { mo[it] >= p.wakeMotion || (mo[it] >= 2 && h[it] > localHrAll[it] + p.wakeHrRise) }
        val onset = (0..n - p.onsetEpochs).firstOrNull { i -> (0 until p.onsetEpochs).none { wake[i + it] } } ?: return null
        val lastSleep = (n - 1 downTo onset).first { !wake[it] }
        val st = Array(n) { if (it < onset || it > lastSleep || wake[it]) "awake" else "light" }
        val asleep = (onset..lastSleep).filter { st[it] != "awake" }
        if (asleep.size < 40) return st.toList()

        // ---- features relative to the night ----
        val hs = rollingMean(h, 2) // 2.5-minute smoothed HR
        val localHr = rollingMean(h, p.local, mask = { st[it] != "awake" })
        val hrDev = DoubleArray(n) { hs[it] - localHr[it] }
        val sdHr = sd(asleep.map { hrDev[it] }).coerceAtLeast(0.5)
        val hrVar = DoubleArray(n) { i -> sd((maxOf(0, i - 4)..minOf(n - 1, i + 4)).map { h[it] }) } // irregular HR (~4 min)
        val varMed = median(asleep.map { hrVar[it] }); val varMad = mad(asleep.map { hrVar[it] }, varMed).coerceAtLeast(0.2)
        val rmDev = rm?.let { r ->
            val lr = rollingMean(r.map { ln(it.coerceAtLeast(1.0)) }.toDoubleArray(), p.local, mask = { st[it] != "awake" })
            DoubleArray(n) { ln(r[it].coerceAtLeast(1.0)) - lr[it] }
        }
        val sdRm = rmDev?.let { d -> sd(asleep.map { d[it] }).coerceAtLeast(0.05) }

        val span = (lastSleep - onset).coerceAtLeast(1).toDouble()
        val deepScore = DoubleArray(n); val remScore = DoubleArray(n)
        for (i in asleep) {
            val frac = (i - onset) / span
            val zHr = hrDev[i] / sdHr
            val zVar = (hrVar[i] - varMed) / varMad
            val zRm = if (rmDev != null && sdRm != null) rmDev[i] / sdRm else 0.0
            val still = (maxOf(0, i - 2)..minOf(n - 1, i + 2)).all { mo[it] == 0 }
            val quiet = (maxOf(0, i - 2)..minOf(n - 1, i + 2)).all { mo[it] <= 1 }
            deepScore[i] = if (!still) Double.NEGATIVE_INFINITY
                else -p.deepHr * zHr + p.deepRm * zRm - p.deepVar * zVar + p.deepHomeo * exp(-2.2 * frac) - p.deepBias
            remScore[i] = if (!quiet || i - onset < p.remLatency) Double.NEGATIVE_INFINITY
                else p.remHr * zHr + p.remVar * zVar - p.remRm * zRm + p.remLate * (1 - exp(-2.0 * frac)) - p.remBias
        }

        // ---- classify, keep within physiological bounds ----
        val deep = pick(asleep, deepScore, p.deepT, p.deepLo..p.deepHi)
        val rem = pick(asleep.filter { it !in deep || remScore[it] > deepScore[it] }, remScore, p.remT, p.remLo..p.remHi)
        for (i in deep) st[i] = "deep"
        for (i in rem) if (st[i] != "deep" || remScore[i] > deepScore[i]) st[i] = "rem"

        smooth(st, onset, lastSleep)
        minBout(st, "deep", p.minDeepBout); minBout(st, "rem", p.minRemBout)
        return st.toList()
    }

    /** Epochs whose score clears [threshold]; if that share of [candidates] is outside [bounds], take the top scores up to the bound. */
    private fun pick(candidates: List<Int>, score: DoubleArray, threshold: Double, bounds: ClosedFloatingPointRange<Double>): Set<Int> {
        val valid = candidates.filter { score[it].isFinite() }.sortedByDescending { score[it] }
        var chosen = valid.filter { score[it] > threshold }
        val lo = (bounds.start * candidates.size).toInt(); val hi = (bounds.endInclusive * candidates.size).toInt()
        if (chosen.size < lo) chosen = valid.take(lo) else if (chosen.size > hi) chosen = valid.take(hi)
        return chosen.toSet()
    }

    /** 5-epoch majority filter over the asleep span (never turns wake into sleep or back). */
    private fun smooth(st: Array<String>, from: Int, to: Int) {
        val src = st.copyOf()
        for (i in from..to) {
            if (src[i] == "awake") continue
            val win = (maxOf(from, i - 2)..minOf(to, i + 2)).map { src[it] }.filter { it != "awake" }
            val top = win.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: continue
            if (top.value >= 3) st[i] = top.key
        }
    }

    /** Runs of [stage] shorter than [min] epochs become light sleep. */
    private fun minBout(st: Array<String>, stage: String, min: Int) {
        var i = 0
        while (i < st.size) {
            if (st[i] != stage) { i++; continue }
            var j = i
            while (j < st.size && st[j] == stage) j++
            if (j - i < min) for (k in i until j) st[k] = "light"
            i = j
        }
    }

    // ---- small numeric helpers ----

    /** Linear interpolation over NaN gaps (edges take the nearest known value). */
    fun fill(a: DoubleArray): DoubleArray {
        val out = a.copyOf()
        val known = a.indices.filter { !a[it].isNaN() }
        if (known.isEmpty()) return out
        for (i in a.indices) if (out[i].isNaN()) {
            val nx = known.firstOrNull { it > i }; val pv = known.lastOrNull { it < i }
            out[i] = when {
                pv == null -> a[nx!!]
                nx == null -> a[pv]
                else -> a[pv] + (a[nx] - a[pv]) * (i - pv) / (nx - pv)
            }
        }
        return out
    }

    private fun rollingMean(a: DoubleArray, half: Int, mask: (Int) -> Boolean = { true }): DoubleArray {
        val n = a.size
        val ps = DoubleArray(n + 1); val pc = IntArray(n + 1)
        for (i in 0 until n) { val m = mask(i); ps[i + 1] = ps[i] + if (m) a[i] else 0.0; pc[i + 1] = pc[i] + if (m) 1 else 0 }
        return DoubleArray(n) { i ->
            val lo = maxOf(0, i - half); val hi = minOf(n, i + half + 1)
            val c = pc[hi] - pc[lo]
            if (c == 0) a[i] else (ps[hi] - ps[lo]) / c
        }
    }

    private fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }

    private fun median(v: List<Double>): Double = v.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
    private fun mad(v: List<Double>, med: Double) = median(v.map { abs(it - med) }) * 1.4826
}
