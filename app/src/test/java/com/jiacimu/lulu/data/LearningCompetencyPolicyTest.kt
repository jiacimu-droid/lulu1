package com.jiacimu.lulu.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LearningCompetencyPolicyTest {
    @Test fun ungradedPrivatePracticeCannotCountAsVerified() {
        assertEquals(LearningEvidenceStage.READING_ONLY,
            LearningCompetencyPolicy.stage(0, 0, 0, 0))
        assertEquals(LearningEvidenceStage.PRACTICED,
            LearningCompetencyPolicy.stage(4, 0, 0, 0))
        assertEquals(LearningEvidenceStage.APPLIED_UNVERIFIED,
            LearningCompetencyPolicy.stage(4, 2, 0, 0))
    }
    @Test fun feedbackMustComeFromDistinctRealActions() {
        assertEquals(LearningEvidenceStage.POSITIVE_FEEDBACK,
            LearningCompetencyPolicy.stage(2, 3, 1, 0))
        assertEquals(LearningEvidenceStage.REPEATEDLY_SUPPORTED,
            LearningCompetencyPolicy.stage(2, 3, 3, 0))
        assertEquals(LearningEvidenceStage.POSITIVE_FEEDBACK,
            LearningCompetencyPolicy.stage(2, 3, 3, 1))
    }
}
