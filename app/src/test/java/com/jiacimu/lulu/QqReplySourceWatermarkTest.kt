package com.jiacimu.lulu

import com.jiacimu.lulu.data.InMemoryLuluChatStore
import com.jiacimu.lulu.data.CompanionActionRuntime
import com.jiacimu.lulu.data.ToolRouter
import com.jiacimu.lulu.data.OrderedCharacterActions
import com.jiacimu.lulu.data.mirroredPrivateReplyStepIndices
import com.jiacimu.lulu.data.samePrivateReplyContent
import com.jiacimu.lulu.data.singlePrivateInboundReply
import com.jiacimu.lulu.data.verifiedRoleDeliveries
import com.jiacimu.lulu.data.ChatGenerationActivity
import com.jiacimu.lulu.data.LuluGroupMember
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.MemoryTestEnvironment
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
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
class QqReplySourceWatermarkTest {
    @Before fun setup() {
        MemoryTestEnvironment.release()
        val app = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(app)
        SharedExperienceTimeline.initialize(app)
    }

    @After fun cleanup() { MemoryTestEnvironment.release() }


    @Test fun groupRosterDoesNotImplyPeerAcquaintance() {
        val a = MigratedDomainStores.characters.create("陌生群成员甲").characterId
        val b = MigratedDomainStores.characters.create("陌生群成员乙").characterId
        val group = MigratedDomainStores.chat.createGroupConversation("刚拉进来的群", listOf(a, b))
        val names = mapOf(a to "甲", b to "乙")
        val initial = groupPeerEvidenceContext(a, group.groupChat!!.members,
            MigratedDomainStores.chat.messages(group.id).value, names)
        assertTrue(initial, initial.contains("只在本群成员名单看到姓名"))
        assertTrue(initial, initial.contains("不自动说明彼此相识"))
        assertFalse(initial.contains("已有真实交集"))

        MigratedDomainStores.chat.appendCharacterMessage(group.id, "我先打声招呼", b)
        val oneSided = groupPeerEvidenceContext(a, group.groupChat!!.members,
            MigratedDomainStores.chat.messages(group.id).value, names)
        assertTrue(oneSided, oneSided.contains("只曾在本群亲眼看到对方发言"))
        assertFalse(oneSided.contains("已有真实交集"))

        MigratedDomainStores.chat.appendCharacterMessage(group.id, "你好", a)
        val exchanged = groupPeerEvidenceContext(a, group.groupChat!!.members,
            MigratedDomainStores.chat.messages(group.id).value, names)
        assertTrue(exchanged, exchanged.contains("已有真实交集：共同群聊认识"))
        assertTrue(exchanged, exchanged.contains("认识不自动等于熟悉或亲密"))
    }

    @Test fun groupPeerKnowledgeLeavesExplicitRelationshipIntact() {
        val unknown = com.jiacimu.lulu.data.CharacterSocialRelationshipSnapshot(
            "a", "b", emptyList(), 0, null, "尚未认识")
        val stranger = groupPeerEvidenceLine("乙", unknown, false)
        assertTrue(stranger, stranger.contains("只在本群成员名单看到姓名"))
        assertFalse(stranger.contains("朋友"))
        val heard = groupPeerEvidenceLine("乙", unknown, true)
        assertTrue(heard, heard.contains("只曾在本群亲眼看到对方发言"))
        val explicit = groupPeerEvidenceLine("乙", unknown, false, "多年老友")
        assertTrue(explicit, explicit.contains("本人的明确关系约定：多年老友"))
        assertFalse(explicit.contains("尚无可核实的双方交往"))
    }

    @Test fun privateReplyCannotTreatEditedThenRestoredTextAsOriginalVersion() {
        val store = InMemoryLuluChatStore().also { it.initialize(RuntimeEnvironment.getApplication()) }
        val role = MigratedDomainStores.characters.create("私聊水位角色").characterId
        val conversation = store.ensureConversation(role, "私聊")
        val first = store.sendUserMessage(conversation.id, "原话")
        val captured = captureReplySourceWatermark(conversation.id, role, setOf(first.id), store)
        assertNotNull(captured)
        assertTrue(isReplySourceWatermarkCurrent(captured!!, store))
        // A character's unrelated persisted message does not change the user source.
        store.appendCharacterMessage(conversation.id, "我听见了。", role)
        assertTrue(isReplySourceWatermarkCurrent(captured, store))
        assertTrue(store.editMessage(first.id, "我其实不是这个意思"))
        assertFalse(isReplySourceWatermarkCurrent(captured, store))
        // Even identical restored wording is a new revision, not the old source.
        assertTrue(store.editMessage(first.id, "原话"))
        assertFalse(isReplySourceWatermarkCurrent(captured, store))
        val corrected = captureReplySourceWatermark(conversation.id, role, setOf(first.id), store)!!
        assertTrue(isReplySourceWatermarkCurrent(corrected, store))
        store.sendUserMessage(conversation.id, "再补充一句")
        assertFalse(isReplySourceWatermarkCurrent(corrected, store))
        assertNull(captureReplySourceWatermark(conversation.id, "another-role", setOf(first.id), store))
    }

