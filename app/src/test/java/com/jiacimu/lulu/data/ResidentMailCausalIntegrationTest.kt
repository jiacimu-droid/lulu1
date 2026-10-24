package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ResidentMailCausalIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication() as android.content.Context

    @org.junit.Before fun initializeWorkerHost() {
        if (runCatching { androidx.work.WorkManager.getInstance(app) }.isFailure)
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
    }

    private fun pair(): Pair<String, String> {
        LuluRepositories.initialize(app)
        SharedExperienceTimeline.initialize(app)
        MigratedDomainStores.initialize(app)
        DigitalLifeProfileStore.initialize(app)
        DigitalWorldStore.initialize(app)
        CharacterInnerLifeStore.initialize(app)
        ResidentSocialMailbox.initialize(app)
        val ids = (1..2).map { index ->
            val role = MigratedDomainStores.characters.create("通信$index-${UUID.randomUUID()}")
            DigitalLifeProfileStore.registerNewLife(role.characterId, role.displayName, "数字生命",
                Instant.now().minusSeconds(7 * 86_400))
            assertTrue(DigitalWorldStore.performAction(role.characterId, "visit_public_place",
                JSONObject().put("locationCode", DigitalWorldPublicPlaces.COURTYARD)).success)
            role.characterId
        }
        return ids[0] to ids[1]
    }

    @Test fun realLetterEntersPerceptionAndOwnRelationshipWithoutCreatingAUserInteraction() {
        val (sender, receiver) = pair()
        val now = Instant.now()
        val sent = ResidentSocialMailbox.send(sender, receiver, "谢谢你记得上次的话题。", at = now)
        val event = SharedExperienceTimeline.eventsByIds(receiver,
            listOf(ResidentSocialMailbox.evidenceId(sent, receiver))).single()
        val pending = CharacterPerceptionContext.pending(app, receiver, now.plusSeconds(1))
        assertTrue(pending.any { it.id == event.id })
        val stimulus = CharacterPerceptionContext.stimulus(event)
        assertEquals(setOf(sender), stimulus.socialIds)
        CharacterInnerLifeStore.observe(receiver, event.id, stimulus.description,
            JSONObject().put("social", JSONObject().put("targetId", sender)
                .put("interpretation", "他还记得我们聊过的事")
                .put("reason", "实际收到来信")), stimulus.socialIds, now)
        assertNotNull(CharacterSocialRelationship.perspective(receiver, sender))
        assertNull(CharacterSocialRelationship.perspective(receiver, "user"))
        assertTrue(CharacterSocialRelationship.snapshot(receiver, sender).knowsEachOther)
        assertTrue(CharacterSocialRelationship.snapshot(receiver, sender).shortContext().contains("私信"))

        SharedExperienceTimeline.deleteEvent(event.id)
        assertNull(CharacterSocialRelationship.perspective(receiver, sender))
        assertTrue(ResidentSocialMailbox.pendingIncoming(receiver).isEmpty())
        assertFalse(ResidentSocialMailbox.inbox(receiver).any { it.id == sent.id })
        // The other participant's independently saved original is retained.
        assertTrue(ResidentSocialMailbox.inbox(sender).any { it.id == sent.id })
    }

    @Test fun offlineLetterSurvivesAmbientWindowAndOnlyAcknowledgesTheConsideredBatch() {
        val (sender, receiver) = pair()
        val now = Instant.now()
        val first = ResidentSocialMailbox.send(sender, receiver, "前天收到的信", at = now.minusSeconds(2 * 86_400))
        val frozen = ResidentSocialMailbox.pendingIncoming(receiver)
        val second = ResidentSocialMailbox.send(sender, receiver, "你思考期间才到的信", at = now)
        assertTrue(CharacterPerceptionContext.pending(app, receiver, now.plusSeconds(1))
            .any { it.id == ResidentSocialMailbox.evidenceId(first, receiver) })
        assertTrue(ResidentSocialMailbox.acknowledgeIncoming(receiver, frozen.last().id))
        assertEquals(listOf(second.id), ResidentSocialMailbox.pendingIncoming(receiver).map { it.id })
        assertTrue(ResidentSocialMailbox.pendingIncoming(sender).isEmpty())
        assertTrue(ResidentSocialMailbox.acknowledgeIncoming(receiver, second.id))
        assertTrue(ResidentSocialMailbox.acknowledgeIncoming(receiver, first.id))
        assertTrue(ResidentSocialMailbox.pendingIncoming(receiver).isEmpty())
    }

    @Test fun editedSourceAndUnrelatedCharacterCannotRecallTheOldLetter() {
        val (sender, receiver) = pair()
        val letter = ResidentSocialMailbox.send(sender, receiver, "随后撤回的旧信")
        val id = ResidentSocialMailbox.evidenceId(letter, receiver)
        val event = SharedExperienceTimeline.eventsByIds(receiver, listOf(id)).single()
        SharedExperienceTimeline.record(id, receiver, event.channel, event.speaker,
            "原始内容已经修正", event.occurredAt, triggerExtraction = false,
            source = event.source, evidenceKind = event.evidenceKind)
        assertFalse(ResidentSocialMailbox.inbox(receiver).any { it.id == letter.id })
        assertTrue(ResidentSocialMailbox.pendingIncoming(receiver).isEmpty())
        assertTrue(ResidentSocialMailbox.inbox("unrelated-${UUID.randomUUID()}").isEmpty())
    }

    @Test(timeout = 60_000) fun callTakingOverAnUnsentDecisionDoesNotConsumeIncomingMail() = kotlinx.coroutines.runBlocking {
        initializeBackgroundRuntime(app)
        val (sender, receiver) = pair()
        val chat = MigratedDomainStores.chat.ensureConversation(receiver, "来信处理测试")
        val letter = ResidentSocialMailbox.send(sender, receiver, "这条信仍然需要你亲自决定")
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                CompanionPresenceStore.beginCall(receiver)
                return okhttp3.mockwebserver.MockResponse().setBody(
                    """{"choices":[{"message":{"content":"{\"action\":\"silent\",\"reason\":\"暂时不回复\"}"}}]}""")
            }
        }
        server.start()
        try {
            val store = com.jiacimu.lulu.ai.LuluAiServices.connectionStore
            val config = store.saveConfiguration(null, "test", server.url("/").toString(), "test")
            store.addArchive(config.id, "test")
            ProactivePerceptionRuntime.runDueCycle(app, "用户手动唤醒·私信处理测试",
                receiver, force = true)
            assertEquals(1, server.requestCount)
            assertEquals(listOf(letter.id), ResidentSocialMailbox.pendingIncoming(receiver).map { it.id })
        } finally {
            CompanionPresenceStore.finishCall(receiver)
            OnlineChatBatchStore.cancel(app, receiver)
            com.jiacimu.lulu.ChatReplyTaskManager.stop(chat.id)
            server.shutdown()
        }
    }
}
