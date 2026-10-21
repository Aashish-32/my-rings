package com.aashish.myrings

import com.aashish.myrings.data.SleepStager
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * Simulated nights with known stages. Physiology per stage (30-s epoch means): wake HR +12 bpm and moving;
 * light HR at baseline; deep HR −5, steady, slightly lower 5-min HRV, still; REM HR +4 and irregular, lower HRV,
 * still. (HRV levels follow the ECG + polysomnography data the HRV weights were fitted on.)
 * Cycles of ~90 min with deep shrinking and REM growing across the night, as in a normal hypnogram.
 * This checks the stager's mechanics, not its accuracy on real people (that needs polysomnography).
 */
class SleepStagerTest {
    private fun night(seed: Int): Triple<List<String>, Triple<DoubleArray, DoubleArray, IntArray>, Int> {
        val rnd = Random(seed)
        val truth = ArrayList<String>()
        fun add(stage: String, min: Int) = repeat(min * 2) { truth += stage }
        add("awake", 12)
        val deep = listOf(45, 35, 18, 6, 0); val rem = listOf(8, 18, 25, 30, 32)
        for (c in 0 until 5) { add("light", 25); add("deep", deep[c]); add("light", 12); add("rem", rem[c]); if (c == 2) add("awake", 4) }
        add("awake", 6)
        val n = truth.size
        val base = 56.0
        val hr = DoubleArray(n); val rm = DoubleArray(n); val mo = IntArray(n)
        for (i in 0 until n) {
            when (truth[i]) {
                "awake" -> { hr[i] = base + 12 + rnd.nextDouble(-3.0, 3.0); rm[i] = 35 + rnd.nextDouble(-8.0, 8.0); mo[i] = 6 + rnd.nextInt(15) }
                "light" -> { hr[i] = base + rnd.nextDouble(-1.5, 1.5); rm[i] = 44 + rnd.nextDouble(-6.0, 6.0); mo[i] = if (rnd.nextDouble() < 0.06) 1 + rnd.nextInt(3) else 0 }
                "deep" -> { hr[i] = base - 5 + rnd.nextDouble(-0.6, 0.6); rm[i] = 38 + rnd.nextDouble(-5.0, 5.0); mo[i] = 0 }
                "rem" -> { hr[i] = base + 4 + rnd.nextDouble(-4.5, 4.5); rm[i] = 32 + rnd.nextDouble(-6.0, 6.0); mo[i] = if (rnd.nextDouble() < 0.05) 1 else 0 }
            }
        }
        // the ring drops some beats: ~10 % of epochs have no heart rate
        for (i in 0 until n) if (rnd.nextDouble() < 0.1) hr[i] = Double.NaN
        return Triple(truth, Triple(hr, rm, mo), n)
    }

    @Test fun recoversSimulatedHypnogram() {
        var agreeSum = 0.0
        for (seed in 1..5) {
            val (truth, sig, n) = night(seed)
            val out = SleepStager.stage(sig.first, sig.second, sig.third)!!
            assertEquals(n, out.size)
            val agree = truth.indices.count { truth[it] == out[it] }.toDouble() / n
            agreeSum += agree
            val asleep = out.count { it != "awake" }.toDouble()
            val deepFrac = out.count { it == "deep" } / asleep; val remFrac = out.count { it == "rem" } / asleep
            assertTrue("seed $seed deep $deepFrac", deepFrac in 0.05..0.30)
            assertTrue("seed $seed rem $remFrac", remFrac in 0.08..0.35)
            // deep is front-loaded, REM back-loaded
            val half = n / 2
            assertTrue(out.subList(0, half).count { it == "deep" } > out.subList(half, n).count { it == "deep" })
            assertTrue(out.subList(half, n).count { it == "rem" } > out.subList(0, half).count { it == "rem" })
            // first REM no earlier than 60 min after onset
            val onset = out.indexOfFirst { it != "awake" }
            assertTrue(out.indexOfFirst { it == "rem" } - onset >= 120)
        }
        val mean = agreeSum / 5
        println("SIM_AGREEMENT %.3f".format(mean))
        assertTrue("mean epoch agreement $mean", mean >= 0.70)
    }

    @Test fun tooLittleHeartDataReturnsNull() {
        val n = 600
        assertNull(SleepStager.stage(DoubleArray(n) { Double.NaN }, DoubleArray(n) { Double.NaN }, IntArray(n)))
    }
}
