import Foundation

/// History events + decoders. Port of the Android `Events.kt`, itself ported from open_oura `oura-protocol::events`.
/// Unknown bodies stay raw. Out-of-range reads throw and make the decoder return nil, as the Kotlin version does with IndexOutOfBounds.
public typealias Decoded = [String: Any]

struct OutOfRange: Error {}

@inline(__always) func u8(_ b: [UInt8], _ i: Int) throws -> Int {
    guard i >= 0, i < b.count else { throw OutOfRange() }
    return Int(b[i])
}
func u16le(_ b: [UInt8], _ i: Int) throws -> Int { try u8(b, i) | (try u8(b, i + 1) << 8) }
func u32le(_ b: [UInt8], _ i: Int) throws -> Int64 {
    Int64(try u8(b, i)) | (Int64(try u8(b, i + 1)) << 8) | (Int64(try u8(b, i + 2)) << 16) | (Int64(try u8(b, i + 3)) << 24)
}
/// Kotlin's roundToInt: half rounds up.
private func roundHalfUp(_ v: Double) -> Double { (v + 0.5).rounded(.down) }
private func r2(_ v: Double) -> Double { roundHalfUp(v * 100.0) / 100.0 }
private func r4(_ v: Double) -> Double { roundHalfUp(v * 10000.0) / 10000.0 }
private func s8(_ x: UInt8) -> Int { Int(Int8(bitPattern: x)) }

public struct RingEvent {
    public let tag: Int
    public let name: String
    /// Ring timestamp in deciseconds (boot-relative).
    public let timestamp: Int64
    public let body: [UInt8]
    public let decoded: Decoded?

    public static func from(_ p: Packet) -> RingEvent {
        let b = p.payload
        let ts = b.count >= 4 ? ((try? u32le(b, 0)) ?? 0) : 0
        let body = b.count > 4 ? Array(b[4...]) : []
        return RingEvent(tag: p.tag, name: Events.name(p.tag), timestamp: ts, body: body, decoded: Events.decode(p.tag, body))
    }
}

public enum Events {
    /// Bump whenever a decoder is added or changed so stored JSON is re-decoded from the raw bytes (analysis always decodes from raw).
    public static let decoderVersion = 2

    public static func name(_ tag: Int) -> String { names[tag] ?? "unknown" }

    public static func decode(_ tag: Int, _ body: [UInt8]) -> Decoded? {
        do {
            switch tag {
            case 0x41: return try ringStart(body)
            case 0x42: return try timeSync(body)
            case 0x5c: return try userInfo(body)
            case 0x43: return ascii(body)
            case 0x61: return try debugData(body)
            case 0x45, 0x53: return try stateText(body)
            case 0x46, 0x69, 0x75: return try temperatures(body)
            case 0x5d: return try hrv(body)
            case 0x80: return try greenIbiQuality(body)
            case 0x6f: return try spo2(body)
            case 0x4b, 0x4e, 0x5a: return try sleepPhases(body)
            case 0x76: return try bedtimePeriod(body)
            case 0x6a: return try sleepPeriodInfo(body)
            case 0x85: return try rtcBeacon(body)
            case 0x60: return try ibiAmplitude(body)
            case 0x50: return try activityInfo(body)
            case 0x47: return try motion(body)
            case 0x6c: return try featureSession(body)
            case 0x49: return try sleepSummary1(body)
            case 0x4c: return try sleepSummary2(body)
            case 0x4f: return try sleepSummary3(body)
            case 0x58: return try sleepSummary4(body)
            case 0x5b: return try telemetry(body, "ble_connection_ind")
            case 0x79: return try telemetry(body, "self_test_data")
            case 0x82: return try telemetry(body, "scan_start")
            case 0x83: return try telemetry(body, "scan_end")
            case 0x56: return body.first.map { ["alert_type": Int($0)] }
            case 0x6b: return try motionPeriod(body)
            case 0x59: return try u16Samples(body, "ambient")
            case 0x74: return try u16Samples(body, "intensity")
            case 0x72: return try sleepAcmPeriod(body)
            case 0x8b: return try spo2RPi(body)
            case 0x86: return try aohr(body)
            case 0x84: return try ambient(body)
            default: return nil
            }
        } catch {
            return nil
        }
    }

