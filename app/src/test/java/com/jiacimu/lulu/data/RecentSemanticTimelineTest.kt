package com.jiacimu.lulu.data

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RecentSemanticTimelineTest {
    private val start = Instant.parse("2026-11-17T00:00:00Z")

    private fun row(id: String, n: Long, source: String, kind: EventEvidenceKind,
                    session: String = "", task: String = "", content: String = id,
                    channel: String = "私聊", owner: String = "role") =
        SharedTimelineEvent(id = id, characterId = owner, channel = channel,
            speaker = if (kind == EventEvidenceKind.UserStatement) "主人" else "江渡",
            content = content, occurredAt = start.plusSeconds(n),
            source = source, evidenceKind = kind, sessionId = session, taskId = task)

    @Test fun hundredRelicActionsDoNotEvictRealConversationAndGameSpeechCountsSeparately() {
        val events = mutableListOf<SharedTimelineEvent>()
        events += row("chat-old", 0, "message", EventEvidenceKind.UserStatement, "dm",
            content = "你还记得十分钟前的事吗")
        events += row("relic:run:role:join", 1, "game-relic", EventEvidenceKind.ToolResult,
            channel = "共同游戏《迷雾摸金》")
        repeat(120) { index ->
            events += row("relic:run:role:$index", index + 2L,
                "game-relic", EventEvidenceKind.ToolResult,
                content = "真实行动 $index", channel = "共同游戏《迷雾摸金》")
        }
        events += row("game-owner-1", 40, "game-team-owner-stt",
            EventEvidenceKind.UserStatement, "game-session-relic_escape-run",
            content = "左边有怪", channel = "共同游戏《迷雾摸金》·队伍交流")
        events += row("game-role-1", 41, "game-team-companion",
            EventEvidenceKind.CharacterStatement, "game-session-relic_escape-run",
            content = "我去引开", channel = "共同游戏《迷雾摸金》·队伍交流")
        events += row("game-owner-2", 42, "game-team-owner-stt",
            EventEvidenceKind.UserStatement, "game-session-relic_escape-run",
            content = "快救我", channel = "共同游戏《迷雾摸金》·队伍交流")
        events += row("game-role-2", 43, "game-team-companion",
            EventEvidenceKind.CharacterStatement, "game-session-relic_escape-run",
            content = "已经过来了", channel = "共同游戏《迷雾摸金》·队伍交流")
        events += row("game-accepted-relic_escape-run-role-90", 150,
            "game-team-action", EventEvidenceKind.ToolResult,
            "game-session-relic_escape-run", content = "救援成功",
            channel = "共同游戏《迷雾摸金》·本人行动")
        events += row("relic:run:role:result", 151, "game-relic", EventEvidenceKind.ToolResult,
            content = "成功撤离", channel = "共同游戏《迷雾摸金》")
        events += row("game-raw-record", 152, "legacy", EventEvidenceKind.Observation,
            content = "迷雾摸金完整结算；真实过程数据：{\"id\":\"run\"}",
            channel = "共同游戏《迷雾摸金》")
        val turns = RecentSemanticTimeline.select(events, 8)
        assertEquals(6, turns.size) // old DM + four actual speech turns + one game
        val game = turns.single { it.key == "game:relic_escape:run" }
        assertEquals(124, game.events.size) // join + 120 actions + accepted + result + replay
        assertEquals(4, turns.count { it.kind == RecentSemanticTimeline.Kind.SPEECH &&
            it.key.startsWith("speech:game-session") })
        assertTrue(turns.any { it.events.any { e -> e.id == "chat-old" } })
        assertTrue(RecentSemanticTimeline.render(game, 900).contains("成功撤离"))
        assertTrue(RecentSemanticTimeline.render(game, 900).contains("relic:run:role:join"))
        assertEquals(3, RecentSemanticTimeline.select(events, 3).size)
    }

    @Test fun userBurstAndCharacterBubblesAreTwoTurnsWithLinkedThought() {
        val rows = listOf(
            row("u1", 1, "message", EventEvidenceKind.UserStatement, "dm", content = "第一句"),
            row("u2", 2, "message", EventEvidenceKind.UserStatement, "dm", content = "第二句"),
            row("r1", 3, "message", EventEvidenceKind.CharacterStatement, "dm", content = "回答一"),
            row("r2", 4, "message", EventEvidenceKind.CharacterStatement, "dm", content = "回答二"),
            row("presence-r1-role", 5, "presence", EventEvidenceKind.Inference,
                "dm", content = "当时心情和心声"),
        )
        val turns = RecentSemanticTimeline.select(rows, 35) { if (it.id in setOf("r1", "r2")) "batch" else null }
        assertEquals(2, turns.size)
        assertEquals(listOf("u1", "u2"), turns.first().events.map { it.id })
        assertEquals(listOf("r1", "r2", "presence-r1-role"),
            turns.last().events.map { it.id })
        val text = RecentSemanticTimeline.render(turns.last(), 700)
        assertTrue(text.contains("回答一"))
        assertTrue(text.contains("回答二"))
        assertTrue(text.contains("当时心情和心声")) // source-linked past thought is within the same turn
        assertTrue(text.contains("未公开的主观状态/心声")) // it is NOT a spoken bubble
        assertEquals(3, RecentSemanticTimeline.select(rows.dropLast(1), 35).size) // without a real batch, two replies
    }

    @Test fun meetingMovementAndSpeechAreDistinctExperienceAndConversationTurns() {
        val rows = (0 until 60).map { i ->
            row("meeting-1-action-$i", i.toLong(), "meeting",
                EventEvidenceKind.Observation, "meeting-1",
                channel = "数字世界见面·咖啡厅")
        } + listOf(
            row("meeting-chat-user", 15, "meeting", EventEvidenceKind.UserStatement,
                "meeting-1", content = "牵我过去吧", channel = "数字世界见面·咖啡厅"),
            row("meeting-chat-role", 16, "meeting", EventEvidenceKind.CharacterStatement,
                "meeting-1", content = "好", channel = "数字世界见面·咖啡厅"),
        )
        val turns = RecentSemanticTimeline.select(rows, 35)
        assertEquals(3, turns.size)
        assertEquals(60, turns.single { it.kind == RecentSemanticTimeline.Kind.EXPERIENCE }.events.size)
        assertEquals(2, turns.count { it.kind == RecentSemanticTimeline.Kind.SPEECH })
    }

    @Test fun multiStepBackgroundPhoneIsOneTaskButIndependentTasksStayIndependent() {
        val steps = (0 until 60).map { i ->
            row("tool-role-task-phone-$i", i.toLong(), "tool-router",
                EventEvidenceKind.ToolResult, task = "task-phone-$i",
                content = "手机步骤 $i", channel = "实际工具执行")
        }
        val first = row("tool-role-task", 100, "tool-router", EventEvidenceKind.ToolResult,
            task = "task", content = "原请求回执", channel = "实际工具执行")
        val next = row("tool-role-other", 101, "tool-router", EventEvidenceKind.ToolResult,
            task = "other", content = "另一个任务", channel = "实际工具执行")
        val turns = RecentSemanticTimeline.select(steps + first + next, 35)
        assertEquals(2, turns.size)
        assertEquals(61, turns.single { it.key == "tool:task" }.events.size)
        assertEquals(1, turns.single { it.key == "tool:other" }.events.size)
    }

    @Test fun removedSourceCannotSurviveProjectionAndDifferentSessionsCannotMerge() {
        val a = row("a", 0, "message", EventEvidenceKind.UserStatement, "chat-1", content = "旧话")
        val b = row("b", 1, "message", EventEvidenceKind.UserStatement, "chat-2", content = "另一会话")
        assertEquals(2, RecentSemanticTimeline.select(listOf(a, b), 35).size)
        val afterDelete = RecentSemanticTimeline.select(listOf(b), 35)
        assertEquals(listOf("b"), afterDelete.flatMap { it.events }.map { it.id })
        assertFalse(afterDelete.any { turn -> RecentSemanticTimeline.render(turn).contains("旧话") })
    }
    @Test fun persistentIslandWorldSplitsSeparateVisitsButNotEveryAction() {
        val session = "game-session-block_island-seed42"
        val rows = (0 until 60).map { index ->
            row("island-action-$index", index.toLong(), "game-team-action",
                EventEvidenceKind.ToolResult, session,
                content = "一次实际采集或移动 $index", channel = "共同游戏《方块小岛》·本人行动")
        } + (0 until 40).map { index ->
            row("island-next-day-$index", 20_000L + index,
                "game-team-action", EventEvidenceKind.ToolResult, session,
                content = "第二次上线的实际行动 $index",
                channel = "共同游戏《方块小岛》·本人行动")
        }
        val turns = RecentSemanticTimeline.select(rows, 35)
        assertEquals(2, turns.size)
        assertEquals(listOf(60, 40), turns.map { it.events.size })
        assertNotEquals(turns[0].key, turns[1].key)
    }

}
