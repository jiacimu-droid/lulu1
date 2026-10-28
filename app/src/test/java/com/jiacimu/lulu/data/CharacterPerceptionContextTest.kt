package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterPerceptionContextTest {
    @Test fun directOnlyForeignEvidenceNeverEntersAnotherCharactersThoughtOrSeenLedger() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(app)
        PerceptionStimulusLedger.initialize(app)
        val a = "friend-a-" + java.util.UUID.randomUUID()
        val b = "friend-b-" + java.util.UUID.randomUUID()
        val bEventId = "private-b-" + java.util.UUID.randomUUID()
        val aEventId = "private-a-" + java.util.UUID.randomUUID()
        val syntheticId = "live-call-" + java.util.UUID.randomUUID()
        try {
            SharedExperienceTimeline.record(bEventId, b, "私聊", "用户",
                "我只对 B 说过这件秘密", Instant.now().minusSeconds(25),
                triggerExtraction = false, source = "message",
                evidenceKind = EventEvidenceKind.UserStatement)
            SharedExperienceTimeline.record(aEventId, a, "私聊", "用户",
                "我向 A 说的话", Instant.now().minusSeconds(15),
                triggerExtraction = false, source = "message",
                evidenceKind = EventEvidenceKind.UserStatement)
            assertEquals(setOf(bEventId), SharedExperienceTimeline.foreignEvidenceIds(
                a, listOf(aEventId, bEventId, syntheticId)))
            // This deliberately bypasses the safe observed = eventsByIds(a)
            // adapter to cover cross-role misrouting by a future group source.
            val wrapped = CharacterPerceptionContext.integrate(app, a,
                observed = emptyList(),
                direct = listOf(
                    PerceptionStimulus(bEventId, "B 的私聊：我只对 B 说过这件秘密"),
                    PerceptionStimulus(aEventId, "A 的真实原话"),
                    PerceptionStimulus(syntheticId, "当前 A 电话实际接通"),
                ),
                deferAcknowledgement = true,
            )
            assertEquals(listOf(aEventId, syntheticId),
                wrapped.freshStimuli.map { it.evidenceId })
            assertFalse(wrapped.combined?.description.orEmpty().contains("B 的私聊"))
            CharacterPerceptionContext.acknowledge(app, a, wrapped)
            assertFalse(PerceptionStimulusLedger.hasSeen(app, a, bEventId))
            assertTrue(PerceptionStimulusLedger.hasSeen(app, a, aEventId))
            assertTrue(PerceptionStimulusLedger.hasSeen(app, a, syntheticId))
            val onlyForeign = CharacterPerceptionContext.integrate(app, a,
                observed = listOfNotNull(SharedExperienceTimeline.eventsByIds(b, listOf(bEventId)).firstOrNull()),
                direct = listOf(PerceptionStimulus(bEventId, "私信内容绕过 observed")), 
                deferAcknowledgement = true)
            assertTrue(onlyForeign.freshStimuli.isEmpty())
            assertNull(onlyForeign.combined)
            val own = CharacterPerceptionContext.integrate(app, b,
                observed = emptyList(),
                direct = listOf(PerceptionStimulus(bEventId, "B 确实读到的本人私信")),
                deferAcknowledgement = true)
            assertEquals(listOf(bEventId), own.freshStimuli.map { it.evidenceId })
        } finally {
            SharedExperienceTimeline.deleteEvent(aEventId)
            SharedExperienceTimeline.deleteEvent(bEventId)
            PerceptionStimulusLedger.clear(a)
            PerceptionStimulusLedger.clear(b)
        }
    }

    @Test fun mixedWorldAndPrivateEventsCannotEnterAnotherCharactersPerceptionOrLedger() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(context)
        PerceptionStimulusLedger.initialize(context)
        val a = "role-a-" + java.util.UUID.randomUUID()
        val b = "role-b-" + java.util.UUID.randomUUID()
        val now = Instant.now()
        val aId = "private-a-" + java.util.UUID.randomUUID()
        val bId = "private-b-" + java.util.UUID.randomUUID()
        val sharedA = "group-a-" + java.util.UUID.randomUUID()
        val sharedB = "group-b-" + java.util.UUID.randomUUID()
        try {
            SharedExperienceTimeline.record(aId, a, "数字世界事件", "观察者",
                "A 独处时得到的私人消息", now.minusSeconds(60),
                triggerExtraction = false, source = "digital-world",
                evidenceKind = EventEvidenceKind.Observation)
            SharedExperienceTimeline.record(bId, b, "数字世界事件", "观察者",
                "B 在自己场景里看到的事实", now.minusSeconds(30),
                triggerExtraction = false, source = "digital-world",
                evidenceKind = EventEvidenceKind.Observation)
            val mixed = SharedExperienceTimeline.eventsByIds(a, listOf(aId)) +
                SharedExperienceTimeline.eventsByIds(b, listOf(bId))
            val sensed = CharacterPerceptionContext.integrate(context, a, mixed,
                deferAcknowledgement = true)
            assertEquals(listOf(aId), sensed.freshStimuli.map { it.evidenceId })
            assertFalse(sensed.combined?.description.orEmpty().contains("B 在自己场景里"))
            CharacterPerceptionContext.acknowledge(context, a, sensed)
            assertTrue(PerceptionStimulusLedger.hasSeen(context, a, aId, 1L))
            assertFalse(PerceptionStimulusLedger.hasSeen(context, a, bId, 1L))
            val other = CharacterPerceptionContext.integrate(context, b, mixed,
                deferAcknowledgement = true)
            assertEquals(listOf(bId), other.freshStimuli.map { it.evidenceId })
            assertFalse(other.combined?.description.orEmpty().contains("A 独处时"))
            // Truly shared group words should be witnessed independently.
            SharedExperienceTimeline.record(sharedA, a, "群聊", "共同朋友",
                "群里公开约好明天见面", now, triggerExtraction = false,
                source = "group-membership", evidenceKind = EventEvidenceKind.Observation)
            SharedExperienceTimeline.record(sharedB, b, "群聊", "共同朋友",
                "群里公开约好明天见面", now, triggerExtraction = false,
                source = "group-membership", evidenceKind = EventEvidenceKind.Observation)
            val shared = SharedExperienceTimeline.eventsByIds(a, listOf(sharedA)) +
                SharedExperienceTimeline.eventsByIds(b, listOf(sharedB))
            assertEquals(listOf(sharedA), CharacterPerceptionContext.integrate(
                context, a, shared, deferAcknowledgement = true).freshStimuli.map { it.evidenceId })
            assertEquals(listOf(sharedB), CharacterPerceptionContext.integrate(
                context, b, shared, deferAcknowledgement = true).freshStimuli.map { it.evidenceId })
        } finally {
            listOf(aId,bId,sharedA,sharedB).forEach(SharedExperienceTimeline::deleteEvent)
            PerceptionStimulusLedger.clear(a)
            PerceptionStimulusLedger.clear(b)
        }
    }

    @Test fun previouslySavedPhonePageVisitsStayArchivedButNeverWakeTheRole() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(app)
        PerceptionStimulusLedger.initialize(app)
        val role = "quiet-navigation-" + java.util.UUID.randomUUID()
        val now = Instant.now()
        val oldId = "$role-phone-page"
        val inviteId = "$role-real-invitation"
        try {
            SharedExperienceTimeline.record(oldId, role, "手机·查看痕迹", "主人",
                "主人以前切换到了消息列表", now.minusSeconds(120),
                source = "owner-phone-inspection", triggerExtraction = false,
                evidenceKind = EventEvidenceKind.Observation)
            SharedExperienceTimeline.record(inviteId, role, "手机·群聊", "系统消息",
                "确实收到了群聊邀请", now.minusSeconds(60),
                source = "group-membership", triggerExtraction = false,
                evidenceKind = EventEvidenceKind.Observation)
            val raw = SharedExperienceTimeline.eventsByIds(role, listOf(oldId, inviteId))
            assertEquals(2, raw.size) // no deletion or rewriting of historical data
            assertEquals(listOf(inviteId),
                CharacterPerceptionContext.pending(app, role, now).map { it.id })
            val integrated = CharacterPerceptionContext.integrate(app, role, raw,
                deferAcknowledgement = true)
            assertEquals(listOf(inviteId), integrated.freshStimuli.map { it.evidenceId })
            assertEquals(listOf(inviteId),
                CharacterPerceptionContext.recent(role, now).map { it.id })
        } finally {
            SharedExperienceTimeline.deleteEvent(oldId)
            SharedExperienceTimeline.deleteEvent(inviteId)
            PerceptionStimulusLedger.clear(role)
        }
    }

    @Test fun personalReceiptsEnterPerceptionWithoutMistakingRoutineMovementOrClaimsForNewExperience() {
        val now = Instant.parse("2026-10-16T12:00:00Z")
        fun receipt(tool: String, action: String = "", status: String = "succeeded") = org.json.JSONObject()
            .put("tool", tool).put("status", status)
            .put("fingerprint", "$tool:" + org.json.JSONObject().put("worldAction", action))
            .put("result", org.json.JSONObject().put("success", status == "succeeded")
                .put("summary", "实际结算840分，未达到开局900分目标").put("evidenceId", "game-fact").toString()).toString()
        val game = SharedTimelineEvent("real-tool", "role", "实际工具执行", "执行器", receipt("play_solo_game"), now,
            source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult)
        val read = game.copy(id = "read", source = "reading:real", content = "真实正文进度",
            evidenceKind = EventEvidenceKind.Observation)
        val move = game.copy(id = "move", content = receipt("digital_world_action", "go_home"))
        val thinking = game.copy(id = "not-done", content = receipt("play_solo_game", status = "running"))
        val claim = game.copy(id = "claim", source = "message", evidenceKind = EventEvidenceKind.CharacterStatement)
        assertEquals(listOf("real-tool", "read"), CharacterPerceptionContext.selectRecent(
            listOf(game, read, move, thinking, claim), now).map { it.id })
        assertTrue(CharacterPerceptionContext.stimulus(game).description.contains("840分"))
        assertEquals("game-fact", CharacterPerceptionContext.outcomeEvidenceId(game.source, game.content))
        assertNull(CharacterPerceptionContext.personalOutcome("message", game.content))
    }
    @Test fun verifiedObservedSearchResultsEnterPerceptionButContradictoryStatesDoNot() {
        val now = Instant.parse("2026-10-25T10:00:00Z")
        fun receipt(id: String, status: String, success: Boolean): SharedTimelineEvent {
            val info = org.json.JSONObject().put("tool", "web_search")
                .put("status", status)
                .put("result", org.json.JSONObject().put("success", success)
                    .put("summary", "实际找到资料来源").toString())
            return SharedTimelineEvent(id, "role", "实际工具执行", "执行器",
                info.toString(), now, source = "tool-router",
                evidenceKind = EventEvidenceKind.ToolResult)
        }
        val observed = receipt("observed-result", "observed", true)
        val contradictory = receipt("false-claim", "succeeded", false)
        val pending = receipt("pending-result", "pending", true)
        assertEquals(listOf(observed.id),
            CharacterPerceptionContext.selectRecent(
                listOf(observed, contradictory, pending), now).map { it.id })
        assertTrue(CharacterPerceptionContext.stimulus(observed).description.contains("实际找到资料来源"))
        assertNull(CharacterPerceptionContext.personalOutcome("tool-router", contradictory.content))
    }

    @Test fun allRealSearchResultsReenterSharedPerceptionWithUsefulEvidence() {
        val now = Instant.parse("2026-10-25T04:10:00Z")
        fun search(tool: String, status: String): SharedTimelineEvent {
            val id = "tool-role-$tool-$status"
            val info = "搜索来源与摘要：" + "资料内容".repeat(180) + "；原始URL=https://example.org/actual-result"
            val result = org.json.JSONObject().put("success", status == "succeeded")
                .put("summary", info).put("evidenceId", id)
            val execution = org.json.JSONObject().put("tool", tool).put("status", status)
                .put("result", result.toString())
            return SharedTimelineEvent(id, "role", "实际工具执行", "执行器",
                execution.toString(), now, source = "tool-router",
                evidenceKind = EventEvidenceKind.ToolResult)
        }
        val search = search("web_search", "succeeded")
        val image = search("image_search", "succeeded")
        val xhs = search("read_xiaohongshu", "succeeded")
        val failed = search("web_search", "failed")
        val pending = search("web_search", "pending")
        val fake = search.copy(id = "story", source = "message",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        assertTrue(SearchReceiptFeedback.completedEvidence(search))
        assertTrue(SearchReceiptFeedback.completedEvidence(failed))
        assertFalse(SearchReceiptFeedback.completedEvidence(pending))
        assertFalse(SearchReceiptFeedback.completedEvidence(fake))
        val fresh = listOf(PerceptionStimulus("unrelated-synthetic", "clock"),
            CharacterPerceptionContext.stimulus(search),
            CharacterPerceptionContext.stimulus(image))
        assertEquals(image.id, CharacterPerceptionContext.latestObservedEvidenceId(
            fresh, listOf(search, image)))
        assertEquals("", CharacterPerceptionContext.latestObservedEvidenceId(
            fresh.take(1), listOf(search, image)))
        assertEquals(listOf(search.id, image.id, xhs.id, failed.id),
            CharacterPerceptionContext.selectRecent(
                listOf(search, image, xhs, failed, pending, fake), now).map { it.id })
        val observed = CharacterPerceptionContext.stimulus(search).description
        assertTrue(observed.contains("https://example.org/actual-result"))
        assertTrue(observed.length > 700)
        assertTrue(CharacterPerceptionContext.render(listOf(search)).contains("原始URL="))
        assertTrue(CharacterPerceptionContext.stimulus(failed).description.contains("搜索来源与摘要"))
        assertNull(CharacterPerceptionContext.personalOutcome("message", search.content))
    }
    @Test fun unsuccessfulRegisteredActionsAndPermissionDenialsReturnAsRealFacts() {
        val now = Instant.parse("2026-10-26T04:00:00Z")
        fun outcome(tool: String, status: String, success: Boolean): SharedTimelineEvent {
            val data = org.json.JSONObject().put("tool", tool).put("status", status)
                .put("result", org.json.JSONObject().put("success", success)
                    .put("error", "确切失败或尚无主动授权").toString())
            return SharedTimelineEvent("receipt-$tool-$status", "role", "工具请求", "执行器",
                data.toString(), now, source = "tool-router",
                evidenceKind = EventEvidenceKind.ToolResult)
        }
        val denied = outcome("capture_screen", "waiting_user", false)
        val failed = outcome("publish_moment", "failed", false)
        val successfulMessage = outcome("send_private_message", "succeeded", true)
        val stillPending = outcome("capture_screen", "pending", true)
        val falseClaim = outcome("capture_screen", "failed", true)
        val invalid = outcome("unknown_external_operation", "succeeded", true)
        assertEquals(listOf(denied.id, failed.id),
            CharacterPerceptionContext.selectRecent(
                listOf(denied, failed, successfulMessage, stillPending, falseClaim, invalid), now)
                .map { it.id })
        assertTrue(CharacterPerceptionContext.stimulus(denied).description.contains("尚无主动授权"))
        assertTrue(CharacterPerceptionContext.stimulus(failed).description.contains("publish_moment"))
        assertNull(CharacterPerceptionContext.personalOutcome("model-generated", denied.content))
    }

    @Test fun verifiedXhsReadingUsesActualLoadedBodyAndCommentsNotTitleAlone() {
        val now = Instant.parse("2026-10-25T04:10:00Z")
        val note = org.json.JSONObject().put("title", "新月飨宴实际笔记")
            .put("url", "https://www.xiaohongshu.com/explore/real-note")
            .put("bodyActuallyLoaded", true)
            .put("text", "真实正文".repeat(200))
            .put("commentCountActuallyLoaded", 1)
            .put("commentsActuallyLoaded", org.json.JSONArray().put("真正加载的玩家评论"))
            .put("imageReadingStatus", "未核实图片像素")
        val observed = SharedTimelineEvent("xhs-observed", "role", "小红书阅读", "执行器",
            note.toString(), now, source = "xiaohongshu-mcp",
            evidenceKind = EventEvidenceKind.Observation)
        assertTrue(CharacterPerceptionContext.stimulus(observed).description.contains("真正加载的玩家评论"))
        assertTrue(CharacterPerceptionContext.render(listOf(observed)).contains("真实正文"))
        val titleOnly = observed.copy(content = note.put("bodyActuallyLoaded", false).toString())
        assertFalse(CharacterPerceptionContext.stimulus(titleOnly).description.contains("实际正文="))
    }
    @Test fun observedCallEndIsAnEventNotAnInferenceAboutUserEmotion() {
        val now = Instant.parse("2026-10-11T00:00:00Z")
        val call = SharedTimelineEvent(
            "interaction-call-end-123", "role", "电话互动", "通话操作",
            InteractionSignalBridge.callEndDescription(
                endedByCharacter = false, elapsedSeconds = 180,
                confirmedUserSpeechCount = 0, secondsSinceConfirmedSpeech = 180,
            ),
            now.minusSeconds(20), source = InteractionSignalBridge.SOURCE,
            evidenceKind = EventEvidenceKind.Observation,
        )
        val assertedEmotion = call.copy(
            id = "model-made-story", content = "你肯定生气了",
            evidenceKind = EventEvidenceKind.CharacterStatement,
        )
        val actual = CharacterPerceptionContext.selectRecent(listOf(call, assertedEmotion), now)
        assertEquals(listOf(call.id), actual.map { it.id })
        assertEquals(setOf("user"), CharacterPerceptionContext.stimulus(call).socialIds)
        assertTrue(actual.single().content.contains("没有成功收到用户的语音转写"))
        assertTrue(actual.single().content.contains("不能由挂断单独推断"))
    }

    @Test fun connectedVideoReceiptsPersistWithoutClaimingAccessToTheUsersCamera() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        UserProfileContext.initialize(app)
        SharedExperienceTimeline.initialize(app)
        val role = "video-evidence-" + java.util.UUID.randomUUID()
        val call = "connected-" + java.util.UUID.randomUUID()
        val chosenId = "interaction-call-video-selected-$call"
        val renderedId = "interaction-call-video-rendered-$call"
        try {
            InteractionSignalBridge.recordVideoSelected(role, call)
            val onlySelected = UserProfileContext.live2dCallContext(role)
            assertFalse(onlySelected.contains("那次角色自己的 Live2D 形象确实成功显示"))
            assertNotNull(SharedExperienceTimeline.eventsByIds(role, listOf(chosenId)).singleOrNull())
            InteractionSignalBridge.recordVideoRendered(role, call)
            InteractionSignalBridge.recordVideoRendered(role, call)
            val verified = SharedExperienceTimeline.eventsByIds(role, listOf(renderedId))
            assertEquals(1, verified.size)
            assertEquals(EventEvidenceKind.Observation, verified.single().evidenceKind)
            val prompt = UserProfileContext.live2dCallContext(role)
            assertTrue(prompt.contains("角色自己的 Live2D 形象确实成功显示"))
            assertTrue(prompt.contains("并不意味着角色可以看见用户本人"))
            assertFalse(prompt.contains("我们已经看到了用户真人"))
        } finally {
            SharedExperienceTimeline.deleteEvent(chosenId)
            SharedExperienceTimeline.deleteEvent(renderedId)
        }
    }

    @Test fun videoToVoiceKeepsOneCallAndOneMemoryTimelineWithAccurateEnding() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(app)
        val role = "call-media-role-" + java.util.UUID.randomUUID()
        val call = "call-media-id-" + java.util.UUID.randomUUID()
        val start = "interaction-call-start-$call"
        val selected = "interaction-call-video-selected-$call"
        val rendered = "interaction-call-video-rendered-$call"
        val end = "interaction-call-end-$call"
        val now = Instant.now()
        try {
            InteractionSignalBridge.recordCallStart(role, call, "", now)
            InteractionSignalBridge.recordVideoSelected(role, call, now.plusSeconds(1))
            assertTrue(InteractionSignalBridge.hasSelectedVideo(role, call))
            assertFalse(InteractionSignalBridge.hasRenderedVideo(role, call))
            assertFalse(InteractionSignalBridge.callMediaSummary(role, call, false)
                .contains("实际使用过 Live2D 视频画面"))
            InteractionSignalBridge.recordVideoRendered(role, call, now.plusSeconds(2))
            InteractionSignalBridge.recordCallMediaSwitch(role, call, true, false, now.plusSeconds(3))
            val finishId = InteractionSignalBridge.recordCallEnd(
                role, call, false, 80, 2, 12, now.plusSeconds(80), endsInVideo = false)
            assertEquals(end, finishId)
            val events = SharedExperienceTimeline.all(role)
            val mode = events.single { it.id.startsWith("interaction-call-media-switch-$call-") }
            assertEquals(call, mode.sessionId)
            assertTrue(mode.content.contains("切换为语音模式"))
            assertTrue(InteractionSignalBridge.hasRenderedVideo(role, call))
            assertTrue(SharedExperienceTimeline.eventsByIds(role, listOf(end)).single()
                .content.contains("仍是同一通电话"))
            assertTrue(InteractionSignalBridge.callMediaSummary(role, call, false)
                .contains("结束时已回到语音模式"))
        } finally {
            SharedExperienceTimeline.all(role).forEach {
                SharedExperienceTimeline.deleteEvent(it.id)
            }
        }
    }

    @Test fun witnessedTouchAndVisitSurviveAlongsideNewChatButStaleAndImaginedEventsDoNot() {
        val now = Instant.parse("2026-10-10T06:00:00Z")
        fun event(id: String, age: Long, kind: EventEvidenceKind) = SharedTimelineEvent(
            id, "role", "数字世界·家里", "场景", "共同在场", now.minusSeconds(age),
            source = "digital-world", evidenceKind = kind)
        val events = listOf(event("old", 90_000, EventEvidenceKind.Observation),
            event("claimed-by-model", 2, EventEvidenceKind.CharacterStatement),
            event("world-visit-1", 10, EventEvidenceKind.Observation),
            event("world-touch-1", 1, EventEvidenceKind.Observation))
        val actual = CharacterPerceptionContext.selectRecent(events, now)
        assertEquals(listOf("world-visit-1", "world-touch-1"), actual.map { it.id })
        val combined = PerceptionStimulusResolver.combine(listOf(
            PerceptionStimulus("chat-1", "用户说话", setOf("user"))) +
            actual.map(CharacterPerceptionContext::stimulus))!!
        assertTrue(combined.evidenceId.contains(":world-touch-1:"))
        assertTrue(combined.evidenceId.contains(":chat-1:"))
        assertEquals(setOf("user"), combined.socialIds)
    }

    @Test fun offlineBacklogSurvivesOlderThanOneDayAndMoreThan160NewerReceipts() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(app)
        PerceptionStimulusLedger.initialize(app)
        val role = "offline-backlog-" + java.util.UUID.randomUUID()
        val otherRole = "unrelated-" + java.util.UUID.randomUUID()
        val now = Instant.now()
        val oldest = "$role-old-unhandled"
        try {
            SharedExperienceTimeline.record(oldest, role, "数字世界事件", "世界",
                "三天前留下、尚未理解的事实", now.minusSeconds(3 * 86_400L),
                triggerExtraction = false, source = "digital-world",
                evidenceKind = EventEvidenceKind.Observation)
            repeat(205) { index ->
                val id = "$role-burst-$index"
                SharedExperienceTimeline.record(id, role, "数字世界事件", "世界",
                    "已有确认回执 $index", now.minusSeconds((205 - index).toLong()),
                    triggerExtraction = false, source = "digital-world",
                    evidenceKind = EventEvidenceKind.Observation)
                assertTrue(PerceptionStimulusLedger.claim(app, role,
                    PerceptionStimulus(id, "先前已理解")))
            }
            SharedExperienceTimeline.record("$role-future", role, "数字世界事件", "世界",
                "明天的事实尚未发生", now.plusSeconds(86_400),
                triggerExtraction = false, source = "digital-world",
                evidenceKind = EventEvidenceKind.Observation)
            SharedExperienceTimeline.record("$role-claim", role, "私聊", "角色",
                "模型自称经历了某事", now.minusSeconds(50),
                triggerExtraction = false, source = "message",
                evidenceKind = EventEvidenceKind.CharacterStatement)
            SharedExperienceTimeline.record("$otherRole-private", otherRole, "数字世界事件", "世界",
                "别人专属的观察", now.minusSeconds(86_400L),
                triggerExtraction = false, source = "digital-world",
                evidenceKind = EventEvidenceKind.Observation)

            val first = CharacterPerceptionContext.pending(app, role, now)
            assertEquals(listOf(oldest), first.map { it.id })
            // A cancelled decision has not acknowledged the input.
            assertEquals(listOf(oldest), CharacterPerceptionContext.pending(app, role, now).map { it.id })
            val perceived = CharacterPerceptionContext.integrate(
                app, role, observed = first, deferAcknowledgement = true)
            CharacterPerceptionContext.acknowledge(app, role, perceived)
            assertTrue(CharacterPerceptionContext.pending(app, role, now).isEmpty())
            assertEquals(listOf("$otherRole-private"),
                CharacterPerceptionContext.pending(app, otherRole, now).map { it.id })
        } finally {
            SharedExperienceTimeline.all(role).forEach { SharedExperienceTimeline.deleteEvent(it.id) }
            SharedExperienceTimeline.all(otherRole).forEach { SharedExperienceTimeline.deleteEvent(it.id) }
            PerceptionStimulusLedger.clear(role)
            PerceptionStimulusLedger.clear(otherRole)
        }
    }

    @Test fun editedOldEvidenceIsReconsideredByExactRevisionNotByItsOriginalDate() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(app)
        PerceptionStimulusLedger.initialize(app)
        val role = "edited-old-event-" + java.util.UUID.randomUUID()
        val id = "$role-event"
        val occurredAt = Instant.now().minusSeconds(5 * 86_400L)
        val now = Instant.now()
        try {
            SharedExperienceTimeline.record(id, role, "数字世界事件", "世界",
                "最初的事实", occurredAt, triggerExtraction = false,
                source = "digital-world", evidenceKind = EventEvidenceKind.Observation)
            assertTrue(PerceptionStimulusLedger.claim(app, role, PerceptionStimulus(id, "最初")))
            SharedExperienceTimeline.record(id, role, "数字世界事件", "世界",
                "修订后的事实", occurredAt, triggerExtraction = false,
                source = "digital-world", evidenceKind = EventEvidenceKind.Observation,
                expectedRevision = 1L)
            val revised = CharacterPerceptionContext.pending(app, role, now)
            assertEquals(listOf(id), revised.map { it.id })
            assertEquals(2L, revised.single().revision)
            assertTrue(revised.single().content.contains("修订"))
            // Editing canonically invalidates the previous revision's checkpoint;
            // the new revision is offered again without retaining stale claims.
            assertFalse(PerceptionStimulusLedger.hasSeen(app, role, id, 1L))
            assertFalse(PerceptionStimulusLedger.hasSeen(app, role, id, 2L))
        } finally {
            SharedExperienceTimeline.deleteEvent(id)
            PerceptionStimulusLedger.clear(role)
        }
    }

    @Test fun burstOfResultsDoesNotHideOlderUnacknowledgedEvents() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(app)
        PerceptionStimulusLedger.initialize(app)
        val role = "receipt-burst-" + java.util.UUID.randomUUID()
        val now = Instant.now()
        repeat(20) { index ->
            SharedExperienceTimeline.record(
                eventId = "$role-$index", characterId = role,
                channel = "真实数字世界", speaker = "世界",
                content = "真实观察 $index",
                occurredAt = now.minusSeconds((20 - index).toLong()),
                triggerExtraction = false,
                source = "digital-world", evidenceKind = EventEvidenceKind.Observation)
        }
        val first = CharacterPerceptionContext.pending(app, role, now)
        assertEquals(12, first.size)
        assertEquals("$role-0", first.first().id)
        first.forEach { event ->
            assertTrue(PerceptionStimulusLedger.claim(app, role,
                CharacterPerceptionContext.stimulus(event)))
        }
        val remainder = CharacterPerceptionContext.pending(app, role, now)
        assertEquals(8, remainder.size)
        assertEquals("$role-12", remainder.first().id)
        assertEquals("$role-19", remainder.last().id)
        PerceptionStimulusLedger.clear(role)
    }

    @Test fun newestRevisionWinsWhenOneEventComesFromTwoSceneAdapters() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val role = "same-event-two-adapters-" + java.util.UUID.randomUUID()
        PerceptionStimulusLedger.initialize(app)
        val event = SharedTimelineEvent("shared-evidence", role, "阅读回执", "执行器",
            "已修改的真实结果", Instant.parse("2026-10-25T08:00:00Z"), revision = 4L)
        val staleDirect = PerceptionStimulus(event.id, "修改前的旧内容", revision = 1L)
        val input = CharacterPerceptionContext.integrate(app, role,
            observed = listOf(event), direct = listOf(staleDirect),
            claimDirect = true, deferAcknowledgement = true)
        assertEquals(1, input.freshStimuli.size)
        assertEquals(4L, input.freshStimuli.single().revision)
        assertTrue(input.combined!!.description.contains("已修改的真实结果"))
        CharacterPerceptionContext.acknowledge(app, role, input,
            direct = listOf(staleDirect), claimDirect = true)
        assertTrue(PerceptionStimulusLedger.hasSeen(app, role, event.id, 4L))
        assertFalse(PerceptionStimulusLedger.hasSeen(app, role, event.id, 1L))
        PerceptionStimulusLedger.clear(role)
    }

    @Test fun cancelledPerceptionCanRetryUntilItsCausalCommitAcknowledgesExactEvidence() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val role = "perception-deferred-" + java.util.UUID.randomUUID()
        PerceptionStimulusLedger.initialize(app)
        val input = PerceptionStimulus("real-event", "新到的实际阅读结果", revision = 3L)
        val preview = CharacterPerceptionContext.integrate(
            app, role, observed = emptyList(), direct = listOf(input),
            claimDirect = true, deferAcknowledgement = true,
        )
        assertEquals(listOf(input), preview.freshStimuli)
        assertFalse(PerceptionStimulusLedger.hasSeen(app, role, input.evidenceId, input.revision))
        // Simulated model cancellation: no acknowledgement means the very same
        // raw evidence is still eligible at the next real processing attempt.
        val recovered = CharacterPerceptionContext.integrate(
            app, role, observed = emptyList(), direct = listOf(input),
            claimDirect = true, deferAcknowledgement = true,
        )
        assertEquals(listOf(input), recovered.freshStimuli)
        CharacterPerceptionContext.acknowledge(app, role, recovered,
            direct = listOf(input), claimDirect = true)
        assertTrue(PerceptionStimulusLedger.hasSeen(app, role, input.evidenceId, 3L))
        assertTrue(CharacterPerceptionContext.integrate(app, role, emptyList(),
            direct = listOf(input), claimDirect = true,
            deferAcknowledgement = true).freshStimuli.isEmpty())
        PerceptionStimulusLedger.clear(role)
    }

    @Test fun foregroundDirectSpeechRemainsUnclaimedWhileRealObservationsAreAcknowledged() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val role = "foreground-deferred-" + java.util.UUID.randomUUID()
        PerceptionStimulusLedger.initialize(app)
        val words = PerceptionStimulus("direct-speech", "主人刚说的话", setOf("user"))
        val reality = SharedTimelineEvent("observed-work", role, "工具结果", "执行器",
            "真实返回的数据", Instant.parse("2026-10-25T01:00:00Z"), revision = 2L)
        val preview = CharacterPerceptionContext.integrate(
            app, role, observed = listOf(reality), direct = listOf(words),
            claimDirect = false, deferAcknowledgement = true)
        CharacterPerceptionContext.acknowledge(app, role, preview,
            direct = listOf(words), claimDirect = false)
        assertFalse(PerceptionStimulusLedger.hasSeen(app, role, words.evidenceId))
        assertTrue(PerceptionStimulusLedger.hasSeen(app, role, reality.id, 2L))
        PerceptionStimulusLedger.clear(role)
    }
    @Test fun sharedTurnCommitDefersAcknowledgementUntilExtraStateSucceeds() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        PerceptionStimulusLedger.initialize(app)
        CharacterInnerLifeStore.initialize(app)
        val role = "shared-turn-" + java.util.UUID.randomUUID()
        val actual = PerceptionStimulus("observed-" + role, "真实观察", revision = 2L)
        val preview = CharacterPerceptionContext.integrate(
            app, role, emptyList(), listOf(actual), claimDirect = true,
            deferAcknowledgement = true)
        val now = Instant.parse("2026-10-26T08:00:00Z")
        fun commit(next: () -> Unit = {}) = CharacterTurnStateCommit.commit(
            context = app, characterId = role, perception = preview,
            direct = listOf(actual), claimDirect = true,
            evidenceId = actual.evidenceId,
            appraisal = org.json.JSONObject().put("meaning", "确实注意到了这件事"),
            innerLife = null, innerThoughtBasis = null,
            selectedAction = "message", innerThought = "", reason = "想说一句话",
            now = now, afterCausalRecord = next,
        )
        try {
            commit { error("模拟保存中断") }
            fail("Expected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("模拟保存中断", expected.message)
        }
        assertFalse(PerceptionStimulusLedger.hasSeen(app, role, actual.evidenceId, 2L))
        commit()
        assertTrue(PerceptionStimulusLedger.hasSeen(app, role, actual.evidenceId, 2L))
        assertEquals(1, CharacterInnerLifeStore.snapshot(role)
            .optJSONArray("causalTransitions")?.length())
        PerceptionStimulusLedger.clear(role)
        CharacterInnerLifeStore.clear(role)
    }

    @Test fun sharedTurnCommitKeepsCallDirectSpeechSeparateFromObservedFacts() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        PerceptionStimulusLedger.initialize(app)
        CharacterInnerLifeStore.initialize(app)
        val role = "call-turn-" + java.util.UUID.randomUUID()
        val direct = PerceptionStimulus("call-user-" + role, "对方真实说的话")
        val observed = PerceptionStimulus("call-observed-" + role, "工具刚返回的结果", revision = 3L)
        val preview = CharacterPerceptionContext.integrate(
            app, role, emptyList(), listOf(direct, observed),
            claimDirect = false, deferAcknowledgement = true)
        CharacterTurnStateCommit.commit(
            context = app, characterId = role, perception = preview,
            direct = listOf(direct), claimDirect = false,
            evidenceId = direct.evidenceId,
            appraisal = org.json.JSONObject().put("meaning", "收到新的说话内容"),
            innerLife = null, innerThoughtBasis = null,
            selectedAction = "reply", innerThought = "", reason = "接话",
            now = Instant.parse("2026-10-26T08:00:00Z"),
        )
        assertFalse(PerceptionStimulusLedger.hasSeen(app, role, direct.evidenceId))
        assertTrue(PerceptionStimulusLedger.hasSeen(app, role, observed.evidenceId, 3L))
        PerceptionStimulusLedger.clear(role)
        CharacterInnerLifeStore.clear(role)
    }

}
