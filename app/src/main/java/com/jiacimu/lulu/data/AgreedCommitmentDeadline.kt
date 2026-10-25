package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Corroborate the role's *spoken* deadline against the user's latest clock
 * and a relative interval anchored to the actual user message time.
 *
 * Unlike a keyword search for "tomorrow" over the whole conversation, this
 * cannot let an explicitly corrected earlier date overwrite the final agreed
 * time. It returns null rather than guessing when evidence is inconclusive.
 * This is a validation boundary, not a replacement for the role's interpretation.
 */
internal object AgreedCommitmentDeadline {
    private val interval = Regex(
        """([0-9]{1,4}|[零〇一二两三四五六七八九十百]{1,6})\s*(?:个)?\s*(小时|分钟)\s*(?:之)?(?:后|以后)"""
    )
    private val dateReference = Regex("后天|明天|明早|明晚|明日|今天|今早|今晚|今日")
    private val corrections = Regex("不对|不是|更正|改成|改到|改为|说错|我指的是|应该是")

    private fun numeral(raw: String): Long? {
        raw.toLongOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2,
            '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6,
            '七' to 7, '八' to 8, '九' to 9)
        var total = 0L
        var digit = 0L
        for (c in raw) {
            when (c) {
                '十' -> { total += (if (digit == 0L) 1 else digit) * 10L; digit = 0L }
                '百' -> { total += (if (digit == 0L) 1 else digit) * 100L; digit = 0L }
                else -> digit = digits[c]?.toLong() ?: return null
            }
        }
        return total + digit
    }

    private fun lastClock(text: String): LocalTime? {
        // AppointmentClock is the canonical local-clock lexer. Scan to the
        // latest occurrence so an earlier "tomorrow 9, no, 8" cannot win.
        val source = interval.replace(text, " ")
        var offset = 0
        var clock: LocalTime? = null
        while (offset < source.length) {
            val found = AppointmentClock.read(source.substring(offset)) ?: break
            clock = LocalTime.of(found.hour, found.minute)
            offset += found.range.last + 1
        }
        return clock
    }

    private fun dayOffset(text: String): Long? =
        dateReference.findAll(text).lastOrNull()?.value?.let {
            when (it) {
                "后天" -> 2L
                "明天", "明早", "明晚", "明日" -> 1L
                else -> 0L
            }
        }

    fun fromAgreement(
        userText: String,
        roleText: String,
        userAt: Instant,
        zone: ZoneId,
    ): Instant? {
        val role = roleText.trim()
        val correction = corrections.findAll(userText).lastOrNull()
        val latestUser = correction?.let { userText.substring(it.range.last + 1) } ?: userText
        val userClock = lastClock(latestUser) ?: return null
        val spokenClock = lastClock(role) ?: return null
        if (userClock != spokenClock) return null

        val userDate = dayOffset(latestUser)
        val spokenDate = dayOffset(role)
        if (userDate != null && spokenDate != null && userDate != spokenDate)
            return null

        val reference = userAt.atZone(zone).toLocalDate()
        val explicitDay = userDate ?: spokenDate
        val candidates = if (explicitDay != null) listOf(explicitDay)
            else (0L..3L).toList()
        val possible = candidates.map { day ->
            reference.plusDays(day).atTime(userClock).atZone(zone).toInstant()
        }.filter { it.isAfter(userAt.plusSeconds(5)) }
        val duration = interval.findAll(role).lastOrNull()?.let { match ->
            val value = numeral(match.groupValues[1]) ?: return@let null
            when (match.groupValues[2]) {
                "小时" -> value.takeIf { it in 1..72 }?.times(3600L)
                else -> value.takeIf { it in 1..4320 }?.times(60L)
            }
        }
        if (duration != null) {
            val expected = userAt.plusSeconds(duration)
            // Conversational hours are often rounded, while a specific clock
            // stays exact. A small tolerance is for checking, not shifting it.
            return possible.minByOrNull { Duration.between(it, expected).abs() }
                ?.takeIf { Duration.between(it, expected).abs() <= Duration.ofMinutes(15) }
        }
        // An explicitly agreed local date is independently verifiable. A
        // date-less clock without relative confirmation is not.
        return if (explicitDay != null) possible.singleOrNull() else null
    }

    internal fun repairedGoal(previous: String, agreed: Instant, zone: ZoneId): String {
        val label = agreed.atZone(zone).let { "%d月%d日 %02d:%02d".format(
            it.monthValue, it.dayOfMonth, it.hour, it.minute
        ) }
        // Normalize the time heading only; keep the actual responsibility.
        val body = previous.replaceFirst(
            Regex("""^\s*(?:(?:\d{1,2}月\d{1,2}日\s*\d{1,2}[:：]\d{2})|(?:(?:今天|明天|后天|今早|明早|今晚|明晚)(?:的)?(?:早上|上午|下午|傍晚|晚上)?\s*\d{1,2}(?:[:：]\d{2}|点(?:半)?)))\s*"""),
            ""
        ).trim()
        return "$label $body".trim()
    }
}
