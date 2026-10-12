package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterConversationAgencyTest {
    @Test fun planningAloneDoesNotRecordDeliveredSpeechAndRepeatedPlaybackReceiptDoesNotDuplicate() {
        val context = RuntimeEnvironment.getApplication()
        val role = "agency-delivery-test"
        CharacterInnerLifeStore.initialize(context)
        CharacterInnerLifeStore.clear(role)
        val receipt = CharacterConversationAgency.receipt("", "想分享自己的看法", "", "", null, "")
        assertNull(CharacterInnerLifeStore.snapshot(role).optJSONArray("decisions"))
        CharacterConversationAgency.delivered(role, receipt, "", "")
        assertNull(CharacterInnerLifeStore.snapshot(role).optJSONArray("decisions"))
        CharacterConversationAgency.delivered(role, receipt, "voice-real-1", "我倒觉得挺有意思的。", phone = true)
        CharacterConversationAgency.delivered(role, receipt, "voice-real-1", "我倒觉得挺有意思的。", phone = true)
        val choices = CharacterInnerLifeStore.snapshot(role).getJSONArray("decisions")
        assertEquals(1, choices.length())
        assertEquals("phone_reply", choices.getJSONObject(0).getString("selected"))
        assertTrue(choices.getJSONObject(0).getString("outcome").contains("已实际播放完毕"))
        CharacterInnerLifeStore.invalidateEvidence("voice-real-1")
        assertEquals(0, CharacterInnerLifeStore.snapshot(role).getJSONArray("decisions").length())
        CharacterInnerLifeStore.clear(role)
    }

    @Test fun choiceMetadataIsSeparateFromSpeakableText() {
        val receipt = JSONObject(CharacterConversationAgency.receipt("user-original", "好奇她的想法", "motive-real", "", null, ""))
        assertEquals("user-original", receipt.getString("evidenceId"))
        assertFalse(receipt.has("text"))
        assertFalse(receipt.has("speechIntent"))
    }
}
