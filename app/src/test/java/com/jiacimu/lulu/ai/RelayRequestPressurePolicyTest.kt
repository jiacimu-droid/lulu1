package com.jiacimu.lulu.ai

import org.junit.Assert.*
import org.junit.Test

class RelayRequestPressurePolicyTest {
    @Test fun onlyExplicitRelayMemory503MayUseOneSmallerBackgroundRequest() {
        assertTrue(RelayRequestPressurePolicy.isMemoryGuard(503,
            "模型请求失败（503）：Relay 请求体内存保护已触发"))
        assertTrue(RelayRequestPressurePolicy.isMemoryGuard(503,
            "Relay could not allocate memory for request body"))
        assertFalse(RelayRequestPressurePolicy.isMemoryGuard(503,
            "服务端临时过载，请稍后重试"))
        assertFalse(RelayRequestPressurePolicy.isMemoryGuard(429,
            "Relay 请求体内存保护已触发"))
        assertTrue(RelayRequestPressurePolicy.canRecover("后台主动感知"))
        assertFalse(RelayRequestPressurePolicy.canRecover("电话生成"))
    }

    @Test fun compactProjectionKeepsCurrentUserVerbatimAndHonorsActualConversationRoles() {
        val current = "我不是第一次认识你。我们之前约好周六见面，这条新消息千万不能删。"
        val old = (0 until 20).map {
            ModelConversationMessage(
                if (it % 2 == 0) ModelConversationMessage.Role.USER
                    else ModelConversationMessage.Role.ASSISTANT,
                "旧聊天回合$it：" + "老故事".repeat(180))
        }
        val selected = RelayRequestPressurePolicy.compactHistory(old, 1_800)
        assertTrue(selected.isNotEmpty())
        assertTrue(selected.size < old.size)
        assertEquals(old.last(), selected.last())
        assertEquals(old.takeLast(selected.size), selected)
        val compacted = RelayRequestPressurePolicy.compactSections(listOf(
            "任务规则/工具协议" to "真实执行与回执",
            "角色身份" to "江渡本人",
            "角色人设" to "尊重关系中的历史与自主表达",
            "核心记忆" to "周六的约定仍然有效",
            "本轮背景依据" to ("早期环境".repeat(5_000) + "\n本轮新事实：有实际相遇")
        ))
        assertTrue(compacted.contains("江渡本人"))
        assertTrue(compacted.contains("周六的约定仍然有效"))
        assertTrue(compacted.contains("本轮新事实：有实际相遇"))
        assertTrue(compacted.contains("部分较早背景省略"))
        assertEquals(current.toByteArray(Charsets.UTF_8).size,
            RelayRequestPressurePolicy.payloadBytes("", current, emptyList(), emptyList()))
        // Compaction affects only the older projection. The current user
        // argument remains untouched in both the first and the second request.
        val original = RelayRequestPressurePolicy.payloadBytes(
            "之前全部上下文".repeat(9_000), current, old, emptyList())
        val compactBytes = RelayRequestPressurePolicy.payloadBytes(
            compacted, current, selected, emptyList())
        assertTrue(RelayRequestPressurePolicy.savesEnough(original, compactBytes))
    }

    @Test fun countingRequestBytesIncludesUtf8ChineseAndRealPhotoData() {
        val textOnly = RelayRequestPressurePolicy.payloadBytes(
            "江渡", "喂", emptyList(), emptyList())
        assertEquals("江渡喂".toByteArray(Charsets.UTF_8).size, textOnly)
        val withImage = RelayRequestPressurePolicy.payloadBytes(
            "江渡", "喂", emptyList(), listOf("data:image/jpeg;base64," + "A".repeat(128)))
        assertTrue(withImage > textOnly)
        assertTrue(RelayRequestPressurePolicy.shouldCompactBeforeSend(
            "后台主动感知", 500_000))
        assertFalse(RelayRequestPressurePolicy.shouldCompactBeforeSend(
            "后台主动感知", 60_000))
    }

    @Test fun compactHistoryDoesNotCutTurnsOrRewriteRecentSpeech() {
        val enormous = ModelConversationMessage(ModelConversationMessage.Role.USER,
            "过去消息".repeat(7_000))
        val final = ModelConversationMessage(ModelConversationMessage.Role.ASSISTANT,
            "旧回答")
        assertEquals(listOf(final), RelayRequestPressurePolicy.compactHistory(
            listOf(enormous, final), 900))
        assertTrue(RelayRequestPressurePolicy.compactHistory(listOf(enormous), 900).isEmpty())
    }
}
