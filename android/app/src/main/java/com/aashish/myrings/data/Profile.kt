package com.aashish.myrings.data

import java.time.LocalDate

enum class Sex(val code: Int, val label: String) {
    MALE(0, "Male"), FEMALE(1, "Female"), UNSPECIFIED(2, "Prefer not to say");

    companion object {
        fun from(code: Int?) = entries.firstOrNull { it.code == code } ?: UNSPECIFIED
    }
}

/** The wearer's body profile, kept per ring (rings can belong to different people). Any field may be unset. */
data class Profile(
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val birthYear: Int? = null,
    val sex: Sex = Sex.UNSPECIFIED,
) {
    fun ageYears(today: LocalDate = LocalDate.now()): Int? = birthYear?.let { today.year - it }
    val isEmpty get() = weightKg == null && heightCm == null && birthYear == null && sex == Sex.UNSPECIFIED
}

/**
 * Profile-driven formulas. BMR and VO2 max are the ones open_oura recovered from Oura's on-device engine
 * (`oura-analysis::ported::metabolic`, constants checked against `libappecore.so`); sleep need is ours.
 */
object Metabolic {
    /** Factory profile a reset ring reports in user_information (age 40, 75 kg, sex 2, 176 cm): not a real person. */
    fun isRingFactoryProfile(age: Int, weightKg: Int, sexCode: Int, heightCm: Int) = age == 40 && weightKg == 75 && sexCode == 2 && heightCm == 176

    const val FALLBACK_WEIGHT_KG = 70.0
    const val FALLBACK_AGE = 40

    /**
     * Schofield basal metabolic rate (kcal/day), the WHO/Schofield coefficients Oura's BMR path uses
     * (ecore ELF 0x10d7e4). Unspecified sex averages the male and female equations, as ecore does.
     */
    fun bmrSchofield(age: Int, sex: Sex, weightKg: Double): Double {
        fun band(c: List<Triple<Double, Double, Double>>) = c.first { age < it.first }.let { it.second * weightKg + it.third }
        val inf = Double.POSITIVE_INFINITY
        val male = listOf(Triple(3.0, 59.512, -30.4), Triple(10.0, 22.706, 504.3), Triple(18.0, 17.686, 658.2),
            Triple(30.0, 15.057, 692.2), Triple(60.0, 11.472, 873.1), Triple(inf, 11.711, 587.7))
        val female = listOf(Triple(3.0, 58.317, -31.1), Triple(10.0, 20.315, 485.9), Triple(18.0, 13.384, 692.6),
            Triple(30.0, 14.818, 486.6), Triple(60.0, 8.126, 845.6), Triple(inf, 9.082, 658.5))
        return when (sex) {
            Sex.MALE -> band(male)
            Sex.FEMALE -> band(female)
            Sex.UNSPECIFIED -> (band(male) + band(female)) / 2
        }
    }

    /**
     * Anthropometric VO2 max estimate (Jackson NES), as in Oura's engine (ecore ELF 0x12bc78):
     * `79.9 − 0.39·age − 13.7·female − 0.127·weight_lb`. It knows nothing about your fitness; Oura's real
     * Cardio Capacity uses a walk test. Null for unspecified sex (the formula needs it).
     */
    fun vo2maxJackson(age: Int, sex: Sex, weightKg: Double): Double? {
        if (sex == Sex.UNSPECIFIED) return null
        return (79.9 - 0.39 * age - 13.7 * (if (sex == Sex.FEMALE) 1 else 0) - 0.127 * weightKg * 2.20462).coerceAtLeast(0.0)
    }

    /** Active kcal for one minute at [met]: 1 MET = 1 kcal per kg per hour, minus the resting MET already in BMR. */
    fun activeKcalPerMinute(met: Double, weightKg: Double) = maxOf(0.0, met - 1) * weightKg / 60

    /**
     * Typical sleep need by age (hours), from the National Sleep Foundation ranges' midpoints: teens 8–10, adults
     * 7–9, 65+ 7–8. Oura sets its sleep-score limits from age too, but those exact limits aren't recoverable.
     */
    fun sleepNeedHours(age: Int?): Double = when {
        age == null -> 8.0
        age < 18 -> 9.0
        age < 65 -> 8.0
        else -> 7.5
    }

    /** Deep sleep that earns full marks, falling with age (slow-wave sleep declines through adulthood). */
    fun deepTargetMin(age: Int?): Double = when (age) {
        null -> 75.0
        else -> Scores.interp(age.toDouble(), listOf(20.0 to 80.0, 40.0 to 70.0, 60.0 to 55.0, 80.0 to 45.0))
    }
}