    private static func v3(_ b: [UInt8], _ from: Int) throws -> String { try (from..<from + 3).map { String(try u8(b, $0)) }.joined(separator: ".") }

    private static func ringStart(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 14 else { return nil }
        return ["reason": try u32le(b, 0), "unknown_byte_4": try u8(b, 4),
                "firmware_version": try v3(b, 5), "bootloader_version": try v3(b, 8), "api_version": try v3(b, 11)]
    }

    private static func timeSync(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 4 else { return nil }
        var m: Decoded = ["unix_time": try u32le(b, 0)]
        if b.count > 4 && String(decoding: b[4...], as: UTF8.self) == "phone" { m["source"] = "phone" }
        // echo of a host 0x12 sync: u64 unix then the UTC offset in signed half-hours
        else if b.count == 9 && (4...7).allSatisfy({ b[$0] == 0 }) { m["tz_half_hours"] = s8(b[8]) }
        return m
    }

    private static func userInfo(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 4 else { return nil }
        let code = try u8(b, 2)
        let sex = code == 0 ? "male" : code == 1 ? "female" : "unspecified"
        return ["age_years": try u8(b, 0), "weight_kg": try u8(b, 1), "sex_code": code, "sex": sex, "height_cm": try u8(b, 3), "_status": "inferred"]
    }

    private static func ascii(_ b: [UInt8]) -> Decoded? {
        let t = String(decoding: b, as: UTF8.self).trimmingCharacters(in: CharacterSet(charactersIn: "\u{0}").union(.whitespacesAndNewlines))
        return t.isEmpty ? nil : ["ascii": t]
    }

