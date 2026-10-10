package com.jiacimu.lulu.data

enum class DevelopmentMaturity { Emerging, Established, Contested }

/** Long-term habits and methods cannot be inferred from a single utterance. */
object DevelopmentPolicy {
    fun maturity(kind: DevelopmentKind, evidenceCount: Int): DevelopmentMaturity {
        val establishedAt = when (kind) {
            DevelopmentKind.NarrativeMeaning,
            DevelopmentKind.Preference,
            DevelopmentKind.Judgment -> 4
            else -> 5
        }
        return if (evidenceCount >= establishedAt) DevelopmentMaturity.Established
        else DevelopmentMaturity.Emerging
    }

    fun counterExamplesToRetire(maturity: DevelopmentMaturity): Int = when (maturity) {
        DevelopmentMaturity.Established -> 3
        DevelopmentMaturity.Emerging -> 2
        DevelopmentMaturity.Contested -> 0
    }

    fun accepts(kind: DevelopmentKind, evidenceCount: Int, explicitUserInstruction: Boolean, counterCount: Int): Boolean {
        if (kind == DevelopmentKind.Interest) return evidenceCount >= 3
        if (kind == DevelopmentKind.NarrativeMeaning) return evidenceCount >= 2 && counterCount == 0
        if (kind == DevelopmentKind.SituationalPattern) return evidenceCount >= 3 && counterCount == 0
        if (evidenceCount <= 0 || counterCount > 0) return false
        return when (kind) {
            DevelopmentKind.Habit, DevelopmentKind.ExpressionHabit, DevelopmentKind.VerifiedMethod -> evidenceCount >= 3
            else -> evidenceCount >= 3 || explicitUserInstruction
        }
    }
}
