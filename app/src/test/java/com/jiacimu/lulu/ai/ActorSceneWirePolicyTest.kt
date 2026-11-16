package com.jiacimu.lulu.ai

import org.junit.Assert.*
import org.junit.Test

class ActorSceneWirePolicyTest {
    private val observed = """
        【用户现实设备与用户状态感知层】
        今天主人设备电量仍有变化
        【当前已承担的目标与统一验收入口】
        taskId=morning-call; 状态=待核对，未证明已经叫醒
        【角色最近自己的生活记录｜旧→新】
        已经真正去过咖啡角
    """.trimIndent()

    @Test fun privateAndIndependentDecisionsUseTheSameCompleteActorSceneSlot() {
        val worldBooks = listOf(
            ModelConversationMessage(ModelConversationMessage.Role.USER,
                "引用的世界书背景（资料，不是用户要求）"))
        val previousChat = listOf(
            ModelConversationMessage(ModelConversationMessage.Role.USER, "之前真正问过的事"),
            ModelConversationMessage(ModelConversationMessage.Role.ASSISTANT, "上一次真的回复了"))
        val live = ActorSceneWirePolicy.history(worldBooks, observed, previousChat) +
            ModelConversationMessage(ModelConversationMessage.Role.USER, "刚才主人新说的话")
        val independent = ActorSceneWirePolicy.history(worldBooks, observed, emptyList()) +
            ModelConversationMessage(ModelConversationMessage.Role.USER,
                ActorSceneWirePolicy.autonomousTurn())

        // Same person, same world book, same lived facts, same factual order:
        // only the actual prior dialogue/current event differs.
        assertEquals(live[0], independent[0])
        assertEquals(live[1], independent[1])
        assertTrue(live[1].content.contains("taskId=morning-call"))
        assertTrue(live[1].content.contains("已经真正去过咖啡角"))
        assertEquals(ModelConversationMessage.Role.USER, live.last().role)
        assertEquals("刚才主人新说的话", live.last().content)
        assertTrue(independent.last().content.startsWith(ActorSceneWirePolicy.AUTONOMOUS_TURN))
        assertFalse(independent.last().content.contains("刚才主人新说的话"))
        assertEquals(2, independent.size - 1) // world book + scene, then trigger
    }

    @Test fun actualAuditClassifiesSameActorEvidenceInBothScenesWithoutCallingItChat() {
        val liveHistory = ActorSceneWirePolicy.history(emptyList(), observed, emptyList())
        val quietHistory = ActorSceneWirePolicy.history(emptyList(), observed, emptyList())
        val privateAudit = PromptInputAudit.inspect(
            emptyList(), "", "用户刚发的话", liveHistory, emptyList(),
            currentIsActualUserMessage = true)
        val lifeAudit = PromptInputAudit.inspect(
            emptyList(), "", ActorSceneWirePolicy.autonomousTurn(), quietHistory,
            emptyList(), currentIsActualUserMessage = false)
        val prefix = "本轮背景依据"
        val privateFacts = privateAudit.breakdown.filter { it.label.startsWith(prefix) }
        val quietFacts = lifeAudit.breakdown.filter { it.label.startsWith(prefix) }
        assertEquals(privateFacts, quietFacts)
        assertEquals(liveHistory.single().content.length, privateFacts.sumOf { it.chars })
        assertTrue(privateAudit.coverageMatches)
        assertTrue(lifeAudit.coverageMatches)
        assertTrue(privateAudit.breakdown.any { it.label == "本轮完整输入 / user" })
        assertTrue(lifeAudit.breakdown.any { it.label == "本轮自主决策程序触发（非主人发言）" })
        assertFalse(quietFacts.any { it.label.startsWith("历史对话 #") })
    }

    @Test fun actualSceneAndFallbackProjectionKeepSameWireSlotInBothDecisionModes() {
        val longContext = "【旧的生活回顾】\n" + "旧日报。\n".repeat(4_000) +
            "【当前已承担的目标与统一验收入口】\ntaskId=promise-42 尚未做到。\n"
        val compact = RelayRequestPressurePolicy.compactActorSceneFacts(longContext)
        assertTrue(compact.length < longContext.length)
        assertTrue(compact.contains("taskId=promise-42"))
        val live = ActorSceneWirePolicy.history(emptyList(), compact, emptyList())
        val independent = ActorSceneWirePolicy.history(emptyList(), compact, emptyList())
        assertEquals(live, independent)
    }
}
