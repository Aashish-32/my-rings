package com.aashish.myrings

import com.aashish.myrings.data.Drift
import com.aashish.myrings.data.Intraday
import org.junit.Assert.*
import org.junit.Test

class DriftTest {
    // ---- last night's lowest heart rate ----
    private val usual = List(7) { 52.0 }

    @Test fun restingHrAtUsualGivesNoNote() = assertNull(Drift.restingNote(usual + 53.0))

    @Test fun restingHrWellAboveUsualGivesANoteTheNextMorning() {
        val n = Drift.restingNote(usual + 58.0)!!
        assertTrue(n, n.contains("6 bpm above your usual"))
    }

    @Test fun aLowerRestingHrIsGoodNewsNotAWarning() = assertNull(Drift.restingNote(usual + 44.0))

    @Test fun needsAFewPriorNightsToKnowTheUsual() = assertNull(Drift.restingNote(listOf(52.0, 60.0)))

    @Test fun nightsWithoutAReadingAreSkipped() {
        val v: List<Double?> = usual + listOf(null, 59.0)
        assertNotNull(Drift.restingNote(v))
    }

    // ---- last night's skin temperature ----
    @Test fun temperatureNotes() {
        assertTrue(Drift.tempNote(0.6)!!.contains("above your normal"))
        assertTrue(Drift.tempNote(-0.7)!!.contains("below your normal"))
        assertNull(Drift.tempNote(0.3))
        assertNull(Drift.tempNote(null))
    }

    // ---- heart rate that does not come back down ----
    private val t0 = 1_800_000_000L                 // start of the exercise
    private val exerciseEnd = t0 + 30 * 60          // 30 min of effort

    /** One MET value per minute: [segments] are (fromMin, toMin, MET). */
    private fun met(vararg segments: Triple<Int, Int, Double>) =
        segments.flatMap { (a, b, v) -> (a until b).map { (t0 + it * 60L) to v } }

    private fun hr(vararg points: Pair<Int, Double>) = points.map { (m, v) -> (t0 + m * 60L) to v }

    private val baseline = 60.0

    @Test fun heartRateThatSettlesAfterExerciseIsNotFlagged() {
        val day = Intraday(
            hr = hr(0 to 70.0, 10 to 130.0, 20 to 135.0, 30 to 128.0, 45 to 95.0, 60 to 78.0, 75 to 66.0, 90 to 63.0, 105 to 62.0, 120 to 61.0, 135 to 61.0),
            temp = emptyList(),
            met = met(Triple(0, 5, 1.0), Triple(5, 30, 6.0), Triple(30, 150, 1.0)),
            alerts = emptyList(),
        )
        assertNull(Drift.notRecovering(day, baseline, now = t0 + 140 * 60))
    }

    @Test fun heartRateThatStaysUpWhileStillIsFlagged() {
        val day = Intraday(
            hr = hr(10 to 130.0, 30 to 128.0, 45 to 100.0, 80 to 82.0, 85 to 81.0, 90 to 83.0, 95 to 82.0, 100 to 84.0, 105 to 83.0, 110 to 82.0),
            temp = emptyList(),
            met = met(Triple(5, 30, 6.0), Triple(30, 130, 1.0)),
            alerts = emptyList(),
        )
        val n = Drift.notRecovering(day, baseline, now = t0 + 112 * 60)
        assertNotNull(n)
        assertTrue(n!!, n.contains("still"))
    }

    @Test fun elevatedHeartRateInsideTheRecoveryWindowIsNormal() {
        // 20 minutes after exercise ended: heart rate is high, as it should be
        val day = Intraday(
            hr = hr(10 to 130.0, 30 to 128.0, 40 to 105.0, 45 to 100.0, 50 to 98.0),
            temp = emptyList(),
            met = met(Triple(5, 30, 6.0), Triple(30, 55, 1.0)),
            alerts = emptyList(),
        )
        assertNull(Drift.notRecovering(day, baseline, now = t0 + 52 * 60))
    }

    @Test fun elevatedHeartRateWhileMovingIsNotCountedAsStill() {
        val day = Intraday(
            hr = hr(80 to 120.0, 85 to 122.0, 90 to 121.0, 95 to 123.0, 100 to 122.0, 105 to 121.0),
            temp = emptyList(),
            met = met(Triple(75, 115, 4.0)), // a brisk walk the whole time
            alerts = emptyList(),
        )
        assertNull(Drift.notRecovering(day, baseline, now = t0 + 110 * 60))
    }

    @Test fun noBaselineMeansNoClaim() {
        val day = Intraday(hr = hr(80 to 90.0, 100 to 90.0), temp = emptyList(), met = met(Triple(70, 120, 1.0)), alerts = emptyList())
        assertNull(Drift.notRecovering(day, baseline = null, now = t0 + 110 * 60))
    }

    @Test fun baselineComesFromPreviousStillPeriods() {
        val quiet = Intraday(
            hr = (0 until 20).map { (t0 + it * 300L) to 60.0 + (it % 3) },
            temp = emptyList(), met = met(Triple(0, 120, 1.0)), alerts = emptyList(),
        )
        val b = Drift.stillBaseline(listOf(quiet))!!
        assertEquals(61.0, b, 1.5)
        assertNull(Drift.stillBaseline(emptyList()))
    }
}
