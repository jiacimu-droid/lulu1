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
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterGrowthTrajectoryLedgerTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun sameTurnChatSelfCorrectionAppearsAsGrowthWithoutPostReplyModelCall() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        val role = "growth-trail-in-turn-role"
        val source = "in-turn-user-source"
        val now = Instant.now()
        SharedExperienceTimeline.record(source, role, "私聊", "用户",
            "你这样让我没有安全感。", now, triggerExtraction = false,
            sessionId = "the-conversation", source = "message",
            evidenceKind = EventEvidenceKind.UserStatement)
        val change = org.json.JSONObject().put("selfCorrection", org.json.JSONObject()
            .put("realization", "我把帮她判断变成了替她决定")
            .put("nextTime", "有分歧时也要先理解她自己的决定"))
        CharacterInnerLifeStore.recordCausalTransition(
            characterId = role, evidenceId = source,
            appraisal = org.json.JSONObject().put("meaning", "她感到被我轻视"),
            innerLife = change, innerThoughtBasis = null,
            selectedAction = "reply", innerThought = "",
            reason = "想认真处理这次分歧", now = now.plusSeconds(1))
        val entries = CharacterGrowthTrajectory.entries(role, now.plusSeconds(3))
        assertTrue(entries.any { it.evidenceId == source &&
            it.label == "当次决定调整做法" && it.detail.contains("替她决定") })
        // There was a meaningful self-correction, not an automatically
        // executed apology, diary, or long-term personality mutation.
        assertFalse(entries.any { it.category == "成长" || it.label == "确实写下日记" })
        SharedExperienceTimeline.deleteEvent(source)
        assertFalse(CharacterGrowthTrajectory.entries(role, now.plusSeconds(5))
            .any { it.evidenceId == source })
    }

    @Test fun oneReplyCanCreateAConcernAndLaterActionReceiptsAdvanceThatSameThread() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        val role = "in-turn-concern-and-action-role"
        val evidenceId = "evidence-trust-discussion"
        val now = Instant.now()
        SharedExperienceTimeline.record(evidenceId, role, "私聊", "用户",
            "我不喜欢你轻视我自己的决定。", now, triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        val appraisal = org.json.JSONObject()
            .put("meaning", "她需要我尊重她自己作决定的能力")
            .put("tension", "我想保护她，也不想替她做主")
            .put("pendingConcern", org.json.JSONObject()
                .put("status", "open")
                .put("focus", "怎么让她在分歧时也感到被尊重")
                .put("whyItMatters", "她明确表示我有时弱化她")
                .put("possibleNextStep", "有分歧时先听她的选择，不急于替她判断"))
        CharacterInnerLifeStore.recordCausalTransition(
            characterId = role, evidenceId = evidenceId, appraisal = appraisal,
            innerLife = org.json.JSONObject().put("thoughts", org.json.JSONArray().put(
                org.json.JSONObject().put("thought", "要不要调整说话时替她做决定的习惯"))),
            innerThoughtBasis = null, selectedAction = "reply", innerThought = "",
            reason = "想回应但要保留自己的判断", now = now.plusSeconds(1))
        val active = CharacterInnerLifeStore.snapshot(role).getJSONArray("openConcerns")
        assertEquals(1, active.length())
        val thread = active.getJSONObject(0).getString("id")
        val before = CharacterGrowthTrajectory.entries(role, now.plusSeconds(2))
        assertTrue(before.any { it.category == "心事" && it.detail.contains("尊重") })
        assertTrue(before.any { it.category == "反思" && it.detail.contains("权衡") })
        assertFalse(before.any { it.category == "成长" })
        // This is a real executor receipt, not a second psychological API call.
        CharacterInnerLifeStore.recordConcernOutcome(
            role, thread, "delivered-action-receipt", "message",
            success = true, outcome = "确实发出了经过斟酌的回复",
            now = now.plusSeconds(5))
        val after = CharacterInnerLifeStore.snapshot(role).getJSONArray("openConcerns")
        assertEquals(thread, after.getJSONObject(0).getString("id"))
        assertTrue(after.getJSONObject(0).getJSONObject("lastOutcome").getBoolean("success"))
        // One successful message alone is not proof the relationship has healed.
        assertEquals(1, after.length())
    }

    @Test fun emotionOnlyAfterSignificantFeedbackIsVisibleWithoutPretendingToBeAHabit() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        val role = "real-emotion-in-reply-role"
        val evidenceId = "message-safety-complaint"
        val now = Instant.now()
        SharedExperienceTimeline.record(evidenceId, role, "私聊", "用户",
            "你让我没有安全感，我不想这样继续。", now, triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        CharacterInnerLifeStore.recordCausalTransition(
            characterId = role, evidenceId = evidenceId, appraisal = null,
            innerLife = org.json.JSONObject().put("emotion", org.json.JSONObject()
                .put("feeling", "有些难过，也意识到她是真的不安")
                .put("cause", "她亲口说自己没有安全感")),
            innerThoughtBasis = null, selectedAction = "reply", innerThought = "",
            reason = "还没想好怎么继续谈", now = now.plusSeconds(1))
        val trace = CharacterGrowthTrajectory.entries(role, now.plusSeconds(3))
        assertTrue(trace.any { it.evidenceId == evidenceId && it.category == "反思" &&
            it.detail.contains("难过") })
        assertFalse(trace.any { it.category == "成长" })
    }

    @Test fun routineConversationalAppraisalIsNotFalselyAdvertisedAsGrowth() {
        val ordinary = org.json.JSONObject().put("selectedAction", "reply")
            .put("appraisal", org.json.JSONObject().put("meaning", "今天只是问候"))
        assertFalse(CharacterGrowthTrajectory.isSignificantInnerChange(ordinary))
        val changed = org.json.JSONObject().put("selectedAction", "reply")
            .put("stateDelta", org.json.JSONObject().put("motiveChanges", 1))
        assertTrue(CharacterGrowthTrajectory.isSignificantInnerChange(changed))
    }

    @Test fun explicitLongTermPreferenceDoesNotExpireAfterSixMonthsAndIsRevisable() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        val role = "old-but-live-relationship-preference"
        val past = Instant.parse("2025-11-01T12:00:00Z")
        SharedExperienceTimeline.record("old-style-feedback", role, "私聊", "用户",
            "你说话太官方了，请自然一点。", past, triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        assertTrue(CharacterGrowthTrajectory.context(role, past.plusSeconds(220L * 86400))
            .contains("说话太官方"))
        SharedExperienceTimeline.record("new-style-feedback", role, "私聊", "用户",
            "我喜欢这样聊天，以后也可以这样说话。", past.plusSeconds(221L * 86400),
            triggerExtraction = false, source = "message",
            evidenceKind = EventEvidenceKind.UserStatement)
        val current = CharacterGrowthTrajectory.context(role, past.plusSeconds(222L * 86400))
        assertTrue(current.contains("我喜欢这样聊天"))
        assertFalse(current.contains("说话太官方"))
        SharedExperienceTimeline.deleteEvent("new-style-feedback")
        assertTrue(CharacterGrowthTrajectory.context(role, past.plusSeconds(222L * 86400))
            .contains("说话太官方"))
        SharedExperienceTimeline.deleteEvent("old-style-feedback")
    }

    @Test fun importantFeedbackIsVisibleBeforeAnyApologyAndRetractionHonorsDeletion() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        val role = "growth-trail-private-role"
        val evidence = "growth-trail-original-feedback"
        val now = Instant.now()
        SharedExperienceTimeline.record(evidence, role, "私聊", "用户",
            "你总是把我弱化，这让我很没有安全感。", now,
            triggerExtraction = false, sessionId = "our-chat", source = "message",
            evidenceKind = EventEvidenceKind.UserStatement)
        val trace = CharacterGrowthTrajectory.entries(role, now.plusSeconds(2))
        assertTrue(trace.any { it.evidenceId == evidence && it.category == "反馈" })
        assertFalse(trace.any { it.category == "成长" })
        assertFalse(trace.any { it.category == "反思" })
        assertTrue(CharacterGrowthTrajectory.context(role, now.plusSeconds(2)).contains("弱化"))

        SharedExperienceTimeline.deleteEvent(evidence)
        assertFalse(CharacterGrowthTrajectory.entries(role, now.plusSeconds(5))
            .any { it.evidenceId == evidence })
        assertFalse(CharacterGrowthTrajectory.context(role, now.plusSeconds(5)).contains("弱化"))
    }
}
