package com.aashish.myrings

import com.aashish.myrings.data.Analytics
import com.aashish.myrings.data.Metabolic
import com.aashish.myrings.data.Profile
import com.aashish.myrings.data.Scores
import com.aashish.myrings.data.Sex
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ProfileTest {
    @Test fun bmrMatchesOpenOuraVector() {
        // open_oura metabolic.rs test: 40 y male, 80 kg -> 11.472*80 + 873.1
        assertEquals(1790.86, Metabolic.bmrSchofield(40, Sex.MALE, 80.0), 0.01)
        // unspecified sex averages the two equations
        val avg = (Metabolic.bmrSchofield(40, Sex.MALE, 80.0) + Metabolic.bmrSchofield(40, Sex.FEMALE, 80.0)) / 2
        assertEquals(avg, Metabolic.bmrSchofield(40, Sex.UNSPECIFIED, 80.0), 1e-9)
    }

    @Test fun vo2maxMatchesOpenOura() {
        val v = Metabolic.vo2maxJackson(30, Sex.MALE, 75.0)!! // open_oura: 30 y male 75 kg -> 44..50
        assertTrue("$v", v in 44.0..50.0)
        assertNull(Metabolic.vo2maxJackson(30, Sex.UNSPECIFIED, 75.0))
    }

    @Test fun ringFactoryProfileIsRecognised() {
        assertTrue(Metabolic.isRingFactoryProfile(40, 75, 2, 176))
        assertFalse(Metabolic.isRingFactoryProfile(31, 82, 0, 180))
    }

    @Test fun sleepTargetsFollowAge() {
        val night = Scores.SleepInput(7.5 * 60, 8 * 60.0, 15.0, 0.05, 95.0, 50.0, 3.0)
        val total = { age: Int? -> Scores.sleepScore(night.copy(ageYears = age)).contributors.first { it.name == "Total sleep" }.value!! }
        val deep = { age: Int? -> Scores.sleepScore(night.copy(ageYears = age)).contributors.first { it.name == "Deep sleep" }.value!! }
        assertTrue("teen needs more sleep", total(16) < total(35))
        assertTrue("70-year-old needs less", total(70) >= total(35))
        assertTrue("50 min deep is better for a 70-year-old than a 25-year-old", deep(70) > deep(25))
    }

    /** One awake hour at MET 3, worn: calories must scale with the profile weight, and BMR adds to total burn. */
    @Test fun caloriesFollowProfileWeight() {
        val t0 = LocalDate.of(2026, 10, 20).atStartOfDay(ZoneOffset.UTC).toEpochSecond() + 9 * 3600
        fun run(p: Profile) = Analytics.compute(Analytics.Clock(longArrayOf(0), doubleArrayOf(t0.toDouble())), ZoneOffset.UTC, p) { sink ->
            for (k in 0 until 60) {
                val t = (k * 60L) * 10
                sink(0x46, t, byteArrayOf((3300 and 0xff).toByte(), (3300 shr 8).toByte())) // 33 °C: worn
                sink(0x50, t + 5, byteArrayOf(0, 30)) // state byte + one minute at MET 3.0
            }
        }.days.single()
        val light = run(Profile(weightKg = 60.0, birthYear = 1990, sex = Sex.FEMALE))
        val heavy = run(Profile(weightKg = 90.0, birthYear = 1990, sex = Sex.FEMALE))
        assertEquals(120, light.activeKcal) // 60 min × (3−1) × 60 kg / 60
        assertEquals(180, heavy.activeKcal)
        assertTrue(heavy.totalKcal!! > heavy.activeKcal!!) // resting burn included
        val def = Analytics.compute(Analytics.Clock(longArrayOf(0), doubleArrayOf(t0.toDouble())), ZoneOffset.UTC) { sink ->
            sink(0x50, 5, byteArrayOf(0, 30)); sink(0x50, 605, byteArrayOf(0, 30))
        }
        assertEquals("default", def.profile!!.weightFrom)
        assertEquals(70.0, def.profile!!.weightKg, 0.0)
    }
}
