package com.jiacimu.lulu.data

/** Long-term habits and methods cannot be inferred from a single utterance. */
object DevelopmentPolicy {
    fun accepts(kind: DevelopmentKind, evidenceCount: Int, explicitUserInstruction: Boolean, counterCount: Int): Boolean {
        if (kind == DevelopmentKind.Interest) return evidenceCount >= 3
        if (evidenceCount <= 0 || counterCount > 0) return false
        return when (kind) {
            DevelopmentKind.Habit, DevelopmentKind.ExpressionHabit, DevelopmentKind.VerifiedMethod -> evidenceCount >= 3
            else -> evidenceCount >= 3 || explicitUserInstruction
        }
    }
}
