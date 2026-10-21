package com.aashish.myrings

import com.aashish.myrings.data.SleepStager
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Opt-in evaluation of [SleepStager] against polysomnography: Walch et al. 2019, PhysioNet `sleep-accel`
 * (Apple Watch heart rate + motion, lab-scored stages). Set SLEEP_EVAL_DIR to a folder of per-subject CSVs
 * (`epoch,label,hr,m0.005,m0.01,m0.02,m0.04,m0.08`; label: awake/light/deep/rem or empty) to run it.
 * Skipped otherwise, so the normal test run never needs the dataset.
 *
 * The watch gives no beat-to-beat intervals, so RMSSD is absent here; this tunes the HR + motion parts.
 *
 * Reproduce (scripts in `android/tools/sleep-eval`; data is ODC-By, cite Walch et al., SLEEP 2019):
 *   bash fetch_all.sh <work>                       # labels + HR, motion reduced on the fly (AWS open-data mirror)
 *   python merge.py <work>/sleep-accel/raw <out>   # one CSV per night
 *   SLEEP_EVAL_DIR=<out> SLEEP_EVAL_ITERS=3000 ./gradlew testDebugUnitTest --tests '*StagerEvalTest*'
 */
class StagerEvalTest {
    private val stages = listOf("awake", "light", "deep", "rem")
    /** Motion column the defaults were fitted with: seconds whose wrist-acceleration range exceeds 0.08 g. */
    private val MOTION_COL = 4

    class Night(val id: String, val label: List<String?>, val hr: DoubleArray, val motion: List<IntArray>,
                val rmssd: DoubleArray? = null)

    private fun load(dir: File): List<Night> = dir.listFiles { f -> f.name.endsWith(".csv") }!!.sortedBy { it.name }.map { f ->
        val rows = f.readLines().drop(1).map { it.split(',') }
        Night(
            f.nameWithoutExtension,
            rows.map { it[1].ifEmpty { null } },
            rows.map { it[2].toDoubleOrNull() ?: Double.NaN }.toDoubleArray(),
            (0 until 5).map { k -> rows.map { r -> r[3 + k].toIntOrNull() ?: -1 }.toIntArray() },
            if (rows.firstOrNull()?.size ?: 0 > 8) rows.map { it.getOrNull(8)?.toDoubleOrNull() ?: Double.NaN }.toDoubleArray() else null,
        )
    }

    data class Result(val acc: Double, val kappa: Double, val recall: Map<String, Double>, val deepMin: Pair<Double, Double>, val remMin: Pair<Double, Double>, val nights: Int)

    private fun eval(nights: List<Night>, p: SleepStager.Params, motionCol: Int): Result {
        val cm = Array(4) { IntArray(4) }
        var deepT = 0.0; var deepP = 0.0; var remT = 0.0; var remP = 0.0; var used = 0
        for (n in nights) {
            val out = SleepStager.stage(n.hr, DoubleArray(n.hr.size) { Double.NaN }, n.motion[motionCol], p) ?: continue
            used++
            for (i in out.indices) {
                val t = n.label[i] ?: continue
                cm[stages.indexOf(t)][stages.indexOf(out[i])]++
            }
            deepT += n.label.count { it == "deep" } * 0.5; deepP += out.count { it == "deep" } * 0.5
            remT += n.label.count { it == "rem" } * 0.5; remP += out.count { it == "rem" } * 0.5
        }
        val tot = cm.sumOf { it.sum() }.toDouble()
        val po = (0 until 4).sumOf { cm[it][it] } / tot
        val pe = (0 until 4).sumOf { i -> cm[i].sum() / tot * (0 until 4).sumOf { cm[it][i] } / tot }
        val recall = stages.indices.associate { i -> stages[i] to cm[i][i] / cm[i].sum().coerceAtLeast(1).toDouble() }
        val k = used.coerceAtLeast(1)
        return Result(po, (po - pe) / (1 - pe), recall, deepT / k to deepP / k, remT / k to remP / k, used)
    }

    /** Staging within sleep only (light / deep / REM where both the lab and the stager say asleep): what HRV should help. */
    data class SleepResult(val kappa: Double, val acc: Double, val deep: Double, val rem: Double, val light: Double,
                           val deepMin: Pair<Double, Double>, val remMin: Pair<Double, Double>, val nights: Int)

