package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * Tolerates reasoning prefixes, markdown fences and trailing prose.
 * NEVER reconstructs incomplete JSON tool commands as executable actions.
 */
internal object ModelStructuredOutput {
    fun objectOrNull(raw: String): JSONObject? {
        val clean = raw.trim().removePrefix("\uFEFF")
        if (clean.isBlank()) return null
        runCatching { JSONObject(clean) }.getOrNull()?.let { return it }
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (i in clean.indices) {
            val c = clean[i]
            if (inString) {
                if (escaped) { escaped = false; continue }
                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> if (depth > 0) inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val json = runCatching { JSONObject(clean.substring(start, i + 1)) }.getOrNull()
                        if (json != null) return json
                        start = -1
                    }
                }
            }
        }
        return null
    }

    /**
     * A model may still place a tiny grammatical tail in its own JSON bubble, e.g.
     * ["原来一直在用功，是我", "猜错了"]. That is not a second interactional move.
     * Merge only when the previous bubble has no completed sentence boundary and the next part is
     * a very short continuation. Obvious standalone backchannels stay separate.
     */
    fun stabilizeReplyBubbles(values: List<String>): List<String> {
        val cleaned = values.map { smoothInlineBreaks(it) }.filter(String::isNotBlank)
        if (cleaned.size < 2) return cleaned
        val result = mutableListOf<String>()
        val sentenceEnd = Regex("""[。！？!?…~～”"』」）)]$""")
        val standalone = Regex("""^(?:嗯+|啊+|诶+|欸+|哦+|哈哈+|嘿嘿+|好+|行+|等等|等下|真的[？！!?]?|为什么[？！!?]?)$""")
        val grammarNeedsTail = Regex(
            """(?:是(?:我|你|他|她|它|我们|你们|他们)|我以为|你以为|还以为|原以为|觉得|感觉|本来|其实|只是|但是|可是|因为|所以|如果|要是|虽然|不过|就是|有点|有些|还没|没有|不会|不该|应该|可能|大概|想|要|让|把|被|跟|和|对|给)$"""
        )
        // Extremely obvious mid-word/phrase cuts need merging even when the
        // following segment is a whole sentence, not merely a <=8-char tail.
        val brokenLexicalBoundary = listOf(
            "说" to "句", "揉" to "揉", "气" to "消",
            "耳" to "朵", "心" to "跳", "亲" to "亲",
            "不" to "好", "可" to "以",
        )
        cleaned.forEach { current ->
            if (result.isEmpty()) {
                result += current
                return@forEach
            }
            val previous = result.last()
            val compact = current.replace(Regex("\\s+"), "")
            val incompletePrevious = !sentenceEnd.containsMatchIn(previous.trim())
            val shortContinuation = compact.length <= 8 && !standalone.matches(compact)
            val previousNeedsContinuation = grammarNeedsTail.containsMatchIn(previous.trim())
            val clippedWord = brokenLexicalBoundary.any { (before, after) ->
                previous.trimEnd().endsWith(before) && current.trimStart().startsWith(after)
            }
            if (incompletePrevious && (clippedWord || shortContinuation && previousNeedsContinuation)) {
                val needsSpace = previous.lastOrNull()?.isLetterOrDigit() == true &&
                    current.firstOrNull()?.isLetterOrDigit() == true &&
                    previous.last().code < 128 && current.first().code < 128
                result[result.lastIndex] = previous + (if (needsSpace) " " else "") + current
            } else {
                result += current
            }
        }
        return result
    }

    /**
     * Model linebreaks are text formatting, not separate send requests.
     * Remove a SINGLE accidental newline only inside an uninterrupted Chinese
     * phrase; keep paragraphs and lines that follow normal sentence endings.
     */
    fun smoothInlineBreaks(text: String): String = text.replace("\r\n", "\n").trim()
        .replace(Regex("""(?<=\p{IsHan})[ \t]*\n[ \t]*(?=\p{IsHan})"""), "")

    /**
     * Readability happens at the outgoing message boundary, NOT by cutting
     * Chinese strings every N characters. An unusually long bubble with
     * complete sentences becomes a few easy-to-read messages, while a tiny
     * backchannel or a single unfinished sentence stays intact.
     *
     * A model-selected bubble may contain several complete sentences; this
     * changes only the send pacing, never the character's decided content.
     */
    fun readableReplyBubbles(values: List<String>, maxBubbles: Int = 8): List<String> {
        val stable = stabilizeReplyBubbles(values)
        val pieces = mutableListOf<String>()
        stable.forEach { bubble ->
            // Never split technical payloads or already short human responses.
            if (bubble.length <= 38 || bubble.startsWith("[") || bubble.startsWith("⟪")) {
                pieces += bubble
                return@forEach
            }
            val clauses = mutableListOf<String>()
            var from = 0
            bubble.forEachIndexed { index, char ->
                val completeSentence = char in "。！？!?；;"
                // A very long comma-delimited sentence can be divided at a
                // legitimate Chinese clause boundary, never at a character count.
                val longCommaClause = char in "，," && index - from >= 38
                if (completeSentence || longCommaClause) {
                    val part = bubble.substring(from, index + 1).trim()
                    if (part.isNotBlank()) clauses += part
                    from = index + 1
                }
            }
            bubble.substring(from).trim().takeIf(String::isNotBlank)?.let(clauses::add)
            if (clauses.size < 2) {
                pieces += bubble
                return@forEach
            }
            var current = ""
            clauses.forEach { clause ->
                // A complete conversational move should not become one
                // giant IM bubble merely because two short sentences fit
                // under the old 52-character grouping target.
                // Respect sentence boundaries, never cut a mid-word fragment.
                // A short complete reaction followed by a substantial new
                // sentence is two natural speaking moves. The old >=14 test
                // glued them into one oversized IM bubble (e.g. a concise
                // clarification followed by an entire phone-call recap).
                // No character count may cut a sentence mid-phrase.
                val independentFollowUp = current.length >= 8 &&
                    (current.lastOrNull()?.let { it in "。！？!?；;" } == true) &&
                    clause.length >= 20
                if (current.isNotBlank() && (independentFollowUp ||
                    (current.length >= 14 && current.length + clause.length > 43))) {
                    pieces += current
                    current = clause
                } else current += clause
            }
            if (current.isNotBlank()) {
                // A tiny dependent tail belongs to the preceding thought.
                if (pieces.isNotEmpty() && current.length < 9 &&
                    pieces.last().length < 70) {
                    pieces[pieces.lastIndex] += current
                } else pieces += current
            }
        }
        // Preserve ALL spoken text, even when a model ignores requested slots.
        // Keep visual slots independent of the planning dialogue-move count.
        val limit = maxBubbles.coerceAtLeast(1)
        if (pieces.size <= limit) return pieces
        return pieces.take(limit - 1) + pieces.drop(limit - 1).joinToString("")
    }

    /** Optional per-bubble voice decision. The text is the authoritative transcript. */
    fun voiceBubbleTexts(raw: String): List<String> {
        val json = objectOrNull(raw) ?: return emptyList()
        if (json.optString("action", "reply").lowercase() !in setOf("reply", "message", "chat", "group_message")) return emptyList()
        val bubbles = json.optJSONArray("bubbles") ?: return emptyList()
        return (0 until minOf(bubbles.length(), 8)).mapNotNull { index ->
            bubbles.optJSONObject(index)?.takeIf { it.optString("delivery").equals("voice", true) }
                ?.optString("text")?.trim()?.takeIf(String::isNotBlank)
        }
    }

    /** Keep voice performances atomic, while ordinary text keeps the usual readable spacing. */
    fun voiceAwareReplyBubbles(raw: String): List<String>? {
        val json = objectOrNull(raw) ?: return null
        if (json.optString("action", "reply").lowercase() != "reply") return null
        val bubbles = json.optJSONArray("bubbles") ?: return null
        val result = mutableListOf<String>()
        for (i in 0 until minOf(bubbles.length(), 8)) {
            val entry = bubbles.opt(i)
            val value = when (entry) {
                is JSONObject -> entry.optString("text")
                else -> bubbles.optString(i)
            }.trim().take(2_000)
            if (value.isBlank()) continue
            if (entry is JSONObject && entry.optString("delivery").equals("voice", true)) result += value
            else result += readableReplyBubbles(listOf(value), maxBubbles = Int.MAX_VALUE)
        }
        return result.takeIf { it.isNotEmpty() }
    }

    /**
     * Preferred text-chat protocol: bubbles are structural JSON, never magic strings inside text.
     * Supports either ["text"] or [{"text":"..."}] during migration.
     */
    fun completedReplyBubbles(raw: String): List<String>? {
        val action = Regex(""""action"\s*:\s*"([^"]+)"""").find(raw)
            ?.groupValues?.getOrNull(1)?.lowercase()
        if (action != null && action != "reply") return null
        val json = objectOrNull(raw) ?: return null
        if (json.optString("action", "reply").lowercase() != "reply") return null

        val bubbles = json.optJSONArray("bubbles")
        if (bubbles != null) {
            val values = buildList {
                for (i in 0 until bubbles.length()) {
                    val item = bubbles.opt(i)
                    val text = when (item) {
                        is JSONObject -> item.optString("text")
                        else -> bubbles.optString(i)
                    }.replace("\r\n", "\n").trim()
                    if (text.isNotBlank()) add(text.take(2_000))
                }
            }.take(8)
            // The model owns the conversational moves. The renderer may split a
            // long move at complete sentence/clause punctuation for readability,
            // but must never cut an unfinished word or discard the spoken text.
            if (values.isNotEmpty()) return readableReplyBubbles(values, maxBubbles = 8)
        }

        return smoothInlineBreaks(json.optString("text"))
            .takeIf(String::isNotBlank)?.let(::listOf)
    }

    /**
     * A retry can finish its speech bubbles but run out of tokens while writing optional
     * inner state. Recover ONLY a fully closed JSON speech array; no partial tools or
     * half-written words are accepted. The missing mental fields remain absent.
     */
    fun replyBubblesWithPartialState(raw: String): List<String>? {
        completedReplyBubbles(raw)?.let { return it }
        val action = Regex(""""action"\s*:\s*"([^"]+)"""").find(raw)
            ?.groupValues?.getOrNull(1)?.lowercase()
        if (action != null && action != "reply") return null
        val marker = Regex("\"bubbles\"\\s*:\\s*\\[").find(raw)
        if (marker != null) {
            val start = raw.indexOf('[', marker.range.first)
            var inString = false
            var escaped = false
            var depth = 0
            for (i in start until raw.length) {
                when {
                    inString && escaped -> escaped = false
                    inString && raw[i] == '\\' -> escaped = true
                    inString && raw[i] == '"' -> inString = false
                    raw[i] == '"' -> inString = true
                    raw[i] == '[' -> depth++
                    raw[i] == ']' -> {
                        depth--
                        if (depth == 0) {
                            val array = runCatching {
                                org.json.JSONArray(raw.substring(start, i + 1))
                            }.getOrNull() ?: return null
                            return (0 until array.length()).mapNotNull { index ->
                                val value = array.opt(index)
                                val bubble = if (value is JSONObject) value.optString("text")
                                    else array.optString(index)
                                bubble.trim().takeIf(String::isNotBlank)?.take(2_000)
                            }.let { readableReplyBubbles(it, maxBubbles = 8) }.takeIf { it.isNotEmpty() }
                        }
                    }
                }
            }
            return null
        }
        // An entirely completed "text" field is still a valid one-bubble reply.
        return completedReplyText(raw)?.let(::listOf)
    }

    /**
     * Recover only a *completed* speech text field from a truncated chat envelope.
     * Invalid command parameters are never executed or marked successful.
     */
    fun completedReplyText(raw: String): String? {
        // A half-written tool instruction is never a completed user-visible reply.
        val action = Regex(""""action"\s*:\s*"([^"]+)"""").find(raw)
            ?.groupValues?.getOrNull(1)?.lowercase()
        if (action != null && action != "reply") return null
        completedReplyBubbles(raw)?.let { bubbles ->
            return bubbles.joinToString("\n").takeIf(String::isNotBlank)
        }
        // This only accepts a completely closed text string; the rest of the
        // optional mood/innerLife JSON may be truncated by token limits.
        val found = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw) ?: return null
        return runCatching {
            JSONObject("{\"text\":\"" + found.groupValues[1] + "\"}")
                .optString("text").takeIf(String::isNotBlank)
        }.getOrNull()
    }
}
