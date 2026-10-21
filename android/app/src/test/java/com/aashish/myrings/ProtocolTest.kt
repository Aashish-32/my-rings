package com.aashish.myrings

import com.aashish.myrings.protocol.*
import org.junit.Assert.*
import org.junit.Test

/** Vectors copied from the open_oura Rust tests, so the Kotlin port is checked against the reference. */
class ProtocolTest {
    private fun pk(hex: String) = Packet.parse(hex.hexToBytes())!!

    @Test fun aesKnownAnswer() {
        val key = "4431967d8bacc2659743142b68391d9a".hexToBytes()
        val nonce = "0e2d6a0a08c99b4365f458e6e97382".hexToBytes()
        assertEquals("a38a8772d3acb6db5c2b516dd56987c8", Auth.encryptNonce(key, nonce).toHex())
    }

    @Test fun requestEncodings() {
        assertEquals("0803000000", Req.firmware().toHex())
        assertEquals("0c00", Req.battery().toHex())
        assertEquals("2f012b", Req.authNonce().toHex())
        assertEquals("10090000000008ffffffff", Req.getEvent(0, 8, -1).toHex())
        assertEquals("2f0c410008e2010000000000ffff", Req.extGetEvent(123_400, 65535, 0).toHex())
        assertEquals("12090000000000000000f6", Req.syncTimeCounter(0, -10).toHex()) // the captured app packet: UTC-5
        assertEquals("12094d07d86a00000000f8", Req.syncTimeCounter(1_792_542_541, -8).toHex()) // exact time, UTC-4
        assertEquals("280100", Req.dataFlush().toHex())
        assertEquals("2f03220201", Req.setFeatureMode(0x02, 0x01).toHex())
        assertEquals("2f02200b", Req.featureStatus(0x0b).toHex())
    }

    @Test fun parseManyConcatenated() {
        val f = Packet.parseMany("0d0639000000360f290100".hexToBytes())
        assertEquals(2, f.size)
        assertEquals(0x0d, f[0].tag)
        assertEquals(0x29, f[1].tag)
    }

    @Test fun parsePaddedFrame() {
        val p = Packet.parse(byteArrayOf(0x25, 0x01, 0x00))!!
        assertEquals(0x25, p.tag)
        assertEquals(1, p.payload.size)
    }

    @Test fun firmwareBatteryAndSerial() {
        val info = DeviceInfo.parse(pk("091202000003040301000105000cffeeddccbbaa"))!!
        assertEquals("2.0.0", info.api)
        assertEquals("3.4.3", info.firmware)
        assertEquals("aa:bb:cc:dd:ee:ff", info.mac)
        assertEquals(0x59, Battery.parse(pk("0d0659000001f00f"))!!.percent)
        assertEquals("XXXXXXXXXXXX", parseProductAscii(pk("191100585858585858585858585858")))
    }

    @Test fun legacyEventAndSummary() {
        val b = History.decode(listOf(pk("43086400000074657374"), pk("1106000000000000")))
        assertEquals(1, b.events.size)
        assertEquals(100L, b.events[0].timestamp)
        assertEquals(0L, b.bytesLeft)
        assertEquals("test", b.events[0].decoded?.get("ascii"))
    }

    @Test fun extendedEnvelope() {
        val b = History.decode(listOf(pk("2f09430600aa430364bbcc"), pk("2f0a42010000000000000000")))
        assertEquals(1, b.events.size)
        assertEquals(1L, b.events[0].timestamp)
        assertArrayEquals(byteArrayOf(0xbb.toByte(), 0xcc.toByte()), b.events[0].body)
    }

    @Test fun rejectsUnterminatedBatch() {
        try { History.decode(listOf(pk("43086400000074657374"))); fail() } catch (e: ProtocolException) {
            assertTrue(e.message!!.contains("without a summary"))
        }
    }

    @Test fun rejectsExtendedResultCode() {
        try { History.decode(listOf(pk("2f0a420000000000000000ff"))); fail() } catch (e: ProtocolException) {
            assertTrue(e.message!!.contains("result code 0xff"))
        }
    }

    private fun ev(tag: Int, ts: Long) = RingEvent(tag, Events.name(tag), ts, ByteArray(0), null)

    @Test fun cursorPoisonRejected() {
        val start = 6_906_561L
        val recs = listOf(0x7e to 6_906_620L, 0xd1 to 2_390_248_269L, 0x8c to 3_970_646_416L, 0x46 to 3_970_646_462L)
        val v = History.validate(HistoryBatch(recs.map { ev(it.first, it.second) }, 0), start)
        assertEquals(1, v.events.size)
        assertEquals(6_906_621L, v.nextCursor)
        assertEquals(3, v.rejected)
    }

    @Test fun replayedTailIsNotProgress() {
        val start = 7_756_757L
        val v = History.validate(HistoryBatch((7_756_750L until start).map { ev(0x43, it) }, 0), start)
        assertTrue(v.events.isEmpty())
        assertEquals(7, v.replayed)
        assertFalse(v.progressed(start))
    }

    @Test fun decoders() {
        // hrv: (hr, rmssd) pairs
        val hrv = Events.decode(0x5d, byteArrayOf(60, 45, 58, 50))!!
        assertEquals(listOf(60, 58), hrv["hr_bpm"])
        assertEquals(listOf(45, 50), hrv["rmssd_ms"])
        // temperature: 3650 centi-degC = 36.5
        assertEquals(listOf(36.5), Events.decode(0x46, byteArrayOf(0x42, 0x0e))!!["temps_c"])
        // sleep phases: 0b00_01_10_11 -> deep,light,rem,awake
        assertEquals(listOf("deep", "light", "rem", "awake"), Events.decode(0x5a, byteArrayOf(0, 0b00011011))!!["phases"])
        // spo2 with sentinel
        assertEquals(listOf(97, 98), Events.decode(0x6f, byteArrayOf(0, 97, 98, 0xff.toByte()))!!["spo2_percent"])
        // time_sync
        val t = 1792528291L
        val tb = byteArrayOf(t.toByte(), (t shr 8).toByte(), (t shr 16).toByte(), (t shr 24).toByte())
        assertEquals(t, Events.decode(0x42, tb)!!["unix_time"])
    }
}

class AnalyticsClockTest {
    @Test fun appStyleSyncTokenIsUndone() {
        val now = 1_792_540_300L
        val low = (now and 0xff).toInt()
        val token = (low * 37 + 0xa5) and 0xff
        val c = (now / 256).toInt()
        val body = byteArrayOf(token.toByte(), c.toByte(), (c shr 8).toByte(), (c shr 16).toByte(), 0, 0, 0, 0, 0xf6.toByte())
        val rawUnix = (token.toLong()) or (c.toLong() shl 8) // what the stored time_sync event decodes to
        assertEquals(now, com.aashish.myrings.data.Analytics.correctedSyncUnix(body, rawUnix))
    }

    @Test fun batteryDrainUsesLatestDischargeRun() {
        val s = listOf(
            com.aashish.myrings.data.BatterySample(0, 40), com.aashish.myrings.data.BatterySample(3600, 100), // charged
            com.aashish.myrings.data.BatterySample(7200, 98), com.aashish.myrings.data.BatterySample(7200 + 7200, 94),
        )
        assertEquals(2.0, com.aashish.myrings.data.Analytics.batteryDrain(s)!!, 0.01)
    }
}
