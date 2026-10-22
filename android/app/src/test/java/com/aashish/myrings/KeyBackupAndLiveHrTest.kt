package com.aashish.myrings

import com.aashish.myrings.ble.OuraClient
import com.aashish.myrings.data.KeyBackup
import com.aashish.myrings.protocol.hexToBytes
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class KeyBackupAndLiveHrTest {
    private val key = "4431967d8bacc2659743142b68391d9a"

    @Test fun backupRoundTrips() {
        val text = KeyBackup.build("Aashish Ring", "4017082615011812", key, LocalDate.of(2026, 10, 21))
        assertEquals(key, KeyBackup.extract(text))
        assertEquals("4017082615011812", KeyBackup.serialOf(text))
        assertTrue(text.contains("Aashish Ring"))
    }

    @Test fun extractAcceptsABareKeyAndIgnoresCase() {
        assertEquals(key, KeyBackup.extract("  " + key.uppercase() + "  "))
    }

    @Test fun extractRefusesAmbiguousText() {
        assertNull(KeyBackup.extract("$key and also 00112233445566778899aabbccddeeff"))
        assertNull(KeyBackup.extract("nothing here"))
        assertNull(KeyBackup.extract("not32hex"))
    }

    @Test fun serialNumberIsNotMistakenForAKey() {
        // a 16-digit serial is not 32 hex characters
        assertNull(KeyBackup.extract("Serial: 4017082615011812"))
    }

    // ---- live heart rate frame (vector from the open_oura Rust tests) ----
    @Test fun liveHrFrameParses() {
        val s = OuraClient.parseLiveHrFrame("2f08280200020000".hexToBytes() + "5913".hexToBytes())!!
        assertEquals(857, s.ibiMs)   // (0x3 << 8) | 0x59
        assertEquals(70, s.bpm)      // 60000 / 857
    }

    @Test fun liveHrIgnoresInvalidOrForeignFrames() {
        // validity nibble 0 (not VALID)
        assertNull(OuraClient.parseLiveHrFrame("2f08280200020000".hexToBytes() + "5903".hexToBytes()))
        // wrong feature id
        assertNull(OuraClient.parseLiveHrFrame("2f08280400020000".hexToBytes() + "5913".hexToBytes()))
        // not a live-HR sub tag
        assertNull(OuraClient.parseLiveHrFrame("2f08290200020000".hexToBytes() + "5913".hexToBytes()))
        // implausible interval (100 ms)
        assertNull(OuraClient.parseLiveHrFrame("2f08280200020000".hexToBytes() + "6410".hexToBytes()))
    }
}