    @Test fun groupReplyUsesEachMembersOwnTimelineRevisionAndLiveMembership() {
        val store = InMemoryLuluChatStore().also { it.initialize(RuntimeEnvironment.getApplication()) }
        val a = MigratedDomainStores.characters.create("群水位甲").characterId
        val b = MigratedDomainStores.characters.create("群水位乙").characterId
        val c = MigratedDomainStores.characters.create("群水位丙").characterId
        val outsider = MigratedDomainStores.characters.create("群外人").characterId
        val group = store.createGroupConversation("版本群", listOf(a, b, c))
        val user = store.sendUserMessage(group.id, "刚才说的那句话")
        val aBefore = captureReplySourceWatermark(group.id, a, setOf(user.id), store)!!
        val bBefore = captureReplySourceWatermark(group.id, b, setOf(user.id), store)!!
        assertTrue(isReplySourceWatermarkCurrent(aBefore, store))
        assertTrue(isReplySourceWatermarkCurrent(bBefore, store))
        assertNull(captureReplySourceWatermark(group.id, outsider, setOf(user.id), store))
        store.appendCharacterMessage(group.id, "甲先说了一句", a)
        assertTrue(isReplySourceWatermarkCurrent(bBefore, store))
        assertTrue(store.editMessage(user.id, "纠正一下"))
        assertFalse(isReplySourceWatermarkCurrent(aBefore, store))
        assertFalse(isReplySourceWatermarkCurrent(bBefore, store))
        val revised = captureReplySourceWatermark(group.id, a, setOf(user.id), store)!!
        assertTrue(store.updateGroupConversation(group.id,
            group.groupChat!!.copy(members = listOf(LuluGroupMember(b), LuluGroupMember(c)))))
        assertFalse(isReplySourceWatermarkCurrent(revised, store))
        assertNull(captureReplySourceWatermark(group.id, a, setOf(user.id), store))
        val bRevised = captureReplySourceWatermark(group.id, b, setOf(user.id), store)!!
        assertTrue(store.deleteMessage(user.id))
        assertFalse(isReplySourceWatermarkCurrent(bRevised, store))
    }

    @Test fun joiningOrRenamingGroupInvalidatesEarlierGeneratedGroupReply() {
        val store = InMemoryLuluChatStore().also { it.initialize(RuntimeEnvironment.getApplication()) }
        val a = MigratedDomainStores.characters.create("房间变更甲").characterId
        val b = MigratedDomainStores.characters.create("房间变更乙").characterId
        val c = MigratedDomainStores.characters.create("房间变更丙").characterId
        val group = store.createGroupConversation("聊天小组", listOf(a, b))
        val msg = store.sendUserMessage(group.id, "一起讨论一下")
        val first = captureReplySourceWatermark(group.id, a, setOf(msg.id), store)!!
        val changed = group.groupChat!!.copy(
            members = group.groupChat!!.members + LuluGroupMember(c),
        )
        assertTrue(store.updateGroupConversation(group.id, changed))
        assertFalse(isReplySourceWatermarkCurrent(first, store))
        val second = captureReplySourceWatermark(group.id, a, setOf(msg.id), store)!!
        assertTrue(isReplySourceWatermarkCurrent(second, store))
        assertTrue(store.updateGroupConversation(group.id, changed.copy(name = "新的小组名")))
        assertFalse(isReplySourceWatermarkCurrent(second, store))
    }

