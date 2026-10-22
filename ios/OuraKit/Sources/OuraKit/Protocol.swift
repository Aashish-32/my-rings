import Foundation
import CommonCrypto

/// GATT + framing + request builders. Port of the Android `Protocol.kt`, itself ported from open_oura `oura-protocol`. All ints little-endian.
public enum Gatt {
    public static let service = "98ed0001-a541-11e4-b6a0-0002a5d5c51b"
    public static let write = "98ed0002-a541-11e4-b6a0-0002a5d5c51b"
    public static let notify = "98ed0003-a541-11e4-b6a0-0002a5d5c51b"
}

public let historyEventPrefix = 0x41

public enum Feature {
    public static let daytimeHr = 0x02
    public static let spo2 = 0x04
    public static let restingHr = 0x08
}

public enum FeatureMode {
    public static let off = 0x00
    public static let automatic = 0x01
    public static let requested = 0x02
    /// Streams live while connected (the on-screen "current heart rate").
    public static let connectedLive = 0x03
}

public struct Packet: Equatable {
    public let tag: Int
    public let payload: [UInt8]

    public init(tag: Int, payload: [UInt8]) { self.tag = tag; self.payload = payload }

    public func encode() -> [UInt8] { [UInt8(tag & 0xff), UInt8(payload.count & 0xff)] + payload }

    /// Extended op tag (first payload byte) for 0x2f frames.
    public var extTag: Int? { tag == 0x2f ? payload.first.map(Int.init) : nil }

    /// Lenient single-packet parse (rings sometimes pad frames).
    public static func parse(_ frame: [UInt8]) -> Packet? {
        guard frame.count >= 2 else { return nil }
        let tag = Int(frame[0]), len = Int(frame[1])
        let end = 2 + len <= frame.count ? 2 + len : frame.count
        return Packet(tag: tag, payload: Array(frame[2..<end]))
    }

    /// Every complete tag|len|payload packet concatenated into one notification.
    public static func parseMany(_ frame: [UInt8]) -> [Packet] {
        var out: [Packet] = []
        var i = 0
        while i + 2 <= frame.count {
            let tag = Int(frame[i]), len = Int(frame[i + 1])
            let start = i + 2, end = start + len
            if end > frame.count { break }
            out.append(Packet(tag: tag, payload: Array(frame[start..<end])))
            i = end
        }
        if out.isEmpty, let p = parse(frame) { out.append(p) }
        return out
    }
}

private func u16(_ v: Int) -> [UInt8] { [UInt8(v & 0xff), UInt8((v >> 8) & 0xff)] }
private func u32(_ v: Int64) -> [UInt8] { (0..<4).map { UInt8((v >> Int64(8 * $0)) & 0xff) } }
private func u64(_ v: Int64) -> [UInt8] { (0..<8).map { UInt8((v >> Int64(8 * $0)) & 0xff) } }
private func bytes(_ v: Int...) -> [UInt8] { v.map { UInt8($0 & 0xff) } }

public enum Req {
    public static func firmware() -> [UInt8] { bytes(0x08, 0x03, 0x00, 0x00, 0x00) }
    public static func battery() -> [UInt8] { bytes(0x0c, 0x00) }
    public static func authNonce() -> [UInt8] { bytes(0x2f, 0x01, 0x2b) }
    public static func authenticate(_ encrypted: [UInt8]) -> [UInt8] {
        precondition(encrypted.count == 16)
        return Packet(tag: 0x2f, payload: [0x2d] + encrypted).encode()
    }
    public static func setAuthKey(_ key: [UInt8]) -> [UInt8] {
        precondition(key.count == 16)
        return Packet(tag: 0x24, payload: key).encode()
    }
    /// Exact unix seconds plus the local UTC offset in signed half-hours (see the Android `Req.syncTime` notes; mirrors open_oura's fix).
    public static func syncTime(unixSecs: Int64, tzHalfHours: Int = 0) -> [UInt8] {
        Packet(tag: 0x12, payload: u64(unixSecs) + [UInt8(truncatingIfNeeded: tzHalfHours)]).encode()
    }
    /// This phone's current UTC offset in signed half-hours (UTC-4 = -8).
    public static func localTzHalfHours() -> Int { max(-24, min(28, TimeZone.current.secondsFromGMT() / 1800)) }
    public static func capabilities(page: Int) -> [UInt8] { bytes(0x2f, 0x02, 0x01, page) }
    public static let serial = bytes(0x18, 0x03, 0x08, 0x00, 0x10)
    public static let hardware = bytes(0x18, 0x03, 0x18, 0x00, 0x10)
    public static func getEvent(startDeciseconds: Int64, maxEvents: Int, flags: Int) -> [UInt8] {
        Packet(tag: 0x10, payload: u32(startDeciseconds) + [UInt8(maxEvents & 0xff)] + u32(Int64(flags) & 0xffff_ffff)).encode()
    }
    public static func extGetEvent(startMs: Int64, maxEvents: Int, bufferId: Int) -> [UInt8] {
        Packet(tag: 0x2f, payload: [0x41, UInt8(bufferId & 0xff)] + u64(startMs) + u16(maxEvents)).encode()
    }
    public static func dataFlush() -> [UInt8] { bytes(0x28, 0x01, 0x00) }
    public static func featureStatus(_ feature: Int) -> [UInt8] { bytes(0x2f, 0x02, 0x20, feature) }
    public static func setFeatureMode(_ feature: Int, _ mode: Int) -> [UInt8] { bytes(0x2f, 0x03, 0x22, feature, mode) }
    /// SetFeatureSubscription (ext 0x26). Mode 2 = latest value pushes, 0 = off.
    public static func setFeatureSubscription(_ capability: Int, _ mode: Int) -> [UInt8] { bytes(0x2f, 0x03, 0x26, capability, mode) }
    /// Start a time-boxed real-time stream (ext 0x06): a u32 type mask, a u16 length in minutes, a u8 delay. ACM (0x20) is the accelerometer.
    public static func setRealtime(mask: Int64, minutes: Int, delay: Int = 0) -> [UInt8] {
        Packet(tag: 0x06, payload: u32(mask) + u16(minutes) + [UInt8(delay & 0xff)]).encode()
    }
    public static func realtimeOff() -> [UInt8] { bytes(0x06, 0x04, 0, 0, 0, 0) }
    public static func setNotification(_ flags: Int) -> [UInt8] { bytes(0x1c, 0x01, flags) }
    public static func checkSleepAnalysis(force: Bool) -> [UInt8] { bytes(0x28, 0x01, force ? 1 : 0) }
}

