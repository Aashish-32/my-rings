package com.aashish.myrings.data

import com.aashish.myrings.protocol.Events
import com.aashish.myrings.protocol.u32le
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class DaySummary(
    val date: String,
    val restingHr: Double?,
    /** "sleep" (lowest 5-min HR of the night that ended this day) or "daytime" (estimated from daytime beats). */
    val restingSrc: String?,
    val avgHr: Double?,
    val hrv: Double?,
    val hrvSrc: String?,
    val temp: Double?,
    val spo2: Double?,
    /** Minutes per stage (deep/light/rem/awake), null if the ring produced no hypnogram that day. */
    val sleepMin: Map<String, Double>?,
    val activeKcal: Int?,
    val wornMin: Int,
    val bleConnections: Int,
    /** Minutes at low (1.5–3 MET), medium (3–6) and high (≥6) intensity. */
    val activeMin: Map<String, Int> = emptyMap(),
    /** Worn minutes below 1.5 MET (sitting or lying still while awake). */
    val inactiveMin: Int = 0,
    val moveAlerts: Int = 0,
    val metMinutes: Int = 0,
    /** Resting burn (Schofield BMR) plus active calories; for today, BMR counts only the part of the day so far. */
    val totalKcal: Int? = null,
)

/** The body profile the numbers were computed with, and where each value came from ("you", "ring" or "default"). */
data class UsedProfile(
    val weightKg: Double, val weightFrom: String,
    val age: Int?, val ageFrom: String,
    val sex: Sex, val sexFrom: String,
    val bmrKcal: Int, val vo2max: Double?,
)

/** One main sleep period (a ring `bedtime_period` of 3 h or more), dated by the morning it ended. */
data class Night(
    val date: String, val start: Long, val end: Long,
    val inBedMin: Double, val totalSleepMin: Double, val efficiency: Double, val latencyMin: Double?,
    /** Minutes per stage from the ring's hypnogram, or null when only a movement-based estimate exists. */
    val stages: Map<String, Double>?,
    val lowestHr: Double?, val avgHr: Double?, val hrv: Double?, val recoveryIndexH: Double?,
    val temp: Double?, val tempDeviation: Double?, val spo2: Double?, val breathRate: Double?,
    val sleepScore: Score, val readiness: Score,
    /** "ring" (the ring's own hypnogram), "estimated" (our [SleepStager]) or null (movement-only, no stages). */
    val stagesSource: String? = null,
    /** One stage per 30 s from the start of the night, for the hypnogram chart. */
    val hypnogram: List<String>? = null,
    /** Oura's sleep debt over the last two weeks, in hours (null until 5 nights exist). */
    val sleepDebtH: Double? = null,
)

data class StressDay(
    val date: String, val stressedMin: Int, val engagedMin: Int, val relaxedMin: Int, val restoredMin: Int,
    val summary: String, val samples: List<Scores.StressSample>,
    val dhrvBaseline: Double?, val neutralHalfMs: Double?,
)

/** Time series for one day's charts; t is unix seconds. */
data class Intraday(
    val hr: List<Pair<Long, Double>>, val temp: List<Pair<Long, Double>>, val met: List<Pair<Long, Double>>,
    val alerts: List<Long>,
)

/** A ring-health note. [devOnly] ones (firmware resets, timing glitches, radio stats) show only in developer mode. */
data class HealthWarning(val level: String, val title: String, val detail: String, val devOnly: Boolean = false)
data class Reboot(val ts: Long, val reason: Int?, val label: String)

data class Analysis(
    val days: List<DaySummary>,
    /** Battery readings the ring recorded itself (arrive with every sync). */
    val ringBattery: List<BatterySample>,
    val reboots: List<Reboot>,
    val warnings: List<HealthWarning>,
    val drainPctPerHour: Double?,
    val nights: List<Night> = emptyList(),
    val stress: Map<String, StressDay> = emptyMap(),
    val intraday: Map<String, Intraday> = emptyMap(),
    /** Firmware log lines (debug_event + debug_data text), oldest first. */
    val logs: List<Pair<Long, String>> = emptyList(),
    val profile: UsedProfile? = null,
    /** Activity sessions found per date (effort, duration, heart rate, calories). */
    val workouts: Map<String, List<Workout>> = emptyMap(),
    /** Estimated Activity score per date. */
    val activity: Map<String, Score> = emptyMap(),
)

/**
 * Everything the app shows is derived here from stored ring events (no cloud). Scores are approximations of
 * Oura's (see [Scores]). Cached until new events land, because the store grows by tens of thousands of rows a day.
 */
