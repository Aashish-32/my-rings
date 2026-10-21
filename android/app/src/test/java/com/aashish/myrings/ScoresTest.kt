package com.aashish.myrings

import com.aashish.myrings.data.Analytics
import com.aashish.myrings.data.Scores
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class ScoresTest {
    @Test fun interpClampsAndInterpolates() {
        val k = listOf(0.0 to 0.0, 10.0 to 100.0)
        assertEquals(0.0, Scores.interp(-5.0, k), 1e-9)
        assertEquals(50.0, Scores.interp(5.0, k), 1e-9)
        assertEquals(100.0, Scores.interp(50.0, k), 1e-9)
    }

    @Test fun goodNightScoresHighAndShortNightLow() {
        val good = Scores.sleepScore(Scores.SleepInput(480.0, 510.0, 12.0, 0.04, 100.0, 70.0, 3.2))
        val bad = Scores.sleepScore(Scores.SleepInput(270.0, 400.0, 70.0, 0.3, 30.0, 15.0, 7.0))
        assertTrue("good night ${good.value}", good.value!! >= 90)
        assertTrue("bad night ${bad.value}", bad.value!! < 50)
        assertFalse(good.provisional)
    }

    @Test fun sleepWithoutStagesDropsRemAndDeepAndIsProvisional() {
        val s = Scores.sleepScore(Scores.SleepInput(450.0, 480.0, 15.0, 0.05, null, null, 3.0))
        assertNull(s.contributors.first { it.name == "REM sleep" }.value)
        assertTrue(s.provisional)
        assertTrue(s.value!! >= 85) // renormalised over the five contributors that exist
    }

    @Test fun timingIsCircularAroundThreeAm() {
        val at3 = Scores.sleepScore(Scores.SleepInput(480.0, 500.0, 15.0, 0.05, 90.0, 60.0, 3.0))
        val at23 = Scores.sleepScore(Scores.SleepInput(480.0, 500.0, 15.0, 0.05, 90.0, 60.0, 23.0))
        val timing = { s: com.aashish.myrings.data.Score -> s.contributors.first { it.name == "Timing" }.value!! }
        assertEquals(100, timing(at3))
        assertEquals(30, timing(at23)) // 4 h from 03:00, not 20 h
    }

    @Test fun readinessUsesBaselinesWhenPresent() {
        val base = Scores.ReadinessInput(50.0, 50.0, 85, 60.0, 60.0, 0.1, 7.6, 40.0, 5.5, 40.0, 20)
        val r = Scores.readiness(base)
        assertTrue("rested ${r.value}", r.value!! >= 90)
        val sick = Scores.readiness(base.copy(lowestHr = 60.0, hrvRecent = 40.0, tempDeviation = 1.2))
        assertTrue("sick ${sick.value} < rested ${r.value}", sick.value!! < r.value!! - 20)
        assertFalse(r.provisional)
    }

    @Test fun readinessAfterFirstNightIsProvisional() {
        val r = Scores.readiness(Scores.ReadinessInput(55.0, null, 80, 50.0, null, null, 7.0, null, 4.0, null, 0))
        assertNotNull(r.value)
        assertTrue(r.provisional)
        assertNull(r.contributors.first { it.name == "HRV balance" }.value)
    }

    @Test fun stressZones() {
        assertEquals(Scores.Zone.STRESSED, Scores.zoneOf(-3.0, 3.0))
        assertEquals(Scores.Zone.ENGAGED, Scores.zoneOf(-1.0, 3.0))
        assertEquals(Scores.Zone.RELAXED, Scores.zoneOf(1.0, 3.0))
        assertEquals(Scores.Zone.RESTORED, Scores.zoneOf(3.0, 3.0))
        assertEquals("Stressful", Scores.stressSummary(90, 20))
        assertEquals("Restored", Scores.stressSummary(10, 80))
        assertEquals("Normal", Scores.stressSummary(40, 40))
    }

    @Test fun fixedSyncWithF6IsNotMisCorrected() {
        // a post-fix sync from a UTC-5 phone: exact time, f6 trailer; the estimate keeps it as is
        val t = 1_792_542_541L
        val body = ByteArray(8) { (t shr (8 * it)).toByte() } + byteArrayOf(0xf6.toByte())
        assertEquals(t, Analytics.correctedSyncUnix(body, t, t + 3.0))
    }
}