    private fun evalSleep(nights: List<Night>, p: SleepStager.Params, useHrv: Boolean): SleepResult =
        evalSleepEach(nights.map { it to p }, useHrv)

    /** Like [evalSleep] but each night carries its own parameters (cross-validation: the fold's tuned params). */
    private fun evalSleepEach(runs: List<Pair<Night, SleepStager.Params>>, useHrv: Boolean): SleepResult {
        val cls = listOf("light", "deep", "rem")
        val cm = Array(3) { IntArray(3) }
        var dT = 0.0; var dP = 0.0; var rT = 0.0; var rP = 0.0; var used = 0
        for ((n, p) in runs) {
            val rm = if (useHrv && n.rmssd != null) n.rmssd else DoubleArray(n.hr.size) { Double.NaN }
            val out = SleepStager.stage(n.hr, rm, IntArray(n.hr.size), p) ?: continue
            used++
            for (i in out.indices) {
                val t = cls.indexOf(n.label[i] ?: continue); val o = cls.indexOf(out[i])
                if (t >= 0 && o >= 0) cm[t][o]++
            }
            dT += n.label.count { it == "deep" } * 0.5; dP += out.count { it == "deep" } * 0.5
            rT += n.label.count { it == "rem" } * 0.5; rP += out.count { it == "rem" } * 0.5
        }
        val tot = cm.sumOf { it.sum() }.toDouble()
        val po = (0 until 3).sumOf { cm[it][it] } / tot
        val pe = (0 until 3).sumOf { i -> cm[i].sum() / tot * (0 until 3).sumOf { cm[it][i] } / tot }
        fun rec(i: Int) = cm[i][i] / cm[i].sum().coerceAtLeast(1).toDouble()
        val k = used.coerceAtLeast(1)
        return SleepResult((po - pe) / (1 - pe), po, rec(1), rec(2), rec(0), dT / k to dP / k, rT / k to rP / k, used)
    }

    private fun fmtS(r: SleepResult) = "kappa %.3f  acc %.3f  recall light %.2f deep %.2f rem %.2f  deep %.0f→%.0f min  rem %.0f→%.0f min  (%d nights)".format(
        r.kappa, r.acc, r.light, r.deep, r.rem, r.deepMin.first, r.deepMin.second, r.remMin.first, r.remMin.second, r.nights)

    /**
     * Opt-in: does HRV help? Set SLEEP_EVAL_HRV_DIR to per-night CSVs with an `rmssd` column built from ECG beats
     * (tools/sleep-eval: slpdb_to_epochs.py, capslpdb_to_epochs.py). Tunes only the two HRV weights on half the
     * nights and reports the other half, against the same stager with HRV switched off.
     */
    @Test fun evaluateHrv() {
        val dirs = System.getenv("SLEEP_EVAL_HRV_DIR")?.split(';')?.map(::File).orEmpty().filter { it.isDirectory }
        assumeTrue("SLEEP_EVAL_HRV_DIR not set", dirs.isNotEmpty())
        val all = dirs.flatMap { load(it) }.filter { it.rmssd != null }
        val train = all.filterIndexed { i, _ -> i % 2 == 0 }; val test = all.filterIndexed { i, _ -> i % 2 == 1 }
        val d = SleepStager.DEFAULT
        println("HRV off      all:  ${fmtS(evalSleep(all, d, false))}")
        println("HRV default  all:  ${fmtS(evalSleep(all, d, true))}")
        var best = d; var bestK = evalSleep(train, d, true).kappa
        for (dr in listOf(-1.0, -0.5, -0.25, 0.0, 0.25, 0.5, 0.7, 1.0, 1.5)) for (rr in listOf(-1.0, -0.5, -0.25, 0.0, 0.25, 0.5, 1.0, 1.5)) {
            val p = d.copy(deepRm = dr, remRm = rr)
            val k = evalSleep(train, p, true).kappa
            if (k > bestK) { bestK = k; best = p }
        }
        println("HRV tuned    deepRm=${best.deepRm} remRm=${best.remRm}")
        println("HRV off      test: ${fmtS(evalSleep(test, d, false))}")
        println("HRV default  test: ${fmtS(evalSleep(test, d, true))}")
        println("HRV tuned    train: ${fmtS(evalSleep(train, best, true))}")
        println("HRV tuned    test: ${fmtS(evalSleep(test, best, true))}")
        println("HRV tuned    all:  ${fmtS(evalSleep(all, best, true))}")

        // 5-fold cross-validation over every night: each night is scored with weights tuned without it
        val grid = listOf(-2.0, -1.5, -1.0, -0.5, 0.0, 0.5, 1.0, 1.5, 2.0)
        val folds = all.indices.groupBy { it % 5 }.values.map { idx -> idx.map { all[it] } }
        val cv = ArrayList<Pair<Night, SleepStager.Params>>()
        for (f in folds) {
            val tr = all - f.toSet()
            var bp = d; var bk = Double.NEGATIVE_INFINITY
            for (dr in grid) for (rr in grid) { val p = d.copy(deepRm = dr, remRm = rr); val k = evalSleep(tr, p, true).kappa; if (k > bk) { bk = k; bp = p } }
            println("CV fold tuned deepRm=${bp.deepRm} remRm=${bp.remRm}")
            f.forEach { cv += it to bp }
        }
        println("CV  HRV off      : ${fmtS(evalSleep(all, d, false))}")
        println("CV  HRV default  : ${fmtS(evalSleep(all, d, true))}")
        println("CV  HRV tuned    : ${fmtS(evalSleepEach(cv, true))}")
        for (dr in listOf(-1.0, -0.5)) for (rr in listOf(0.5, 1.0)) println("FIXED deepRm=$dr remRm=$rr : ${fmtS(evalSleep(all, d.copy(deepRm = dr, remRm = rr), true))}")
    }