public enum AuthResult: Equatable {
    case success, authenticationError, inFactoryReset, notOriginalOnboardedDevice
    case unknown(Int)

    public init(_ b: Int) {
        switch b {
        case 0: self = .success
        case 1: self = .authenticationError
        case 2: self = .inFactoryReset
        case 3: self = .notOriginalOnboardedDevice
        default: self = .unknown(b)
        }
    }
    public var isSuccess: Bool { self == .success }
}

public enum Auth {
    /// AES-128/ECB/PKCS7 of the (typically 15-byte) nonce -> 16-byte block. CryptoKit has no ECB, so this uses CommonCrypto.
    public static func encryptNonce(key: [UInt8], nonce: [UInt8]) -> [UInt8] {
        precondition(key.count == 16)
        let input = Array(nonce.prefix(16))
        var out = [UInt8](repeating: 0, count: input.count + kCCBlockSizeAES128)
        var written = 0
        let status = CCCrypt(CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionECBMode | kCCOptionPKCS7Padding),
                             key, key.count, nil, input, input.count, &out, out.count, &written)
        precondition(status == kCCSuccess, "AES failed: \(status)")
        return Array(out.prefix(written))
    }
}

public extension Array where Element == UInt8 {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}

public extension String {
    /// Hex string to bytes; nil when the length is odd or a character is not hex.
    var hexBytes: [UInt8]? {
        let s = trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "")
        guard s.count % 2 == 0 else { return nil }
        var out: [UInt8] = []
        var idx = s.startIndex
        while idx < s.endIndex {
            let next = s.index(idx, offsetBy: 2)
            guard let b = UInt8(s[idx..<next], radix: 16) else { return nil }
            out.append(b); idx = next
        }
        return out
    }
}

// MARK: - device info

public struct DeviceInfo: Equatable {
    public let api, firmware, bootloader, btStack, mac: String

    public static func parse(_ p: Packet) -> DeviceInfo? {
        guard p.tag == 0x09, p.payload.count >= 18 else { return nil }
        let b = p.payload
        func v3(_ from: Int) -> String { (from..<from + 3).map { String(b[$0]) }.joined(separator: ".") }
        let mac = (12...17).reversed().map { String(format: "%02x", b[$0]) }.joined(separator: ":")
        return DeviceInfo(api: v3(0), firmware: v3(3), bootloader: v3(6), btStack: v3(9), mac: mac)
    }
}

public struct Battery: Equatable {
    public let percent, chargingProgress, chargingRecommended: Int

    public static func parse(_ p: Packet) -> Battery? {
        guard p.tag == 0x0d, p.payload.count >= 3 else { return nil }
        return Battery(percent: Int(p.payload[0]), chargingProgress: Int(p.payload[1]), chargingRecommended: Int(p.payload[2]))
    }
}

public func parseProductAscii(_ p: Packet) -> String? {
    guard p.tag == 0x19, let first = p.payload.first, first == 0 else { return nil }
    let text = String(decoding: p.payload.dropFirst(), as: UTF8.self)
        .trimmingCharacters(in: CharacterSet(charactersIn: "\u{0}").union(.whitespaces))
    return text.isEmpty ? nil : text
}
