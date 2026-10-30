package com.jiacimu.lulu

/** A real observation may advance a duty without inventing a new user utterance. */
internal object CallCommitmentDecisionPolicy {
    fun canApply(rewriting: Boolean, observationOnly: Boolean, evidenceIds: List<String>): Boolean =
        !rewriting && (!observationOnly || evidenceIds.isNotEmpty())
}
