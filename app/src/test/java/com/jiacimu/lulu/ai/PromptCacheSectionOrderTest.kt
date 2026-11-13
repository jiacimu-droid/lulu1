package com.jiacimu.lulu.ai

import org.junit.Assert.*
import org.junit.Test

class PromptCacheSectionOrderTest {
    private fun render(clock: String, task: String): List<Pair<String, String>> =
        PromptCacheSectionOrder.arrange(
            topWorldBooks = listOf("全局世界书/TOP" to "真实人物背景顶部设定"),
            fixedSections = listOf("任务规则/工具协议" to "稳定通用行为原则",
                "角色人设" to "稳定的独立性格与关系"),
            normalWorldBooks = listOf("角色世界书/NORMAL" to "不得改动的世界细节"),
            memorySections = listOf("核心记忆" to "主人明确保存的长期记忆",
                "未完成责任" to "已接受且仍需核实的职责",
                "近期共同记忆" to "本轮变化的历史召回"),
            liveScene = listOf("数字世界权威状态" to "本轮移动和现场状态"),
            dynamicTail = listOf("本轮表达证据" to "本轮从真实语音观察出的变化",
                "本轮任务指令" to task, "权威现实时钟" to clock),
            bottomWorldBooks = listOf("角色世界书/BOTTOM" to "用户明确放在末尾的设定"),
        )

    @Test fun clockAndTaskChangesLeaveTheCompletePrefixStable() {
        val first = render("2026-11-13T09:16", "本人决定下一步")
        val second = render("2026-11-13T09:21", "这轮新的真实动作")
        val firstDynamic = first.indexOfFirst { it.first == "数字世界权威状态" }
        assertEquals(5, firstDynamic)
        assertEquals(first.take(firstDynamic), second.take(firstDynamic))
        assertEquals("核心记忆", first[firstDynamic - 1].first)
        assertEquals("角色世界书/BOTTOM", first.last().first)
        assertEquals("角色世界书/BOTTOM", second.last().first)
        assertEquals("2026-11-13T09:21", second.single { it.first == "权威现实时钟" }.second)
        assertEquals("这轮新的真实动作", second.single { it.first == "本轮任务指令" }.second)
    }

    @Test fun sectionReorderingPreservesEveryOriginalLabelAndValue() {
        val all = render("现在", "任务")
        assertEquals(11, all.size)
        assertEquals(11, all.map { it.first }.toSet().size)
        assertEquals(1, all.count { it.first == "核心记忆" })
        assertEquals("已接受且仍需核实的职责",
            all.single { it.first == "未完成责任" }.second)
        assertTrue(all.indexOfFirst { it.first == "角色世界书/NORMAL" } <
            all.indexOfFirst { it.first == "核心记忆" })
        assertTrue(all.indexOfFirst { it.first == "本轮任务指令" } <
            all.indexOfFirst { it.first == "角色世界书/BOTTOM" })
    }
}
