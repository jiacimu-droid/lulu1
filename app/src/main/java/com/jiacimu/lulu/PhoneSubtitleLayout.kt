package com.jiacimu.lulu

/**
 * Group nearby natural clauses into readable caption bubbles.
 *
 * Commas mark possible breath pauses, NOT compulsory bubbles; very short
 * sentences (including "嗯。" or "宝宝，") are normally grouped with their
 * neighbor. Never alter the original words passed to TTS or persisted chat.
 */
internal object PhoneSubtitleLayout {
    fun lines(raw: String, maxCharacters: Int = 38): List<String> {
        if (raw.isBlank()) return emptyList()
        val preferred = maxCharacters.coerceIn(24, 70)
        val minReadable = (preferred * 0.46).toInt().coerceIn(12, 22)
        val result = mutableListOf<String>()

        fun appendParagraph(paragraph: String) {
            if (paragraph.isBlank()) return
            val clauses = mutableListOf<String>()
            val piece = StringBuilder()
            val closers = "”’\"'）)】]"
            fun emitClause() {
                if (piece.isNotEmpty()) {
                    clauses += piece.toString()
                    piece.clear()
                }
            }
            paragraph.forEachIndexed { index, ch ->
                piece.append(ch)
                val boundary = ch in "。！？!?；;，,"
                val next = paragraph.getOrNull(index + 1)
                if (boundary && next !in closers) emitClause()
                else if (ch in closers && index > 0 && paragraph[index - 1] in "。！？!?；;，,") emitClause()
            }
            emitClause()
            if (clauses.isEmpty()) return

            val grouped = mutableListOf<String>()
            val current = StringBuilder()
            fun emitGroup() {
                if (current.isNotEmpty()) {
                    grouped += current.toString()
                    current.clear()
                }
            }
            clauses.forEachIndexed { index, clause ->
                // A long unpunctuated sentence stays intact. No arbitrary
                // character slicing, which would change playback pacing.
                if (current.length >= minReadable && current.length + clause.length > preferred + 8) emitGroup()
                current.append(clause)
                val last = current.lastOrNull()
                val strong = last in "。！？!?；;”’\"'）)】]"
                val soft = last in "，,"
                val more = index < clauses.lastIndex
                if (more && current.length >= minReadable &&
                    (current.length >= preferred ||
                        (strong && current.length >= preferred * 0.70) ||
                        (soft && current.length >= preferred * 0.92))) {
                    emitGroup()
                }
            }
            emitGroup()
            if (grouped.size >= 2 && grouped.last().length < minReadable &&
                grouped[grouped.lastIndex - 1].length + grouped.last().length <= preferred + 16) {
                val tail = grouped.removeAt(grouped.lastIndex)
                grouped[grouped.lastIndex] += tail
            }
            result += grouped.map(String::trim).filter(String::isNotBlank)
        }

        raw.split('\n').forEach(::appendParagraph)
        return result
    }

    fun captionRows(raw: String, speaker: String): List<String> =
        lines(raw).map { "$speaker：$it" }

    fun format(raw: String): String = lines(raw).joinToString("\n")
}
