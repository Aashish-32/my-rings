package com.aashish.myrings.protocol

class ProtocolException(msg: String) : Exception(msg)

class HistoryBatch(val events: List<RingEvent>, val bytesLeft: Long)

class ValidatedBatch(
    val events: List<RingEvent>,
    val bytesLeft: Long,
    val nextCursor: Long,
    val rejected: Int,
    val replayed: Int,
) {
    fun progressed(previous: Long) = events.isNotEmpty() && nextCursor > previous
}

/** A retained batch may skip quiet periods but never by years (corrupt envelopes poison the cursor). */
private const val MAX_CURSOR_ADVANCE_DS = 180L * 24 * 60 * 60 * 10

object History {
    fun validate(batch: HistoryBatch, batchStart: Long): ValidatedBatch {
        var maxTs = batchStart
        var rejected = 0
        var replayed = 0
        val kept = batch.events.filter { e ->
            when {
                e.timestamp < batchStart -> { replayed++; false }
                e.timestamp <= batchStart + MAX_CURSOR_ADVANCE_DS -> { maxTs = maxOf(maxTs, e.timestamp); true }
                else -> { rejected++; false }
            }
        }
        val next = if (kept.isEmpty()) batchStart else maxTs + 1
        return ValidatedBatch(kept, batch.bytesLeft, next, rejected, replayed)
    }

    fun decode(packets: List<Packet>): HistoryBatch {
        var bytesLeft: Long? = null
        var resultCode: Int? = null
        val events = ArrayList<RingEvent>()
        val envelopes = ExtEventEnvelopeParser()
        for (p in packets) {
            val first = p.payload.firstOrNull()?.toInt()?.and(0xff)
            when {
                p.tag == 0x11 -> bytesLeft = EventBatchSummary.parse(p)?.bytesLeft
                p.tag == 0x2f && first == 0x42 -> ExtEventBatchSummary.parse(p)?.let { bytesLeft = it.bytesLeft; resultCode = it.resultCode }
                p.tag == 0x2f && first == 0x43 ->
                    envelopes.pushPacket(p).filter { it.tag >= HISTORY_EVENT_PREFIX }.mapTo(events) { RingEvent.fromPacket(it) }
                p.tag >= HISTORY_EVENT_PREFIX -> events.add(RingEvent.fromPacket(p))
            }
        }
        val left = bytesLeft ?: throw ProtocolException("event batch ended without a summary packet (${packets.size} packet(s) received)")
        resultCode?.takeIf { it != 0 }?.let { throw ProtocolException("extended history request failed with result code 0x%02x".format(it)) }
        return HistoryBatch(events, left)
    }
}
