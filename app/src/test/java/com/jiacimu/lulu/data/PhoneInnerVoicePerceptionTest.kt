package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
