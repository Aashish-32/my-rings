package com.aashish.myrings

import com.aashish.myrings.data.*
import org.junit.Assert.*
import org.junit.Test

/** Reference values come from the open_oura Rust tests of the same ecore routines. */
class RingAlgorithmsTest {
    // ---- baseline ----
    @Test fun baselineWarmsUpThenSettles() {
        val b = EmaBaseline()
        b.update(100, 0); assertEquals(400, b.meanX8)   // delta 800 -> +400 (Rust test)
        b.update(100, 0); assertEquals(600, b.meanX8)   // delta 400 -> +200
        repeat(400) { b.update(100, 30) }
        assertEquals(100.0, b.mean, 2.5)                 // settles inside the fixed-point dead band
    }

    @Test fun baselineOfNightlyValuesStartsFromRealData() {
        // a steady 52 bpm resting rate must read as ~52, not be dragged toward zero by an empty start
        val steady = EmaBaseline.of(List(6) { 52.0 })!!
        assertEquals(52.0, steady, 0.3)
        // a change shows up faster during warm-up than a plain average of the same nights would suggest
        val shifting = EmaBaseline.of(listOf(52.0, 52.0, 52.0, 60.0, 60.0, 60.0))!!
        assertTrue("moved toward the new level", shifting > 55.0 && shifting < 60.5)
    }

    @Test fun baselineNeedsEnoughNights() {
        assertNull(EmaBaseline.of(listOf(52.0)))
        assertNull(EmaBaseline.of(listOf(null, 52.0, null)))
        assertNotNull(EmaBaseline.of(listOf(52.0, null, 53.0)))
    }

    @Test fun baselineKeepsDecimalsWithScale() {
        val t = EmaBaseline.of(List(6) { 33.45 }, scale = 100.0)!!
        assertEquals(33.45, t, 0.05)
    }

    // ---- sleep debt ----
    @Test fun fiveNightsOneHourShortGivesThreeHoursRoundedTo45Min() {
        val d = SleepDebt.compute(List(5) { 25_200 }, List(5) { 28_800 })
        assertEquals(10_800, d.debtS)       // weights 1, .8125, .625, .4375, .25 -> 11250 s -> rounded to 10800
        assertEquals(3_600, d.recentShortfallS)
        assertTrue(d.valid)
    }

    @Test fun debtIsCappedAndNeedsFiveNights() {
        val capped = SleepDebt.compute(List(7) { 7_200 }, List(7) { 28_800 })
        // the 10 h cap is applied first and the result is then rounded to 45 minutes, exactly as in ecore: 36000 s -> 13 steps of 2700 s
        assertEquals(35_100, capped.debtS)
        assertFalse(SleepDebt.compute(List(3) { 25_200 }, List(3) { 28_800 }).valid)
    }

    @Test fun sleepingMoreThanNeededDoesNotCreateNegativeDebt() {
        assertEquals(0, SleepDebt.compute(List(6) { 32_400 }, List(6) { 28_800 }).debtS)
    }

    // ---- nightly temperature ----
    @Test fun constantNightGivesThatTemperature() {
        assertEquals(35.0, NightlyTemperature.compute(List(150) { 35.0 })!!, 0.001)
    }

    @Test fun nightlyValueIsTheSmallestWindowMaximum() {
        val s = List(30) { 34.0 } + List(30) { 35.5 } + List(30) { 34.5 } + List(30) { 36.0 } + List(30) { 35.0 }
        assertEquals(34.0, NightlyTemperature.compute(s)!!, 0.001)
    }

    @Test fun windowsWithABigSwingAreRejected() {
        // each window swings by 3 C, above the 2.5 C gate: nothing valid
        val swing = (0 until 150).map { if ((it / 10) % 2 == 0) 33.0 else 36.0 }
        assertNull(NightlyTemperature.compute(swing))
    }

    @Test fun tooFewWindowsGiveNothing() {
        assertNull(NightlyTemperature.compute(List(90) { 35.0 }))
    }

    @Test fun aBriefSpikeIsIgnoredByTheMedian() {
        val s = MutableList(150) { 35.0 }.also { it[40] = 40.0 }
        assertEquals(35.0, NightlyTemperature.compute(s)!!, 0.001)
    }

    // ---- workouts ----
    private val t0 = 1_800_000_000L
    private fun minutes(from: Int, to: Int, met: Double) = (from until to).map { (t0 + it * 60L) to met }

    @Test fun aSustainedEffortIsOneWorkout() {
        val met = minutes(0, 30, 1.2) + minutes(30, 55, 6.0) + minutes(55, 90, 1.2)
        val hr = (30..55 step 5).map { (t0 + it * 60L) to 140.0 }
        val w = Workouts.detect(met, hr, 70.0).single()
        assertEquals(25, w.durationMin)
        assertEquals("Moderate", w.intensity)
        assertEquals(140.0, w.avgHr!!, 0.1)
        assertTrue("${w.kcal}", w.kcal in 140..150)  // (6-1) MET * 25 min * 70 kg / 60 = 146
    }

    @Test fun aBriefDipIsBridgedButALongGapSplits() {
        val bridged = minutes(0, 12, 5.0) + minutes(12, 14, 1.0) + minutes(14, 30, 5.0)
        assertEquals(1, Workouts.detect(bridged, emptyList(), 70.0).size)
        val split = minutes(0, 15, 5.0) + minutes(15, 25, 1.0) + minutes(25, 40, 5.0)
        assertEquals(2, Workouts.detect(split, emptyList(), 70.0).size)
    }

    @Test fun shortBurstsAreNotWorkouts() {
        assertTrue(Workouts.detect(minutes(0, 6, 7.0) + minutes(6, 40, 1.2), emptyList(), 70.0).isEmpty())
    }

    @Test fun aWalkThroughTheDayIsLightNotVigorous() {
        val w = Workouts.detect(minutes(0, 20, 3.5), emptyList(), 70.0).single()
        assertEquals("Light", w.intensity)
    }

    // ---- activity score ----
    @Test fun anActiveDayScoresHigherThanASedentaryOne() {
        val dayMinutes = minutes(0, 600, 1.2).mapIndexed { i, p -> if (i % 50 < 6) p.first to 2.5 else p }
        val workout = Workouts.detect(minutes(100, 140, 6.0), emptyList(), 70.0)
        val active = ActivityScore.score(dayMinutes, activeKcal = 450, targetKcal = 400.0, inactiveMin = 300, weekWorkouts = workout + workout)
        val still = ActivityScore.score(minutes(0, 600, 1.1), activeKcal = 40, targetKcal = 400.0, inactiveMin = 580, weekWorkouts = emptyList())
        assertTrue("${active.value} vs ${still.value}", active.value!! > still.value!! + 20)
        assertTrue(active.provisional)
    }
}
