package com.aashish.myrings

import com.aashish.myrings.ble.OuraClient
import com.aashish.myrings.data.MotionLevel
import com.aashish.myrings.data.MotionMeter
import com.aashish.myrings.protocol.Req
import com.aashish.myrings.protocol.hexToBytes
import com.aashish.myrings.protocol.toHex
import org.junit.Assert.*
import org.junit.Test

class MotionMeterTest {
    @Test fun aHandHeldStillIsStillEvenWhenTiltedDifferently() {
        // real resting readings from a Ring 4 (about 1 g in total, drifting by a few counts)
        val m = MotionMeter()
        val rest = listOf(Triple(-206, -284, 942), Triple(-190, -280, 951), Triple(-220, -294, 928), Triple(-215, -284, 924), Triple(-233, -295, 921), Triple(-235, -294, 924))
        rest.forEachIndexed { i, (x, y, z) -> m.add(x, y, z, nowMs = 1000L + i * 100) }
        assertEquals(MotionLevel.STILL, m.level(nowMs = 1600))
    }

    @Test fun wavingIsMoving() {
        // readings captured while the hand was waved: large swings from one sample to the next
        val m = MotionMeter()
        val wave = listOf(Triple(-251, -21, 980), Triple(-342, 16, 1010), Triple(-281, 125, 974), Triple(-343, 100, 953), Triple(-416, -13, 926), Triple(-102, -52, 948), Triple(-87, -235, 1103))
        wave.forEachIndexed { i, (x, y, z) -> m.add(x, y, z, nowMs = 1000L + i * 100) }
        assertEquals(MotionLevel.MOVING, m.level(nowMs = 1700))
    }

    @Test fun steadyTiltChangeAloneIsNotMotion() {
        // rotating slowly changes the axes but not their sample-to-sample change by much
        val m = MotionMeter()
        for (i in 0 until 12) m.add(-200 + i * 4, -280, 940 - i * 2, nowMs = 1000L + i * 80)
        assertEquals(MotionLevel.STILL, m.level(nowMs = 1960))
    }

    @Test fun oldSamplesExpireAndTooFewSamplesGiveNoLevel() {
        val m = MotionMeter()
        assertNull(m.level())
        for (i in 0 until 6) m.add(i * 300, 0, 1000, nowMs = 1000L + i * 100)
        assertEquals(MotionLevel.MOVING, m.level(nowMs = 1550))
        assertNull(m.level(nowMs = 9000)) // the window has emptied
    }

    @Test fun acmFramesParseAndRealtimeRequestsMatchTheReference() {
        // ACM indication: 33 len rate seq then x,y,z i16 LE (two samples)
        val f = "33 0e 32 01 ea ff e4 fe ae 03 f0 ff e0 fe b0 03".hexToBytes()
        val s = OuraClient.parseAcmFrame(f)
        assertEquals(2, s.size)
        assertEquals(Triple(-22, -284, 942), s[0])
        assertEquals(Triple(-16, -288, 944), s[1])
        assertTrue(OuraClient.parseAcmFrame("2f0102".hexToBytes()).isEmpty())
        // requests from the open_oura Rust tests
        assertEquals("060720000000010000", Req.setRealtime(0x20, 1, 0).toHex())
        assertEquals("060400000000", Req.realtimeOff().toHex())
    }
}
