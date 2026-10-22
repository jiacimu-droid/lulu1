package com.jiacimu.lulu.ai

import org.junit.Assert.*
import org.junit.Test

class PromptInputAuditTest {
    @Test fun everyTextCharacterIsAccountedForIncludingRulesSeparatorsAndHistory() {
        val instruction = "【当下真实私聊：一次理解、一次决定】\n先听清楚。\n【一次决策的动作协议】\n只输出一轮。"
        val rules = "统一基础规则\n本次任务：" + instruction
        val components = listOf(
            "任务规则/工具协议" to rules,
            "角色身份" to "阿澈",
            "未使用的空段" to "",
            "当前记忆" to "上次约好的事情",
        )
        val system = components.map { it.second }.filter(String::isNotBlank).joinToString("\n\n")
        val history = listOf(
            ModelConversationMessage(ModelConversationMessage.Role.USER, "你还记得吗"),
            ModelConversationMessage(ModelConversationMessage.Role.ASSISTANT, "我记得。"),
        )
        val image = "data:image/png;base64,AAAA"
        val audit = PromptInputAudit.inspect(
            components, system, "嗯，那继续吧", history, listOf(image), instruction,
        )
        assertTrue(audit.coverageMatches)
        assertEquals((system + "嗯，那继续吧").length + history.sumOf { it.content.length },
            audit.textCharacters)
        assertEquals(1, audit.imageCount)
        assertEquals(image.toByteArray(Charsets.UTF_8).size.toLong(), audit.imageDataUrlBytes)
        assertEquals(4, audit.messageCount)
        assertTrue(audit.breakdown.any { it.label.contains("本轮任务指令") })
        assertTrue(audit.breakdown.any { it.label.contains("历史对话 #2") })
        assertEquals(audit.breakdown.sumOf { it.chars.toLong() }, audit.coveredCharacters)
        assertTrue(audit.textUtf8Bytes > audit.textCharacters.toLong())
    }

    @Test fun reducedRequestReportsOnlyTheFinalSystemAndHistory() {
        val audit = PromptInputAudit.inspect(
            listOf("Relay精简后系统上下文" to "真实记忆不被删除"),
            "真实记忆不被删除", "新的用户原话", emptyList(), emptyList(), reduced = true,
        )
        assertTrue(audit.coverageMatches)
        assertTrue(audit.reduced)
        assertEquals(0, audit.imageCount)
        assertEquals(2, audit.messageCount)
        assertEquals(2, audit.breakdown.size)
    }

    @Test fun unknownUserAuthoredInstructionHeadingIsNeverSavedAsDiagnosticLabel() {
        val privateHeader = "【私密用户输入银行卡编号和地址】"
        val task = "前言\n" + privateHeader + "\n敏感正文"
        val rules = "基础\n本次任务：" + task
        val audit = PromptInputAudit.inspect(
            listOf("任务规则/工具协议" to rules), rules, "", emptyList(), emptyList(), task,
        )
        assertTrue(audit.coverageMatches)
        assertFalse(audit.breakdown.any { it.label.contains("银行卡") || it.label.contains("地址") })
    }
}
