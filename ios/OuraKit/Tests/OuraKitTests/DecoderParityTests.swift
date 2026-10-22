import XCTest
@testable import OuraKit

/// Decodes real bodies captured from a Ring 4 (the same `event_fixtures.tsv` the Android app tests against) and checks the Swift
/// decoders produce the same JSON as the Rust reference decoder that stored them.
final class DecoderParityTests: XCTestCase {
    private func fixtures() throws -> [(Int, [UInt8], String)] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "event_fixtures", withExtension: "tsv", subdirectory: "Resources"))
        let text = try String(contentsOf: url, encoding: .utf8)
        return text.split(whereSeparator: \.isNewline).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.map { line in
            let p = line.split(separator: "\t", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
            return (Int(p[0])!, p[1].hexBytes!, p[2])
        }
    }

    func testFixturesLoaded() throws { XCTAssertGreaterThan(try fixtures().count, 20) }

    func testSwiftDecodersMatchRustOnRealData() throws {
        var covered = Set<Int>()
        for (tag, body, rustJson) in try fixtures() {
            let mine = try XCTUnwrap(Events.decode(tag, body), String(format: "tag 0x%02x: decoder returned nil for %@", tag, body.hex))
            let expected = try JSONSerialization.jsonObject(with: Data(rustJson.utf8)) as! NSDictionary
            let actual = try JSONSerialization.jsonObject(with: JSONSerialization.data(withJSONObject: mine)) as! NSDictionary
            XCTAssertEqual(expected, actual, String(format: "tag 0x%02x mismatch\n rust:  %@\n swift: %@", tag, expected, actual))
            covered.insert(tag)
        }
        XCTAssertGreaterThanOrEqual(covered.count, 8, "expected several tags covered, got \(covered)")
    }
}
