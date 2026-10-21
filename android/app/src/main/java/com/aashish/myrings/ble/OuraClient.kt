package com.aashish.myrings.ble

import com.aashish.myrings.protocol.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** A bidirectional link to a ring: write requests, receive notification frames. */
interface RingTransport {
    val incoming: Channel<ByteArray>
    suspend fun write(data: ByteArray)
    fun close()
}

class AuthException(msg: String) : Exception(msg)

data class BatchProgress(val nextCursor: Long, val bytesLeft: Long, val eventsSynced: Int)

private const val DEFAULT_QUIET_MS = 1500L
private const val DRAIN_QUIET_MS = 6000L
private const val EXT_BATCH_MAX_EVENTS = 4096
private const val MAX_TAIL_PASSES = 3
private const val TAIL_BATCH_EVENTS = 32

/** High-level ring API over any [RingTransport]. Mirrors open_oura `oura-link::OuraClient`. */
class OuraClient(private val t: RingTransport, private val quietMs: Long = DEFAULT_QUIET_MS) {

    private suspend fun request(bytes: ByteArray, quiet: Long = quietMs, terminal: (Packet) -> Boolean = { false }): List<Packet> {
        while (t.incoming.tryReceive().isSuccess) { /* drop backlog */ }
        t.write(bytes)
        val packets = ArrayList<Packet>()
        while (true) {
            val r = withTimeoutOrNull(quiet) { t.incoming.receiveCatching() } ?: break
            val frame = r.getOrNull() ?: throw ProtocolException("the ring disconnected")
            val ps = Packet.parseMany(frame)
            packets.addAll(ps)
            if (ps.any(terminal)) break
        }
        return packets
    }

    private suspend fun requestTag(bytes: ByteArray, tag: Int) = request(bytes) { it.tag == tag }
    private suspend fun requestExt(bytes: ByteArray, ext: Int) = request(bytes) { it.extTag == ext }

    private fun dump(ps: List<Packet>) =
        if (ps.isEmpty()) "<no packets>" else ps.joinToString(", ") { "{tag=0x%02x payload=%s}".format(it.tag, it.payload.toHex()) }

    // ---- device info ----
    suspend fun firmware(): DeviceInfo =
        requestTag(Req.firmware(), 0x09).firstOrNull { it.tag == 0x09 }?.let(DeviceInfo::parse)
            ?: throw ProtocolException("no firmware response")

    suspend fun battery(): Battery =
        requestTag(Req.battery(), 0x0d).firstOrNull { it.tag == 0x0d }?.let(Battery::parse)
            ?: throw ProtocolException("no battery response (auth required?)")

    suspend fun serial(): String =
        requestTag(Req.SERIAL, 0x19).firstOrNull { it.tag == 0x19 }?.let(::parseProductAscii)
            ?: throw ProtocolException("no serial response")

    suspend fun hardwareId(): String =
        requestTag(Req.HARDWARE, 0x19).firstOrNull { it.tag == 0x19 }?.let(::parseProductAscii)
            ?: throw ProtocolException("no hardware response")

    // ---- auth ----
    suspend fun authenticate(key: ByteArray): Auth.Result {
        val nonceResp = requestExt(Req.authNonce(), 0x2c).firstOrNull { it.extTag == 0x2c && it.payload.size > 1 }
            ?: throw AuthException("no nonce response from ring")
        val nonce = nonceResp.payload.copyOfRange(1, nonceResp.payload.size)
        val enc = Auth.encryptNonce(key, nonce)
        val resp = requestExt(Req.authenticate(enc), 0x2e)
        val state = resp.firstOrNull { it.extTag == 0x2e }?.payload?.getOrNull(1)?.toInt()?.and(0xff)
            ?: throw AuthException("no authenticate response (${dump(resp)})")
        val result = Auth.Result.from(state)
        if (!result.isSuccess) {
            val hint = when (result) {
                Auth.Result.AuthenticationError -> "the ring rejected the key — it does not match this ring's installed key"
                Auth.Result.InFactoryReset -> "the ring is factory-reset (no key installed yet) — pair it first"
                Auth.Result.NotOriginalOnboardedDevice -> "the ring is bonded to a different onboarding"
                else -> "unexpected auth state"
            }
            throw AuthException("ring rejected auth: $result — $hint")
        }
        return result
    }

    /** Install a new auth key; only valid on a factory-reset ring. */
    suspend fun setAuthKey(key: ByteArray) {
        val r = request(Req.setAuthKey(key)).firstOrNull { it.tag == 0x25 }?.payload?.firstOrNull()?.toInt()?.and(0xff)
            ?: throw ProtocolException("no set_auth_key response")
        if (r != 0) throw AuthException("set_auth_key status 0x%02x (is the ring factory-reset?)".format(r))
    }

    /** Sets the ring clock to this phone's exact UTC time and timezone (the official app's connect-time sync). */
    suspend fun syncTimeApp() {
        request(Req.syncTimeCounter(System.currentTimeMillis() / 1000, Req.localTzHalfHours()))
    }

    suspend fun syncTimeLegacy() {
        request(Req.syncTime(System.currentTimeMillis() / 1000, Req.localTzHalfHours()))
    }

