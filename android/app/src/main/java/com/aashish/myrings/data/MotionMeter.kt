package com.aashish.myrings.data

import kotlin.math.sqrt

/** How much the wearer is moving, from the ring's live accelerometer. */
enum class MotionLevel(val text: String) {
    STILL("Still"),
    SLIGHT("Slight movement"),
    MOVING("Moving"),
}

/**
 * Turns the ring's live x/y/z accelerometer readings into a "how still are you" level.
 *
 * Units: at rest the three axes add up to about 1000 counts, so one count is roughly one milli-g (observed on a Ring 4; the open_oura
 * docs publish no thresholds). Gravity alone doesn't change from one sample to the next, so the *change between consecutive samples*
 * (jerk) is what separates a hand held still from one that is moving, whatever the ring's orientation. It is averaged (root mean square)
 * over the last second or so so a single bump doesn't flip the level.
 */
class MotionMeter(private val windowMs: Long = 1200) {
    private class Sample(val tMs: Long, val x: Int, val y: Int, val z: Int)
    private val samples = ArrayDeque<Sample>()

    fun add(x: Int, y: Int, z: Int, nowMs: Long = System.currentTimeMillis()) {
        samples.addLast(Sample(nowMs, x, y, z))
        trim(nowMs)
    }

    private fun trim(nowMs: Long) { while (samples.isNotEmpty() && nowMs - samples.first().tMs > windowMs) samples.removeFirst() }

    /** RMS change between consecutive samples over the window, in counts (about milli-g), or null with too little data. */
    fun jerk(nowMs: Long = System.currentTimeMillis()): Double? {
        trim(nowMs)
        if (samples.size < 4) return null
        var sum = 0.0
        var n = 0
        var prev = samples.first()
        for (s in samples.drop(1)) {
            val dx = (s.x - prev.x).toDouble(); val dy = (s.y - prev.y).toDouble(); val dz = (s.z - prev.z).toDouble()
            sum += dx * dx + dy * dy + dz * dz
            n++
            prev = s
        }
        return sqrt(sum / n)
    }

    fun level(nowMs: Long = System.currentTimeMillis()): MotionLevel? = jerk(nowMs)?.let { levelFor(it) }

    companion object {
        const val STILL_BELOW = 30.0
        const val MOVING_ABOVE = 90.0
        fun levelFor(jerk: Double) = when {
            jerk < STILL_BELOW -> MotionLevel.STILL
            jerk < MOVING_ABOVE -> MotionLevel.SLIGHT
            else -> MotionLevel.MOVING
        }
    }
}
