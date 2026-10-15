package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RelativeContactCommitmentParserTest {
    private val now = Instant.parse("2026-10-15T04:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun fiveMinuteMessageGetsRealDeadlineAndMessageDelivery() {
        val task = RelativeContactCommitmentParser.parse("5分钟后给我发消息", "好，我会找你", now, zone)!!
        assertEquals(now.plusSeconds(300), task.dueAt)
        assertEquals("send_private_message", task.deliveryAction)
        assertTrue(task.goal.contains("发消息"))
        assertFalse(task.needsClarification)
    }

    @Test fun twentyMinuteCallAndChineseNumeralsAreSupported() {
        val task = RelativeContactCommitmentParser.parse("二十分钟之后给我打电话", "好", now, zone)!!
        assertEquals(now.plusSeconds(1200), task.dueAt)
        assertEquals("start_call", task.deliveryAction)
        assertEquals(now.plusSeconds(300), RelativeContactCommitmentParser.parse(
            "五分钟后发消息给我", "收到", now, zone)?.dueAt)
    }

    @Test fun refusalAndQuestionCannotSilentlySchedule() {
        assertNull(RelativeContactCommitmentParser.parse("5分钟后给我发消息", "不行", now, zone))
        assertNull(RelativeContactCommitmentParser.parse("别5分钟后给我发消息", "好的", now, zone))
        assertNull(RelativeContactCommitmentParser.parse("5分钟后给我发消息", "要不要我发？", now, zone))
    }

    @Test fun explicitRescheduleKeepsSameTaskId() {
        val old = CommitmentTask(characterId = "role", goal = "按约定给用户发消息",
            dueAt = now.plusSeconds(60), deliveryAction = "send_private_message")
        val raw = RelativeContactCommitmentParser.parse("改成5分钟后给我发消息", "好", now, zone)!!
        val updated = RelativeContactCommitmentParser.draftsFor(raw, listOf(old),
            "改成5分钟后给我发消息").single()
        assertEquals("reschedule", updated.action)
        assertEquals(old.id, updated.targetTaskId)
        assertEquals(now.plusSeconds(300), updated.dueAt)
    }
}
