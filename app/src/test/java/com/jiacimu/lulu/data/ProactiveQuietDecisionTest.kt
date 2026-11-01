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

    @Test fun autonomyPromptPreservesExplicitSupervisionWithoutForcingEveryRoleToTalk() {
        val instruction = proactiveDecisionInstruction("bedtime-prompt-test")
        assertTrue(instruction.contains("已接受的责任与普通心事不同"))
        assertTrue(instruction.contains("不能仅凭猜测替她撤回约定"))
        assertTrue(instruction.contains("如果此刻选择 silent 且没有有效后续闹钟"))
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
