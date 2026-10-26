package com.jiacimu.lulu

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
    @Test fun onlyGroupMembersCanBeImpersonatedAndEveryMemberSeesTrueOrigin() {
        val ctx = RuntimeEnvironment.getApplication()
        MigratedDomainStores.initialize(ctx)
        DigitalLifeProfileStore.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        val first = MigratedDomainStores.characters.create("群聊代发甲-" + UUID.randomUUID())
        val second = MigratedDomainStores.characters.create("群聊代发乙-" + UUID.randomUUID())
        val outsider = MigratedDomainStores.characters.create("群聊局外人-" + UUID.randomUUID())
        listOf(first, second, outsider).forEach {
            DigitalLifeProfileStore.registerNewLife(it.characterId, it.displayName, "测试")
        }
        val group = MigratedDomainStores.chat.createGroupConversation(
            "真人群聊", listOf(first.characterId, second.characterId))
        val thread = InspectedThread(key = "chat:${group.id}", title = group.title,
            lastText = "", updatedAt = java.time.Instant.now(),
            conversationId = group.id, isGroup = true)
        assertTrue(runCatching {
            rolePhoneSendOnBehalf(outsider.characterId, thread, "我不应发进来", listOf(group))
        }.isFailure)
        val id = rolePhoneSendOnBehalf(first.characterId, thread, "主人在群里开个玩笑",
            listOf(group))
        for (viewer in listOf(first.characterId, second.characterId)) {
            val event = SharedExperienceTimeline.eventsByIds(viewer,
                listOf("$id:group:$viewer")).single()
            assertEquals("owner-on-behalf", event.source)
            assertEquals(EventEvidenceKind.Observation, event.evidenceKind)
        }
        assertTrue(MigratedDomainStores.chat.messages(group.id).value
            .first { it.id == id }.onBehalfByOwner)
    }

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

        // Robolectric may give separate application sandboxes to individual
        // tests, while the global MigratedDomainStores singleton can remain
        // bound to the previous test's SharedPreferences. Explicitly reload
        // from the SAME real backing store used by the writer, as would happen
        // when Android restarts the process and reads its persisted preferences.
        val prefsField = InMemoryLuluChatStore::class.java.getDeclaredField("prefs").apply {
            isAccessible = true
        }
        val savedPrefs = prefsField.get(MigratedDomainStores.chat) as SharedPreferences
        val savedJson = org.json.JSONObject(
            requireNotNull(savedPrefs.getString("state_v1", null))
        ).getJSONObject("messages").getJSONArray(chat.id)
        assertTrue("Persisted chat must contain the sent message",
            (0 until savedJson.length()).any { savedJson.getJSONObject(it).optString("id") == id })
        val backingContext = object : ContextWrapper(ctx) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "lulu_chat_store") savedPrefs
                else ctx.getSharedPreferences(name, mode)
        }
        val restored = InMemoryLuluChatStore()
        restored.initialize(backingContext)
        val recovered = restored.messages(chat.id).value.singleOrNull { it.id == id }
        assertNotNull("A new store must decode the same persisted original", recovered)
        assertTrue(recovered!!.onBehalfByOwner)
    }
}
