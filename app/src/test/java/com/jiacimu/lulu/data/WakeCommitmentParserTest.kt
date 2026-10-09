package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WakeCommitmentParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-09T07:20:00Z") // 15:20 local

    @Test fun tomorrowMorningAtTenProducesRealExactDeadline() {
        val draft = WakeCommitmentParser.parse("明天早上你10点钟叫我", "好，明天十点我叫你。", now, zone)
        assertNotNull(draft)
        assertEquals(Instant.parse("2026-10-10T02:00:00Z"), draft!!.dueAt)
        assertEquals("Asia/Shanghai", draft.timezone)
        assertEquals("send_private_message", draft.deliveryAction)
        assertFalse(draft.needsClarification)
        assertTrue(draft.goal.contains("叫醒"))
    }

    @Test fun afternoonAndMinutesAndExplicitPhone() {
        val draft = WakeCommitmentParser.parse("后天下午3点30分打电话叫我起床", "行，交给我。", now, zone)
        assertNotNull(draft)
        assertEquals(Instant.parse("2026-10-11T07:30:00Z"), draft!!.dueAt)
        assertEquals("start_call", draft.deliveryAction)
    }

    @Test fun aRefusalOrQuestionIsNotAnAcceptedCommitment() {
        assertNull(WakeCommitmentParser.parse("明天10点叫我", "不行，我做不到。", now, zone))
        assertNull(WakeCommitmentParser.parse("明天10点叫我", "要不要我叫你？", now, zone))
    }

    @Test fun withoutDateOrActualAcceptanceWeDoNotInventTime() {
        assertNull(WakeCommitmentParser.parse("10点叫我", "好呀", now, zone))
        assertNull(WakeCommitmentParser.parse("明天10点叫我", "嗯？", now, zone))
        assertNull(WakeCommitmentParser.parse("明天10点叫我", "", now, zone))
        assertNull(WakeCommitmentParser.parse("今早10点叫我", "好", now, zone))
    }

    @Test fun roleMakingAPlanDoesNotSubstituteForClockExecution() {
        val draft = WakeCommitmentParser.parse("明天早上10点叫我", "好呀，我明早起来给你写日记，顺便叫你。", now, zone)
        assertNotNull(draft)
        assertTrue(draft!!.steps.any { it.contains("手机闹钟") })
        assertTrue(draft.steps.any { it.contains("到点") })
    }
}
