package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TimelineStatementOwnershipTest {
    @Before fun before() {
        MemoryTestEnvironment.release()
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLifeStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        CharacterDevelopmentStore.initialize(context)
    }
    @After fun after() { MemoryTestEnvironment.release() }

    private fun event(message: LuluChatMessage, witness: String): SharedTimelineEvent =
        SharedExperienceTimeline.eventsByIds(witness, listOf("${message.id}:group:$witness")).single()

    @Test fun witnessedPeerSpeechCannotBecomeMyWordsStyleOrActionReceipt() {
        val a = "speech-author-a"
        val b = "speech-author-b"
        val group = MigratedDomainStores.chat.createGroupConversation("同名", listOf(a, b), true)
        val own = MigratedDomainStores.chat.appendCharacterMessage(group.id, "我想留在这里。", a)
        val peer = MigratedDomainStores.chat.appendCharacterMessage(group.id, "我决定离开。", b)
        val mine = event(own, a)
        val heard = event(peer, a)
        assertTrue(TimelineStatementOwnership.isOwnCharacterStatement(mine))
        assertFalse(TimelineStatementOwnership.isOwnCharacterStatement(heard))
        assertTrue(CharacterActionEvidence.supports(mine, "group_message"))
        assertFalse(CharacterActionEvidence.supports(heard, "group_message"))
        assertEquals(heard, CharacterActionEvidence.resolve(a, peer.id)) // Witness history is preserved.
        val now = Instant.now().plusSeconds(1)
        assertEquals(listOf(own.content), ConversationTurnEconomy.recentOwnReplies(
            SharedExperienceTimeline.all(a), a, group.id, now))
        assertEquals(own.id + ":group:$a", CharacterContinuityRuntime.snapshot(a, now = now)
            .lastCharacterStatement?.id)
        assertEquals(peer.id + ":group:$b", CharacterContinuityRuntime.snapshot(b, now = now)
            .lastCharacterStatement?.id)
        assertTrue(SharedExperienceTimeline.eventsByIds("not-a-member",
            listOf("${peer.id}:group:not-a-member")).isEmpty())

        // Display names are mutable and may collide; stable authorship does not.
        MigratedDomainStores.chat.updateGroupConversation(group.id, group.groupChat!!.copy(
            members = listOf(LuluGroupMember(a, "同名"), LuluGroupMember(b, "同名"))))
        assertFalse(TimelineStatementOwnership.isOwnCharacterStatement(heard.copy(speaker = "同名")))
        assertTrue(TimelineStatementOwnership.isOwnCharacterStatement(mine.copy(speaker = "同名")))
        assertFalse(TimelineStatementOwnership.isOwnCharacterStatement(heard.copy(sessionId = "missing")))
        assertFalse(TimelineStatementOwnership.isOwnCharacterStatement(mine.copy(source = "legacy")))
    }

    @Test fun groupLifeRemainsVisibleWithoutInventingContactWithTheUser() {
        val a = "independent-life-a"
        val b = "independent-life-b"
        val at = Instant.now().minusSeconds(3_600)
        SharedExperienceTimeline.record("old-direct-user", a, "私聊", "用户", "此前的聊天",
            occurredAt = at, triggerExtraction = false, source = "legacy",
            evidenceKind = EventEvidenceKind.UserStatement)
        val group = MigratedDomainStores.chat.createGroupConversation("自己的社交", listOf(a, b), false)
        val own = MigratedDomainStores.chat.appendCharacterMessage(group.id, "我在和朋友讨论这本书。", a)
        MigratedDomainStores.chat.appendCharacterMessage(group.id, "我有不同看法。", b)
        val now = Instant.now().plusSeconds(1)
        val snapshot = CharacterContinuityRuntime.snapshot(a, now = now)
        assertEquals(at.toEpochMilli(), snapshot.previousInteractionAt?.toEpochMilli())
        assertEquals(own.content, snapshot.lastCharacterStatement?.content)
        assertFalse(TimelineStatementOwnership.involvesUser(event(own, a)))
        // Adding the user later must not retrospectively turn old social speech into user contact.
        MigratedDomainStores.chat.updateGroupConversation(group.id, group.groupChat!!.copy(ownerIncluded = true))
        assertEquals(at.toEpochMilli(), CharacterContinuityRuntime.snapshot(a, now = now)
            .previousInteractionAt?.toEpochMilli())
        val rendered = CharacterContinuityRuntime.render(snapshot.copy(previousInteractionAt = null,
            minutesSinceInteraction = null, lastUserStatement = null, preferredAddress = "",
            activeMotiveAims = emptyList()))
        assertTrue(rendered.contains(own.content))
        assertTrue(rendered.contains("群聊·自己的社交"))
        assertFalse(rendered.contains("距上一条真实双方互动"))
        val user = MigratedDomainStores.chat.sendUserMessage(group.id, "我也加入讨论。")
        assertTrue(TimelineStatementOwnership.involvesUser(event(user, a)))
        assertEquals(user.createdAt.toEpochMilli(), CharacterContinuityRuntime.snapshot(a,
            now = Instant.now().plusSeconds(1)).previousInteractionAt?.toEpochMilli())
    }

    @Test fun migratedGroupPrefixStillUsesCanonicalMessageAuthorship() {
        val a = "migrated-group-a"
        val b = "migrated-group-b"
        val group = MigratedDomainStores.chat.createGroupConversation("归档群", listOf(a, b), true)
        val userMessage = MigratedDomainStores.chat.sendUserMessage(group.id, "真正发送的群消息")
        val ownMessage = MigratedDomainStores.chat.appendCharacterMessage(group.id, "本人发言", a)
        val peerMessage = MigratedDomainStores.chat.appendCharacterMessage(group.id, "另一人的发言", b)
        val migratedChannel = "手机群聊·归档群"

        // A prefix or an imported row without the conventional group suffix
        // must never bypass the shared authorship check.
        val orphan = event(userMessage, a).copy(
            id = "missing-original", channel = migratedChannel)
        assertFalse(TimelineStatementOwnership.involvesUser(orphan))
        assertTrue(TimelineStatementOwnership.involvesUser(
            event(userMessage, a).copy(channel = migratedChannel)))
        assertTrue(TimelineStatementOwnership.isOwnCharacterStatement(
            event(ownMessage, a).copy(channel = migratedChannel)))
        assertFalse(TimelineStatementOwnership.isOwnCharacterStatement(
            event(peerMessage, a).copy(channel = migratedChannel)))
        assertEquals(listOf(event(userMessage, a).id),
            CompanionContactClock.verifiedUserContacts(listOf(
                orphan, event(userMessage, a).copy(channel = migratedChannel))).map { it.id })
    }

    @Test fun bareGroupMessageIdDoesNotProveThatThisCharacterWitnessedIt() {
        val a = "group-witness-identity-a"
        val b = "group-witness-identity-b"
        val group = MigratedDomainStores.chat.createGroupConversation("见证归属", listOf(a, b), true)
        val user = MigratedDomainStores.chat.sendUserMessage(group.id, "这句确实发到群里了")
        val own = MigratedDomainStores.chat.appendCharacterMessage(group.id, "我确实说过", a)
        val userWitness = event(user, b)
        val ownWitness = event(own, a)
        assertTrue(TimelineStatementOwnership.involvesUser(userWitness))
        assertTrue(TimelineStatementOwnership.isOwnCharacterStatement(ownWitness))

        // Same session, original message, content and time, but not the
        // persisted witness ID for this character. Do not turn an imported
        // bare chat message into a firsthand encounter or self speech.
        val bareUser = userWitness.copy(id = user.id)
        val bareOwn = ownWitness.copy(id = own.id)
        assertFalse(TimelineStatementOwnership.involvesUser(bareUser))
        assertFalse(TimelineStatementOwnership.isOwnCharacterStatement(bareOwn))
        assertTrue(CompanionContactClock.verifiedUserContacts(
            listOf(bareUser, userWitness)).map { it.id } == listOf(userWitness.id))
        assertFalse(TimelineStatementOwnership.involvesUser(
            userWitness.copy(id = "${user.id}:group:$a")))
    }

    @Test fun staleOrForeignGroupWitnessCannotBorrowARealMessageId() {
        val a = "group-origin-a"
        val b = "group-origin-b"
        val group = MigratedDomainStores.chat.createGroupConversation("保留来源", listOf(a, b), true)
        val sent = MigratedDomainStores.chat.sendUserMessage(group.id, "原本说的话")
        val witnessed = event(sent, a)
        assertTrue(TimelineStatementOwnership.involvesUser(witnessed))
        assertFalse(TimelineStatementOwnership.involvesUser(
            witnessed.copy(content = "从未说过的假话")))
        assertFalse(TimelineStatementOwnership.involvesUser(
            witnessed.copy(occurredAt = witnessed.occurredAt.plusSeconds(60))))

        // Group-looking history cannot borrow an unrelated direct message.
        val direct = MigratedDomainStores.chat.ensureConversation(a, "私人会话")
        val privateMessage = MigratedDomainStores.chat.sendUserMessage(direct.id, "私人原话")
        val spoofed = witnessed.copy(
            id = privateMessage.id,
            sessionId = direct.id,
            channel = "手机群聊·假来源",
            content = privateMessage.content,
            occurredAt = privateMessage.createdAt,
        )
        assertFalse(TimelineStatementOwnership.involvesUser(spoofed))

        // Editing updates the original timeline projection. An outdated copy
        // cannot be silently reused after the authoritative message changes.
        assertTrue(MigratedDomainStores.chat.editMessage(sent.id, "重新说明的原话"))
        assertFalse(TimelineStatementOwnership.involvesUser(witnessed))
        val revised = SharedExperienceTimeline.eventsByIds(a, listOf(witnessed.id)).single()
        assertEquals("重新说明的原话", revised.content)
        assertTrue(TimelineStatementOwnership.involvesUser(revised))
        assertTrue(CompanionContactClock.verifiedUserContacts(listOf(witnessed, revised))
            .map { it.content }.contains("重新说明的原话"))
        assertFalse(CompanionContactClock.verifiedUserContacts(listOf(witnessed, revised))
            .map { it.content }.contains("原本说的话"))
    }

    @Test fun orphanOrFailedGroupUserWitnessCannotOverwriteActualLastUserWords() {
        val role = "group-user-witness-validation"
        val peer = "group-user-witness-peer"
        val at = Instant.now().minusSeconds(120)
        SharedExperienceTimeline.record("prior-direct-user", role, "私聊", "用户",
            "我先去吃饭，回来再聊。", at, triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        val group = MigratedDomainStores.chat.createGroupConversation(
            "可核验的群聊", listOf(role, peer), true)
        val sent = MigratedDomainStores.chat.sendUserMessage(group.id, "我回来啦。")
        val valid = event(sent, role)
        assertTrue(TimelineStatementOwnership.involvesUser(valid))
        assertEquals(valid.id, CharacterContinuityRuntime.snapshot(role,
            now = Instant.now().plusSeconds(2)).lastUserStatement?.id)

        val forged = valid.copy(id = "orphan:group:$role",
            content = "这是没有真实消息来源的用户原话")
        assertFalse(TimelineStatementOwnership.involvesUser(forged))
        SharedExperienceTimeline.record(forged.id, role, forged.channel, "用户",
            forged.content, Instant.now().plusSeconds(1),
            sessionId = group.id, source = "message", triggerExtraction = false,
            evidenceKind = EventEvidenceKind.UserStatement)
        assertEquals(valid.id, CharacterContinuityRuntime.snapshot(role,
            now = Instant.now().plusSeconds(3)).lastUserStatement?.id)

        MigratedDomainStores.chat.markFailed(sent.id)
        assertFalse(TimelineStatementOwnership.involvesUser(valid))
        val snapshot = CharacterContinuityRuntime.snapshot(role,
            now = Instant.now().plusSeconds(3))
        assertEquals("prior-direct-user", snapshot.lastUserStatement?.id)
        assertEquals(at.toEpochMilli(), snapshot.previousInteractionAt?.toEpochMilli())
    }

    @Test fun legacyDefaultAuthorAndOwnerOnBehalfKeepTheirActualMeaning() {
        val a = "legacy-speaker-a"
        val b = "legacy-speaker-b"
        val group = MigratedDomainStores.chat.createGroupConversation("兼容", listOf(a, b), true)
        val legacy = MigratedDomainStores.chat.appendCharacterMessage(group.id, "默认作者的原话。")
        assertTrue(TimelineStatementOwnership.isOwnCharacterStatement(event(legacy, a)))
        assertFalse(TimelineStatementOwnership.isOwnCharacterStatement(event(legacy, b)))
        val delegated = MigratedDomainStores.chat.appendCharacterMessage(group.id,
            "用户代发的内容。", a, onBehalfByOwner = true)
        assertFalse(TimelineStatementOwnership.isOwnCharacterStatement(event(delegated, a)))
        assertFalse(CharacterActionEvidence.supports(event(delegated, a), "message"))
        assertFalse(TimelineStatementOwnership.isOwnCharacterStatement(event(legacy, a)
            .copy(id = "unknown:group:$a")))
        val stale = event(legacy, a)
        MigratedDomainStores.chat.markFailed(legacy.id)
        assertFalse(TimelineStatementOwnership.isOwnCharacterStatement(stale))
    }

    @Test fun growthAcceptsRealGroupExpressionsButCannotBorrowThePeersInterest() {
        val a = "growth-speaker-a"
        val b = "growth-speaker-b"
        val group = MigratedDomainStores.chat.createGroupConversation("成长来源", listOf(a, b), true)
        val own = (1..2).map {
            event(MigratedDomainStores.chat.appendCharacterMessage(group.id, "我喜欢研究不同星图，第${it}次感受。", a), a).id
        }
        val peer = (1..2).map {
            event(MigratedDomainStores.chat.appendCharacterMessage(group.id, "我喜欢收藏硬币，第${it}次感受。", b), a).id
        }
        val exposures = (1..3).map { index ->
            val id = "growth-exposure-$index"
            SharedExperienceTimeline.record(id, a, "实际见闻", "观察", "第${index}次独立经历",
                triggerExtraction = false, source = "test-observation", evidenceKind = EventEvidenceKind.Observation)
            id
        }
        val persona = CharacterRuntime.personaConstraintSnapshot(a)
        fun propose(kind: DevelopmentKind, speech: List<String>) = CharacterDevelopmentStore.applyProposal(
            a, "ownership:${kind.name}", kind, "从实际经历形成的本人倾向", exposures + speech,
            emptyList(), persona)
        assertFalse(propose(DevelopmentKind.ExpressionHabit, peer))
        assertTrue(propose(DevelopmentKind.ExpressionHabit, own))
        assertFalse(propose(DevelopmentKind.Interest, peer))
        assertTrue(propose(DevelopmentKind.Interest, own))
        assertTrue(CharacterDevelopmentStore.active(a).all { record -> peer.none { it in record.evidence } })
        assertTrue(CharacterDevelopmentStore.active(b).isEmpty())
    }

    @Test fun ownershipReadDoesNotWaitForTheChatMutationLockHeldByAnEditor() {
        val a = "concurrent-author-a"
        val group = MigratedDomainStores.chat.createGroupConversation("并发来源", listOf(a, "concurrent-author-b"))
        val own = MigratedDomainStores.chat.appendCharacterMessage(group.id, "真实发送的内容", a)
        val proof = event(own, a)
        val chat = MigratedDomainStores.chat
        val mutationLock = chat.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(chat)
        val worker = Executors.newSingleThreadExecutor()
        try {
            synchronized(mutationLock) {
                // Receipt writers may hold an inner-life lock, while an editor
                // holds this chat lock and awaits evidence withdrawal. A read
                // must complete before the editor can release its mutation lease.
                val read = worker.submit<Boolean> { TimelineStatementOwnership.isOwnCharacterStatement(proof) }
                assertTrue(read.get(3, TimeUnit.SECONDS))
            }
            assertNull(chat.recordedMessage("missing-conversation", "missing-message"))
            assertTrue(chat.editMessage(own.id, "编辑后的完整内容"))
            assertEquals("编辑后的完整内容", chat.recordedMessage(group.id, own.id)?.content)
            chat.markFailed(own.id)
            assertFalse(TimelineStatementOwnership.isOwnCharacterStatement(proof))
            assertTrue(chat.deleteMessage(own.id))
            assertNull(chat.recordedMessage(group.id, own.id))
        } finally { worker.shutdownNow() }
    }
    @Test fun privateWitnessNeedsTheSameSentOriginalAndCurrentRevision() {
        val role = "private-canonical-proof"
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "真实私聊")
        val user = MigratedDomainStores.chat.sendUserMessage(conversation.id, "先等等，我还没决定。")
        val own = MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "好，我等你。", role)
        val userEvent = SharedExperienceTimeline.eventsByIds(role, listOf(user.id)).single()
        val ownEvent = SharedExperienceTimeline.eventsByIds(role, listOf(own.id)).single()
        assertTrue(TimelineStatementOwnership.involvesUser(userEvent))
        assertEquals(listOf(userEvent), CompanionContactClock.verifiedUserContacts(listOf(userEvent)))
        assertTrue(TimelineStatementOwnership.isOwnDialogueStatement(ownEvent))
        assertFalse(TimelineStatementOwnership.involvesUser(
            userEvent.copy(content = "我已经决定了")))
        assertFalse(TimelineStatementOwnership.involvesUser(
            userEvent.copy(occurredAt = userEvent.occurredAt.plusSeconds(1))))
        assertFalse(TimelineStatementOwnership.involvesUser(
            userEvent.copy(sessionId = "other-conversation")))
        MigratedDomainStores.chat.markFailed(user.id)
        assertFalse(TimelineStatementOwnership.involvesUser(userEvent))
        assertTrue(CompanionContactClock.verifiedUserContacts(listOf(userEvent)).isEmpty())
        MigratedDomainStores.chat.markFailed(own.id)
        assertFalse(TimelineStatementOwnership.isOwnDialogueStatement(ownEvent))
    }


}