    @Test fun userCorrectionDuringTypingLeadStopsOldBubblesBeforeAnyDelivery() = runBlocking {
        val role = MigratedDomainStores.characters.create("慢回复水位角色").characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "慢回复")
        val first = MigratedDomainStores.chat.sendUserMessage(conversation.id, "先回答这个")
        val captured = captureReplySourceWatermark(conversation.id, role, setOf(first.id))!!
        val pending = async {
            appendRoleReplyWithPacing(conversation.id, role, "慢回复水位角色",
                parseCharacterReplyPresentation("旧答案第一句。⟪BUBBLE⟫旧答案第二句。"),
                actionableUserMessageIds = setOf(first.id),
                sourceWatermark = captured)
        }
        delay(100)
        MigratedDomainStores.chat.sendUserMessage(conversation.id, "不对，我改主意了")
        assertEquals("", pending.await())
        assertTrue(MigratedDomainStores.chat.messages(conversation.id).value.none {
            it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
        })
    }

    @Test fun tracedBubbleDeliveryRecordsOnlyActualMessagesAndCompletion() = runBlocking {
        val role = MigratedDomainStores.characters.create("回合诊断测试角色").characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "回合诊断")
        val user = MigratedDomainStores.chat.sendUserMessage(conversation.id, "接一下话")
        LuluRepositories.performance.clearTimings()
        val trace = "opaque-test-turn"
        val delivered = appendRoleReplyWithPacing(
            conversation.id, role, "回合诊断测试角色",
            parseCharacterReplyPresentation("第一句。⟪BUBBLE⟫第二句。"),
            actionableUserMessageIds = setOf(user.id),
            diagnosticTurnTraceId = trace,
        )
        assertTrue(delivered, delivered.isNotBlank())
        val sent = MigratedDomainStores.chat.messages(conversation.id).value
            .filter { it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character }
        assertEquals(2, sent.size)
        val records = LuluRepositories.performance.timingRecords.first()
            .filter { it.detail.contains("trace=$trace") }
        val bubbles = records.filter { it.stage == "P08a气泡入库" }
        assertEquals(2, bubbles.size)
        sent.forEach { message ->
            assertTrue(bubbles.any { it.detail.contains("message=${message.id}") })
        }
        assertTrue(records.any { it.stage == "P08a发送结局" &&
            it.detail.contains("status=complete") && it.detail.contains("delivered=2") })
        assertFalse(records.any { it.detail.contains("第一句") || it.detail.contains("第二句") })
    }

    @Test fun actionExecutorDoesNotReportSuccessWhenCorrectedReplyWasNeverSent() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val role = MigratedDomainStores.characters.create("动作回执水位角色").characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "动作回执")
        val first = MigratedDomainStores.chat.sendUserMessage(conversation.id, "先说原问题")
        val args = JSONObject().put("text", "针对旧问题的回复")
            .put("_replyEvidenceIds", JSONArray().put(first.id))
        val pending = async {
            CompanionActionRuntime.execute(app, role, "send_private_message", args)
        }
        // Wait for the real sender to enter its paced-delivery lease,
        // rather than racing the executor's synchronous setup.
        withTimeout(8_000) {
            while (!ChatGenerationActivity.isRunning(conversation.id)) delay(10)
        }
        MigratedDomainStores.chat.sendUserMessage(conversation.id, "刚才说错了，按这条来")
        val result = pending.await()
        assertFalse("Stale reply cannot be marked as a successful action", result.success)
        assertTrue(MigratedDomainStores.chat.messages(conversation.id).value.none {
            it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
        })
    }

    @Test fun privateActionPartialDeliveryReportsOnlyPersistedBubbleIdsAfterCorrection() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val role = MigratedDomainStores.characters.create("部分气泡回执角色").characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "部分送达")
        val source = MigratedDomainStores.chat.sendUserMessage(conversation.id, "先回答这个")
        val args = JSONObject().put("text", "第一泡。⟪BUBBLE⟫第二泡。⟪BUBBLE⟫第三泡。")
            .put("_replyEvidenceIds", JSONArray().put(source.id))
        val pending = async {
            CompanionActionRuntime.execute(app, role, "send_private_message", args)
        }
        // Wait for the actual first persisted bubble, not merely the typing lease.
        withTimeout(8_000) {
            while (MigratedDomainStores.chat.messages(conversation.id).value.none {
                it.authorCharacterId == role
            }) delay(10)
        }
        MigratedDomainStores.chat.sendUserMessage(conversation.id, "纠正：旧回复不要继续")
        val result = pending.await()
        val persisted = MigratedDomainStores.chat.messages(conversation.id).value
            .filter { it.authorCharacterId == role }
        assertTrue("At least one bubble must have reached the real store", persisted.isNotEmpty())
        assertFalse("A corrected partial turn must not report whole-turn success", result.success)
        assertEquals(persisted.map { it.id }, result.deliveredMessageIds)
        assertEquals(persisted.last().id, result.evidenceId)
        assertTrue("No stale bubble may follow the correction", persisted.size < 3)
    }

    @Test fun groupActionIgnoresOtherMembersUnreadAndSendsOnlyOwnBubble() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val a = MigratedDomainStores.characters.create("群聊先发言成员").characterId
        val b = MigratedDomainStores.characters.create("群聊后发言成员").characterId
        val group = MigratedDomainStores.chat.createGroupConversation("后宫测试群", listOf(a, b))
        val user = MigratedDomainStores.chat.sendUserMessage(group.id, "把我打入冷宫吗")
        val earlierRole = MigratedDomainStores.chat.appendCharacterMessage(group.id, "我先回一句", a)
        // Online perception includes the user's raw ID AND another character's
        // ID. Only the former is a user-message watermark for member B.
        val ids = setOf(user.id, earlierRole.id)
        assertEquals(setOf(user.id),
            com.jiacimu.lulu.data.replyUserMessageIdsForDestination(group.id, b, ids))
        val args = JSONObject().put("groupId", group.id).put("text", "那当然不会啦")
            .put("_replyEvidenceIds", JSONArray().put(user.id).put(earlierRole.id))
        val outcome = CompanionActionRuntime.execute(app, b, "send_group_message", args)
        assertTrue(outcome.summary, outcome.success)
        assertTrue(MigratedDomainStores.chat.messages(group.id).value.any {
            it.authorCharacterId == b && it.content == "那当然不会啦"
        })
    }

    @Test fun groupActionStillStopsOnNewUserCorrectionWithOtherRoleEvidence() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val a = MigratedDomainStores.characters.create("群消息纠正甲").characterId
        val b = MigratedDomainStores.characters.create("群消息纠正乙").characterId
        val group = MigratedDomainStores.chat.createGroupConversation("纠正测试群", listOf(a, b))
        val user = MigratedDomainStores.chat.sendUserMessage(group.id, "之前的问题")
        val olderRole = MigratedDomainStores.chat.appendCharacterMessage(group.id, "先接一句", a)
        val args = JSONObject().put("groupId", group.id).put("text", "应该停止的旧回复")
            .put("_replyEvidenceIds", JSONArray().put(olderRole.id).put(user.id))
        val pending = async { CompanionActionRuntime.execute(app, b, "send_group_message", args) }
        withTimeout(8_000) {
            while (!ChatGenerationActivity.isRunning(group.id)) delay(10)
        }
        MigratedDomainStores.chat.sendUserMessage(group.id, "不对，我改主意了")
        val outcome = pending.await()
        assertFalse(outcome.success)
        assertTrue(MigratedDomainStores.chat.messages(group.id).value.none {
            it.authorCharacterId == b && it.content == "应该停止的旧回复"
        })
    }

    @Test fun finalCommitGuardRejectsEditedSourceAndNewUserCorrection() {
        val store = InMemoryLuluChatStore().also { it.initialize(RuntimeEnvironment.getApplication()) }
        val role = MigratedDomainStores.characters.create("提交水位角色").characterId
        val conversation = store.ensureConversation(role, "提交门禁")
        val original = store.sendUserMessage(conversation.id, "原来的意思")
        val first = captureReplySourceWatermark(conversation.id, role, setOf(original.id), store)!!
        assertTrue(store.editMessage(original.id, "已经纠正"))
        val rejected = store.appendCharacterMessageIfSourceCurrent(
            conversation.id, "不能发送的旧回复", role, null, "old-batch",
            false, "", sourceStillCurrent = { isReplySourceWatermarkCurrent(first, store) },
        )
        assertNull(rejected)
        assertEquals(0, store.messages(conversation.id).value.count {
            it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
        })
        val corrected = captureReplySourceWatermark(conversation.id, role, setOf(original.id), store)!!
        store.sendUserMessage(conversation.id, "再补充一句")
        assertNull(store.appendCharacterMessageIfSourceCurrent(
            conversation.id, "同样不应发送", role, null, "old-batch-2",
            false, "", sourceStillCurrent = { isReplySourceWatermarkCurrent(corrected, store) },
        ))
    }

    @Test fun concurrentCorrectionCannotSlipBetweenFinalValidationAndPersistedBubble() {
        val store = InMemoryLuluChatStore().also { it.initialize(RuntimeEnvironment.getApplication()) }
        val role = MigratedDomainStores.characters.create("并发提交水位角色").characterId
        val conversation = store.ensureConversation(role, "原子提交")
        val original = store.sendUserMessage(conversation.id, "原问题")
        val watermark = captureReplySourceWatermark(conversation.id, role, setOf(original.id), store)!!
        val guardEntered = java.util.concurrent.CountDownLatch(1)
        val releaseGuard = java.util.concurrent.CountDownLatch(1)
        val editStarted = java.util.concurrent.CountDownLatch(1)
        val editFinished = java.util.concurrent.CountDownLatch(1)
        val sent = java.util.concurrent.atomic.AtomicReference<com.jiacimu.lulu.data.LuluChatMessage?>()
        val errors = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val sender = Thread {
            try {
                sent.set(store.appendCharacterMessageIfSourceCurrent(
                    conversation.id, "真正送达的第一句", role, null, "concurrent-batch",
                    false, "", sourceStillCurrent = {
                        guardEntered.countDown()
                        check(releaseGuard.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        isReplySourceWatermarkCurrent(watermark, store)
                    },
                ))
            } catch (error: Throwable) { errors.set(error) }
        }
        val editor = Thread {
            try {
                editStarted.countDown()
                check(store.editMessage(original.id, "我改主意了"))
            } catch (error: Throwable) { errors.set(error) }
            finally { editFinished.countDown() }
        }
        sender.start()
        try {
            assertTrue(guardEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            editor.start()
            assertTrue(editStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // The editor cannot commit while the final check owns the store lock.
            assertEquals(1L, editFinished.count)
        } finally { releaseGuard.countDown() }
        sender.join(5_000)
        editor.join(5_000)
        assertFalse(sender.isAlive)
        assertFalse(editor.isAlive)
        assertNull(errors.get())
        assertNotNull(sent.get())
        assertEquals(1, store.messages(conversation.id).value.count {
            it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
        })
        assertFalse(isReplySourceWatermarkCurrent(watermark, store))
    }


    @Test fun actualToolReplyReturnsOnlyItsPersistedBubbleIds() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val role = MigratedDomainStores.characters.create("精确消息回执角色").characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "回执并发检查")
        val unrelated = MigratedDomainStores.chat.appendCharacterMessage(
            conversation.id, "属于另一次行动的消息", role)
        val source = MigratedDomainStores.chat.sendUserMessage(conversation.id, "请回应这一条")
        val args = JSONObject().put("text", "第一泡。⟪BUBBLE⟫第二泡。")
            .put("_replyEvidenceIds", JSONArray().put(source.id))
        val receipt = JSONObject(ToolRouter.execute(app, role, "send_private_message",
            args, requestId = "exact-message-proof-" + java.util.UUID.randomUUID().toString()))
        assertTrue(receipt.toString(), receipt.optBoolean("success"))
        val ids = receipt.getJSONArray("deliveredMessageIds")
            .let { array -> (0 until array.length()).map(array::getString) }
        assertEquals(2, ids.size)
        assertEquals(ids.last(), receipt.getString("evidenceId"))
        assertFalse(ids.contains(unrelated.id))
        val actual = verifiedRoleDeliveries(conversation.id, role, ids)
        assertEquals(ids, actual.map { it.id })
        assertEquals(listOf("第一泡。", "第二泡。"), actual.map { it.content })
    }

    @Test fun deliveryBindingUsesAllExactIdsEvenBeyondEightAndRejectsOtherTurns() {
        val role = MigratedDomainStores.characters.create("长回复消息归属角色").characterId
        val outsider = MigratedDomainStores.characters.create("另一角色").characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "精确绑定")
        val messages = (1..10).map { index ->
            MigratedDomainStores.chat.appendCharacterMessage(
                conversation.id, "第${index}条真实消息", role)
        }
        val unrelatedSameActor = MigratedDomainStores.chat.appendCharacterMessage(
            conversation.id, "同角色另一轮", role)
        val unrelatedOtherActor = MigratedDomainStores.chat.appendCharacterMessage(
            conversation.id, "其他角色的消息", outsider)
        val ids = messages.map { it.id }
        val exact = verifiedRoleDeliveries(conversation.id, role,
            ids + unrelatedOtherActor.id + ids.first() + "不存在的消息")
        assertEquals(ids, exact.map { it.id })
        assertEquals(10, exact.size)
        assertFalse(exact.any { it.id == unrelatedSameActor.id || it.id == unrelatedOtherActor.id })
        assertTrue(verifiedRoleDeliveries("wrong-conversation", role, ids).isEmpty())
        assertTrue(verifiedRoleDeliveries(conversation.id, outsider, ids).isEmpty())
    }


    @Test fun sideActionReplyReceiptsKeepTheirOwnConversationAndTrace() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val role = MigratedDomainStores.characters.create("多动作回执角色").characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "多动作")
        val source = MigratedDomainStores.chat.sendUserMessage(conversation.id, "你可以发两句话")
        LuluRepositories.performance.clearTimings()
        val trace = "side-action-trace-proof"
        val steps = JSONArray().put(JSONObject()
            .put("tool", "send_private_message")
            .put("args", JSONObject().put("text", "附加第一泡⟪BUBBLE⟫附加第二泡")
                .put("_turnTraceId", "not-model-controlled")))
        val report = OrderedCharacterActions.execute(app, role, steps,
            requestId = "side-action-message-" + java.util.UUID.randomUUID().toString(),
            userEvidenceIds = setOf(source.id), turnTraceId = trace)
        assertEquals(1, report.completedCount)
        assertEquals(2, report.deliveredMessageRefs.size)
        assertTrue(report.deliveredMessageRefs.all { it.conversationId == conversation.id })
        val actual = verifiedRoleDeliveries(conversation.id, role,
            report.deliveredMessageRefs.map { it.messageId })
        assertEquals(listOf("附加第一泡", "附加第二泡"), actual.map { it.content })
        val timings = LuluRepositories.performance.timingRecords.first()
        assertTrue(timings.any { it.stage == "P08a发送结局" &&
            it.detail.contains("trace=$trace") &&
            it.detail.contains("status=complete") })
        assertFalse(timings.any { it.detail.contains("trace=not-model-controlled") })
    }


    @Test fun oneInboundReplyOwnsAllUnconditionalSpeechAndPreservesOtherTools() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val role = MigratedDomainStores.characters.create("私聊一次正文归口角色").characterId
        val chat = MigratedDomainStores.chat.ensureConversation(role, "一次回复多个气泡")
        val user = MigratedDomainStores.chat.sendUserMessage(chat.id, "学习累了")
        val first = "学得怎么样了，宝宝？"
        val second = "头还晕不晕？"
        // The old dual route used to send primary bubbles AND this one-piece
        // private tool message, producing exactly the screenshot symptom.
        val steps = JSONArray()
            .put(JSONObject().put("tool", "send_private_message").put("args",
                JSONObject().put("text", "$first $second")))
            .put(JSONObject().put("action", "message").put("text", "喝口温水。"))
            .put(JSONObject().put("tool", "plan_personal_activity").put("args",
                JSONObject().put("title", "之后再核实").put("intent", "询问实际学习进度")))
        val selected = singlePrivateInboundReply(first + SemanticBubbleSeparator + second, steps)
        assertEquals(2, selected.absorbedSends)
        assertEquals(first + SemanticBubbleSeparator + second + SemanticBubbleSeparator + "喝口温水。", selected.text)
        assertEquals(1, selected.actions?.length())
        assertEquals("plan_personal_activity",
            selected.actions!!.getJSONObject(0).optString("tool"))
        val receipt = JSONObject(ToolRouter.execute(app, role, "send_private_message",
            JSONObject().put("text", selected.text)
                .put("_replyEvidenceIds", JSONArray().put(user.id)),
            requestId = "normalized-private-reply-" + java.util.UUID.randomUUID()))
        assertTrue(receipt.toString(), receipt.optBoolean("success"))
        assertEquals(3, receipt.getJSONArray("deliveredMessageIds").length())
        val actual = MigratedDomainStores.chat.messages(chat.id).value.filter {
            it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
        }
        assertEquals(listOf(first, second, "喝口温水。"), actual.map { it.content })
        // The only remaining side step is a non-speech tool. An extra
        // send_private_message can no longer be dispatched through it.
        assertFalse(selected.actions.toString().contains("send_private_message"))
    }

    @Test fun oneReplyPlanRetainsSideVoiceAndSeparatesScheduledReminders() {
        val voice = JSONArray().put(JSONObject().put("tool", "send_private_message")
            .put("args", JSONObject()
                .put("text", "晚安。")
                .put("bubbleDeliveries", JSONArray().put("voice"))
                .put("bubbleSpeechTracks", JSONArray().put("晚安。"))))
        val grouped = singlePrivateInboundReply("我在呢。", voice)
        assertEquals("我在呢。" + SemanticBubbleSeparator + "晚安。", grouped.text)
        assertEquals(listOf("voice"), grouped.addedModes)
        assertEquals(listOf("晚安。"), grouped.addedTracks)
        assertNull(grouped.actions)

        // Scheduled reminders are actual independently linked actions, not
        // duplicate dialogue; preserve the executable planId and its receipt.
        val reminder = JSONArray().put(JSONObject().put("tool", "send_private_message")
            .put("args", JSONObject()
                .put("text", "定时提醒：喝水")
                .put("planId", "real-existing-scheduled-id")))
        val selected = singlePrivateInboundReply("这轮先说别的。", reminder)
        assertEquals(0, selected.absorbedSends)
        assertEquals(1, selected.actions?.length())
        assertEquals("real-existing-scheduled-id",
            selected.actions!!.getJSONObject(0).getJSONObject("args").getString("planId"))
    }

    @Test fun inboundReplyPreservesConditionalSendsAndIndependentSilence() {
        val actions = JSONArray()
            .put(JSONObject().put("tool", "send_private_message")
                .put("requiresPreviousSuccess", true)
                .put("args", JSONObject().put("text", "搜索完成了才告诉你结果。")))
            .put(JSONObject().put("tool", "read_book")
                .put("args", JSONObject().put("bookId", "existing-book")))
        val selected = singlePrivateInboundReply("我先查查看。", actions)
        assertEquals("我先查查看。", selected.text)
        assertEquals(0, selected.absorbedSends)
        assertEquals(2, selected.actions?.length())
        // With no inbound normalizer, independent life turns still run
        // whatever independently authored send actions they chose.
        val followup = singlePrivateInboundReply("", JSONArray()
            .put(JSONObject().put("tool", "send_private_message")
                .put("args", JSONObject().put("text", "嗨，我刚想到一件事。"))))
        assertEquals(1, followup.absorbedSends)
        assertEquals("嗨，我刚想到一件事。", followup.text)
        assertNull(followup.actions)
    }

    @Test fun oneDecisionCannotResendItsAlreadyDeliveredPrimaryThroughSideActions() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val role = MigratedDomainStores.characters.create("主副消息重复屏障角色").characterId
        val chat = MigratedDomainStores.chat.ensureConversation(role, "一次决定一份正文")
        val user = MigratedDomainStores.chat.sendUserMessage(chat.id, "学完了")
        val first = MigratedDomainStores.chat.appendCharacterMessage(chat.id,
            "学得怎么样了，宝宝？", role)
        val second = MigratedDomainStores.chat.appendCharacterMessage(chat.id,
            "现在都快十一点半了，头还晕不晕？要是累了，咱们就收拾准备洗漱。", role)
        val duplicate = JSONObject().put("action", "message")
            .put("text", first.content + second.content)
        val followUp = JSONObject().put("tool", "send_private_message")
            .put("args", JSONObject().put("text", "记得喝一点水哦。"))
        val steps = JSONArray().put(duplicate).put(followUp)
        assertEquals(setOf(0), mirroredPrivateReplyStepIndices(
            steps, first.content + SemanticBubbleSeparator + second.content))
        val result = OrderedCharacterActions.execute(app, role, steps,
            requestId = "primary-side-mirror-" + java.util.UUID.randomUUID().toString(),
            userEvidenceIds = setOf(user.id),
            alreadyDeliveredPrivateText = first.content + second.content)
        assertEquals(result.notes.joinToString(), 1, result.completedCount)
        assertEquals(1, result.deliveredMessageRefs.size)
        val actual = MigratedDomainStores.chat.messages(chat.id).value.filter {
            it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
        }
        assertEquals(listOf(first.content, second.content, "记得喝一点水哦。"),
            actual.map { it.content })
        assertTrue(result.notes.any { it.contains("主回复已真实送达相同正文") })
    }

    @Test fun mirroredSideSpeechMustMatchTheWholeTurnRatherThanARepeatedPhrase() {
        // One model-authored turn may be re-segmented into different bubbles.
        assertTrue(samePrivateReplyContent(
            "学得怎么样了，宝宝？" + SemanticBubbleSeparator + "头还晕不晕？",
            "学得怎么样了，宝宝？ 头还晕不晕？"))
        assertFalse(samePrivateReplyContent("记得喝水。", "记得喝水，而且早点睡。"))
        val steps = JSONArray().put(JSONObject().put("tool", "send_private_message")
            .put("args", JSONObject().put("text", "接着说，我在听。")))
        assertTrue(mirroredPrivateReplyStepIndices(steps, "我在听，接着说。").isEmpty())
        val split = JSONArray()
            .put(JSONObject().put("action", "message").put("text", "第一句。"))
            .put(JSONObject().put("action", "message").put("text", "第二句！"))
            .put(JSONObject().put("action", "message").put("text", "后来想到的补充。"))
        assertEquals(setOf(0, 1), mirroredPrivateReplyStepIndices(
            split, "第一句。" + SemanticBubbleSeparator + "第二句！"))
        assertTrue(mirroredPrivateReplyStepIndices(
            split, "第一句。第二句！这句不同。").isEmpty())
    }

    @Test fun sameToolRequestReplaysAcrossNewDiagnosticTurnIdsWithoutSendingAgain() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val role = MigratedDomainStores.characters.create("幂等诊断角色").characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "幂等回执")
        val requestId = "trace-retry-" + java.util.UUID.randomUUID().toString()
        val original = JSONObject(ToolRouter.execute(app, role, "send_private_message",
            JSONObject().put("text", "这一条只应真实发送一次")
                .put("_turnTraceId", "trace-before-retry"), requestId = requestId))
        assertTrue(original.toString(), original.optBoolean("success"))
        val replayed = JSONObject(ToolRouter.execute(app, role, "send_private_message",
            JSONObject().put("text", "这一条只应真实发送一次")
                .put("_turnTraceId", "trace-after-retry"), requestId = requestId))
        assertEquals(original.toString(), replayed.toString())
        val actual = MigratedDomainStores.chat.messages(conversation.id).value.filter {
            it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character &&
                it.content == "这一条只应真实发送一次"
        }
        assertEquals(1, actual.size)
        assertEquals(actual.single().id, replayed.getString("evidenceId"))
    }

    @Test fun oldPersistedFingerprintWithTraceIsStillRecognizedAsSameRequest() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val role = MigratedDomainStores.characters.create("旧请求兼容角色").characterId
        val requestId = "legacy-trace-" + java.util.UUID.randomUUID().toString()
        val prior = JSONObject().put("success", true).put("summary", "此前已完成的真实记录")
        val legacy = JSONObject()
            .put("fingerprint", """send_private_message:{"_turnTraceId":"previous-turn","text":"此前选择的内容"}""")
            .put("status", "succeeded").put("result", prior.toString())
        val preferences = app.getSharedPreferences("lulu_tool_executions", android.content.Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("$role:$requestId", legacy.toString()).commit())
        val replay = JSONObject(ToolRouter.execute(app, role, "send_private_message",
            JSONObject().put("text", "此前选择的内容")
                .put("_turnTraceId", "brand-new-diagnostic"), requestId = requestId))
        assertEquals(prior.toString(), replay.toString())
        assertFalse(MigratedDomainStores.chat.conversations.value.any { it.characterId == role &&
            MigratedDomainStores.chat.messages(it.id).value.any { msg ->
                msg.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
            } })
        val changed = JSONObject(ToolRouter.execute(app, role, "send_private_message",
            JSONObject().put("text", "被实质改写的内容"), requestId = requestId))
        assertFalse(changed.optBoolean("success"))
    }

    @Test fun correctionBetweenTwoBubblesKeepsFirstReceiptButStopsSecond() = runBlocking {
        val role = MigratedDomainStores.characters.create("气泡中途纠正角色").characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "气泡中断")
        val first = MigratedDomainStores.chat.sendUserMessage(conversation.id, "最初的问题")
        val captured = captureReplySourceWatermark(conversation.id, role, setOf(first.id))!!
        val pending = async {
            appendRoleReplyWithPacingReceipt(conversation.id, role, "气泡中途纠正角色",
                parseCharacterReplyPresentation("第一句已送达。⟪BUBBLE⟫第二句不该再发。"),
                actionableUserMessageIds = setOf(first.id),
                sourceWatermark = captured)
        }
        withTimeout(8_000) {
            while (MigratedDomainStores.chat.messages(conversation.id).value.none {
                    it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
                }) delay(25)
        }
        MigratedDomainStores.chat.sendUserMessage(conversation.id, "停一下，我改问题了")
        val receipt = pending.await()
        assertFalse(receipt.complete)
        assertTrue(receipt.superseded)
        assertEquals("", receipt.text)
        val actual = MigratedDomainStores.chat.messages(conversation.id).value.filter {
            it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
        }
        assertEquals(1, actual.size)
        assertEquals(listOf(actual.single().id), receipt.messageIds)
        assertEquals("第一句已送达。", actual.single().content)
    }
}
