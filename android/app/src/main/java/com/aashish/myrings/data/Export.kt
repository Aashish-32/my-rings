package com.aashish.myrings.data

/** Plain CSV exports of what the app has worked out, so the data can leave the phone in a format any tool can open. */
object Export {
    private fun esc(v: Any?): String {
        val s = when (v) { null -> ""; is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v); else -> v.toString() }
        return if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun row(vararg v: Any?) = v.joinToString(",") { esc(it) }

    /** One row per day: heart rate, HRV, temperature, SpO2, calories and activity minutes. */
    fun dailyCsv(a: Analysis): String = buildString {
        appendLine("date,resting_hr_bpm,resting_hr_source,avg_hr_bpm,hrv_ms,hrv_source,skin_temp_c,spo2_pct,active_kcal,total_kcal,worn_min,inactive_min,low_activity_min,medium_activity_min,high_activity_min,move_reminders")
        for (d in a.days) appendLine(row(
            d.date, d.restingHr, d.restingSrc, d.avgHr, d.hrv, d.hrvSrc, d.temp, d.spo2, d.activeKcal, d.totalKcal, d.wornMin, d.inactiveMin,
            d.activeMin["low"], d.activeMin["medium"], d.activeMin["high"], d.moveAlerts,
        ))
    }

    /** One row per recorded night, with the scores and their contributors. */
    fun nightsCsv(a: Analysis): String = buildString {
        appendLine("date,sleep_start_unix,sleep_end_unix,time_in_bed_min,total_sleep_min,efficiency_pct,latency_min,deep_min,light_min,rem_min,awake_min,lowest_hr_bpm,avg_hr_bpm,hrv_ms,recovery_index_h,temp_c,temp_deviation_c,spo2_pct,breathing_rate,sleep_score,readiness_score,sleep_debt_h,stages_source")
        for (n in a.nights) appendLine(row(
            n.date, n.start, n.end, n.inBedMin, n.totalSleepMin, n.efficiency, n.latencyMin,
            n.stages?.get("deep"), n.stages?.get("light"), n.stages?.get("rem"), n.stages?.get("awake"),
            n.lowestHr, n.avgHr, n.hrv, n.recoveryIndexH, n.temp, n.tempDeviation, n.spo2, n.breathRate,
            n.sleepScore.value, n.readiness.value, n.sleepDebtH, n.stagesSource,
        ))
    }

    /** One row per detected activity session. */
    fun workoutsCsv(a: Analysis): String = buildString {
        appendLine("date,start_unix,end_unix,duration_min,intensity,avg_met,peak_met,avg_hr_bpm,max_hr_bpm,kcal")
        for ((date, list) in a.workouts.toSortedMap()) for (w in list) appendLine(row(
            date, w.start, w.end, w.durationMin, w.intensity, w.avgMet, w.peakMet, w.avgHr, w.maxHr, w.kcal,
        ))
    }

    fun fileName(kind: String, serial: String) = "myrings-$kind-$serial.csv"
}
