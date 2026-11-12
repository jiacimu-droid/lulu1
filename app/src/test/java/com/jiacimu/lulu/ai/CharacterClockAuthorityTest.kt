package com.jiacimu.lulu.ai

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CharacterClockAuthorityTest {
    @Test fun phoneClockIsExplicitAndChangesAcrossZonesWithoutChangingTheInstant() {
        val at = Instant.parse("2026-11-12T01:39:00Z")
        val shanghai = CharacterClockAuthority.render(at, ZoneId.of("Asia/Shanghai"))
        val tokyo = CharacterClockAuthority.render(at, ZoneId.of("Asia/Tokyo"))
        assertTrue(shanghai.contains("2026-11-12T09:39:00+08:00"))
        assertTrue(tokyo.contains("2026-11-12T10:39:00+09:00"))
        assertTrue(shanghai.contains("时区 Asia/Shanghai"))
        assertTrue(tokyo.contains("时区 Asia/Tokyo"))
        assertTrue(shanghai.contains("历史聊天、回忆、日记"))
        assertFalse(shanghai.contains("2026-11-12T02:00:00+08:00"))
    }

    @Test fun aLaterModelStepGetsANewCurrentClockRatherThanTheEarlierTurnTimestamp() {
        val zone = ZoneId.of("Asia/Shanghai")
        val initial = CharacterClockAuthority.render(Instant.parse("2026-11-12T01:39:00Z"), zone)
        val afterTool = CharacterClockAuthority.render(Instant.parse("2026-11-12T01:43:00Z"), zone)
        assertTrue(initial.contains("09:39:00"))
        assertTrue(afterTool.contains("09:43:00"))
        assertFalse(afterTool.contains("09:39:00"))
    }
}
