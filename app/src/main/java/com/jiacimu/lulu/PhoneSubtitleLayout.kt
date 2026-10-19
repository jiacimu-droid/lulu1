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
                if (boundary && (next == null || !closers.contains(next))) emitClause()
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
                val strong = last != null && last in "。！？!?；;”’\"'）)】]"
                val soft = last != null && last in "，,"
                val more = index < clauses.lastIndex
                if (more && current.length >= minReadable &&
                    (current.length >= preferred ||
                        (strong && current.length >= preferred * 0.70) ||
                        (soft && current.length >= preferred * 0.92))) {
                    emitGroup()
                }
            }
            emitGroup()
            if (grouped.size >= 2 && grouped.last().length < minReadable) {
                val lastIndex = grouped.lastIndex
                val preceding = grouped[lastIndex - 1]
                val tail = grouped[lastIndex]
                // Prefer shifting a complete clause from the previous bubble.
                // Merging everything into one 45-55-character bubble hides the
                // character's speaking rhythm and makes each subtitle too long.
                val boundaryChars = "，,。！？!?；;"
                val cut = preceding.indices.reversed().firstOrNull { index ->
                    preceding[index] in boundaryChars &&
                        index + 1 >= minReadable &&
                        preceding.length - index - 1 + tail.length >= minReadable &&
                        preceding.length - index - 1 + tail.length <= preferred + 8
                }
                if (cut != null) {
                    grouped[lastIndex - 1] = preceding.substring(0, cut + 1)
                    grouped[lastIndex] = preceding.substring(cut + 1) + tail
                } else if (preceding.length + tail.length <= preferred + 4) {
                    grouped[lastIndex - 1] = preceding + tail
                    grouped.removeAt(lastIndex)
                }
            }
            result += grouped.map { it.trim() }.filter { it.isNotBlank() }
        }

        raw.split('\n').forEach(::appendParagraph)
        return result
    }

    /**
     * Live phone captions advance one spoken sentence at a time. Completed
     * transcript bubbles remain grouped by lines(), independently of playback.
     * In particular, five short sentences must never become one early caption.
     */
    fun liveLines(raw: String): List<String> {
        val result = mutableListOf<String>()
        val endStops = "。！？!?；;"
        val closers = "”’）)】]"
        raw.lines().forEach { paragraph ->
            val current = StringBuilder()
            fun emit() {
                val line = current.toString().trim()
                if (line.isNotBlank()) result += line
                current.clear()
            }
            paragraph.forEachIndexed { index, ch ->
                current.append(ch)
                val next = paragraph.getOrNull(index + 1)
                when {
                    ch in endStops && (next == null || (next !in closers && next != '"')) -> emit()
                    (ch in closers || ch == '"') && index > 0 &&
                        paragraph[index - 1] in endStops -> emit()
                    ch in "，," && current.length >= 26 -> emit()
                }
            }
            emit()
        }
        return result
    }

    fun captionRows(raw: String, speaker: String): List<String> =
        lines(raw).map { "$speaker：$it" }

    fun format(raw: String): String = lines(raw).joinToString("\n")
}
