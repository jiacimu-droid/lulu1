package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Shared post-decision causal commit for live private replies, calls, group turns,
 * and autonomous perception. The scene adapters still own their IO and model
 * choices; this owns the order of the durable subjective trace, optional inner
 * voice, and finally the acknowledgement of already-witnessed stimuli.
 *
 * No new storage or model call. An interruption before acknowledgement leaves
 * the same real evidence available to the existing recovery path. The existing
 * stores remain the sole authorities for identity, psychology, and receipts.
 */
internal object CharacterTurnStateCommit {
    fun commit(
        context: Context,
        characterId: String,
        perception: CharacterPerceptionInput,
        direct: List<PerceptionStimulus>,
        claimDirect: Boolean,
        evidenceId: String,
        appraisal: JSONObject?,
        innerLife: JSONObject?,
        innerThoughtBasis: JSONObject?,
        selectedAction: String,
        innerThought: String,
        reason: String,
        now: Instant,
        alternatives: JSONArray? = null,
        recordInnerVoice: Boolean = true,
        voiceFingerprint: String = "",
        afterCausalRecord: () -> Unit = {},
    ) {
        CharacterInnerLifeStore.recordCausalTransition(
            characterId = characterId,
            evidenceId = evidenceId,
            appraisal = appraisal,
            innerLife = innerLife,
            innerThoughtBasis = innerThoughtBasis,
            selectedAction = selectedAction,
            innerThought = innerThought,
            reason = reason,
            now = now,
            alternatives = alternatives,
        )
        // Each adapter can persist its previously existing ancillary state
        // before an event is consumed. Failure must not claim that event.
        afterCausalRecord()
        if (recordInnerVoice) {
            CharacterInnerLifeStore.recordInnerVoice(
                characterId, evidenceId, innerThought, now, voiceFingerprint,
            )
        }
        CharacterPerceptionContext.acknowledge(
            context, characterId, perception, direct = direct,
            claimDirect = claimDirect,
        )
    }
}