    /// debug_data (0x61): subtype 0x24 is a battery record (percent, voltage mV); other bodies are firmware log lines.
    private static func debugData(_ b: [UInt8]) throws -> Decoded? {
        guard !b.isEmpty else { return nil }
        let sub = try u8(b, 0)
        if sub == 0x24 && b.count >= 4 {
            let mv = try u8(b, 2) | (try u8(b, 3) << 8)
            return ["kind": "battery", "pct": try u8(b, 1), "voltage_mv": mv == 0 ? NSNull() as Any : mv]
        }
        var text = sub < 0x20 ? Array(b[1...]) : b
        while let last = text.last, last == 0 { text.removeLast() }
        let printable = text.allSatisfy { $0 >= 0x20 && $0 <= 0x7e }
        let alnum = text.filter { ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x41 && $0 <= 0x5a) || ($0 >= 0x61 && $0 <= 0x7a) }.count
        if printable && alnum >= 3 {
            return ["kind": "log", "text": String(decoding: text, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)]
        }
        return nil
    }

    private static func stateText(_ b: [UInt8]) throws -> Decoded? {
        guard !b.isEmpty else { return nil }
        let t = String(decoding: b.dropFirst(), as: UTF8.self).trimmingCharacters(in: CharacterSet(charactersIn: "\u{0}").union(.whitespacesAndNewlines))
        return ["state": try u8(b, 0), "text": t]
    }

    private static func temperatures(_ b: [UInt8]) throws -> Decoded? {
        guard !b.isEmpty, b.count % 2 == 0 else { return nil }
        var temps: [Double] = []
        for i in stride(from: 0, to: b.count, by: 2) {
            let centi = Int(Int16(bitPattern: UInt16((try u8(b, i + 1) << 8) | (try u8(b, i)))))
            let c = Double(centi) / 100.0
            if c < -40.0 || c > 85.0 { return nil }
            temps.append(r2(c))
        }
        return ["temps_c": temps]
    }

    private static func hrv(_ b: [UInt8]) throws -> Decoded? {
        guard !b.isEmpty, b.count % 2 == 0 else { return nil }
        var hr: [Int] = [], rm: [Int] = []
        for i in stride(from: 0, to: b.count, by: 2) { hr.append(try u8(b, i)); rm.append(try u8(b, i + 1)) }
        return ["hr_bpm": hr, "rmssd_ms": rm, "interval_min": 5]
    }

    private static func greenIbiQuality(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 2 else { return nil }
        var ibis: [Int] = [], qs: [Int] = [], hr: [Int] = []
        var i = 0
        while i + 1 < b.count {
            let ibi = (try u8(b, i + 1) & 7) | (try u8(b, i) << 3)
            let q = (try u8(b, i + 1) >> 3) & 3
            if q == 1 && (300...2000).contains(ibi) { hr.append(60000 / ibi) }
            ibis.append(ibi); qs.append(q); i += 2
        }
        return ["ibi_ms": ibis, "quality": qs, "hr_bpm": hr]
    }

    private static func spo2(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 2 else { return nil }
        var end = b.count
        if try u8(b, end - 1) == 0xff { end -= 1 }
        guard end > 1 else { return nil }
        return ["spo2_percent": try (1..<end).map { try u8(b, $0) }]
    }

    private static let phase = ["deep", "light", "rem", "awake"]
    private static func sleepPhases(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 2 else { return nil }
        var ph: [String] = []
        for k in 1..<b.count { for shift in [6, 4, 2, 0] { ph.append(phase[(try u8(b, k) >> shift) & 3]) } }
        return ["header": try u8(b, 0), "phases": ph]
    }

    private static func bedtimePeriod(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 8 else { return nil }
        let s = try u32le(b, 0), e = try u32le(b, 4)
        return ["bedtime_start_ds": s, "bedtime_end_ds": e, "duration_hours": r2(Double(max(0, e - s)) / 10.0 / 3600.0)]
    }

    private static func sleepPeriodInfo(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 10 else { return nil }
        let motion = try u8(b, 6), state = s8(b[7])
        if motion >= 120 || !(0...2).contains(state) { return nil }
        return [
            "average_hr": r4(Double(try u8(b, 0)) * 0.5), "hr_trend": r4(Double(s8(b[1])) * 0.0625),
            "mzci": r4(Double(try u8(b, 2)) * 0.0625), "dzci": r4(Double(try u8(b, 3)) * 0.0625),
            "breath": r4(Double(try u8(b, 4)) / 8.0), "breath_v": r4(Double(try u8(b, 5)) / 8.0),
            "motion_count": motion, "sleep_state": state, "cv": r4(Double(try u16le(b, 8)) / 65536.0),
        ]
    }

    private static func rtcBeacon(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 10 else { return nil }
        return ["unix_time": try u32le(b, 0), "trailer": try u16le(b, 8)]
    }

    private static func ibiAmplitude(_ b: [UInt8]) throws -> Decoded? {
        guard b.count == 14 else { return nil }
        func x(_ i: Int) -> Int { Int(b[i]) }
        let ibi = [
            (x(6) & 1) | (x(0) << 3) | ((x(12) >> 5) & 6),
            (x(7) & 1) | (x(1) << 3) | ((x(12) >> 3) & 6),
            (x(8) & 1) | (x(2) << 3) | ((x(12) >> 1) & 6),
            (x(9) & 1) | (x(3) << 3) | ((x(12) & 3) << 1),
            (x(10) & 1) | (x(4) << 3) | ((x(13) >> 5) & 6),
            (x(11) & 1) | (x(5) << 3) | ((x(13) >> 3) & 6),
        ]
        let shift = (x(13) & 0x0f) == 7 ? 0 : (x(13) & 0x0f) + 1
        let amp = (0..<6).map { (x(6 + $0) >> 1) << shift }
        let hr = ibi.filter { (300...2000).contains($0) }.map { 60000 / $0 }
        return ["ibi_ms": ibi, "amplitude": amp, "hr_bpm": hr]
    }

    private static func activityInfo(_ b: [UInt8]) throws -> Decoded? {
        guard !b.isEmpty else { return nil }
        let met = (1..<max(1, b.count)).map { i -> Double in
            let v = Int(b[i])
            return r2(v < 0x80 ? Double(v) * 0.1 : 12.8 + Double(v - 128) * 0.2)
        }
        return ["state": try u8(b, 0), "met": met]
    }

    private static func motion(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 4 else { return nil }
        var m: Decoded = [
            "orientation": try u8(b, 0) >> 5, "motion_seconds": try u8(b, 0) & 0x1f,
            "avg_x": s8(b[1]) * 8, "avg_y": s8(b[2]) * 8, "avg_z": s8(b[3]) * 8,
        ]
        if b.count >= 5 { if try u8(b, 4) & 0x40 != 0 { return nil }; m["low_intensity"] = try u8(b, 4) & 0x3f }
        if b.count >= 6 { if try u8(b, 5) & 0x40 != 0 { return nil }; m["high_intensity"] = try u8(b, 5) & 0x3f }
        return m
    }

    // The ring's own sleep summaries: layouts come from the firmware parser but are "unvalidated" in open_oura, so they stay raw and flagged.
    private static func sleepSummary1(_ b: [UInt8]) throws -> Decoded? {
        b.count < 4 ? nil : ["start_offset_min": try u16le(b, 0), "end_offset_min": try u16le(b, 2), "_status": "unvalidated"]
    }

    private static func sleepSummary2(_ b: [UInt8]) throws -> Decoded? {
        guard b.count == 14 else { return nil }
        var a: UInt64 = 0
        for i in stride(from: 7, through: 0, by: -1) { a = (a << 8) | UInt64(b[i]) }
        return ["field_a_u64": String(a), "field_b_u16": try u16le(b, 8), "field_c_u32": try u32le(b, 10), "_status": "unvalidated"]
    }

    private static func sleepSummary3(_ b: [UInt8]) throws -> Decoded? {
        guard b.count == 11 else { return nil }
        return [
            "field_a": try u8(b, 0) >> 3, "field_b": try u8(b, 1) >> 3, "field_c": (try u16le(b, 2) >> 3) & 0xff,
            "field_d_u32": try u32le(b, 4), "field_e_u16": try u16le(b, 8), "field_f_u8": try u8(b, 10), "_status": "unvalidated",
        ]
    }

    private static func sleepSummary4(_ b: [UInt8]) throws -> Decoded? {
        guard b.count == 7 else { return nil }
        return ["field_a_u32": try u32le(b, 0), "field_b_u16": try u16le(b, 4), "field_c_u8": try u8(b, 6), "_status": "unvalidated"]
    }

    /// feature_session (0x6c): [feature_id][session_status][optional u16 value].
    private static func featureSession(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 2 else { return nil }
        var m: Decoded = ["feature_id": try u8(b, 0), "session_status": try u8(b, 1)]
        if b.count >= 4 { m["value"] = try u16le(b, 2) }
        return m
    }

    /// BLE/radio telemetry and self-test events: layout is device-internal, so keep the sub-type and raw bytes.
    private static func telemetry(_ b: [UInt8], _ kind: String) throws -> Decoded? {
        guard !b.isEmpty else { return nil }
        return ["kind": kind, "subtype": try u8(b, 0), "raw": b.hex]
    }

    /// motion_period (0x6b): header (type bits 6-7, last-byte count bits 4-5, low nibble) + 2-bit levels, MSB first.
    private static func motionPeriod(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 2 else { return nil }
        let h = try u8(b, 0)
        let lastCount = (h >> 4) & 3
        var levels: [Int] = []
        for i in 1..<b.count {
            let n = i == b.count - 1 ? lastCount : 4
            for k in 0..<n { levels.append((try u8(b, i) >> (6 - 2 * k)) & 3) }
        }
        return ["period_type": h >> 6, "low_nibble": h & 0x0f, "motion_levels": levels]
    }

    private static func u16Samples(_ b: [UInt8], _ key: String) throws -> Decoded? {
        guard !b.isEmpty, b.count % 2 == 0 else { return nil }
        return [key: try stride(from: 0, to: b.count, by: 2).map { try u16le(b, $0) }]
    }

    /// sleep_acm_period (0x72): six accelerometer MAD statistics, three `int + frac/255`, three `12-bit/4095 + nibble`.
    private static func sleepAcmPeriod(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 12 else { return nil }
        func fp(_ frac: Int, _ int: Int) -> Double { r4(Double(int) + Double(frac) / 255.0) }
        func q12(_ lo: Int, _ hi: Int) -> Double { r4(Double(lo | ((hi & 0x0f) << 8)) / 4095.0 + Double(hi >> 4)) }
        return ["acm_mad": [
            fp(try u8(b, 0), try u8(b, 1)), fp(try u8(b, 2), try u8(b, 3)), fp(try u8(b, 4), try u8(b, 5)),
            q12(try u8(b, 6), try u8(b, 7)), q12(try u8(b, 8), try u8(b, 9)), q12(try u8(b, 10), try u8(b, 11)),
        ]]
    }

    /// spo2_r_pi_event (0x8b): header then 3-byte samples of (R: u16 BE / 16384, perfusion index: u8/255 x 0.05).
    private static func spo2RPi(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 4, (b.count - 1) % 3 == 0 else { return nil }
        var r: [Double] = [], pi: [Double] = []
        var o = 1
        while o + 3 <= b.count {
            r.append(roundHalfUp(Double((try u8(b, o) << 8) | (try u8(b, o + 1))) / 16384.0 * 1000) / 1000.0)
            pi.append(roundHalfUp(Double(try u8(b, o + 2)) / 255.0 * 0.05 * 10000) / 10000.0)
            o += 3
        }
        return ["r": r, "perfusion_index": pi]
    }

    /// aohr_event (0x86): always-on HR, `count` (bpm, quality) pairs at 1920 ms. Layout from the native parser.
    private static func aohr(_ b: [UInt8]) throws -> Decoded? {
        guard b.count >= 3 else { return nil }
        let n = try u8(b, 2)
        guard b.count == n * 2 + 3 else { return nil }
        return [
            "flag": try u8(b, 0) & 1, "base_offset": try u8(b, 1), "interval_ms": 1920,
            "bpm": try (0..<n).map { try u8(b, 3 + 2 * $0) }, "quality": try (0..<n).map { try u8(b, 4 + 2 * $0) },
            "_status": "unvalidated",
        ]
    }

    /// ambient_event (0x84): signed 16-bit samples every 5 minutes.
    private static func ambient(_ b: [UInt8]) throws -> Decoded? {
        guard !b.isEmpty, b.count % 2 == 0 else { return nil }
        return ["values": try stride(from: 0, to: b.count, by: 2).map { Int(Int16(bitPattern: UInt16(try u16le(b, $0)))) }, "interval_min": 5, "_status": "unvalidated"]
    }

    private static let names: [Int: String] = [
        0x41: "ring_start", 0x42: "time_sync", 0x43: "debug_event", 0x44: "ibi_event", 0x45: "state_change",
        0x46: "temp_event", 0x47: "motion_event", 0x48: "sleep_period_information", 0x49: "sleep_summary_1",
        0x4a: "ppg_amplitude", 0x4b: "sleep_phase_information", 0x4c: "sleep_summary_2",
        0x4d: "ring_sleep_feature_information", 0x4e: "sleep_phase_details", 0x4f: "sleep_summary_3",
        0x50: "activity_information", 0x51: "activity_summary_1", 0x52: "activity_summary_2", 0x53: "wear_event",
        0x54: "recovery_summary", 0x55: "sleep_heart_rate", 0x56: "alert_event",
        0x57: "ring_sleep_feature_information_2", 0x58: "sleep_summary_4", 0x59: "eda_event",
        0x5a: "sleep_phase_data", 0x5b: "ble_connection", 0x5c: "user_information", 0x5d: "hrv_event",
        0x5e: "self_test_event", 0x5f: "raw_acm_event", 0x60: "ibi_and_amplitude_event", 0x61: "debug_data",
        0x62: "on_demand_meas", 0x63: "ppg_peak_event", 0x64: "raw_ppg_event", 0x65: "on_demand_session",
        0x66: "on_demand_motion", 0x67: "raw_ppg_summary", 0x68: "raw_ppg_data", 0x69: "temp_period",
        0x6a: "sleep_period_information_2", 0x6b: "motion_period", 0x6c: "feature_session",
        0x6d: "meas_quality_event", 0x6e: "spo2_ibi_and_amplitude_event", 0x6f: "spo2_event",
        0x70: "spo2_smoothed_event", 0x71: "green_ibi_and_amplitude_event", 0x72: "sleep_acm_period",
        0x73: "ehr_trace_event", 0x74: "ehr_acm_intensity_event", 0x75: "sleep_temp_event",
        0x76: "bedtime_period", 0x77: "spo2_dc_event", 0x79: "self_test_data_event", 0x7a: "tag_event",
        0x7e: "real_step_event_feature_1", 0x7f: "real_step_event_feature_2", 0x80: "green_ibi_quality_event",
        0x81: "cva_raw_ppg_data", 0x8b: "spo2_r_pi_event", 0x82: "scan_start", 0x83: "scan_end", 0x85: "rtc_beacon",
    ]
}

