package com.jiacimu.lulu.ai

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared read-only wall-clock evidence for real-world character model calls.
 * No role choice, memory mutation, extra model request or user-intent parsing.
 */
internal object CharacterClockAuthority {
    fun render(at: Instant, zone: ZoneId): String {
        val local = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at.atZone(zone))
        return "【当前现实时钟｜手机读取】$local（时区 ${zone.id}）。" +
            "这是本次模型请求的当前时刻。历史聊天、回忆、日记里说的“现在/今晚/凌晨/两点”" +
            "只表示当时的情境，不能代替本轮日期、钟点或时段。"
    }
}
