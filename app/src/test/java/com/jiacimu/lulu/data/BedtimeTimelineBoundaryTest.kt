package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Bedtime speech remains in the phone transcript, not the character's life story. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BedtimeTimelineBoundaryTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun deliveredBedtimeNarrationStaysInTranscriptWithoutBecomingSharedLifeEvidence() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        val role = MigratedDomainStores.characters.create("哄睡时间线隔离回归")
        val call = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        val sleepSpeechId = "voice-sleep-example-call-agent-played"
        val normalSpeechId = "voice-example-call-agent-played"
        val userSpeechId = "voice-example-call-user-heard"

        MigratedDomainStores.chat.appendVoiceMessage(
            call.id, sleepSpeechId, "这是一段已经真正播完的长篇身体放松引导", true)
        assertTrue(MigratedDomainStores.chat.messages(call.id).value.any {
            it.id == sleepSpeechId && it.content.contains("身体放松")
        })
        assertTrue(SharedExperienceTimeline.eventsByIds(role.characterId,
            listOf(sleepSpeechId)).isEmpty())

        // Changing to ordinary conversation still records genuine new speech.
        MigratedDomainStores.chat.appendVoiceMessage(call.id,
            normalSpeechId, "我刚想到一件我们一起经历的小事", true)
        MigratedDomainStores.chat.appendVoiceMessage(call.id,
            userSpeechId, "我还没睡着，想再聊聊", false)
        assertEquals(1, SharedExperienceTimeline.eventsByIds(role.characterId,
            listOf(normalSpeechId)).size)
        assertEquals(1, SharedExperienceTimeline.eventsByIds(role.characterId,
            listOf(userSpeechId)).size)

        // A factual bedtime-mode receipt must remain available without copying
        // the story or claiming that the listener truly fell asleep.
        InteractionSignalBridge.recordUserActivity(
            role.characterId, "example-call", "sleep-guidance", "end")
        val modeReceipt = SharedExperienceTimeline.eventsByIds(role.characterId,
            listOf("interaction-user-activity-example-call-sleep-guidance-end"))
        assertEquals(1, modeReceipt.size)
        assertTrue(modeReceipt.single().content.contains("哄睡模式已结束"))
        assertFalse(modeReceipt.single().content.contains("身体放松引导"))
    }
}
