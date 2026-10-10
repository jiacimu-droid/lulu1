package com.jiacimu.lulu

/** Subtitles split at natural pauses, never at arbitrary character boundaries.
 * TTS and transcript persistence continue to use the original input.
 */
internal object PhoneSubtitleLayout {
    @Suppress("UNUSED_PARAMETER")
    fun lines(raw: String, maxCharacters: Int = 31): List<String> {
        if (raw.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            current.toString().trim().takeIf(String::isNotBlank)?.let(result::add)
            current.clear()
        }
        raw.forEachIndexed { index, c ->
            if (c == '\n') {
                flush()
                return@forEachIndexed
            }
            current.append(c)
            val punctuation = c in "。！？!?；;，,"
            val closingQuote = c in "”’\"'）)】]"
            if (punctuation && raw.getOrNull(index + 1) !in listOf('”', '’', '"', '\'', '）', ')', '】', ']')) {
                flush()
            } else if (closingQuote && index > 0 && raw[index - 1] in "。！？!?；;，,") {
                flush()
            }
        }
        flush()
        return result
    }

    fun captionRows(raw: String, speaker: String): List<String> =
        lines(raw).map { "$speaker：$it" }

    fun format(raw: String): String = lines(raw).joinToString("\n")
}
