package com.jiacimu.lulu.data

/** Exact stated clock time, without inventing its calendar date. */
internal object AppointmentClock {
    data class Reading(val hour: Int, val minute: Int, val range: IntRange)
    private val pattern = Regex("([0-9零〇一二两三四五六七八九十]{1,3})(?:[:：]([0-5][0-9])|[点时](?:钟)?(半|一刻|三刻|[0-9零〇一二两三四五六七八九十]{1,3}分?)?)")
    private fun number(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2,
            '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (text == "十") return 10
        if ('十' in text) {
            val sides = text.split('十')
            if (sides.size != 2) return null
            val tens = if (sides[0].isEmpty()) 1 else sides[0].singleOrNull()?.let(digits::get) ?: return null
            val ones = if (sides[1].isEmpty()) 0 else sides[1].singleOrNull()?.let(digits::get) ?: return null
            return tens * 10 + ones
        }
        return text.singleOrNull()?.let(digits::get)
    }
    fun read(text: String): Reading? {
        val match = pattern.find(text) ?: return null
        val hour = number(match.groupValues[1]) ?: return null
        val minutes = match.groupValues[2].ifBlank { match.groupValues[3] }.removeSuffix("分")
        val minute = when (minutes) { "" -> 0; "半" -> 30; "一刻" -> 15; "三刻" -> 45; else -> number(minutes) ?: return null }
        return if (hour in 0..23 && minute in 0..59) Reading(hour, minute, match.range) else null
    }
}