    /** Ring 5 only: app-style stream registration. Ring 4 (hardware *_07) skips it. */
    suspend fun setupAppStream() {
        val hw = runCatching { hardwareId() }.getOrDefault("")
        if (hw.substringAfterLast('_', "") != "05") return
        requestTag(byteArrayOf(0x16, 0x01, 0x02), 0x17)
        for ((cat, flags) in listOf(0x14 to 0x1000, 0x18 to 0x1000, 0x28 to 0x0900, 0x34 to 0x0400, 0x04 to 0x1000, 0x08 to 0x1000)) {
            requestTag(byteArrayOf(0x18, 0x03, cat.toByte(), flags.toByte(), (flags shr 8).toByte()), 0x19)
        }
        for ((req, ext) in listOf(
            byteArrayOf(0x2f, 0x02, 0x20, 0x02) to 0x21, byteArrayOf(0x2f, 0x02, 0x20, 0x04) to 0x21,
            byteArrayOf(0x2f, 0x02, 0x03, 0x01) to 0x04, byteArrayOf(0x2f, 0x02, 0x20, 0x0b) to 0x21,
            byteArrayOf(0x2f, 0x02, 0x20, 0x0d) to 0x21, byteArrayOf(0x2f, 0x02, 0x20, 0x03) to 0x21,
            byteArrayOf(0x2f, 0x02, 0x20, 0x0b) to 0x21, byteArrayOf(0x2f, 0x02, 0x20, 0x10) to 0x21,
        )) requestExt(req, ext)
    }

    suspend fun checkSleepAnalysis(force: Boolean = false) {
        request(Req.checkSleepAnalysis(force))
    }

    // ---- features ----
    suspend fun setFeatureMode(feature: Int, mode: Int) {
        val r = request(Req.setFeatureMode(feature, mode)).firstOrNull { it.extTag == 0x23 }?.payload?.getOrNull(2)?.toInt()?.and(0xff)
            ?: throw ProtocolException("no set_feature_mode response")
        if (r != 0) throw ProtocolException("set_feature_mode result 0x%02x".format(r))
    }

    data class FeatureStatus(val feature: Int, val mode: Int, val status: Int, val state: Int, val subscription: Int)

    suspend fun featureStatus(feature: Int): FeatureStatus {
        val p = request(Req.featureStatus(feature)).firstOrNull { it.extTag == 0x21 && it.payload.size >= 6 }
            ?: throw ProtocolException("no feature-status response")
        val b = p.payload
        return FeatureStatus(u8(b, 1), u8(b, 2), u8(b, 3), u8(b, 4), u8(b, 5))
    }

    // ---- history ----
    /**
     * Drain history events from [cursor] (deciseconds). [onBatch] is called per fully-decoded batch
     * with the events and advanced cursor so the caller can persist both atomically; return false to abort.
     */
    suspend fun drainEvents(
        cursor: Long,
        onBatch: suspend (events: List<RingEvent>, progress: BatchProgress) -> Boolean,
    ): Pair<Int, Long> {
        var start = cursor
        var total = 0
        var tailPasses = 0
        var useExtended = true
        val batchTerminal = { p: Packet ->
            p.tag == 0x11 || (p.tag == 0x2f && (p.payload.firstOrNull()?.toInt() == 0x42 || p.payload.firstOrNull()?.toInt() == 0x00))
        }
        val quiet = DRAIN_QUIET_MS.coerceAtLeast(quietMs)
        repeat(100_000) {
            val packets = ArrayList(requestTag(Req.dataFlush(), 0x29))
            if (useExtended) {
                val ext = request(Req.extGetEvent(start * 100, EXT_BATCH_MAX_EVENTS, 0), quiet, batchTerminal)
                val unsupported = ext.any { it.tag == 0x2f && it.payload.firstOrNull()?.toInt() == 0x00 }
                if (unsupported) {
                    useExtended = false
                    packets.addAll(request(Req.getEvent(start, 255, -1), quiet, batchTerminal))
                } else packets.addAll(ext)
            } else {
                packets.addAll(request(Req.getEvent(start, 255, -1), quiet, batchTerminal))
            }

            val batch = try {
                History.decode(packets)
            } catch (e: ProtocolException) {
                throw ProtocolException("${e.message} — link lost mid-batch? cursor $start is checkpointed; sync again to resume")
            }
            val v = History.validate(batch, start)
            total += v.events.size
            val progressed = v.progressed(start)
            val tail = progressed && v.bytesLeft == 0L && v.events.size < TAIL_BATCH_EVENTS
            if (progressed) start = v.nextCursor
            if (!onBatch(v.events, BatchProgress(start, v.bytesLeft, total))) throw ProtocolException("batch callback failed")
            if (!progressed) {
                if (v.bytesLeft == 0L) return total to start // drained: an empty pass and the ring agrees
                throw ProtocolException("ring reports ${v.bytesLeft} bytes left but the batch had none — stopping (cursor $start)")
            }
            if (tail) {
                tailPasses++
                if (tailPasses >= MAX_TAIL_PASSES) return total to start
            } else tailPasses = 0
        }
        return total to start
    }
}
