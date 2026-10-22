import Foundation

public struct ProtocolError: Error, CustomStringConvertible {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}

public struct HistoryBatch {
    public let events: [RingEvent]
    public let bytesLeft: Int64
    public init(events: [RingEvent], bytesLeft: Int64) { self.events = events; self.bytesLeft = bytesLeft }
}

public struct ValidatedBatch {
    public let events: [RingEvent]
    public let bytesLeft: Int64
    public let nextCursor: Int64
    public let rejected: Int
    public let replayed: Int

    public func progressed(previous: Int64) -> Bool { !events.isEmpty && nextCursor > previous }
}

/// A retained batch may skip quiet periods but never by years (corrupt envelopes poison the cursor).
private let maxCursorAdvanceDs: Int64 = 180 * 24 * 60 * 60 * 10

public enum History {
    public static func validate(_ batch: HistoryBatch, batchStart: Int64) -> ValidatedBatch {
        var maxTs = batchStart
        var rejected = 0, replayed = 0
        var kept: [RingEvent] = []
        for e in batch.events {
            if e.timestamp < batchStart { replayed += 1 }
            else if e.timestamp <= batchStart + maxCursorAdvanceDs { maxTs = max(maxTs, e.timestamp); kept.append(e) }
            else { rejected += 1 }
        }
        let next = kept.isEmpty ? batchStart : maxTs + 1
        return ValidatedBatch(events: kept, bytesLeft: batch.bytesLeft, nextCursor: next, rejected: rejected, replayed: replayed)
    }

    public static func decode(_ packets: [Packet]) throws -> HistoryBatch {
        var bytesLeft: Int64?
        var resultCode: Int?
        var events: [RingEvent] = []
        let envelopes = ExtEventEnvelopeParser()
        for p in packets {
            let first = p.payload.first.map(Int.init)
            if p.tag == 0x11 {
                bytesLeft = EventBatchSummary.parse(p)?.bytesLeft
            } else if p.tag == 0x2f && first == 0x42 {
                if let s = ExtEventBatchSummary.parse(p) { bytesLeft = s.bytesLeft; resultCode = s.resultCode }
            } else if p.tag == 0x2f && first == 0x43 {
                for q in envelopes.push(p) where q.tag >= historyEventPrefix { events.append(RingEvent.from(q)) }
            } else if p.tag >= historyEventPrefix {
                events.append(RingEvent.from(p))
            }
        }
        guard let left = bytesLeft else { throw ProtocolError("event batch ended without a summary packet (\(packets.count) packet(s) received)") }
        if let code = resultCode, code != 0 { throw ProtocolError(String(format: "extended history request failed with result code 0x%02x", code)) }
        return HistoryBatch(events: events, bytesLeft: left)
    }
}