/// Summary frame at the end of a legacy GetEvent batch (tag 0x11).
public struct EventBatchSummary: Equatable {
    public let eventsReceived, sleepProgress: Int
    public let bytesLeft: Int64

    public static func parse(_ p: Packet) -> EventBatchSummary? {
        guard p.tag == 0x11, p.payload.count >= 6, let a = try? u8(p.payload, 0), let b = try? u8(p.payload, 1), let c = try? u32le(p.payload, 2) else { return nil }
        return EventBatchSummary(eventsReceived: a, sleepProgress: b, bytesLeft: c)
    }
}

/// Extended ExtGetEvent confirmation (0x2f ext 0x42).
public struct ExtEventBatchSummary: Equatable {
    public let eventsReceived, sleepProgress: Int
    public let bytesLeft: Int64
    public let bufferId, resultCode: Int

    public static func parse(_ p: Packet) -> ExtEventBatchSummary? {
        guard p.tag == 0x2f, p.payload.first == 0x42 else { return nil }
        let q = Array(p.payload.dropFirst())
        guard q.count >= 9, let a = try? u16le(q, 0), let b = try? u8(q, 2), let c = try? u32le(q, 3), let d = try? u8(q, 7), let e = try? u8(q, 8) else { return nil }
        return ExtEventBatchSummary(eventsReceived: a, sleepProgress: b, bytesLeft: c, bufferId: d, resultCode: e)
    }
}

