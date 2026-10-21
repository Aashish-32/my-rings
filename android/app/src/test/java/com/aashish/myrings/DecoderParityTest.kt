package com.aashish.myrings

import com.aashish.myrings.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Decodes real bodies captured from a Ring 4 (exported from the PC's oura.db) and checks the Kotlin decoders
 * produce the same JSON as the Rust reference decoder that stored them.
 */
class DecoderParityTest {
    private val fixtures: List<Triple<Int, ByteArray, String>> by lazy {
        javaClass.getResourceAsStream("/event_fixtures.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .map { l -> val p = l.split('\t'); Triple(p[0].toInt(), p[1].hexToBytes(), p[2]) }
    }

    @Test fun fixturesLoaded() = assertTrue(fixtures.size > 20)

    @Test fun kotlinDecodersMatchRustOnRealData() {
        val covered = mutableSetOf<Int>()
        for ((tag, body, rustJson) in fixtures) {
            val mine = Events.decode(tag, body)
            assertNotNull("tag 0x%02x: Kotlin decoder returned null for %s".format(tag, body.toHex()), mine)
            val a = JSONObject(rustJson)
            val b = JSONObject(toJson(mine))
            assertTrue("tag 0x%02x mismatch\n rust:   %s\n kotlin: %s".format(tag, a, b), a.similar(b))
            covered += tag
        }
        assertTrue("expected several tags covered, got $covered", covered.size >= 8)
    }

    @Test fun debugDataBatteryRecordIsNotAscii() {
        val m = Events.decode(0x61, byteArrayOf(0x24, 0x5e, 0, 0, 0))!!
        assertEquals("battery", m["kind"])
        assertEquals(0x5e, m["pct"])
        assertNull(m["voltage_mv"])
    }

    @Test fun debugDataLogLineWithLevelByte() {
        val body = byteArrayOf(0x04) + "chg_rp;1;6552".toByteArray()
        assertEquals("chg_rp;1;6552", Events.decode(0x61, body)!!["text"])
    }
}