object Analytics {
    private const val WORN_TEMP_C = 30.0 // probe-0 at/above this means the ring is on a finger
    private const val BUCKET = 300L // 5-minute buckets for HR / temperature / HRV
    private const val WINDOW_DAYS = 60L // analysed history; baselines need 14
    private const val MIN_NIGHT_S = 3 * 3600L
    private const val STRESS_MAX_MET = 1.8 // the stress model rejects active samples above this
    private val RESET_REASONS = mapOf(0 to "power-on / factory reset", 4 to "watchdog reset")
    /** SpO2 "simple" quadratic for Gen 4 hardware, from the app (open_oura docs/spo2-calibration.md). */
    private fun spo2FromR(r: Double) = (-13.4 * r * r - 5.1 * r + 105.2).coerceIn(85.0, 100.0)

    private val cache = HashMap<String, Pair<Any, Analysis>>() // key: (event stamp, profile)

    /**
     * Wall-clock time a `time_sync` event marks. Older syncs (this app before the time-sync fix, and the open_oura
     * CLI) put a scrambled token, `(now % 256) * 37 + 0xa5`, where the low byte of the unix time belongs, and the ring
     * echoes it; read raw, the anchor is up to 255 s off. Fixed syncs send the exact time in the same layout, so when
     * [estimate] (from the sync session's own capture time) is known, keep whichever reading is closer to it.
     */
    fun correctedSyncUnix(body: ByteArray, unix: Long, estimate: Double? = null): Long {
        if (body.size != 9 || !body.slice(4..7).all { it.toInt() == 0 }) return unix
        val low = (((body[0].toInt() and 0xff) - 0xA5) * 173) and 0xFF // 173 = 37^-1 mod 256
        val fixed = (unix and 0xFFL.inv()) or low.toLong()
        if (estimate == null) return if ((body[8].toInt() and 0xff) == 0xF6) fixed else unix // pre-fix syncs all ended in f6
        return if (abs(fixed - estimate) < abs(unix - estimate)) fixed else unix
    }

    /** ring timestamp (deciseconds) -> unix seconds, anchored on the nearest earlier time_sync. */
    class Clock(private val keys: LongArray, private val unix: DoubleArray) {
        fun toUnix(ts: Long): Double {
            var lo = 0; var hi = keys.size // bisect_right
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (keys[mid] <= ts) lo = mid + 1 else hi = mid }
            val i = maxOf(0, lo - 1)
            return unix[i] + (ts - keys[i]) / 10.0
        }

