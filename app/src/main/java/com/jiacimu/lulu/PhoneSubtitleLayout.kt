package com.jiacimu.lulu

/**
 * Visual-only punctuation-aware line breaking. The voice keeps its single
 * expressive TTS request; transcript storage keeps the original exact text.
 */
internal object PhoneSubtitleLayout {
    fun lines(raw: String, maxCharacters: Int = 31): List<String> {
        if (raw.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val line = StringBuilder()
        fun flush() {
            val text = line.toString().trim()
            if (text.isNotEmpty()) result.add(text)
            line.clear()
        }
        raw.forEach { c ->
            if (c == '\n') { flush(); return@forEach }
            line.append(c)
            val terminal = c in "。！？!?；;"
            val pause = c in "，,、"
            when {
                terminal && line.length >= 3 -> flush()
                pause && line.length >= 18 -> flush()
                line.length >= maxCharacters.coerceIn(16, 60) -> flush()
            }
        }
        flush()
        return result
    }

    fun format(raw: String): String = lines(raw).joinToString("\n")
}
