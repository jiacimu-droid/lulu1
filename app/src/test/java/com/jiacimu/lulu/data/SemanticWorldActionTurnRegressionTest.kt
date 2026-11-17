package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import java.time.Instant
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Screenshot regression: one "go home" is visible twice in the RAW ledger
 * (tool receipt and native world proof) and an online-awareness receipt follows.
 * Neither the alias nor the heartbeat is another memory turn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SemanticWorldActionTurnRegressionTest {
    private val role = "turn-home-role"
    private val at = Instant.parse("2026-11-17T05:35:00Z")
    private val nativeId = "world-fact-home-actual-1"
    private val toolId = "tool-$role-request-home-1"

    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    private fun row(
        id: String, atSecond: Long, channel: String, source: String,
        content: String, kind: EventEvidenceKind, taskId: String = "",
        sessionId: String = "", owner: String = role,
    ) = SharedTimelineEvent(
        id = id, characterId = owner, channel = channel,
        speaker = when (source) {
            "tool-router" -> "执行器"
            "online-awareness" -> "实际观察"
            else -> if (kind == EventEvidenceKind.UserStatement) "主人" else "江渡"
        }, content = content, occurredAt = at.plusSeconds(atSecond),
        sessionId = sessionId, source = source, evidenceKind = kind, taskId = taskId,
    )

    private fun world(atSecond: Long = 1, taskId: String = "") = row(
        nativeId, atSecond, "数字世界", "legacy",
        "江渡从云眠原 回到了自己的空中家园。",
        EventEvidenceKind.Legacy, taskId = taskId,
    )

    private fun receipt(nativeEvidenceId: String = nativeId,
                        success: Boolean = true, owner: String = role,
                        atSecond: Long = 2): SharedTimelineEvent {
        val result = JSONObject().put("success", success)
            .put("evidenceId", nativeEvidenceId)
            .put("summary", "江渡从云眠原 回到了自己的空中家园。")
        val envelope = JSONObject().put("requestId", "request-home-1")
            .put("tool", "digital_world_action")
            .put("status", if (success) "succeeded" else "failed")
            // Production ToolRouter writes the result as a JSON STRING.
            .put("result", result.toString())
        return row(toolId, atSecond, "实际工具执行", "tool-router",
            envelope.toString(), EventEvidenceKind.ToolResult,
            taskId = "request-home-1", owner = owner)
    }

    private fun awareness(atSecond: Long, source: String) = row(
        "awareness-$source-$atSecond", atSecond,
        if (source == "online-awareness") "在线感知" else "后台复查",
        source, "本次在线感知实际读取当前时间和设备状态；新增聊天=false；新增场景观察=0",
        EventEvidenceKind.Observation)

    @Test fun legacyRawScreenshotsRepresentOneExperienceAndZeroAwarenessTurns() {
        val events = listOf(world(), receipt(), awareness(3, "online-awareness"),
            awareness(4, "background-awareness"))
        val turns = RecentSemanticTimeline.select(events, 20)
        assertEquals(1, turns.size)
        assertEquals(RecentSemanticTimeline.Kind.EXPERIENCE, turns.single().kind)
        assertEquals(setOf(nativeId, toolId), turns.single().events.map { it.id }.toSet())
        assertEquals("tool:request-home-1", turns.single().key)
        val shown = MemoryExtractionTurnView.build(turns)
        assertEquals(1, shown.turnCount)
        assertEquals(2, shown.rawEventCount)
        assertTrue(shown.visibleSources.any { it.id == nativeId })
        assertTrue(shown.visibleSources.any { it.id == toolId })
    }

    @Test fun persistedNativeTaskIdStillCoalescesWithoutReadingWrapperJson() {
        val turns = RecentSemanticTimeline.select(listOf(
            world(taskId = "request-home-1"),
            receipt(nativeEvidenceId = "missing-or-edited-fact"),
            awareness(3, "online-awareness"),
        ), 20)
        assertEquals(1, turns.size)
        assertEquals(2, turns.single().events.size)
        assertEquals("tool:request-home-1", turns.single().key)
    }

    @Test fun unverifiedAliasesNeverMergeDifferentRealActionsOrCharacterRecords() {
        val unmatched = receipt(nativeEvidenceId = "world-fact-another-real-action")
        assertEquals(2, RecentSemanticTimeline.select(listOf(world(), unmatched), 20).size)
        val failed = receipt(success = false)
        assertEquals(2, RecentSemanticTimeline.select(listOf(world(), failed), 20).size)
        val otherRole = receipt(owner = "not-this-role")
        assertEquals(2, RecentSemanticTimeline.select(listOf(world(), otherRole), 20).size)
    }

    @Test fun recentContextAndFifteenTurnExtractionUseIdenticalBoundaries() {
        val app = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(app)
        SharedExperienceTimeline.initialize(app)
        // Fourteen complete owner/role utterances, followed by one verified
        // world experience and five more utterances. The newest five stay
        // protected. A diagnostic heartbeat must not shift that boundary.
        fun speech(i: Int, sec: Long) {
            val row = row("speech-$i", sec, "私聊", "message",
                "第${i}轮真实说出口的话", EventEvidenceKind.UserStatement,
                sessionId = "conversation-$i")
            SharedExperienceTimeline.record(row.id, row.characterId, row.channel,
                row.speaker, row.content, row.occurredAt,
                triggerExtraction = false, sessionId = row.sessionId,
                source = row.source, evidenceKind = row.evidenceKind)
        }
        (0 until 14).forEach { speech(it, it.toLong()) }
        val native = world(14)
        SharedExperienceTimeline.record(native.id, native.characterId,
            native.channel, native.speaker, native.content, native.occurredAt,
            triggerExtraction = false, source = native.source,
            evidenceKind = native.evidenceKind)
        val wrapper = receipt(atSecond = 15)
        SharedExperienceTimeline.record(wrapper.id, wrapper.characterId,
            wrapper.channel, wrapper.speaker, wrapper.content, wrapper.occurredAt,
            triggerExtraction = false, source = wrapper.source,
            evidenceKind = wrapper.evidenceKind, taskId = wrapper.taskId)
        for (observation in listOf(awareness(16, "online-awareness"),
                awareness(17, "background-awareness"))) {
            SharedExperienceTimeline.record(observation.id, observation.characterId,
                observation.channel, observation.speaker, observation.content,
                observation.occurredAt, triggerExtraction = false,
                source = observation.source, evidenceKind = observation.evidenceKind)
        }
        (14 until 19).forEach { speech(it, (18 + it).toLong()) }
        val memory = LuluRepositories.memory
        val turns = memory.contextTimelineTurns(role)
        assertEquals(20, turns.size)
        assertEquals(19, turns.count { it.kind == RecentSemanticTimeline.Kind.SPEECH })
        assertEquals(1, turns.count { it.kind == RecentSemanticTimeline.Kind.EXPERIENCE })
        assertTrue(turns.none { t ->
            t.events.any { it.source == "online-awareness" || it.source == "background-awareness" }
        })
        assertEquals(15, memory.pendingMessageCount(role))
        val pending = memory.pendingTimelineEvents(role)
        assertEquals(16, pending.size) // 14 spoken original rows + 2 receipts
        assertTrue(pending.any { it.id == nativeId })
        assertTrue(pending.any { it.id == toolId })
        assertFalse(pending.any { it.id == "speech-18" })
        val view = MemoryExtractionTurnView.build(turns.take(15))
        assertEquals(15, view.turnCount)
        assertEquals(16, view.rawEventCount)
        assertEquals(setOf(nativeId, toolId),
            view.visibleSources.map { it.id }.filter { it in setOf(nativeId, toolId) }.toSet())
        assertEquals(21, memory.contextTimelineEvents(role).size) // raw facts retained, counted once
    }
}
