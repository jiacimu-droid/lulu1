package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RelativeWakeCommitmentParserTest {
    private val now = Instant.parse("2026-10-15T04:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun twentyMinutesLaterIsAnExactPromisedDeadline() {
        val task = RelativeWakeCommitmentParser.parse("现在20分钟之后再叫我", "好，我会叫你", now, zone)!!
        assertEquals(now.plusSeconds(20 * 60), task.dueAt)
        assertEquals("send_private_message", task.deliveryAction)
        assertFalse(task.needsClarification)
        assertTrue(task.goal.contains("12:20"))
    }

    @Test fun oneMinuteAndChineseNumeralsWorkWithoutModelGuessing() {
        assertEquals(now.plusSeconds(60), RelativeWakeCommitmentParser.parse(
            "1分钟后叫醒我", "好", now, zone)?.dueAt)
        assertEquals(now.plusSeconds(20 * 60), RelativeWakeCommitmentParser.parse(
            "二十分钟后叫我起床", "好", now, zone)?.dueAt)
        assertEquals(now.plusSeconds(30 * 60), RelativeWakeCommitmentParser.parse(
            "半小时之后喊醒我", "好", now, zone)?.dueAt)
    }

    @Test fun phoneCallAndDeclineAreDistinguished() {
        assertEquals("start_call", RelativeWakeCommitmentParser.parse(
            "20分钟后打电话叫醒我", "好", now, zone)?.deliveryAction)
        assertNull(RelativeWakeCommitmentParser.parse("20分钟后叫我", "不行", now, zone))
        assertNull(RelativeWakeCommitmentParser.parse("不要20分钟后叫我", "好", now, zone))
        assertNull(RelativeWakeCommitmentParser.parse("有空叫我", "好", now, zone))
    }

    @Test fun reschedulingOneMinuteWakeToTwentyMinutesKeepsItsPhoneCall() {
        val old = CommitmentTask(characterId = "role",
            goal = "1分钟后按约定叫醒用户",
            dueAt = now.plusSeconds(60),
            deliveryAction = "start_call")
        val new = RelativeWakeCommitmentParser.parse("现在20分钟之后再叫我", "好", now, zone)!!
        val change = RelativeWakeCommitmentParser.draftsFor(new, listOf(old),
            "现在20分钟之后再叫我").single()
        assertEquals("reschedule", change.action)
        assertEquals(old.id, change.targetTaskId)
        assertEquals("start_call", change.deliveryAction)
        assertEquals(now.plusSeconds(20 * 60), change.dueAt)
        assertTrue(RelativeWakeCommitmentParser.draftsFor(
            change, listOf(old.copy(dueAt = change.dueAt)), "现在20分钟之后再叫我"
        ).isEmpty())
    }
}