    private fun fmt(r: Result) = "acc %.3f  kappa %.3f  recall wake %.2f light %.2f deep %.2f rem %.2f  deep %.0f→%.0f min  rem %.0f→%.0f min  (%d nights)".format(
        r.acc, r.kappa, r.recall["awake"], r.recall["light"], r.recall["deep"], r.recall["rem"], r.deepMin.first, r.deepMin.second, r.remMin.first, r.remMin.second, r.nights)

    @Test fun evaluateAndTune() {
        val dir = System.getenv("SLEEP_EVAL_DIR")?.let(::File)
        assumeTrue("SLEEP_EVAL_DIR not set", dir != null && dir.isDirectory)
        val all = load(dir!!)
        val train = all.filterIndexed { i, _ -> i % 2 == 0 }; val test = all.filterIndexed { i, _ -> i % 2 == 1 }
        val d = SleepStager.DEFAULT
        for (mc in 0 until 5) println("DEFAULT motion col $mc  all: ${fmt(eval(all, d, mc))}")
        for (mc in 0 until 5) println("NO-BASELINE motion col $mc  all: ${fmt(eval(all, d.copy(motionBaselineQ = 0.0), mc))}")

        val rnd = Random(42)
        fun r(a: Double, b: Double) = a + rnd.nextDouble() * (b - a)
        var best = d to MOTION_COL; var bestK = eval(train, d, MOTION_COL).kappa
        repeat((System.getenv("SLEEP_EVAL_ITERS") ?: "600").toInt()) {
            val p = SleepStager.Params(
                local = listOf(60, 90, 120).random(rnd), remLatency = listOf(60, 90, 120).random(rnd),
                minDeepBout = listOf(6, 10, 14).random(rnd), minRemBout = listOf(4, 6, 10).random(rnd),
                wakeMotion = listOf(3, 5, 8, 12).random(rnd), wakeHrRise = r(5.0, 15.0),
                deepT = r(0.3, 1.5), remT = r(0.3, 1.5),
                deepHr = r(0.3, 1.5), deepVar = r(0.0, 1.0), deepHomeo = r(0.5, 2.5), deepBias = r(0.0, 1.2),
                remHr = r(0.3, 1.5), remVar = r(0.0, 1.2), remLate = r(0.3, 2.0), remBias = r(0.0, 1.0),
            )
            val mc = rnd.nextInt(5)
            val k = eval(train, p, mc).kappa
            if (k > bestK) { bestK = k; best = p to mc }
        }
        val (bp, bmc) = best
        println("BEST motion col $bmc  $bp")
        println("BEST  train: ${fmt(eval(train, bp, bmc))}")
        println("BEST  test:  ${fmt(eval(test, bp, bmc))}")
        println("DEFAULT test: ${fmt(eval(test, d, MOTION_COL))}")
        println("DEFAULT train: ${fmt(eval(train, d, MOTION_COL))}")
        println("BEST  all:   ${fmt(eval(all, bp, bmc))}")
    }
}