/// Reassembles ExtGetEvent envelope chunks (0x2f ext 0x43) into normal history-event packets.
public final class ExtEventEnvelopeParser {
    private var lenBuf: [Int] = []
    private var expected: Int?
    private var payload: [UInt8] = []

    public init() {}

    public func push(_ p: Packet) -> [Packet] {
        guard p.tag == 0x2f, p.payload.first == 0x43 else { return [] }
        var out: [Packet] = []
        var pos = 1
        let data = p.payload
        while pos < data.count {
            if expected == nil {
                let take = min(2 - lenBuf.count, data.count - pos)
                for k in 0..<take { lenBuf.append(Int(data[pos + k])) }
                pos += take
                if lenBuf.count < 2 { continue }
                let len = lenBuf[0] | (lenBuf[1] << 8)
                lenBuf.removeAll()
                payload.removeAll()
                expected = len == 0 ? nil : len
            }
            if let exp = expected {
                let take = min(exp - payload.count, data.count - pos)
                payload.append(contentsOf: data[pos..<pos + take])
                pos += take
                if payload.count == exp {
                    out.append(contentsOf: parseBundle(payload))
                    payload.removeAll()
                    expected = nil
                }
            }
        }
        return out
    }

    private func parseBundle(_ b: [UInt8]) -> [Packet] {
        var out: [Packet] = []
        var i = 0
        var lastMs: Int64?
        var lastTs: Int64?
        while i + 3 <= b.count {
            let tag = Int(b[i + 1])
            let extLen = Int(b[i + 2])
            let start = i + 3, end = start + extLen
            if tag < historyEventPrefix || end > b.count { break }
            guard let (encoded, used) = readVarint(b, start, end) else { break }
            let ms: Int64 = (lastMs != nil && encoded < 100_000_000) ? lastMs! + encoded : encoded
            lastMs = ms
            var ts = ms / 100
            if let pt = lastTs, encoded > 0, ts <= pt { ts = pt + 1 }
            lastTs = ts
            let tsBytes = (0..<4).map { UInt8((ts >> Int64(8 * $0)) & 0xff) }
            out.append(Packet(tag: tag, payload: tsBytes + Array(b[(start + used)..<end])))
            i = end
        }
        return out
    }

    private func readVarint(_ b: [UInt8], _ start: Int, _ end: Int) -> (Int64, Int)? {
        var v: Int64 = 0
        for i in 0..<min(10, end - start) {
            let x = Int(b[start + i])
            v |= Int64(x & 0x7f) << Int64(7 * i)
            if x & 0x80 == 0 { return (v, i + 1) }
        }
        return nil
    }
}
