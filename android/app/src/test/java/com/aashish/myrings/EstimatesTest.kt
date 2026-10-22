package com.aashish.myrings

import com.aashish.myrings.data.ResilienceEstimate
import com.aashish.myrings.data.StressDay
import com.aashish.myrings.data.Steps
import org.junit.Assert.*
import org.junit.Test

class EstimatesTest {
    @Test fun nothingBelowWalkingEffort() {
        assertEquals(0.0, Steps.cadence(1.2), 1e-9)   // sitting
        assertEquals(0.0, Steps.cadence(2.0), 1e-9)   // fidgeting
    }

    @Test fun cadenceRisesWithEffortAndStaysBelowARunnersCeiling() {
        assertEquals(100.0, Steps.cadence(3.3), 1e-9) // a brisk walk
        assertTrue(Steps.cadence(3.0) < Steps.cadence(4.0))
        assertEquals(175.0, Steps.cadence(20.0), 1e-9)
    }

    @Test fun tenMinutesOfBriskWalkingIsAboutAThousandSteps() {
        val walk = List(10) { 3.3 }
        assertEquals(1000, Steps.estimate(walk))
        assertEquals(1100, Steps.estimate(walk, 1.1)) // a calibrated scale applies
    }

    @Test fun calibrationScaleIsBoundedAndNeedsEnoughData() {
        assertEquals(1.1, Steps.calibrationScale(1100, 1000.0)!!, 1e-9)
        assertEquals(2.0, Steps.calibrationScale(5000, 1000.0)!!, 1e-9) // clamped
        assertNull(Steps.calibrationScale(10, 1000.0))                  // too few counted
        assertNull(Steps.calibrationScale(500, 5.0))                    // the walk barely registered
    }

    private fun stress(calm: Int, busy: Int) = StressDay("d", busy, 0, 0, calm, "Normal", emptyList(), null, null)

    @Test fun resilienceLevelsAndDaytimeRecovery() {
        assertEquals("Limited", ResilienceEstimate.level(39))
        assertEquals("Solid", ResilienceEstimate.level(60))
        assertEquals("Exceptional", ResilienceEstimate.level(90))
        assertEquals(100, ResilienceEstimate.daytimeRecovery(listOf(stress(calm = 360, busy = 240))))  // 60 % calm
        assertEquals(50, ResilienceEstimate.daytimeRecovery(listOf(stress(calm = 180, busy = 420))))   // 30 % calm
        assertNull(ResilienceEstimate.daytimeRecovery(listOf(stress(calm = 10, busy = 10))))             // too little to say
    }

    @Test fun hrvBalanceIsLevelWhenRecentMatchesAverageAndFallsWhenItDrops() {
        assertEquals(70, ResilienceEstimate.hrvBalance(List(7) { 50.0 }))
        assertTrue(ResilienceEstimate.hrvBalance(List(4) { 60.0 } + List(3) { 45.0 })!! < 70)
        assertNull(ResilienceEstimate.hrvBalance(listOf(50.0, 52.0)))
    }
}
