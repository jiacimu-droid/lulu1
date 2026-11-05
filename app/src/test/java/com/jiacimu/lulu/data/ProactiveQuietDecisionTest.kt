package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verify online agency can truthfully stay quiet without treating it as malformed JSON. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProactiveQuietDecisionTest {
    @Test fun onlineExpiryPromptKeepsDecisionAndHandoffContract() {
        // Runtime now supplies only live facts for an expiring lease; static
        // instructions must retain the single model-owned decision and its
        // executable task handoff. This is not a real-model quality test.
        val renewal = onlineRenewalDecisionInstruction()
        assertEquals(1, renewal.split("【五分钟自主续醒决定｜本轮必须回答】").size - 1)
        for (required in listOf(
            "onlineDecision", "continue|sleep", "choice 和 reason 均为必填",
            "nextChecks 写 taskId", "带时区偏移", "动作完成不等于目标达成",
            "提醒已送达不代表她已入睡", "不能因有挂心就被迫续时",
            "不能把时间到了当作所有挂心自动完成",
        )) assertTrue("Missing renewal requirement: $required", renewal.contains(required))
        assertTrue(renewal.contains("字段缺失时程序只会请你补正"))
        assertTrue(renewal.contains("由你本人选择"))
    }

    @Test fun idleAutonomousDecisionStillCanActOrStaySilentAfterRuntimeRuleTrim() {
        val prompt = proactiveDecisionInstruction("autonomy-no-new-messages")
        for (required in listOf(
            "plan_personal_activity", "send_character_message",
            "web_search", "read_xiaohongshu", "learn_topic",
            "action=tool", "silent", "真实", "innerLife.motives",
        )) assertTrue("Missing autonomous capability: $required", prompt.contains(required))
        assertTrue(prompt.contains("自己确实决定暂不行动"))
    }

    @Test fun compiledAutonomousPromptKeepsUniqueActionContractsAfterRuleConsolidation() {
        val prompt = proactiveDecisionInstruction("prompt-assembly-test-role")
        for (field in listOf(
            "web_search", "read_xiaohongshu", "image_search", "learn_topic",
            "experienceId", "innerThoughtBasis", "innerLife", "motiveRef",
            "outcomeEvidenceId", "concernId", "send_character_message",
            "plan_personal_activity", "requiresPreviousSuccess",
        )) assertTrue("Missing contract: $field", prompt.contains(field))
        val causal = CharacterDecisionProtocol.runtimePrinciples.trim()
        assertEquals(prompt.indexOf(causal), prompt.lastIndexOf(causal))
    }

    @Test fun autonomousChoiceKeepsUnansweredOwnerActivityUncertainAndOffersRealConsentTools() {
        val independent = proactiveDecisionInstruction("uncertainty-test-role")
        val privateChat = proactiveDecisionInstruction("uncertainty-test-role", directPrivateChat = true)
        assertTrue(independent.contains("未回复不自动取消尚未落实的关心与目标"))
        assertTrue(independent.contains("经过授权用现有屏幕/监督工具获得真实观察"))
        assertTrue(privateChat.contains("单纯未回复、切到别的应用或旧的忙碌记录"))
        assertTrue(privateChat.contains("不能把猜测当作必须停止关心的事实"))
        assertTrue(privateChat.contains("start_study_watch"))
        assertTrue(privateChat.contains("不能把一次拒绝推断成未来长期禁止"))
        assertTrue(privateChat.contains("必须获得新时段的主人明确许可"))
    }

    @Test fun acceptedBedtimeSupervisionIsNotReducedToUnspokenConcern() {
        val now = java.time.Instant.parse("2026-11-01T16:31:00Z")
        val promise = CommitmentTask(
            id = "bedtime-commitment",
            characterId = "role",
            goal = "每天晚上主动哄睡并确认她是否准备休息",
            status = CommitmentTaskStatus.WaitingForFeedback,
            dueAt = now.minusSeconds(61 * 60L),
            completionCondition = "睡眠状态获得真实可追溯核实",
            lastActionResult = "23:30 已展示弹窗，尚无入睡证据",
            repeat = "daily",
        )
        val cue = ActiveCommitmentAttention.render(listOf(promise), now)
        assertTrue(cue.contains("taskId=bedtime-commitment"))
        assertTrue(cue.contains("真实效果尚未证实"))
        assertTrue(cue.contains("未安排"))
        assertTrue(cue.contains("没有新回复"))
        assertTrue(cue.contains("schedule_commitment_review"))
        assertTrue(cue.contains("弹窗"))
        assertTrue("Review is not a free delay", cue.contains("等待代价"))
        assertTrue("The model should consider acting now, without a forced interval",
            cue.contains("现在做与以后再做"))
        assertTrue("A stored alarm cannot unconditionally silence an independent decision",
            cue.contains("闹钟当成停止判断的理由"))
        // Only a future one-shot reminder is not a demand for another immediate
        // message; do not create a generic night-time conversation mandate.
        val future = promise.copy(
            status = CommitmentTaskStatus.Scheduled,
            dueAt = now.plusSeconds(3600),
            nextCheckAt = now.plusSeconds(3600),
        )
        assertEquals("", ActiveCommitmentAttention.render(listOf(future), now))
        assertEquals("", ActiveCommitmentAttention.render(emptyList(), now))
    }

    @Test fun overnightReviewMustNotEraseHowLateOriginalCommitmentAlreadyIs() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val now = java.time.Instant.parse("2026-11-03T17:00:00Z") // 11/04 01:00
        val original = java.time.Instant.parse("2026-11-03T15:30:00Z") // 11/03 23:30
        val review = java.time.Instant.parse("2026-11-03T17:30:00Z") // 11/04 01:30
        val task = CommitmentTask(
            id = "overnight-check", characterId = "role",
            goal = "在约定时间开始睡前提醒，后来持续核实入睡结果",
            status = CommitmentTaskStatus.WaitingForFeedback,
            dueAt = original, nextCheckAt = review, timezone = zone.id,
            followUpKind = "verification", linkedAlarmId = "review-alarm",
            lastActionResult = "原定提醒已发送，入睡结果尚无可靠证据",
        )
        val facts = CommitmentClockFacts.render(task, now, zone)
        assertTrue(facts, facts.contains("原定行动时间=11月3日 23:30（已过去90分钟）"))
        assertTrue(facts, facts.contains("结果复查时间=11月4日 01:30（距离现在还有30分钟）"))
        assertTrue(facts, facts.contains("原定行动时间没有因此被改写"))
        val autonomy = ActiveCommitmentAttention.render(listOf(task), now)
        assertTrue(autonomy, autonomy.contains("原定行动时间="))
        assertTrue(autonomy, autonomy.contains("结果复查时间="))
        assertTrue(autonomy, autonomy.contains("复查时刻是不同事实"))
        assertTrue(autonomy, autonomy.contains("无证据仍不能推断对方已经睡着或仍然醒着"))
        // The same factual distinction reaches the normal commitment model:
        // no program branch chooses call/silent or extends the original goal.
        val decision = CommitmentDecisionProtocol.context(listOf(task), now, zone)
        assertTrue(decision, decision.contains("已过去90分钟"))
        assertTrue(decision, decision.contains("距离现在还有30分钟"))
        assertEquals(original, task.dueAt)
        assertEquals(review, task.nextCheckAt)
    }

    @Test fun pendingReviewOrOtherDaytimeGoalUsesSameClockWithoutInventingBedtime() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val now = java.time.Instant.parse("2026-11-04T05:00:00Z") // 13:00 local
        val task = CommitmentTask(
            id = "daytime-task", characterId = "role",
            goal = "午后核查项目审核结果",
            status = CommitmentTaskStatus.WaitingForFeedback,
            dueAt = now.minusSeconds(60 * 40),
            nextCheckAt = now.plusSeconds(60 * 20),
        )
        val facts = CommitmentClockFacts.render(task, now, zone)
        assertTrue(facts, facts.contains("已过去40分钟"))
        assertTrue(facts, facts.contains("距离现在还有20分钟"))
        assertFalse(facts.contains("睡觉"))
        assertFalse(facts.contains("催"))
        val futureDue = task.copy(status = CommitmentTaskStatus.Scheduled,
            dueAt = now.plusSeconds(30 * 60), nextCheckAt = null)
        assertTrue(CommitmentClockFacts.render(futureDue, now, zone)
            .contains("距离现在还有30分钟"))
        // An upcoming task has factual timing but does not cause an alarm or
        // an automatic conversational decision in the passive attention policy.
        assertEquals("", ActiveCommitmentAttention.render(listOf(futureDue), now))
        val undated = task.copy(dueAt = null, nextCheckAt = null)
        assertEquals("", CommitmentClockFacts.render(undated, now, zone))
        val expired = task.copy(status = CommitmentTaskStatus.Completed)
        assertEquals("", ActiveCommitmentAttention.render(listOf(expired), now))
    }

    @Test fun autonomyPromptPreservesExplicitSupervisionWithoutForcingEveryRoleToTalk() {
        val instruction = proactiveDecisionInstruction("bedtime-prompt-test")
        assertTrue(instruction.contains("已接受的责任与普通心事不同"))
        assertTrue(instruction.contains("不能仅凭猜测替她撤回约定"))
        assertTrue(instruction.contains("无论本轮说过话、用了其他工具还是选择 silent"))
        assertTrue(instruction.contains("不自动变成取消监督或必须等她主动报备"))
        assertTrue(instruction.contains("真实拒绝与权限不足继续遵守"))
    }

    @Test fun validSilentChoiceIsParsedAndKeepsItsReason() {
        val parsed = parse("""{"action":"silent","reason":"今晚真的想一个人消化刚才的经历","mood":"平静"}""")
        assertNotNull(parsed)
        assertTrue(parsed.toString().contains("action=SILENT"))
        assertTrue(parsed.toString().contains("今晚真的想"))
    }

    @Test fun actualSpontaneousSharingAndJournalingStayExecutable() {
        assertTrue(parse("""{"action":"moment","text":"刚读到那句我愣住了。"}""")
            .toString().contains("action=MOMENT"))
        assertTrue(parse("""{"action":"journal","journalTitle":"今天","journalContent":"还在想那一幕"}""")
            .toString().contains("action=JOURNAL"))
    }

    @Test fun invalidUnknownActionIsNotSilentlySwallowed() {
        assertNull(parse("""{"action":"invented_human_action","reason":"这是不存在的工具"}"""))
    }

    @Test fun modelChosenAutonomousBubblesSurviveTheDecisionAndSender() {
        val decision = parse("""{"action":"message","bubbles":["辛苦啦宝宝，先歇一会儿。","那张震惊猫猫头把我逗笑了。"]}""")!!
        val field = decision.javaClass.getDeclaredField("text").apply { isAccessible = true }
        val spoken = field.get(decision) as String
        assertTrue(spoken.contains(com.jiacimu.lulu.SemanticBubbleSeparator))
        assertEquals(listOf("辛苦啦宝宝，先歇一会儿。", "那张震惊猫猫头把我逗笑了。"),
            com.jiacimu.lulu.semanticReplyBubbles(spoken))
    }

    @Test fun realParsedPrivateDecisionHasOneSpeechOwnerBeforeExecution() {
        val parsed = parse("""{
            "action":"message",
            "bubbles":["学得怎么样了，宝宝？","头还晕不晕？"],
            "steps":[
                {"action":"message","text":"学得怎么样了，宝宝？头还晕不晕？"},
                {"tool":"send_private_message","args":{"text":"喝点温水。"}},
                {"tool":"control_music","args":{"command":"pause"}}
            ]
        }""")!!
        val before = parsed.javaClass.getDeclaredField("sideActions").apply { isAccessible = true }
            .get(parsed) as org.json.JSONArray
        assertEquals(3, before.length())
        val normalize = ProactivePerceptionRuntime::class.java.declaredMethods.first {
            it.name == "withSinglePrivateInboundReply" && it.parameterCount == 1
        }.apply { isAccessible = true }
        val consolidated = normalize.invoke(ProactivePerceptionRuntime, parsed)!!
        val textField = consolidated.javaClass.getDeclaredField("text").apply { isAccessible = true }
        val spoken = textField.get(consolidated) as String
        assertEquals(listOf("学得怎么样了，宝宝？", "头还晕不晕？", "喝点温水。"),
            com.jiacimu.lulu.semanticReplyBubbles(spoken))
        val after = consolidated.javaClass.getDeclaredField("sideActions").apply { isAccessible = true }
            .get(consolidated) as org.json.JSONArray
        assertEquals(1, after.length())
        assertEquals("control_music", after.getJSONObject(0).getString("tool"))
        assertFalse(after.toString().contains("send_private_message"))
    }

    @Test fun partialAutonomousJsonKeepsCompletedBubblesWithoutInventingAnotherReply() {
        val incomplete = """{"action":"message","bubbles":["刚学完？","过来让我抱抱。"],"innerLife":{"unfinished":"""
        val recovered = AutonomousDecisionRecovery.completedPrivateMessage(incomplete)
        assertNotNull(recovered)
        assertEquals(listOf("刚学完？", "过来让我抱抱。"),
            com.jiacimu.lulu.semanticReplyBubbles(recovered!!.optString("text")))
    }

    private fun parse(raw: String): Any? {
        val method = ProactivePerceptionRuntime::class.java.declaredMethods.first {
            it.name == "parseDecision" && it.parameterCount == 2
        }
        method.isAccessible = true
        return method.invoke(ProactivePerceptionRuntime, raw, false)
    }
}
