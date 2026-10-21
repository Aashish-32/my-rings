package com.aashish.myrings.protocol

import kotlin.math.roundToInt

/** History events + decoders. Ported from open_oura `oura-protocol::events`. Unknown bodies stay raw. */
class RingEvent(
    val tag: Int,
    val name: String,
    /** Ring timestamp in deciseconds (boot-relative). */
    val timestamp: Long,
    val body: ByteArray,
    val decoded: Map<String, Any?>?,
) {
    companion object {
        fun fromPacket(p: Packet): RingEvent {
            val b = p.payload
            val ts = if (b.size >= 4) u32le(b, 0) else 0L
            val body = if (b.size > 4) b.copyOfRange(4, b.size) else ByteArray(0)
            return RingEvent(p.tag, Events.name(p.tag), ts, body, Events.decode(p.tag, body))
        }
    }
}

internal fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
internal fun u16le(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
internal fun u32le(b: ByteArray, i: Int): Long =
    (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

object Events {
    fun name(tag: Int): String = NAMES[tag] ?: "unknown"

    fun decode(tag: Int, body: ByteArray): Map<String, Any?>? = try {
        when (tag) {
            0x41 -> ringStart(body)
            0x42 -> timeSync(body)
            0x5c -> userInfo(body)
            0x43 -> ascii(body)
            0x61 -> debugData(body)
            0x45, 0x53 -> stateText(body)
            0x46, 0x69, 0x75 -> temperatures(body)
            0x5d -> hrv(body)
            0x80 -> greenIbiQuality(body)
            0x6f -> spo2(body)
            0x4b, 0x4e, 0x5a -> sleepPhases(body)
            0x76 -> bedtimePeriod(body)
            0x6a -> sleepPeriodInfo(body)
            0x85 -> rtcBeacon(body)
            0x60 -> ibiAmplitude(body)
            0x50 -> activityInfo(body)
            0x47 -> motion(body)
            0x6c -> featureSession(body)
            0x5b -> telemetry(body, "ble_connection_ind")
            0x79 -> telemetry(body, "self_test_data")
            0x82 -> telemetry(body, "scan_start")
            0x83 -> telemetry(body, "scan_end")
            0x56 -> body.firstOrNull()?.let { mapOf("alert_type" to (it.toInt() and 0xff)) }
            0x6b -> motionPeriod(body)
            0x59 -> u16Samples(body, "ambient")
            0x74 -> u16Samples(body, "intensity")
            0x72 -> sleepAcmPeriod(body)
            0x8b -> spo2RPi(body)
            0x86 -> aohr(body)
            0x84 -> ambient(body)
            else -> null
        }
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun r2(v: Double) = (v * 100.0).roundToInt() / 100.0
    private fun r4(v: Double) = (v * 10000.0).roundToInt() / 10000.0
    private fun v3(b: ByteArray, from: Int) = (from until from + 3).joinToString(".") { u8(b, it).toString() }

    private fun ringStart(b: ByteArray): Map<String, Any?>? {
        if (b.size < 14) return null
        return mapOf(
            "reason" to u32le(b, 0), "unknown_byte_4" to u8(b, 4),
            "firmware_version" to v3(b, 5), "bootloader_version" to v3(b, 8), "api_version" to v3(b, 11),
        )
    }

    private fun timeSync(b: ByteArray): Map<String, Any?>? {
        if (b.size < 4) return null
        val m = mutableMapOf<String, Any?>("unix_time" to u32le(b, 0))
        if (b.size > 4 && String(b, 4, b.size - 4) == "phone") m["source"] = "phone"
        // echo of a host 0x12 sync: u64 unix then the UTC offset in signed half-hours
        else if (b.size == 9 && (4..7).all { b[it].toInt() == 0 }) m["tz_half_hours"] = b[8].toInt()
        return m
    }

    private fun userInfo(b: ByteArray): Map<String, Any?>? {
        if (b.size < 4) return null
        val sex = when (u8(b, 2)) { 0 -> "male"; 1 -> "female"; else -> "unspecified" }
        return mapOf("age_years" to u8(b, 0), "weight_kg" to u8(b, 1), "sex_code" to u8(b, 2), "sex" to sex, "height_cm" to u8(b, 3), "_status" to "inferred")
    }

    private fun ascii(b: ByteArray): Map<String, Any?>? {
        val t = String(b, Charsets.UTF_8).trimEnd('\u0000').trim()
        return if (t.isEmpty()) null else mapOf("ascii" to t)
    }

    /**
     * debug_data (0x61). The upstream decoder treats any printable-looking body as ASCII, so a battery record
     * with zero voltage bytes (`24 5e 00 00 00`) came out as the string "$^". Subtype 0x24 is a battery record
     * (percent, voltage mV); other bodies are firmware log lines, optionally with a leading level byte.
     */
    private fun debugData(b: ByteArray): Map<String, Any?>? {
        if (b.isEmpty()) return null
        val sub = u8(b, 0)
        if (sub == 0x24 && b.size >= 4) {
            val mv = u8(b, 2) or (u8(b, 3) shl 8)
            return mapOf("kind" to "battery", "pct" to u8(b, 1), "voltage_mv" to (if (mv == 0) null else mv))
        }
        var text = if (sub < 0x20) b.copyOfRange(1, b.size) else b
        var end = text.size
        while (end > 0 && text[end - 1].toInt() == 0) end--
        text = text.copyOf(end)
        val printable = text.all { it.toInt() in 0x20..0x7e }
        if (printable && text.count { it.toInt().toChar().isLetterOrDigit() } >= 3) {
            return mapOf("kind" to "log", "text" to String(text, Charsets.US_ASCII).trim())
        }
        return null
    }

    private fun stateText(b: ByteArray): Map<String, Any?>? {
        if (b.isEmpty()) return null
        val t = String(b, 1, b.size - 1, Charsets.UTF_8).trimEnd('\u0000').trim()
        return mapOf("state" to u8(b, 0), "text" to t)
    }

    private fun temperatures(b: ByteArray): Map<String, Any?>? {
        if (b.isEmpty() || b.size % 2 != 0) return null
        val temps = ArrayList<Double>()
        for (i in b.indices step 2) {
            val centi = ((u8(b, i + 1) shl 8) or u8(b, i)).toShort().toInt()
            val c = centi / 100.0
            if (c < -40.0 || c > 85.0) return null
            temps.add(r2(c))
        }
        return mapOf("temps_c" to temps)
    }

    private fun hrv(b: ByteArray): Map<String, Any?>? {
        if (b.isEmpty() || b.size % 2 != 0) return null
        val hr = ArrayList<Int>()
        val rm = ArrayList<Int>()
        for (i in b.indices step 2) { hr.add(u8(b, i)); rm.add(u8(b, i + 1)) }
        return mapOf("hr_bpm" to hr, "rmssd_ms" to rm, "interval_min" to 5)
    }

    private fun greenIbiQuality(b: ByteArray): Map<String, Any?>? {
        if (b.size < 2) return null
        val ibis = ArrayList<Int>(); val qs = ArrayList<Int>(); val hr = ArrayList<Int>()
        var i = 0
        while (i + 1 < b.size) {
            val ibi = (u8(b, i + 1) and 7) or (u8(b, i) shl 3)
            val q = (u8(b, i + 1) shr 3) and 3
            if (q == 1 && ibi in 300..2000) hr.add(60000 / ibi)
            ibis.add(ibi); qs.add(q); i += 2
        }
        return mapOf("ibi_ms" to ibis, "quality" to qs, "hr_bpm" to hr)
    }

    private fun spo2(b: ByteArray): Map<String, Any?>? {
        if (b.size < 2) return null
        var end = b.size
        if (u8(b, end - 1) == 0xff) end--
        if (end <= 1) return null
        return mapOf("spo2_percent" to (1 until end).map { u8(b, it) })
    }

    private val PHASE = listOf("deep", "light", "rem", "awake")
    private fun sleepPhases(b: ByteArray): Map<String, Any?>? {
        if (b.size < 2) return null
        val ph = ArrayList<String>()
        for (k in 1 until b.size) for (shift in intArrayOf(6, 4, 2, 0)) ph.add(PHASE[(u8(b, k) shr shift) and 3])
        return mapOf("header" to u8(b, 0), "phases" to ph)
    }

    private fun bedtimePeriod(b: ByteArray): Map<String, Any?>? {
        if (b.size < 8) return null
        val s = u32le(b, 0); val e = u32le(b, 4)
        return mapOf("bedtime_start_ds" to s, "bedtime_end_ds" to e, "duration_hours" to r2(maxOf(0L, e - s) / 10.0 / 3600.0))
    }

    private fun sleepPeriodInfo(b: ByteArray): Map<String, Any?>? {
        if (b.size < 10) return null
        val motion = u8(b, 6); val state = b[7].toInt()
        if (motion >= 120 || state !in 0..2) return null
        return mapOf(
            "average_hr" to r4(u8(b, 0) * 0.5), "hr_trend" to r4(b[1].toInt() * 0.0625),
            "mzci" to r4(u8(b, 2) * 0.0625), "dzci" to r4(u8(b, 3) * 0.0625),
            "breath" to r4(u8(b, 4) / 8.0), "breath_v" to r4(u8(b, 5) / 8.0),
            "motion_count" to motion, "sleep_state" to state, "cv" to r4(u16le(b, 8) / 65536.0),
        )
    }

    private fun rtcBeacon(b: ByteArray): Map<String, Any?>? {
        if (b.size < 10) return null
        return mapOf("unix_time" to u32le(b, 0), "trailer" to u16le(b, 8))
    }

    private fun ibiAmplitude(b: ByteArray): Map<String, Any?>? {
        if (b.size != 14) return null
        fun x(i: Int) = u8(b, i)
        val ibi = intArrayOf(
            (x(6) and 1) or (x(0) shl 3) or ((x(12) shr 5) and 6),
            (x(7) and 1) or (x(1) shl 3) or ((x(12) shr 3) and 6),
            (x(8) and 1) or (x(2) shl 3) or ((x(12) shr 1) and 6),
            (x(9) and 1) or (x(3) shl 3) or ((x(12) and 3) shl 1),
            (x(10) and 1) or (x(4) shl 3) or ((x(13) shr 5) and 6),
            (x(11) and 1) or (x(5) shl 3) or ((x(13) shr 3) and 6),
        )
        val shift = if ((x(13) and 0x0f) == 7) 0 else (x(13) and 0x0f) + 1
        val amp = (0 until 6).map { (x(6 + it) shr 1) shl shift }
        val hr = ibi.filter { it in 300..2000 }.map { 60000 / it }
        return mapOf("ibi_ms" to ibi.toList(), "amplitude" to amp, "hr_bpm" to hr)
    }

    private fun activityInfo(b: ByteArray): Map<String, Any?>? {
        if (b.isEmpty()) return null
        val met = (1 until b.size).map {
            val v = u8(b, it)
            r2(if (v < 0x80) v * 0.1 else 12.8 + (v - 128) * 0.2)
        }
        return mapOf("state" to u8(b, 0), "met" to met)
    }

    private fun motion(b: ByteArray): Map<String, Any?>? {
        if (b.size < 4) return null
        val m = mutableMapOf<String, Any?>(
            "orientation" to (u8(b, 0) shr 5), "motion_seconds" to (u8(b, 0) and 0x1f),
            "avg_x" to b[1].toInt() * 8, "avg_y" to b[2].toInt() * 8, "avg_z" to b[3].toInt() * 8,
        )
        if (b.size >= 5) { if (u8(b, 4) and 0x40 != 0) return null; m["low_intensity"] = u8(b, 4) and 0x3f }
        if (b.size >= 6) { if (u8(b, 5) and 0x40 != 0) return null; m["high_intensity"] = u8(b, 5) and 0x3f }
        return m
    }

    /** feature_session (0x6c): [feature_id][session_status][optional u16 value]. */
    private fun featureSession(b: ByteArray): Map<String, Any?>? {
        if (b.size < 2) return null
        val m = mutableMapOf<String, Any?>("feature_id" to u8(b, 0), "session_status" to u8(b, 1))
        if (b.size >= 4) m["value"] = u16le(b, 2)
        return m
    }

    /** BLE/radio telemetry and self-test events: layout is device-internal, so keep the sub-type and raw bytes. */
    private fun telemetry(b: ByteArray, kind: String): Map<String, Any?>? {
        if (b.isEmpty()) return null
        return mapOf("kind" to kind, "subtype" to u8(b, 0), "raw" to b.toHex())
    }

    /** motion_period (0x6b): header (type bits 6-7, last-byte count bits 4-5, low nibble) + 2-bit levels, MSB first. */
    private fun motionPeriod(b: ByteArray): Map<String, Any?>? {
        if (b.size < 2) return null
        val h = u8(b, 0)
        val lastCount = (h shr 4) and 3
        val levels = ArrayList<Int>()
        for (i in 1 until b.size) {
            val n = if (i == b.size - 1) lastCount else 4
            for (k in 0 until n) levels.add((u8(b, i) shr (6 - 2 * k)) and 3)
        }
        return mapOf("period_type" to (h shr 6), "low_nibble" to (h and 0x0f), "motion_levels" to levels)
    }

    private fun u16Samples(b: ByteArray, key: String): Map<String, Any?>? {
        if (b.isEmpty() || b.size % 2 != 0) return null
        return mapOf(key to (b.indices step 2).map { u16le(b, it) })
    }

    /** sleep_acm_period (0x72): six accelerometer MAD statistics, three `int + frac/255`, three `12-bit/4095 + nibble`. */
    private fun sleepAcmPeriod(b: ByteArray): Map<String, Any?>? {
        if (b.size < 12) return null
        fun fp(frac: Int, int: Int) = r4(int + frac / 255.0)
        fun q12(lo: Int, hi: Int) = r4((lo or ((hi and 0x0f) shl 8)) / 4095.0 + (hi shr 4))
        return mapOf("acm_mad" to listOf(
            fp(u8(b, 0), u8(b, 1)), fp(u8(b, 2), u8(b, 3)), fp(u8(b, 4), u8(b, 5)),
            q12(u8(b, 6), u8(b, 7)), q12(u8(b, 8), u8(b, 9)), q12(u8(b, 10), u8(b, 11)),
        ))
    }

    /** spo2_r_pi_event (0x8b): header then 3-byte samples of (R: u16 BE / 16384, perfusion index: u8/255 × 0.05). */
    private fun spo2RPi(b: ByteArray): Map<String, Any?>? {
        if (b.size < 4 || (b.size - 1) % 3 != 0) return null
        val r = ArrayList<Double>(); val pi = ArrayList<Double>()
        var o = 1
        while (o + 3 <= b.size) {
            r.add(Math.round(((u8(b, o) shl 8) or u8(b, o + 1)) / 16384.0 * 1000) / 1000.0)
            pi.add(Math.round(u8(b, o + 2) / 255.0 * 0.05 * 10000) / 10000.0)
            o += 3
        }
        return mapOf("r" to r, "perfusion_index" to pi)
    }

    /** aohr_event (0x86): always-on HR, `count` (bpm, quality) pairs at 1920 ms. Layout from the native parser. */
    private fun aohr(b: ByteArray): Map<String, Any?>? {
        if (b.size < 3) return null
        val n = u8(b, 2)
        if (b.size != n * 2 + 3) return null
        return mapOf(
            "flag" to (u8(b, 0) and 1), "base_offset" to u8(b, 1), "interval_ms" to 1920,
            "bpm" to (0 until n).map { u8(b, 3 + 2 * it) }, "quality" to (0 until n).map { u8(b, 4 + 2 * it) },
            "_status" to "unvalidated",
        )
    }

    /** ambient_event (0x84): signed 16-bit samples every 5 minutes. */
    private fun ambient(b: ByteArray): Map<String, Any?>? {
        if (b.isEmpty() || b.size % 2 != 0) return null
        return mapOf("values" to (b.indices step 2).map { u16le(b, it).toShort().toInt() }, "interval_min" to 5, "_status" to "unvalidated")
    }

    private val NAMES = mapOf(
        0x41 to "ring_start", 0x42 to "time_sync", 0x43 to "debug_event", 0x44 to "ibi_event", 0x45 to "state_change",
        0x46 to "temp_event", 0x47 to "motion_event", 0x48 to "sleep_period_information", 0x49 to "sleep_summary_1",
        0x4a to "ppg_amplitude", 0x4b to "sleep_phase_information", 0x4c to "sleep_summary_2",
        0x4d to "ring_sleep_feature_information", 0x4e to "sleep_phase_details", 0x4f to "sleep_summary_3",
        0x50 to "activity_information", 0x51 to "activity_summary_1", 0x52 to "activity_summary_2", 0x53 to "wear_event",
        0x54 to "recovery_summary", 0x55 to "sleep_heart_rate", 0x56 to "alert_event",
        0x57 to "ring_sleep_feature_information_2", 0x58 to "sleep_summary_4", 0x59 to "eda_event",
        0x5a to "sleep_phase_data", 0x5b to "ble_connection", 0x5c to "user_information", 0x5d to "hrv_event",
        0x5e to "self_test_event", 0x5f to "raw_acm_event", 0x60 to "ibi_and_amplitude_event", 0x61 to "debug_data",
        0x62 to "on_demand_meas", 0x63 to "ppg_peak_event", 0x64 to "raw_ppg_event", 0x65 to "on_demand_session",
        0x66 to "on_demand_motion", 0x67 to "raw_ppg_summary", 0x68 to "raw_ppg_data", 0x69 to "temp_period",
        0x6a to "sleep_period_information_2", 0x6b to "motion_period", 0x6c to "feature_session",
        0x6d to "meas_quality_event", 0x6e to "spo2_ibi_and_amplitude_event", 0x6f to "spo2_event",
        0x70 to "spo2_smoothed_event", 0x71 to "green_ibi_and_amplitude_event", 0x72 to "sleep_acm_period",
        0x73 to "ehr_trace_event", 0x74 to "ehr_acm_intensity_event", 0x75 to "sleep_temp_event",
        0x76 to "bedtime_period", 0x77 to "spo2_dc_event", 0x79 to "self_test_data_event", 0x7a to "tag_event",
        0x7e to "real_step_event_feature_1", 0x7f to "real_step_event_feature_2", 0x80 to "green_ibi_quality_event",
        0x81 to "cva_raw_ppg_data", 0x8b to "spo2_r_pi_event", 0x82 to "scan_start", 0x83 to "scan_end", 0x85 to "rtc_beacon",
    )
}

/** Summary frame at the end of a legacy GetEvent batch (tag 0x11). */
data class EventBatchSummary(val eventsReceived: Int, val sleepProgress: Int, val bytesLeft: Long) {
    companion object {
        fun parse(p: Packet): EventBatchSummary? {
            if (p.tag != 0x11 || p.payload.size < 6) return null
            return EventBatchSummary(u8(p.payload, 0), u8(p.payload, 1), u32le(p.payload, 2))
        }
    }
}

/** Extended ExtGetEvent confirmation (0x2f ext 0x42). */
data class ExtEventBatchSummary(val eventsReceived: Int, val sleepProgress: Int, val bytesLeft: Long, val bufferId: Int, val resultCode: Int) {
    companion object {
        fun parse(p: Packet): ExtEventBatchSummary? {
            if (p.tag != 0x2f || p.payload.firstOrNull()?.toInt() != 0x42) return null
            val q = p.payload.copyOfRange(1, p.payload.size)
            if (q.size < 9) return null
            return ExtEventBatchSummary(u16le(q, 0), u8(q, 2), u32le(q, 3), u8(q, 7), u8(q, 8))
        }
    }
}

/** Reassembles ExtGetEvent envelope chunks (0x2f ext 0x43) into normal history-event packets. */
class ExtEventEnvelopeParser {
    private val lenBuf = ArrayList<Int>()
    private var expected: Int? = null
    private val payload = ArrayList<Byte>()

    fun pushPacket(p: Packet): List<Packet> {
        if (p.tag != 0x2f || p.payload.firstOrNull()?.toInt() != 0x43) return emptyList()
        val out = ArrayList<Packet>()
        var pos = 1
        val data = p.payload
        while (pos < data.size) {
            if (expected == null) {
                val take = minOf(2 - lenBuf.size, data.size - pos)
                for (k in 0 until take) lenBuf.add(u8(data, pos + k))
                pos += take
                if (lenBuf.size < 2) continue
                val len = lenBuf[0] or (lenBuf[1] shl 8)
                lenBuf.clear()
                payload.clear()
                expected = if (len == 0) null else len
            }
            val exp = expected
            if (exp != null) {
                val take = minOf(exp - payload.size, data.size - pos)
                for (k in 0 until take) payload.add(data[pos + k])
                pos += take
                if (payload.size == exp) {
                    out.addAll(parseBundle(payload.toByteArray()))
                    payload.clear()
                    expected = null
                }
            }
        }
        return out
    }

    private fun parseBundle(b: ByteArray): List<Packet> {
        val out = ArrayList<Packet>()
        var i = 0
        var lastMs: Long? = null
        var lastTs: Long? = null
        while (i + 3 <= b.size) {
            val tag = u8(b, i + 1)
            val extLen = u8(b, i + 2)
            val start = i + 3
            val end = start + extLen
            if (tag < HISTORY_EVENT_PREFIX || end > b.size) break
            val (encoded, used) = readVarint(b, start, end) ?: break
            val prev = lastMs
            val ms = if (prev != null && encoded < 100_000_000L) prev + encoded else encoded
            lastMs = ms
            var ts = ms / 100
            val pt = lastTs
            if (pt != null && encoded > 0 && ts <= pt) ts = pt + 1
            lastTs = ts
            val body = b.copyOfRange(start + used, end)
            val tsBytes = byteArrayOf(ts.toByte(), (ts shr 8).toByte(), (ts shr 16).toByte(), (ts shr 24).toByte())
            out.add(Packet(tag, tsBytes + body))
            i = end
        }
        return out
    }

    private fun readVarint(b: ByteArray, start: Int, end: Int): Pair<Long, Int>? {
        var v = 0L
        for (i in 0 until minOf(10, end - start)) {
            val x = u8(b, start + i)
            v = v or ((x and 0x7f).toLong() shl (7 * i))
            if (x and 0x80 == 0) return v to (i + 1)
        }
        return null
    }
}

/** Minimal JSON writer for decoded maps (kept dependency-free so it also works in JVM unit tests). */
fun toJson(v: Any?): String = when (v) {
    null -> "null"
    is String -> buildString {
        append('"')
        for (c in v) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
    is Number, is Boolean -> v.toString()
    is Map<*, *> -> v.entries.joinToString(",", "{", "}") { toJson(it.key.toString()) + ":" + toJson(it.value) }
    is Iterable<*> -> v.joinToString(",", "[", "]") { toJson(it) }
    else -> toJson(v.toString())
}