/** End-to-end analytics on a synthetic night + day of ring events. */
class AnalyticsComputeTest {
    private val t0 = 1_792_450_800L // 2026-10-19 22:00 UTC
    private fun le32(v: Long) = ByteArray(4) { (v shr (8 * it)).toByte() }
    private fun ds(unix: Long) = (unix - t0) * 10 // ring deciseconds, anchored at t0

    private fun events(): List<Triple<Int, Long, ByteArray>> {
        val ev = ArrayList<Triple<Int, Long, ByteArray>>()
        val bedStart = t0 + 1800; val bedEnd = bedStart + 8 * 3600
        // skin temperature every minute from 22:00 to 18:00 the next day (worn)
        for (t in t0 until bedEnd + 10 * 3600 step 60) {
            val c = (if (t in bedStart until bedEnd) 35.0 else 33.0) * 100
            ev += Triple(0x46, ds(t), byteArrayOf((c.toInt() and 0xff).toByte(), (c.toInt() shr 8).toByte()))
        }
        // still all night (motion every 30 s, 0 s of movement)
        for (t in bedStart until bedEnd step 30) ev += Triple(0x47, ds(t), byteArrayOf(0x40, 0, 0, 0))
        // the ring's 5-min HR/HRV: HR falls to 48 bpm at 02:00 then rises
        for (k in 0 until 96) {
            val t = bedStart + k * 300
            val hr = 48 + kotlin.math.abs(k - 42) / 6
            ev += Triple(0x5d, ds(t), byteArrayOf(hr.toByte(), 70))
        }
        // daytime green IBIs (quality 1): ~70 bpm with beat-to-beat variation, every 5 s for 2 hours
        val dayStart = bedEnd + 3600
        var k = 0
        for (t in dayStart until dayStart + 2 * 3600 step 5) {
            val body = ByteArray(14)
            for (i in 0 until 7) {
                val ibi = 857 + (if ((k + i) % 2 == 0) 25 else -25)
                body[2 * i] = (ibi shr 3).toByte(); body[2 * i + 1] = ((ibi and 7) or (1 shl 3)).toByte()
            }
            ev += Triple(0x80, ds(t), body); k++
        }
        ev += Triple(0x76, ds(bedEnd + 60), le32(ds(bedStart)) + le32(ds(bedEnd)))
        return ev.sortedBy { it.second }
    }

    private fun run() = Analytics.compute(Analytics.Clock(longArrayOf(0), doubleArrayOf(t0.toDouble())), ZoneOffset.UTC) { sink ->
        events().forEach { (tag, ts, body) -> sink(tag, ts, body) }
    }

    @Test fun nightIsDetectedAndScored() {
        val a = run()
        assertEquals(1, a.nights.size)
        val n = a.nights.single()
        assertEquals("2026-10-20", n.date)
        assertEquals(480.0, n.inBedMin, 0.5)
        assertEquals(48.0, n.lowestHr!!, 1.0)
        assertEquals(70.0, n.hrv!!, 0.1)
        assertNotNull(n.sleepScore.value)
        assertNotNull(n.readiness.value)
        assertTrue(n.readiness.provisional) // first night: no baselines yet
        assertTrue("recovery ${n.recoveryIndexH}", n.recoveryIndexH!! in 4.0..5.0) // minimum ~02:00, wake 06:30
        assertEquals(35.0, n.temp!!, 0.01)
    }

    @Test fun daytimeStressAndRestingHr() {
        val a = run()
        val day = a.days.first { it.date == "2026-10-20" }
        assertEquals("sleep", day.restingSrc)
        assertEquals(48.0, day.restingHr!!, 1.0)
        val s = a.stress["2026-10-20"]!!
        assertTrue("stress samples ${s.samples.size}", s.samples.size >= 20)
        assertTrue(day.wornMin > 600)
        assertTrue(a.intraday["2026-10-20"]!!.hr.isNotEmpty())
    }
}