        companion object {
            fun from(syncs: List<Store.TimeSyncRow>): Clock? {
                val a = syncs.mapNotNull { s ->
                    if (s.body.size < 4) return@mapNotNull null
                    val est = s.captured - (s.newestInCapture - s.ringTs) / 10.0
                    s.ringTs to correctedSyncUnix(s.body, u32le(s.body, 0), est).toDouble()
                }
                return if (a.isEmpty()) null else Clock(a.map { it.first }.toLongArray(), a.map { it.second }.toDoubleArray())
            }
        }
    }

    fun analyze(store: Store, serial: String, zone: ZoneId = ZoneId.systemDefault()): Analysis {
        val profile = store.profile(serial)
        val stamp = store.eventStamp(serial) to profile // editing the profile recomputes calories and scores
        cache[serial]?.let { if (it.first == stamp) return it.second }
        val clock = Clock.from(store.timeSyncs(serial))
            ?: return Analysis(emptyList(), emptyList(), emptyList(), emptyList(), null).also { cache[serial] = stamp to it }
        val from = maxOf(0L, store.newestRingTs(serial) - WINDOW_DAYS * 86400 * 10)
        val result = compute(clock, zone, profile) { sink -> store.forEachEvent(serial, from) { tag, _, ts, body, _ -> sink(tag, ts, body) } }
        cache[serial] = stamp to result
        return result
    }

    /** Pure analysis over a stream of (tag, ring_ts, body); [source] calls the sink once per event in ring-time order. */
    fun compute(clock: Clock, zone: ZoneId, profile: Profile = Profile(), source: ((Int, Long, ByteArray) -> Unit) -> Unit): Analysis {
        val beats = TreeMap<Long, MutableList<Double>>() // 5-min bucket -> bpm (green IBI, good quality)
        val beats60 = TreeMap<Long, MutableList<Double>>() // fallback beat stream (ibi_and_amplitude)
        val rr = TreeMap<Long, MutableList<Double>>() // 5-min bucket -> squared successive differences
        // 30-s epoch -> [Σ bpm, beats, Σ squared successive differences, differences]: compact input for sleep staging
        val epochs = TreeMap<Long, DoubleArray>()
        fun addBeats(t: Long, ibis: List<Double>, valid: (Int) -> Boolean) {
            var at = t * 1000.0; var prev: Double? = null
            for (k in ibis.indices) {
                val ibi = ibis[k]
                if (ibi > 0) at += ibi
                val ok = valid(k) && ibi in 300.0..2000.0 && (prev == null || abs(ibi - prev) <= 0.25 * prev)
                if (ok) {
                    val e = epochs.getOrPut((at / 1000).toLong() / SleepStager.EPOCH_S) { DoubleArray(4) }
                    e[0] += 60000 / ibi; e[1] += 1
                    if (prev != null && abs(ibi - prev) <= 0.2 * prev) { e[2] += (ibi - prev) * (ibi - prev); e[3] += 1 }
                }
                prev = if (ok) ibi else null
            }
        }
        val hrvEv = ArrayList<Triple<Long, Double, Double>>() // (t, hr, rmssd) from the ring's 5-min hrv_event
        val temps = ArrayList<Pair<Long, Double>>() // probe-0 temperature, every reading
        val sleepTemps = ArrayList<Pair<Long, Double>>()
        val spo2Pct = ArrayList<Pair<Long, Double>>(); val spo2R = ArrayList<Pair<Long, Double>>()
        val phases = ArrayList<Triple<Long, String, List<String>>>()
        val met = TreeMap<Long, Double>() // minute -> MET
        val motion = TreeMap<Long, Int>() // t -> motion seconds in that ~30 s window
        val restless = ArrayList<Triple<Long, Int, Int>>() // motion_period: (t, readings at level 2-3, readings)
        val breath = ArrayList<Pair<Long, Double>>()
        val bedtimes = ArrayList<Pair<Long, Long>>()
        val alerts = ArrayList<Long>()
        val battery = ArrayList<BatterySample>()
        val reboots = ArrayList<Reboot>()
        val bleTimes = ArrayList<Long>()
        val logs = ArrayList<Pair<Long, String>>()
        var ringProfile: Profile? = null // the ring's stored user_information, unless it's the factory default
        var wdt = 0; var negT = 0

        fun nums(m: Map<String, Any?>, k: String) = (m[k] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }.orEmpty()

        source { tag, ts, body ->
            val t = clock.toUnix(ts).toLong()
            val m = Events.decode(tag, body) ?: return@source
            when (tag) {
                0x61 -> when (m["kind"]) {
                    "battery" -> battery += BatterySample(t, m["pct"] as Int)
                    "log" -> logs += t to (m["text"] as String)
                }
                0x43 -> (m["ascii"] as? String)?.let { txt ->
                    logs += t to txt
                    if (txt.startsWith("wdt ")) wdt++ else if (txt.startsWith("DHR_info:neg t")) negT++
                }
                0x45, 0x53 -> logs += t to "${Events.name(tag)}: [${m["state"]}] ${m["text"]}"
                0x41 -> {
                    val r = (m["reason"] as? Number)?.toInt()
                    reboots += Reboot(t, r, RESET_REASONS[r] ?: "reason $r")
                }
                0x5c -> {
                    val age = (m["age_years"] as Number).toInt(); val kg = (m["weight_kg"] as Number).toInt()
                    val sx = (m["sex_code"] as Number).toInt(); val cm = (m["height_cm"] as Number).toInt()
                    if (!Metabolic.isRingFactoryProfile(age, kg, sx, cm) && kg > 0)
                        ringProfile = Profile(kg.toDouble(), cm.toDouble(), LocalDate.now(zone).year - age, Sex.from(sx))
                }
                0x80 -> { // green IBI + quality: the cleanest beat stream
                    val ibis = nums(m, "ibi_ms"); val qs = nums(m, "quality")
                    val b = t / BUCKET * BUCKET
                    var prev: Double? = null
                    for (k in 0 until minOf(ibis.size, qs.size)) {
                        val ibi = ibis[k]
                        val good = qs[k] == 1.0 && ibi in 300.0..2000.0
                        if (good) {
                            beats.getOrPut(b) { ArrayList() }.add(60000 / ibi)
                            if (prev != null && abs(ibi - prev) <= 0.2 * prev) rr.getOrPut(b) { ArrayList() }.add((ibi - prev) * (ibi - prev))
                        }
                        prev = if (good) ibi else null
                    }
                    addBeats(t, ibis) { k -> qs.getOrNull(k) == 1.0 }
                }
                0x60 -> {
                    nums(m, "hr_bpm").filter { it in 30.0..200.0 }.takeIf { it.isNotEmpty() }?.let { beats60.getOrPut(t / BUCKET * BUCKET) { ArrayList() }.addAll(it) }
                    addBeats(t, nums(m, "ibi_ms")) { true }
                }
                0x5d -> {
                    val hr = nums(m, "hr_bpm"); val rm = nums(m, "rmssd_ms")
                    for (i in hr.indices) if (hr[i] > 0 || rm.getOrElse(i) { 0.0 } > 0) hrvEv += Triple(t + i * BUCKET, hr[i], rm.getOrElse(i) { 0.0 })
                }
                0x46 -> nums(m, "temps_c").firstOrNull()?.let { temps += t to it }
                0x69, 0x75 -> nums(m, "temps_c").filter { it >= WORN_TEMP_C }.forEach { sleepTemps += t to it }
                0x6f, 0x70 -> nums(m, "spo2_percent").filter { it > 50 && it <= 100 }.forEach { spo2Pct += t to it }
                0x8b -> nums(m, "r").filter { it > 0.2 && it < 2.0 }.forEach { spo2R += t to it }
                0x4b, 0x4e, 0x5a -> (m["phases"] as? List<*>)?.let { p -> phases += Triple(t, Events.name(tag), p.map { it.toString() }) }
                0x50 -> { // activity_information: one MET value per minute, ending at the event time
                    val mets = nums(m, "met")
                    mets.forEachIndexed { i, v -> met[(t - (mets.size - 1 - i) * 60L) / 60 * 60] = v }
                }
                0x47 -> (m["motion_seconds"] as? Number)?.let { motion[t] = it.toInt() }
                0x6b -> (m["motion_levels"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }?.takeIf { it.isNotEmpty() }?.let { lv -> restless += Triple(t, lv.count { it >= 2 }, lv.size) }
                0x6a -> (m["breath"] as? Number)?.toDouble()?.takeIf { it in 5.0..40.0 }?.let { breath += t to it }
                0x76 -> {
                    val s = (m["bedtime_start_ds"] as Number).toLong(); val e = (m["bedtime_end_ds"] as Number).toLong()
                    if (e > s) bedtimes += clock.toUnix(s).toLong() to clock.toUnix(e).toLong()
                }
                0x56 -> alerts += t
                0x5b -> if (body.isNotEmpty() && body[0].toInt() == 2) bleTimes += t
            }
        }

        // ---- the body profile in effect: yours, else the ring's stored one, else defaults ----
        val rp = ringProfile
        val weight = profile.weightKg ?: rp?.weightKg ?: Metabolic.FALLBACK_WEIGHT_KG
        val age = profile.ageYears(LocalDate.now(zone)) ?: rp?.ageYears(LocalDate.now(zone))
        val sex = if (profile.sex != Sex.UNSPECIFIED) profile.sex else rp?.sex ?: Sex.UNSPECIFIED
        val bmr = Metabolic.bmrSchofield(age ?: Metabolic.FALLBACK_AGE, sex, weight)
        val used = UsedProfile(
            weight, if (profile.weightKg != null) "you" else if (rp?.weightKg != null) "ring" else "default",
            age, if (profile.birthYear != null) "you" else if (rp != null) "ring" else "default",
            sex, if (profile.sex != Sex.UNSPECIFIED) "you" else if (rp != null && rp.sex != Sex.UNSPECIFIED) "ring" else "default",
            bmr.roundToInt(), age?.let { Metabolic.vo2maxJackson(it, sex, weight) }?.let { Math.round(it * 10) / 10.0 },
        )

        fun dateOf(t: Long) = Instant.ofEpochSecond(t).atZone(zone).toLocalDate().toString()
        fun median(a: List<Double>): Double? { if (a.isEmpty()) return null; val s = a.sorted(); val n = s.size; return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2 }
        fun mean(a: List<Double>): Double? = if (a.isEmpty()) null else a.average()
        fun r(v: Double?, nd: Int = 1): Double? = v?.let { val p = Math.pow(10.0, nd.toDouble()); Math.round(it * p) / p }
        fun <V> TreeMap<Long, V>.within(a: Long, b: Long) = subMap(a, true, b, false)

        // ---- wear: probe-0 temperature at/above WORN_TEMP_C ----
        val worn = TreeMap<Long, Boolean>().apply { temps.forEach { (t, c) -> put(t, c >= WORN_TEMP_C) } }
        fun isWorn(t: Long) = worn.floorEntry(t)?.let { t - it.key <= 900 && it.value } ?: false
        val hrBuckets = TreeMap<Long, Double>().apply {
            beats.forEach { (b, v) -> if (v.size >= 3 && isWorn(b + BUCKET / 2)) median(v)?.let { put(b, it) } }
        }
        val rmssdBuckets = TreeMap<Long, Double>().apply { rr.forEach { (b, v) -> if (v.size >= 8) put(b, sqrt(v.average())) } }

        // ---- nights: merge overlapping bedtime reports, keep main sleeps (≥ 3 h) ----
        val windows = ArrayList<LongArray>()
        for ((s, e) in bedtimes.sortedBy { it.first }) {
            val last = windows.lastOrNull()
            if (last != null && s <= last[1]) last[1] = maxOf(last[1], e) else windows += longArrayOf(s, e)
        }
        val sleepWindows = windows.filter { it[1] - it[0] >= MIN_NIGHT_S }
        fun inSleep(t: Long) = windows.any { t >= it[0] && t < it[1] }

        // ---- per-day activity ----
        val metByDay = met.entries.groupBy({ dateOf(it.key) }, { it.value })
        fun activeMinutes(d: String): Double? = metByDay[d]?.takeIf { it.size >= 60 }?.count { it >= 3.0 }?.toDouble()

        data class RawNight(val w: LongArray, val date: String, val sleep: Scores.SleepInput, val stages: Map<String, Double>?,
                            val lowest: Double?, val avg: Double?, val hrv: Double?, val recovery: Double?, val temp: Double?,
                            val spo2: Double?, val breath: Double?, val stagesSource: String?, val hyp: List<String>?)

        val raw = sleepWindows.map { w ->
            val (s, e) = w[0] to w[1]
            val inBed = (e - s) / 60.0
            // hypnogram: the source tag with the most 30 s epochs around the window
            val ringHyp = phases.filter { it.first in (s - 1800)..(e + 1800) }.groupBy { it.second }
                .mapValues { (_, v) -> v.flatMap { it.third } }.values.maxByOrNull { it.size }.orEmpty()
            // no hypnogram from the ring (Ring 4 leaves staging to the phone): stage it ourselves
            val ourHyp = if (ringHyp.size >= 20) null else stageNight(s, e, epochs, motion, hrvEv)
            val hyp = ourHyp ?: ringHyp
            val stagesSource = when { ringHyp.size >= 20 -> "ring"; ourHyp != null -> "estimated"; else -> null }
            val total: Double; val latency: Double?; val waso: Double; var stages: Map<String, Double>? = null
            if (hyp.size >= 20) {
                stages = hyp.groupingBy { it }.eachCount().mapValues { it.value * 0.5 }
                val onset = (0..hyp.size - 3).firstOrNull { i -> (0 until 3).all { hyp[i + it] != "awake" } }
                latency = onset?.let { it * 0.5 }
                val after = if (onset == null) emptyList() else hyp.drop(onset)
                total = hyp.count { it != "awake" } * 0.5
                waso = if (after.isEmpty()) 0.0 else after.count { it == "awake" }.toDouble() / after.size
            } else {
                // movement-based estimate: a ~30 s window with ≥ 5 s of motion counts as awake/restless
                val mv = motion.within(s, e).values.toList()
                val awake = mv.map { it >= 5 }
                val run = 20 // 10 minutes still = asleep
                val onset = (0..awake.size - run).firstOrNull { i -> (0 until run).none { awake[i + it] } }
                latency = onset?.let { it * inBed / maxOf(1, awake.size) }
                val after = if (onset == null) emptyList() else awake.drop(onset)
                waso = if (after.isEmpty()) 0.0 else after.count { it }.toDouble() / after.size
                total = if (awake.isEmpty()) inBed else (inBed - (latency ?: 0.0)) * (1 - waso)
            }
            // overnight heart rate: the ring's 5-min hrv_event, else our beat buckets
            val hrSeries = hrvEv.filter { it.first in s until e && it.second > 0 }.map { it.first to it.second }
                .ifEmpty { hrBuckets.within(s, e).map { it.key to it.value } }
                .ifEmpty { beats60.within(s, e).mapNotNull { (b, v) -> median(v)?.let { b to it } } }
            val smooth = hrSeries.indices.map { i -> hrSeries.subList(maxOf(0, i - 1), minOf(hrSeries.size, i + 2)).map { it.second }.average() }
            val iMin = smooth.indices.minByOrNull { smooth[it] }
            val rmssd = hrvEv.filter { it.first in s until e && it.third > 0 }.map { it.third }
            val nightTemps = temps.filter { it.first in s until e && it.second >= WORN_TEMP_C }.map { it.second } +
                sleepTemps.filter { it.first in s until e }.map { it.second }
            val ox = spo2Pct.filter { it.first in s until e }.map { it.second }
                .ifEmpty { spo2R.filter { it.first in s until e }.map { spo2FromR(it.second) } }
            val mid = Instant.ofEpochSecond((s + e) / 2).atZone(zone).toLocalTime()
            // Oura's nightly temperature: smallest of the calm 30-sample window maxima (ignores warm-up and edge spikes); plain mean as fallback
            val nightTemp = NightlyTemperature.compute(temps.filter { it.first in s until e && it.second >= WORN_TEMP_C }.map { it.second }) ?: mean(nightTemps)
            val rs = restless.filter { it.first in s until e }
            val restlessFrac = if (rs.sumOf { it.third } >= 20) rs.sumOf { it.second }.toDouble() / rs.sumOf { it.third } else null
            RawNight(
                w, dateOf(e),
                Scores.SleepInput(total, inBed, latency, waso, stages?.get("rem") ?: stages?.let { 0.0 }, stages?.get("deep") ?: stages?.let { 0.0 },
                    mid.hour + mid.minute / 60.0, stagesEstimated = stagesSource == "estimated", restlessFraction = restlessFrac),
                stages, iMin?.let { smooth[it] }, mean(hrSeries.map { it.second }),
                mean(rmssd) ?: median(rmssdBuckets.within(s, e).values.toList()),
                iMin?.let { (e - hrSeries[it].first) / 3600.0 },
                nightTemp, mean(ox), mean(breath.filter { it.first in s until e }.map { it.second }),
                stagesSource, hyp.takeIf { it.size >= 20 },
            )
        }

        val nights = raw.mapIndexed { i, n ->
            val prior = raw.subList(maxOf(0, i - Scores.BASELINE_NIGHTS), i)
            val sleepScore = Scores.sleepScore(n.sleep.copy(ageYears = age))
            // baselines use the ring's own annealing moving average (usable in the first days), not a plain mean
            val tempBase = EmaBaseline.of(prior.map { it.temp }, scale = 100.0, minSamples = 3)
            val tempDev = if (n.temp != null && tempBase != null) n.temp - tempBase else null
            val recent = raw.subList(maxOf(0, i - 2), i + 1).mapNotNull { it.hrv }
            val hrvBase = EmaBaseline.of(raw.subList(0, maxOf(0, i - 2)).map { it.hrv }, minSamples = 2)
            val date = LocalDate.parse(n.date)
            val actDays = (1..14).mapNotNull { activeMinutes(date.minusDays(it.toLong()).toString()) }
            val needS = (Metabolic.sleepNeedHours(age) * 3600).toInt()
            val lastNights = raw.subList(maxOf(0, i - 13), i + 1).reversed() // newest first, as ecore expects
            val debt = SleepDebt.compute(lastNights.map { (it.sleep.totalSleepMin * 60).toInt() }, lastNights.map { needS })
            val debtH = debt.takeIf { it.valid }?.debtHours
            val readiness = Scores.readiness(Scores.ReadinessInput(
                lowestHr = n.lowest, rhrBaseline = EmaBaseline.of(prior.map { it.lowest }, minSamples = 2),
                sleepScore = sleepScore.value,
                hrvRecent = mean(recent), hrvBaseline = hrvBase,
                tempDeviation = tempDev,
                sleepBalanceH = raw.subList(maxOf(0, i - 13), i + 1).map { it.sleep.totalSleepMin / 60 }.average(),
                prevDayActiveMin = activeMinutes(date.minusDays(1).toString()),
                recoveryIndexH = n.recovery,
                activityBalanceMin = actDays.takeIf { it.size >= 2 }?.average(),
                nightsOfHistory = i,
                sleepNeedH = Metabolic.sleepNeedHours(age),
                sleepDebtH = debtH,
            ))
            Night(
                n.date, n.w[0], n.w[1], r(n.sleep.inBedMin, 0)!!, r(n.sleep.totalSleepMin, 0)!!,
                r(if (n.sleep.inBedMin > 0) 100 * n.sleep.totalSleepMin / n.sleep.inBedMin else 0.0, 0)!!, r(n.sleep.latencyMin, 0),
                n.stages, r(n.lowest, 0), r(n.avg, 0), r(n.hrv, 0), r(n.recovery), r(n.temp, 2), r(tempDev, 2), r(n.spo2), r(n.breath),
                sleepScore, readiness, n.stagesSource, n.hyp, sleepDebtH = debtH?.let { Math.round(it * 10) / 10.0 },
            )
        }

        // ---- daytime stress: awake, sedentary 5-min HRV vs your daytime baseline ----
        val nightHrv = nights.mapNotNull { it.hrv }
        val dayRmssd = TreeMap(rmssdBuckets.filter { (b, _) -> !inSleep(b) && isWorn(b + BUCKET / 2) })
        val stress = dayRmssd.entries.groupBy { dateOf(it.key) }.mapValues { (d, entries) ->
            val dayEnd = LocalDate.parse(d).plusDays(1).atStartOfDay(zone).toEpochSecond()
            val base = median(dayRmssd.subMap(dayEnd - 14 * 86400, dayEnd).values.toList())
            val nb = median(nightHrv.takeLast(Scores.BASELINE_NIGHTS)) ?: base
            val half = nb?.let { Scores.neutralHalf(it) }
            val samples = if (base == null || half == null) emptyList() else entries.mapNotNull { (b, v) ->
                val mets = met.within(b, b + BUCKET).values
                if (mets.isNotEmpty() && mets.average() > STRESS_MAX_MET) return@mapNotNull null
                val intensity = v - base
                Scores.StressSample(b, r(v)!!, r(intensity)!!, Scores.zoneOf(intensity, half))
            }
            fun minutes(z: Scores.Zone) = samples.count { it.zone == z } * 5
            val st = minutes(Scores.Zone.STRESSED); val rs = minutes(Scores.Zone.RESTORED)
            StressDay(d, st, minutes(Scores.Zone.ENGAGED), minutes(Scores.Zone.RELAXED), rs, Scores.stressSummary(st, rs), samples, r(base), half)
        }

        // ---- per-day summaries + intraday series ----
        val nightByDate = nights.associateBy { it.date }
        val dates = (hrBuckets.keys.map { dateOf(it) } + met.keys.map { dateOf(it) } + temps.map { dateOf(it.first) } + nights.map { it.date }).toSortedSet()
        val intraday = HashMap<String, Intraday>()
        val days = dates.map { d ->
            val start = LocalDate.parse(d).atStartOfDay(zone).toEpochSecond(); val end = start + 86400
            val hr = hrBuckets.within(start, end)
            val dayHr = hr.filterKeys { !inSleep(it) }.values.toList()
            val dayTemps = temps.filter { it.first in start until end && it.second >= WORN_TEMP_C }
            val mets = met.within(start, end)
            val metsWorn = mets.filterKeys { isWorn(it) }.values
            val wornMin = temps.zipWithNext().filter { (a, b) -> a.first in start until end && a.second >= WORN_TEMP_C }
                .sumOf { (a, b) -> minOf(b.first - a.first, 120L) } / 60
            val n = nightByDate[d]
            val dayRr = rmssdBuckets.within(start, end).filterKeys { !inSleep(it) }.values.toList()
            val (resting, rsrc) = when {
                n?.lowestHr != null -> n.lowestHr to "sleep"
                dayHr.size >= 6 -> dayHr.sorted().take(3).average().roundToInt().toDouble() to "daytime"
                else -> null to null
            }
            val (hrv, hsrc) = when {
                n?.hrv != null -> n.hrv to "sleep"
                dayRr.size >= 3 -> r(median(dayRr)) to "daytime"
                else -> null to null
            }
            intraday[d] = Intraday(
                hr = hr.map { (b, v) -> b + BUCKET / 2 to r(v)!! },
                temp = dayTemps.groupBy { it.first / BUCKET * BUCKET }.toSortedMap().map { (b, v) -> b + BUCKET / 2 to r(median(v.map { it.second }), 2)!! },
                met = mets.map { it.key to it.value },
                alerts = alerts.filter { it in start until end },
            )
            DaySummary(
                date = d, restingHr = resting, restingSrc = rsrc, avgHr = r(mean(dayHr)), hrv = hrv, hrvSrc = hsrc,
                temp = n?.temp ?: r(median(dayTemps.map { it.second }), 2), spo2 = n?.spo2, sleepMin = n?.stages,
                activeKcal = if (mets.isEmpty()) null else mets.values.sumOf { Metabolic.activeKcalPerMinute(it, weight) }.roundToInt(),
                totalKcal = if (mets.isEmpty()) null else {
                    val dayFrac = ((minOf(end, System.currentTimeMillis() / 1000) - start) / 86400.0).coerceIn(0.0, 1.0)
                    (bmr * dayFrac + mets.values.sumOf { Metabolic.activeKcalPerMinute(it, weight) }).roundToInt()
                },
                wornMin = wornMin.toInt(), bleConnections = bleTimes.count { it in start until end },
                activeMin = mapOf("low" to mets.values.count { it >= 1.5 && it < 3 }, "medium" to mets.values.count { it >= 3 && it < 6 }, "high" to mets.values.count { it >= 6 }),
                inactiveMin = metsWorn.count { it < 1.5 }, moveAlerts = alerts.count { it in start until end }, metMinutes = mets.size,
            )
        }

        // ---- activity sessions and the estimated Activity score ----
        val workouts = intraday.mapValues { (_, d) -> Workouts.detect(d.met, d.hr, weight) }.filterValues { it.isNotEmpty() }
        val activity = HashMap<String, Score>()
        for (d in days) {
            val intra = intraday[d.date] ?: continue
            if (intra.met.size < 120) continue
            val week = (0..6).flatMap { workouts[LocalDate.parse(d.date).minusDays(it.toLong()).toString()].orEmpty() }
            activity[d.date] = ActivityScore.score(intra.met, d.activeKcal, ActivityScore.defaultTargetKcal(bmr), d.inactiveMin, week)
        }

        val sortedBattery = battery.sortedBy { it.ts }
        val drain = batteryDrain(sortedBattery)
        return Analysis(days, sortedBattery, reboots, warnings(reboots, negT, drain, bleTimes), drain, nights, stress, intraday, logs, used, workouts = workouts, activity = activity)
    }

    private fun warnings(reboots: List<Reboot>, negT: Int, drain: Double?, bleTimes: List<Long>): List<HealthWarning> {
        val w = ArrayList<HealthWarning>()
        val wd = reboots.filter { it.reason == 4 }
        if (wd.isNotEmpty()) w += HealthWarning("bad", "${wd.size} watchdog reset(s)",
            "The firmware hung and restarted itself. Isolated resets during pairing are harmless; repeated ones while worn are worth reporting.", devOnly = true)
        if (negT > 0) w += HealthWarning("warn", "Daytime HR scheduler saw a negative time step ×$negT",
            "Appears after the ring clock is changed by a time sync; HR samples around it may be skipped.", devOnly = true)
        if (drain != null) w += HealthWarning(
            if (drain > 2) "bad" else if (drain > 1) "warn" else "good",
            "Battery draining ${"%.2f".format(drain)}%/h (~${(100 / drain).roundToInt()} h per charge)",
            "From the ring's own battery records. A Ring 4 normally lasts 5–8 days (~0.5–0.8%/h). Every Bluetooth connection costs power.")
        val recent = if (bleTimes.isEmpty()) emptyList() else bleTimes.filter { it >= bleTimes.last() - 6 * 3600 }
        if (recent.size >= 2) {
            val span = (recent.last() - recent.first()) / 3600.0
            if (span > 0.5) {
                val perH = recent.size / span
                w += HealthWarning(if (perH > 6) "bad" else "good", "${"%.1f".format(perH)} Bluetooth connections per hour",
                    "${recent.size} connections recorded by the ring over the last ${"%.1f".format(span)} h. The Oura app connects only a few times a day.", devOnly = true)
            }
        }
        return w
    }

    /**
     * Build the stager's per-epoch inputs for one night: HR and ~5-minute RMSSD from beats, falling back to the
     * ring's 5-min hrv_event where beats are sparse; movement from motion_event.
     */
    private fun stageNight(s: Long, e: Long, epochs: TreeMap<Long, DoubleArray>, motion: TreeMap<Long, Int>,
                           hrvEv: List<Triple<Long, Double, Double>>): List<String>? {
        val ep = SleepStager.EPOCH_S
        val n = ((e - s + ep - 1) / ep).toInt()
        if (n < 60) return null
        val k0 = s / ep
        val agg = Array(n) { epochs[k0 + it] }
        val hr = DoubleArray(n) { i -> agg[i]?.takeIf { it[1] >= 2 }?.let { it[0] / it[1] } ?: Double.NaN }
        val rm = DoubleArray(n) { i ->
            var sq = 0.0; var c = 0.0
            for (j in maxOf(0, i - 5)..minOf(n - 1, i + 5)) agg[j]?.let { sq += it[2]; c += it[3] }
            if (c >= 8) sqrt(sq / c) else Double.NaN
        }
        // sparse beats: fill from the ring's own 5-minute HR / HRV
        val five = hrvEv.filter { it.first in (s - 300) until (e + 300) }
        if (five.isNotEmpty()) for (i in 0 until n) {
            val t = s + i * ep + ep / 2
            val near = five.minByOrNull { abs(it.first + 150 - t) }!!
            if (abs(near.first + 150 - t) > 450) continue
            if (hr[i].isNaN() && near.second > 0) hr[i] = near.second
            if (rm[i].isNaN() && near.third > 0) rm[i] = near.third
        }
        val mo = IntArray(n) { -1 }
        for ((t, v) in motion.subMap(s, true, e, false)) { val i = ((t - s) / ep).toInt(); if (i in 0 until n) mo[i] = maxOf(mo[i], v) }
        return SleepStager.stage(hr, rm, mo)
    }

    /** %/hour over the most recent discharge run (ring-recorded samples), or null. */
    fun batteryDrain(samples: List<BatterySample>): Double? {
        val run = ArrayList<BatterySample>()
        for (s in samples.asReversed()) {
            if (run.isNotEmpty() && s.pct < run.last().pct) break // going back in time the level dropped: it was charging here
            run += s
        }
        if (run.size < 2) return null
        val hours = (run.first().ts - run.last().ts) / 3600.0
        val drop = run.last().pct - run.first().pct
        return if (hours >= 1 && drop > 0) Math.round(drop / hours * 100) / 100.0 else null
    }

    /** Convenience for callers that only need the per-day summaries. */
    fun daily(store: Store, serial: String): List<DaySummary> = analyze(store, serial).days
}
