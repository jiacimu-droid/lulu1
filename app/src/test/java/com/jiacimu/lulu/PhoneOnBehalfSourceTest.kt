package com.jiacimu.lulu

import com.jiacimu.lulu.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PhoneOnBehalfSourceTest {
    @Test fun impersonatedOwnerChatIsPersistedAsOwnerActionNotRoleSpeech() {
        val ctx = RuntimeEnvironment.getApplication()
        MigratedDomainStores.initialize(ctx)
        DigitalLifeProfileStore.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        val role = MigratedDomainStores.characters.create("查手机来源测试-" + UUID.randomUUID())
        DigitalLifeProfileStore.registerNewLife(role.characterId, role.displayName, "测试")
        val chat = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        val thread = InspectedThread(key = "chat:${chat.id}", title = "主人",
            lastText = "", updatedAt = java.time.Instant.now(), conversationId = chat.id)
        val id = rolePhoneSendOnBehalf(role.characterId, thread, "主人的恶作剧台词",
            listOf(chat))
        val actual = MigratedDomainStores.chat.messages(chat.id).value.single { it.id == id }
        assertEquals("主人的恶作剧台词", actual.content)
        assertTrue(actual.onBehalfByOwner)
        val original = SharedExperienceTimeline.eventsByIds(role.characterId, listOf(id)).single()
        assertEquals("owner-on-behalf", original.source)
        assertEquals(EventEvidenceKind.Observation, original.evidenceKind)
        assertTrue(original.speaker.contains("主人"))
        assertEquals(1, SharedExperienceTimeline.eventsByIds(role.characterId, listOf(id)).size)

        // A fresh chat projection must retain provenance rather than
        // reclassifying the prank as the character's own statement.
        val restored = InMemoryLuluChatStore()
        restored.initialize(ctx)
        assertTrue(restored.messages(chat.id).value.single { it.id == id }.onBehalfByOwner)
    }
}
