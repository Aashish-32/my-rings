import XCTest
@testable import OuraKit

/// Vectors copied from the open_oura Rust tests (via the Android ProtocolTest), so the Swift port is checked against the reference.
final class ProtocolTests: XCTestCase {
    private func pk(_ hex: String) -> Packet { Packet.parse(hex.hexBytes!)! }

    func testAesKnownAnswer() {
        let key = "4431967d8bacc2659743142b68391d9a".hexBytes!
        let nonce = "0e2d6a0a08c99b4365f458e6e97382".hexBytes!
        XCTAssertEqual(Auth.encryptNonce(key: key, nonce: nonce).hex, "a38a8772d3acb6db5c2b516dd56987c8")
    }

    func testRequestEncodings() {
        XCTAssertEqual(Req.firmware().hex, "0803000000")
        XCTAssertEqual(Req.battery().hex, "0c00")
        XCTAssertEqual(Req.authNonce().hex, "2f012b")
        XCTAssertEqual(Req.getEvent(startDeciseconds: 0, maxEvents: 8, flags: -1).hex, "10090000000008ffffffff")
        XCTAssertEqual(Req.extGetEvent(startMs: 123_400, maxEvents: 65535, bufferId: 0).hex, "2f0c410008e2010000000000ffff")
        XCTAssertEqual(Req.syncTime(unixSecs: 0, tzHalfHours: -10).hex, "12090000000000000000f6")             // the captured app packet: UTC-5
        XCTAssertEqual(Req.syncTime(unixSecs: 1_792_542_541, tzHalfHours: -8).hex, "12094d07d86a00000000f8")   // exact time, UTC-4
        XCTAssertEqual(Req.dataFlush().hex, "280100")
        XCTAssertEqual(Req.setFeatureMode(0x02, 0x01).hex, "2f03220201")
        XCTAssertEqual(Req.featureStatus(0x0b).hex, "2f02200b")
    }

    func testParseManyConcatenated() {
        let f = Packet.parseMany("0d0639000000360f290100".hexBytes!)
        XCTAssertEqual(f.count, 2)
        XCTAssertEqual(f[0].tag, 0x0d)
        XCTAssertEqual(f[1].tag, 0x29)
    }

    func testParsePaddedFrame() {
        let p = Packet.parse([0x25, 0x01, 0x00])!
        XCTAssertEqual(p.tag, 0x25)
        XCTAssertEqual(p.payload.count, 1)
    }

    func testFirmwareBatteryAndSerial() {
        let info = DeviceInfo.parse(pk("091202000003040301000105000cffeeddccbbaa"))!
        XCTAssertEqual(info.api, "2.0.0")
        XCTAssertEqual(info.firmware, "3.4.3")
        XCTAssertEqual(info.mac, "aa:bb:cc:dd:ee:ff")
        XCTAssertEqual(Battery.parse(pk("0d0659000001f00f"))!.percent, 0x59)
        XCTAssertEqual(parseProductAscii(pk("191100585858585858585858585858")), "XXXXXXXXXXXX")
    }

    func testLegacyEventAndSummary() throws {
        let b = try History.decode([pk("43086400000074657374"), pk("1106000000000000")])
        XCTAssertEqual(b.events.count, 1)
        XCTAssertEqual(b.events[0].timestamp, 100)
        XCTAssertEqual(b.bytesLeft, 0)
        XCTAssertEqual(b.events[0].decoded?["ascii"] as? String, "test")
    }

    func testExtendedEnvelope() throws {
        let b = try History.decode([pk("2f09430600aa430364bbcc"), pk("2f0a42010000000000000000")])
        XCTAssertEqual(b.events.count, 1)
        XCTAssertEqual(b.events[0].timestamp, 1)
        XCTAssertEqual(b.events[0].body, [0xbb, 0xcc])
    }

    func testRejectsUnterminatedBatch() {
        XCTAssertThrowsError(try History.decode([pk("43086400000074657374")])) { XCTAssertTrue("\($0)".contains("without a summary")) }
    }

    func testRejectsExtendedResultCode() {
        XCTAssertThrowsError(try History.decode([pk("2f0a420000000000000000ff")])) { XCTAssertTrue("\($0)".contains("result code 0xff")) }
    }

    private func ev(_ tag: Int, _ ts: Int64) -> RingEvent { RingEvent(tag: tag, name: Events.name(tag), timestamp: ts, body: [], decoded: nil) }

    func testCursorPoisonRejected() {
        let start: Int64 = 6_906_561
        let recs: [(Int, Int64)] = [(0x7e, 6_906_620), (0xd1, 2_390_248_269), (0x8c, 3_970_646_416), (0x46, 3_970_646_462)]
        let v = History.validate(HistoryBatch(events: recs.map { ev($0.0, $0.1) }, bytesLeft: 0), batchStart: start)
        XCTAssertEqual(v.events.count, 1)
        XCTAssertEqual(v.nextCursor, 6_906_621)
        XCTAssertEqual(v.rejected, 3)
    }

    func testReplayedTailIsNotProgress() {
        let start: Int64 = 7_756_757
        let v = History.validate(HistoryBatch(events: (7_756_750..<start).map { ev(0x43, $0) }, bytesLeft: 0), batchStart: start)
        XCTAssertTrue(v.events.isEmpty)
        XCTAssertEqual(v.replayed, 7)
        XCTAssertFalse(v.progressed(previous: start))
    }

    func testDecoders() {
        XCTAssertEqual(Events.decode(0x5d, [60, 45, 58, 50])!["hr_bpm"] as? [Int], [60, 58])
        XCTAssertEqual(Events.decode(0x5d, [60, 45, 58, 50])!["rmssd_ms"] as? [Int], [45, 50])
        XCTAssertEqual(Events.decode(0x46, [0x42, 0x0e])!["temps_c"] as? [Double], [36.5])             // 3650 centi-degC
        XCTAssertEqual(Events.decode(0x5a, [0, 0b00011011])!["phases"] as? [String], ["deep", "light", "rem", "awake"])
        XCTAssertEqual(Events.decode(0x6f, [0, 97, 98, 0xff])!["spo2_percent"] as? [Int], [97, 98])
        let t: Int64 = 1_792_528_291
        let tb = (0..<4).map { UInt8((t >> Int64(8 * $0)) & 0xff) }
        XCTAssertEqual(Events.decode(0x42, tb)!["unix_time"] as? Int64, t)
    }

    func testDebugDataBatteryRecordIsNotAscii() {
        let m = Events.decode(0x61, [0x24, 0x5e, 0, 0, 0])!
        XCTAssertEqual(m["kind"] as? String, "battery")
        XCTAssertEqual(m["pct"] as? Int, 0x5e)
        XCTAssertTrue(m["voltage_mv"] is NSNull)
    }

    func testDebugDataLogLineWithLevelByte() {
        let body: [UInt8] = [0x04] + Array("chg_rp;1;6552".utf8)
        XCTAssertEqual(Events.decode(0x61, body)!["text"] as? String, "chg_rp;1;6552")
    }
}
