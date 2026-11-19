package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PhoneInnerVoicePerceptionTest {
    private val thought = "我忽然想听她把刚才那句话说完。"
    private val basis = JSONObject()
        .put("focus", "电话接通后开场的停顿")
        .put("change", "注意力从要说的话转移到想先听她的声音")

    @Test fun freshConnectedInteractionCanGroundAShortUnspokenThought() {
        val delta = PrivateStateDeltaEngine.evaluate(
            JSONObject(), null, null, basis, thought, hasFreshDirectObservation = true,
        )
        assertEquals(thought, CharacterHeartVoicePolicy.keepOrBlank(
            thought, outward = "喂，我在。", basis = basis, delta = delta, hasFreshEvidence = true,
        ))
    }

    @Test fun sameTopicNewPrivateInterpretationMustSurviveVoiceFilter() {
        val earlier = JSONObject().put("causalTransitions", org.json.JSONArray().put(
            JSONObject().put("innerThoughtBasis", basis),
        ))
        val changedBasis = JSONObject()
            .put("focus", "电话接通后开场的停顿")
            .put("change", "现在更想确认她是不是希望我主动说点什么")
        val newVoice = "明明还在等她开口，可我现在想自己主动问一句。"
        val delta = PrivateStateDeltaEngine.evaluate(
            earlier, null, null, changedBasis, newVoice, hasFreshUserMessage = true,
        )
        assertFalse(delta.focusChanged)
        assertTrue(delta.basisChangeChanged)
        assertTrue(delta.meaningful)
        assertEquals(newVoice, CharacterHeartVoicePolicy.keepOrBlank(
            newVoice, outward = "怎么啦？", basis = changedBasis,
            delta = delta, hasFreshEvidence = true,
        ))
    }

    @Test fun clockTickAndRepeatedFocusDoNotWriteAnotherThought() {
        val later = JSONObject().put("causalTransitions", org.json.JSONArray().put(
            JSONObject().put("innerThoughtBasis", basis),
        ))
        val repeated = PrivateStateDeltaEngine.evaluate(
            later, null, null, basis, thought, hasFreshDirectObservation = true,
        )
        val tick = PrivateStateDeltaEngine.evaluate(JSONObject(), null, null, basis, thought)
        assertEquals("", CharacterHeartVoicePolicy.keepOrBlank(
            thought, basis = basis, delta = repeated, hasFreshEvidence = true,
        ))
        assertEquals("", CharacterHeartVoicePolicy.keepOrBlank(
            thought, basis = basis, delta = tick, hasFreshEvidence = false,
        ))
    }

    @Test fun verifiedSpeechKeepsPrivateReactionAndFiltersSpokenDuplicate() {
        val delta = PrivateStateDeltaEngine.evaluate(
            JSONObject(), null, null, basis, thought, hasFreshUserMessage = true,
        )
        assertEquals(thought, CharacterHeartVoicePolicy.keepOrBlank(
            thought, outward = "你慢慢说。", basis = basis, delta = delta, hasFreshEvidence = true,
        ))
        assertEquals("", CharacterHeartVoicePolicy.keepOrBlank(
            thought, outward = thought, basis = basis, delta = delta, hasFreshEvidence = true,
        ))
    }
    @Test fun quietPhoneStillHasACharacterAuthoredIdeaBeforeChoosingSilence() {
        val authored = JSONObject().put("privateIdeas", org.json.JSONArray()
            .put("我想继续听她说，暂时不插话"))
        val concise = CharacterDeliberationContext.conciseAppraisal(authored)
        assertEquals("我想继续听她说，暂时不插话",
            concise?.optJSONArray("privateIdeas")?.optString(0))
        assertTrue(CharacterDecisionProtocol.hasAuthoredMomentaryIdea(authored))
        assertFalse(CharacterDecisionProtocol.hasAuthoredMomentaryIdea(JSONObject()))
        assertFalse(CharacterDecisionProtocol.hasAuthoredMomentaryIdea(JSONObject()
            .put("privateIdeas", org.json.JSONArray().put(""))))
        assertEquals("想继续听她说，暂时不插话",
            CharacterMomentSnapshot.reflection(null, appraisal = authored))
        // No new private monologue is a valid outcome; it does not cancel
        // the present-tense idea that preceded the choice to stay silent.
        assertEquals("", CharacterHeartVoicePolicy.keepOrBlank(
            thought = "", hasFreshEvidence = true))
    }

    @Test fun sharedDecisionContractNeverTreatsSilenceAsAbsenceOfMentalChoice() {
        assertTrue(CharacterDecisionProtocol.runtimePrinciples.contains(
            "appraisal.privateIdeas 中写出至少一个"))
        assertTrue(CharacterDecisionProtocol.causalPrinciples.contains(
            "想法是每轮自主选择的心理前提"))
        assertFalse(CharacterDecisionProtocol.causalPrinciples.contains(
            "想法不是动作的必经工序"))
    }

}
