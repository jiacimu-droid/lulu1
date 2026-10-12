package com.jiacimu.lulu.data

/** Derived from real receipts only. A practice artifact is not a pass or endorsement. */
internal enum class LearningEvidenceStage {
    READING_ONLY, PRACTICED, APPLIED_UNVERIFIED, POSITIVE_FEEDBACK, REPEATEDLY_SUPPORTED
}

internal object LearningCompetencyPolicy {
    fun stage(exercises: Int, actions: Int, positiveActions: Int, negativeActions: Int): LearningEvidenceStage =
        when {
            actions >= 3 && positiveActions >= 3 && negativeActions == 0 ->
                LearningEvidenceStage.REPEATEDLY_SUPPORTED
            positiveActions >= 1 -> LearningEvidenceStage.POSITIVE_FEEDBACK
            actions >= 1 -> LearningEvidenceStage.APPLIED_UNVERIFIED
            exercises >= 1 -> LearningEvidenceStage.PRACTICED
            else -> LearningEvidenceStage.READING_ONLY
        }
}
