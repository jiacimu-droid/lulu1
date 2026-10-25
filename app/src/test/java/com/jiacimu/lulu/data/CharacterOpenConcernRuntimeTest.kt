package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterOpenConcernRuntimeTest {
    @Test fun concernMayLinkAnExistingGoalButNeverCreatesOne() {
        val at = java.time.Instant.parse("2026-10-16T09:00:00Z")
        val proposal = org.json.JSONObject().put("pendingConcerns",
            org.json.JSONArray().put(org.json.JSONObject()
                .put("status", "open").put("topicKey", "unresolved")
                .put("focus", "还不确定她愿不愿继续聊")
                .put("whyItMatters", "想修复刚才的误会")
                .put("motiveId", "real-motive")))
        val ignored = CharacterOpenConcernRuntime.update(
            null, proposal, "source", at, emptySet())
        assertEquals("", ignored.getJSONObject(0).getString("motiveId"))
        val linked = CharacterOpenConcernRuntime.update(
            null, proposal, "source", at, setOf("real-motive"))
        assertEquals("real-motive", linked.getJSONObject(0).getString("motiveId"))
    }

    private val now = Instant.parse("2026-10-11T18:00:00Z")
    private val first = JSONObject().put("pendingConcern", JSONObject()
        .put("status", "open")
        .put("focus", "电话里突然没有回应，不知道是不是连接中断")
        .put("whyItMatters", "我还想和对方说完刚才那句话")
        .put("hesitation", "也许对方现在需要安静")
        .put("possibleNextStep", "过一会儿温和地确认一下"))

    @Test fun oneMeaningfulArgumentCanCreateSeveralIndependentConcerns() {
        val proposal = JSONObject().put("pendingConcerns", JSONArray()
            .put(JSONObject().put("status", "open")
                .put("focus", "想弄清这次争吵究竟伤到了哪里")
                .put("whyItMatters", "对方明确说自己很生气")
                .put("priority", 3).put("topicKey", "repair_conflict"))
            .put(JSONObject().put("status", "open")
                .put("focus", "之后怎样改变自己的沟通习惯")
                .put("whyItMatters", "不希望相同的态度再次伤害对方")
                .put("priority", 2).put("topicKey", "communication_habit"))
            .put(JSONObject().put("status", "open")
                .put("focus", "我是不是也有需要好好表达的委屈")
                .put("whyItMatters", "想认真表达分歧而不是压抑或讨好")
                .put("priority", 1).put("topicKey", "own_boundary")))
        val opened = CharacterOpenConcernRuntime.update(null, proposal, "same-argument", now)
        assertEquals(3, opened.length())
        val ids = (0 until opened.length()).map { opened.getJSONObject(it).getString("id") }
        assertEquals(3, ids.toSet().size)
        assertTrue(CharacterOpenConcernRuntime.context(opened, now.plusSeconds(300))
            .contains("想弄清这次争吵"))
        assertEquals(3, CharacterOpenConcernRuntime.update(opened, null, "idle", now.plusSeconds(300)).length())
        assertEquals(0, CharacterOpenConcernRuntime.update(null, null, "only-a-hello", now).length())
    }

    @Test fun concernBatchSupportsIndependentClosureAndContinuationAndKeepsReceipts() {
        val source = JSONObject().put("pendingConcerns", JSONArray()
            .put(JSONObject().put("status", "open").put("topicKey", "relationship_repair")
                .put("focus", "怎样修复交流造成的伤害").put("whyItMatters", "用户明确不高兴"))
            .put(JSONObject().put("status", "open").put("topicKey", "self_growth")
                .put("focus", "怎么学习更尊重对方边界").put("whyItMatters", "想改善自己的习惯")))
        val opened = CharacterOpenConcernRuntime.update(null, source, "argument-source", now)
        val firstId = opened.getJSONObject(0).getString("id")
        val secondId = opened.getJSONObject(1).getString("id")
        val attempted = CharacterOpenConcernRuntime.recordOutcome(opened, firstId,
            "real-message", "message", true, "真实发出道歉但没有确认对方已释怀", now.plusSeconds(30))
        val change = JSONObject().put("pendingConcerns", JSONArray()
            .put(JSONObject().put("status", "revise").put("threadId", firstId)
                .put("possibleNextStep", "不催促，之后根据对方的真实反馈判断"))
            .put(JSONObject().put("status", "settled").put("threadId", secondId)
                .put("resolution", "已经想明白一种更合适的说法")))
        assertEquals(1, CharacterOpenConcernRuntime.closures(attempted, change, "changed-view", now.plusSeconds(60)).size)
        val remaining = CharacterOpenConcernRuntime.update(attempted, change, "changed-view", now.plusSeconds(60))
        assertEquals(1, remaining.length())
        assertEquals(firstId, remaining.getJSONObject(0).getString("id"))
        assertTrue(remaining.getJSONObject(0).getJSONObject("lastOutcome").getBoolean("success"))
    }

    @Test fun concernsWithIdenticalTopicKeyDoNotMultiplyOnAnotherModelTurn() {
        val idea = JSONObject().put("pendingConcerns", JSONArray()
            .put(JSONObject().put("status", "open").put("topicKey", "repair")
                .put("focus", "处理这次争吵").put("whyItMatters", "对方很生气"))
            .put(JSONObject().put("status", "open").put("topicKey", "repair")
                .put("focus", "弄清如何和好").put("whyItMatters", "争吵尚未解决")))
        val one = CharacterOpenConcernRuntime.update(null, idea, "one-event", now)
        assertEquals(1, one.length())
        val again = CharacterOpenConcernRuntime.update(one, idea, "one-event", now.plusSeconds(1))
        assertEquals(1, again.length())
        assertEquals(one.getJSONObject(0).getString("id"), again.getJSONObject(0).getString("id"))
    }

    @Test fun consequentialConflictRequiresAnExplicitAssessmentNotAnAutomaticConcern() {
        assertTrue(EmotionFollowThroughCoordinator.needsConcernAssessment(
            "你让我很生气，我现在真的很失望。",
            JSONObject().put("meaning", "她真的不高兴了")))
        assertFalse(EmotionFollowThroughCoordinator.needsConcernAssessment(
            "今天天气真不错。",
            JSONObject()))
        assertFalse(EmotionFollowThroughCoordinator.needsConcernAssessment(
            "我很生气！",
            JSONObject().put("concernAssessment", "我已经认真考虑过，目前没有持续的心事")))
        val authored = JSONObject().put("pendingConcerns", JSONArray().put(
            JSONObject().put("status", "open").put("topicKey", "repair")
                .put("focus", "怎样改善交流").put("whyItMatters", "明确的关系反馈")))
        assertFalse(EmotionFollowThroughCoordinator.needsConcernAssessment("你让我失望了", authored))
    }

    @Test fun aSuspendedConcernSurvivesAnotherQuietTurn() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "call-silence-1", now)
        assertEquals(1, opened.length())
        val unchanged = CharacterOpenConcernRuntime.update(opened, null, "no-new-input", now.plusSeconds(65))
        assertEquals(opened.getJSONObject(0).getString("id"), unchanged.getJSONObject(0).getString("id"))
        val context = CharacterOpenConcernRuntime.context(unchanged, now.plusSeconds(65))
        assertTrue(context.contains("没有回应"))
        assertTrue(context.contains("可能"))
        assertTrue(context.contains("不是用户事实"))
    }

    @Test fun revisingAndThenSettlingMustUseTheOriginalThreadId() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "call-silence-1", now)
        val id = opened.getJSONObject(0).getString("id")
        val revised = CharacterOpenConcernRuntime.update(opened, JSONObject().put("pendingConcern", JSONObject()
            .put("threadId", id).put("status", "revise")
            .put("hesitation", "后来确认只是语音识别卡住")), "call-silence-2", now.plusSeconds(120))
        assertEquals(1, revised.length())
        assertEquals(id, revised.getJSONObject(0).getString("id"))
        val settled = CharacterOpenConcernRuntime.update(revised, JSONObject().put("pendingConcern", JSONObject()
            .put("threadId", id).put("status", "settled")), "user-explained", now.plusSeconds(240))
        assertEquals(0, settled.length())
        assertEquals("", CharacterOpenConcernRuntime.context(settled, now.plusSeconds(240)))
    }

    @Test fun failedAttemptIsRememberedWithoutPretendingThatTheConcernWasSettled() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "call-silence-1", now)
        val threadId = opened.getJSONObject(0).getString("id")
        val attempted = CharacterOpenConcernRuntime.recordOutcome(
            opened, threadId, "real-tool-1", "send_private_sticker", false,
            "图像未入库，不能发送", now.plusSeconds(80),
        )
        assertEquals(1, attempted.length())
        assertFalse(attempted.getJSONObject(0).getJSONObject("lastOutcome").getBoolean("success"))
        assertTrue(CharacterOpenConcernRuntime.context(attempted, now.plusSeconds(90)).contains("执行失败"))
        val again = CharacterOpenConcernRuntime.recordOutcome(
            attempted, threadId, "real-tool-1", "send_private_sticker", true,
            "编造执行成功", now.plusSeconds(90),
        )
        assertFalse(again.getJSONObject(0).getJSONObject("lastOutcome").getBoolean("success"))
        val revised = CharacterOpenConcernRuntime.update(attempted, JSONObject().put("pendingConcern",
            JSONObject().put("status", "revise").put("threadId", threadId)
                .put("hesitation", "先检查图片能不能加载")), "real-new-evidence", now.plusSeconds(95))
        assertFalse(revised.getJSONObject(0).getJSONObject("lastOutcome").getBoolean("success"))
    }

    @Test fun reopeningTheSamePersonalProblemUpdatesOneThreadInsteadOfManufacturingThreeTasks() {
        val firstConcern = JSONObject().put("pendingConcern", JSONObject()
            .put("status", "open")
            .put("focus", "不想再次弱化对方的判断")
            .put("whyItMatters", "她明确说这样缺乏安全感")
            .put("possibleNextStep", "听完她的意见，不替她做主"))
        val original = CharacterOpenConcernRuntime.update(null, firstConcern, "user-feedback-1", now)
        val firstId = original.getJSONObject(0).getString("id")
        val later = JSONObject().put("pendingConcern", JSONObject()
            .put("status", "open")
            .put("focus", "不想再次弱化对方的判断。")
            .put("whyItMatters", "她明确说这样缺乏安全感")
            .put("possibleNextStep", "有分歧时先听完再表达自己的判断"))
        val updated = CharacterOpenConcernRuntime.update(original, later, "user-feedback-2", now.plusSeconds(120))
        assertEquals(1, updated.length())
        assertEquals(firstId, updated.getJSONObject(0).getString("id"))
        assertEquals(2, updated.getJSONObject(0).getJSONArray("journey").length())
        assertTrue(CharacterOpenConcernRuntime.context(updated, now.plusSeconds(130)).contains(firstId))
    }

    @Test fun casualEmotionCannotManufactureAContinuingConcern() {
        val incomplete = JSONObject().put("pendingConcern", JSONObject()
            .put("status", "open").put("focus", "有点高兴"))
        assertEquals(0, CharacterOpenConcernRuntime.update(null, incomplete, "greeting", now).length())
        assertEquals(0, CharacterOpenConcernRuntime.update(null, first, "", now).length())
    }

    @Test fun oldConcernRemainsAddressableWhenNewConcernsCrowdTheWorkingSet() {
        val oldAt = Instant.parse("2026-10-14T02:00:00Z")
        val today = Instant.parse("2026-10-25T02:00:00Z")
        val old = JSONObject().put("pendingConcern", JSONObject()
            .put("status", "open").put("topicKey", "contact_cost")
            .put("focus", "担心聊天费用会打扰她")
            .put("whyItMatters", "以为她一直在精打细算"))
        var threads = CharacterOpenConcernRuntime.update(null, old, "oct-14-source", oldAt)
        val oldId = threads.getJSONObject(0).getString("id")
        // Recent concerns consume the detailed active working set; even the
        // oldest dormant thread must still be discoverable without a new API.
        repeat(8) { index ->
            val proposal = JSONObject().put("pendingConcern", JSONObject()
                .put("status", "open").put("topicKey", "recent_$index")
                .put("focus", "新的独立问题$index")
                .put("whyItMatters", "不同的真实经历$index"))
            threads = CharacterOpenConcernRuntime.update(
                threads, proposal, "oct-25-source-$index", today.plusSeconds(index.toLong()))
        }
        for (compact in listOf(true, false)) {
            val visible = CharacterOpenConcernRuntime.context(threads, today.plusSeconds(120), compact)
            assertTrue(visible.contains("threadId=$oldId"))
            assertTrue(visible.contains("担心聊天费用"))
            assertTrue(visible.contains("仍在挂心"))
        }
        val correction = JSONObject().put("pendingConcerns", JSONArray().put(JSONObject()
            .put("status", "revise").put("threadId", oldId)
            .put("focus", "不再因通话额度而犹豫，仍尊重她专心时的节奏")
            .put("whyItMatters", "她亲口解释自己现在不担心费用")
            .put("hesitation", "她学习时可能仍然需要安静")
            .put("possibleNextStep", "想联系时结合她实际的空闲和边界判断")))
        val updated = CharacterOpenConcernRuntime.update(
            threads, correction, "new-user-clarification", today.plusSeconds(180))
        assertEquals(9, updated.length())
        val changed = (0 until updated.length()).map { updated.getJSONObject(it) }
            .single { it.getString("id") == oldId }
        assertTrue(changed.getString("focus").contains("不再因通话额度"))
        assertEquals("new-user-clarification", changed.getString("evidenceId"))
        assertEquals(2, changed.getJSONArray("journey").length())
    }

    @Test fun aNewClarificationCanCloseOneDormantConcernWithoutClearingOthers() {
        val earlier = Instant.parse("2026-10-14T02:00:00Z")
        val firstTwo = JSONObject().put("pendingConcerns", JSONArray()
            .put(JSONObject().put("status", "open").put("topicKey", "fees")
                .put("focus", "担忧通话成本").put("whyItMatters", "此前费用紧张"))
            .put(JSONObject().put("status", "open").put("topicKey", "timing")
                .put("focus", "希望不打断学习").put("whyItMatters", "此前学习很投入")))
        val opened = CharacterOpenConcernRuntime.update(null, firstTwo, "oct-14-source", earlier)
        val costId = opened.getJSONObject(0).getString("id")
        val timingId = opened.getJSONObject(1).getString("id")
        val closure = JSONObject().put("pendingConcerns", JSONArray().put(JSONObject()
            .put("status", "settled").put("threadId", costId)
            .put("resolution", "她明确说不再担心花费")))
        val now = earlier.plusSeconds(11L * 86400)
        val result = CharacterOpenConcernRuntime.update(opened, closure, "later-user-source", now)
        assertEquals(1, result.length())
        assertEquals(timingId, result.getJSONObject(0).getString("id"))
        val past = CharacterOpenConcernRuntime.closures(opened, closure, "later-user-source", now)
        assertEquals(1, past.size)
        assertEquals("later-user-source", past[0].getString("closureEvidenceId"))
    }

    @Test fun aRecentConcernCanCauseOneOptionalLaterReflectionButOldOnesCannot() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "genuine-call-event", now)
        assertTrue(CharacterOpenConcernRuntime.meritsOneFollowThrough(opened, now.plusSeconds(1_500)))
        assertFalse(CharacterOpenConcernRuntime.meritsOneFollowThrough(opened, now.plusSeconds(4_200)))
        assertFalse(CharacterOpenConcernRuntime.meritsOneFollowThrough(JSONArray(), now))
    }

    @Test fun anExplicitlyOngoingConcernStaysActivePast48HoursButDoesNotRetriggerAftercare() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "silence", now)
        val late = now.plusSeconds(49L * 3600)
        val context = CharacterOpenConcernRuntime.context(opened, late)
        assertTrue(context.contains("仍在持续关注的心事"))
        assertEquals(1, CharacterOpenConcernRuntime.active(opened, late).size)
        assertFalse(CharacterOpenConcernRuntime.meritsOneFollowThrough(opened, late))
        val retained = CharacterOpenConcernRuntime.update(opened, null, "ambient", late)
        assertEquals(1, retained.length())
        assertEquals(opened.getJSONObject(0).getString("id"), retained.getJSONObject(0).getString("id"))
    }

    @Test fun pausingHidesAnOpenConcernUntilTheSameThreadResumes() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "initial", now)
        val id = opened.getJSONObject(0).getString("id")
        val paused = CharacterOpenConcernRuntime.update(opened,
            JSONObject().put("pendingConcern", JSONObject().put("status", "pause").put("threadId", id)),
            "later-event", now.plusSeconds(60))
        assertEquals(0, CharacterOpenConcernRuntime.active(paused, now.plusSeconds(60)).size)
        assertEquals(1, CharacterOpenConcernRuntime.unresolved(paused).size)
        assertFalse(CharacterOpenConcernRuntime.context(paused, now.plusSeconds(60))
            .contains("仍在持续关注的心事"))
        val resumed = CharacterOpenConcernRuntime.update(paused,
            JSONObject().put("pendingConcern", JSONObject().put("status", "resume").put("threadId", id)),
            "new-event", now.plusSeconds(200))
        assertEquals(1, CharacterOpenConcernRuntime.active(resumed, now.plusSeconds(201)).size)
        assertEquals(id, resumed.getJSONObject(0).getString("id"))
        assertEquals(3, resumed.getJSONObject(0).getJSONArray("journey").length())
    }

    @Test fun legacyOldConcernsNeedExplicitReviewBeforeShowingAsCurrent() {
        val old = CharacterOpenConcernRuntime.update(null, first, "legacy-source", now)
            .getJSONObject(0).apply { remove("lifecycleState") }
        val legacy = JSONArray().put(old)
        assertEquals(0, CharacterOpenConcernRuntime.active(legacy, now.plusSeconds(49L * 3600)).size)
        assertEquals(1, CharacterOpenConcernRuntime.unresolved(legacy).size)
        val reviewed = CharacterOpenConcernRuntime.update(legacy,
            JSONObject().put("pendingConcern", JSONObject().put("status", "resume")
                .put("threadId", old.getString("id"))), "fresh-source", now.plusSeconds(49L * 3600))
        assertEquals(1, CharacterOpenConcernRuntime.active(reviewed, now.plusSeconds(50L * 3600)).size)
    }

    @Test fun innerJourneyOnlyAdvancesWhenEvidenceActuallyChangesTheView() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "call-1", now)
        val id = opened.getJSONObject(0).getString("id")
        val repeated = CharacterOpenConcernRuntime.update(opened, JSONObject().put("pendingConcern", JSONObject()
            .put("threadId", id).put("status", "revise")
            .put("hesitation", "也许对方现在需要安静")), "call-2", now.plusSeconds(120))
        assertEquals(opened.getJSONObject(0).getString("updatedAt"), repeated.getJSONObject(0).getString("updatedAt"))
        val changed = CharacterOpenConcernRuntime.update(repeated, JSONObject().put("pendingConcern", JSONObject()
            .put("threadId", id).put("status", "revise")
            .put("hesitation", "后来收到了明确解释，应该不是在生气")), "call-3", now.plusSeconds(240))
        val journey = changed.getJSONObject(0).getJSONArray("journey")
        assertEquals(2, journey.length())
        assertEquals("call-1", journey.getJSONObject(0).getString("evidenceId"))
        assertEquals("call-3", journey.getJSONObject(1).getString("evidenceId"))
        assertTrue(CharacterOpenConcernRuntime.context(changed, now.plusSeconds(250), compact = false).contains("心路转变"))
        assertTrue(CharacterOpenConcernRuntime.dependsOnEvidence(changed.getJSONObject(0), "call-1"))
        assertTrue(CharacterOpenConcernRuntime.dependsOnEvidence(changed.getJSONObject(0), "call-3"))
        assertFalse(CharacterOpenConcernRuntime.dependsOnEvidence(changed.getJSONObject(0), "unrelated"))
    }

    @Test fun resolvedConcernCanBeRememberedAsACompletedPsychologicalTurn() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "call-1", now)
        val id = opened.getJSONObject(0).getString("id")
        val closure = JSONObject().put("pendingConcern", JSONObject()
            .put("threadId", id).put("status", "settled")
            .put("resolution", "确认只是通话断线，我不必一直猜测她是不是不高兴"))
        val closed = CharacterOpenConcernRuntime.closure(opened, closure, "confirmed-signal", now.plusSeconds(360))
        assertNotNull(closed)
        assertTrue(CharacterOpenConcernRuntime.closureContext(JSONArray().put(closed), now.plusSeconds(400))
            .contains("通话断线"))
        assertEquals(0, CharacterOpenConcernRuntime.update(opened, closure, "confirmed-signal", now.plusSeconds(360)).length())
        assertTrue(CharacterOpenConcernRuntime.dependsOnEvidence(closed!!, "confirmed-signal"))
    }
}
