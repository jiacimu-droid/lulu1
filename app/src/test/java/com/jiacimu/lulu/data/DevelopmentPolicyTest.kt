package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class DevelopmentPolicyTest {
    @Test fun longTermHabitNeedsRepeatedEvidence() {
        assertFalse(DevelopmentPolicy.accepts(DevelopmentKind.Habit, 1, true, 0))
        assertTrue(DevelopmentPolicy.accepts(DevelopmentKind.Habit, 3, false, 0))
    }
    @Test fun expressiveHabitsRequireThreeRealExposuresNotRepeatedDiaryClaims() {
        assertFalse(DevelopmentPolicy.accepts(DevelopmentKind.ExpressionHabit, 0, true, 0))
        assertFalse(DevelopmentPolicy.accepts(DevelopmentKind.ExpressionHabit, 2, true, 0))
        assertTrue(DevelopmentPolicy.accepts(DevelopmentKind.ExpressionHabit, 3, false, 0))
        assertFalse(DevelopmentPolicy.accepts(DevelopmentKind.ExpressionHabit, 5, false, 1))
    }

    @Test fun explicitPreferenceMayChangeImmediatelyButNeedsEvidence() {
        assertTrue(DevelopmentPolicy.accepts(DevelopmentKind.Preference, 1, true, 0))
        assertFalse(DevelopmentPolicy.accepts(DevelopmentKind.Preference, 0, true, 0))
        assertFalse(DevelopmentPolicy.accepts(DevelopmentKind.Preference, 1, false, 0))
    }
    @Test fun counterEvidenceBlocksConflictingChange() {
        assertFalse(DevelopmentPolicy.accepts(DevelopmentKind.Judgment, 6, true, 1))
    }
    @Test fun verifiedMethodCannotComeFromOneSuccessfulResult() {
        assertFalse(DevelopmentPolicy.accepts(DevelopmentKind.VerifiedMethod, 1, true, 0))
        assertTrue(DevelopmentPolicy.accepts(DevelopmentKind.VerifiedMethod, 3, false, 0))
    }
    @Test fun maturityNeedsMoreEvidenceThanInitialFormation() {
        assertEquals(DevelopmentMaturity.Emerging,
            DevelopmentPolicy.maturity(DevelopmentKind.SituationalPattern, 3))
        assertEquals(DevelopmentMaturity.Established,
            DevelopmentPolicy.maturity(DevelopmentKind.SituationalPattern, 5))
        assertEquals(2, DevelopmentPolicy.counterExamplesToRetire(DevelopmentMaturity.Emerging))
        assertEquals(3, DevelopmentPolicy.counterExamplesToRetire(DevelopmentMaturity.Established))
    }

}
