package com.aashish.myrings

import com.aashish.myrings.data.Drift
import org.junit.Assert.*
import org.junit.Test

class DriftTest {
    private val steady = List(7) { 54.0 }

    @Test fun steadyValuesGiveNoNote() {
        assertNull(Drift.note(steady + listOf(54.0, 55.0, 54.0), Drift.RestingHr))
    }

    @Test fun threeDaysAboveUsualGivesANote() {
        val n = Drift.note(steady + listOf(60.0, 61.0, 60.0), Drift.RestingHr)!!
        assertTrue(n, n.contains("6 bpm above"))
    }

    @Test fun oneOutlierDayIsIgnored() {
        assertNull(Drift.note(steady + listOf(54.0, 54.0, 70.0), Drift.RestingHr))
    }

    @Test fun needsEnoughHistory() {
        assertNull(Drift.note(listOf(54.0, 54.0, 60.0, 60.0, 60.0), Drift.RestingHr))
    }

    @Test fun lowerThanUsualIsReportedToo() {
        val n = Drift.note(List(7) { 33.0 } + listOf(32.4, 32.5, 32.4), Drift.Temperature)!!
        assertTrue(n, n.contains("below your usual"))
    }

    @Test fun missingDaysAreSkipped() {
        val v: List<Double?> = steady + listOf(null, 60.0, 61.0, null, 60.0)
        assertNotNull(Drift.note(v, Drift.RestingHr))
    }
}
