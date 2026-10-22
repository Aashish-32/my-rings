package com.aashish.myrings

import com.aashish.myrings.data.CalmBeat
import com.aashish.myrings.data.CalmSession
import org.junit.Assert.*
import org.junit.Test

class CalmSessionTest {
    /** 60 s of beats: steady fast beats first, then a slower and more varied rhythm, as slow breathing tends to produce. */
    private fun session(startIbi: Int, endIbi: Int, startVar: Int, endVar: Int): List<CalmBeat> {
        val out = ArrayList<CalmBeat>(); var t = 0L; var i = 0
        while (t < 60_000) {
            val p = t / 60_000.0
            val ibi = (startIbi + (endIbi - startIbi) * p + (if (i % 2 == 0) 1 else -1) * (startVar + (endVar - startVar) * p)).toInt()
            out += CalmBeat(t, ibi); t += ibi; i++
        }
        return out
    }

    @Test fun aSessionThatSettlesShowsLowerHrAndHigherHrv() {
        val r = CalmSession.summarize(session(startIbi = 800, endIbi = 950, startVar = 15, endVar = 45))
        assertTrue("hr ${r.hrStart} -> ${r.hrEnd}", r.hrEnd!! < r.hrStart!!)
        assertTrue("hrv ${r.hrvStart} -> ${r.hrvEnd}", r.hrvEnd!! > r.hrvStart!! * 1.5)
        assertTrue(CalmSession.message(r).contains("calm"))
    }

    @Test fun noChangeIsReportedAsLittleChange() {
        val r = CalmSession.summarize(session(850, 850, 25, 25))
        assertTrue(CalmSession.message(r).startsWith("Little change"))
    }

    @Test fun tooFewBeatsGivesNoComparison() {
        val r = CalmSession.summarize(listOf(CalmBeat(0, 800), CalmBeat(800, 810), CalmBeat(1600, 790)))
        assertNull(r.hrvChangePct)
        assertTrue(CalmSession.message(r).startsWith("Not enough"))
    }

    @Test fun rmssdMatchesAHandCalculationAndSkipsJumps() {
        // successive differences 20, -20, 20, -20, 20, -20, 20 -> RMSSD 20
        val ibis = listOf(800, 820, 800, 820, 800, 820, 800, 820)
        assertEquals(20.0, CalmSession.rmssd(ibis)!!, 0.01)
        // a doubled beat (1600) is a >25% jump and is left out instead of inflating HRV
        val withJump = ibis.take(4) + 1600 + ibis.drop(4)
        assertEquals(20.0, CalmSession.rmssd(withJump)!!, 0.5)
    }

    @Test fun breathingGuideBreathesInThenOutSmoothly() {
        assertEquals(0.0, CalmSession.breathFraction(0.0), 1e-9)
        assertEquals(1.0, CalmSession.breathFraction(CalmSession.BREATH_IN_S), 1e-9)
        assertEquals(0.0, CalmSession.breathFraction(CalmSession.BREATH_IN_S + CalmSession.BREATH_OUT_S), 1e-9)
        assertEquals("Breathe in", CalmSession.phase(1.0))
        assertEquals("Breathe out", CalmSession.phase(7.0))
        assertTrue(CalmSession.breathFraction(1.0) < CalmSession.breathFraction(3.0)) // rising while breathing in
    }
}
