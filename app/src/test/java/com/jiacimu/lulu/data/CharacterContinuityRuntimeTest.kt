package com.jiacimu.lulu.data

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CharacterContinuityRuntimeTest {
    private fun event(
        id: String,
        content: String,
        kind: EventEvidenceKind,
        at: String,
    ) = SharedTimelineEvent(
        id = id,
        characterId = "role",
        channel = "私聊",
        speaker = if (kind == EventEvidenceKind.UserStatement) "用户" else "角色",
        content = content,
        occurredAt = Instant.parse(at),
        evidenceKind = kind,
    )

    @Test fun longGapStableAddressAndOwnLastWordsRemainVisibleTogether() {
        val state = CharacterContinuityRuntime.Snapshot(
            previousInteractionAt = Instant.parse("2026-10-01T12:00:00Z"),
            minutesSinceInteraction = 9L * 24 * 60,
            lastUserStatement = event("u1", "我这几天会很忙", EventEvidenceKind.UserStatement, "2026-10-01T11:59:00Z"),
            lastCharacterStatement = event("c1", "好，我记着。", EventEvidenceKind.CharacterStatement, "2026-10-01T12:00:00Z"),
            preferredAddress = "宝宝",
            addressIsManual = true,
            activeMotiveAims = listOf("把答应她的事记住"),
        )
        val text = CharacterContinuityRuntime.render(state)
        assertTrue(text.contains("长期空档"))
        assertTrue(text.contains("宝宝"))
        assertTrue(text.contains("角色自己上一次真实说过"))
        assertTrue(text.contains("不能下一轮当成用户说的"))
        assertTrue(text.contains("把答应她的事记住"))
        assertTrue(text.contains("不能擅自断言用户故意冷落"))
    }

    @Test fun recentContactDoesNotPretendToBeAReunion() {
        val state = CharacterContinuityRuntime.Snapshot(
            previousInteractionAt = Instant.parse("2026-10-10T12:00:00Z"),
            minutesSinceInteraction = 18,
            lastUserStatement = null,
            lastCharacterStatement = null,
            preferredAddress = "",
            addressIsManual = false,
            activeMotiveAims = emptyList(),
        )
        val text = CharacterContinuityRuntime.render(state)
        assertTrue(text.contains("同一段近期关系流"))
        assertTrue(text.contains("不要把它当久别重逢"))
    }
}
