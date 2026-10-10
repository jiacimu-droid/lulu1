package com.jiacimu.lulu.data

/** Long-term habits and methods cannot be inferred from a single utterance. */
object DevelopmentPolicy {
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
