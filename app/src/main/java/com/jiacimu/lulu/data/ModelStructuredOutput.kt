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
        val cleaned = values.map { it.replace("\r\n", "\n").trim() }.filter(String::isNotBlank)
        if (cleaned.size < 2) return cleaned
        val result = mutableListOf<String>()
        val sentenceEnd = Regex("""[。！？!?…~～”"』」）)]$""")
        val standalone = Regex("""^(?:嗯+|啊+|诶+|欸+|哦+|哈哈+|嘿嘿+|好+|行+|等等|等下|真的[？！!?]?|为什么[？！!?]?)$""")
        cleaned.forEach { current ->
            if (result.isEmpty()) {
                result += current
                return@forEach
            }
            val previous = result.last()
            val compact = current.replace(Regex("\\s+"), "")
            val incompletePrevious = !sentenceEnd.containsMatchIn(previous.trim())
            val shortContinuation = compact.length <= 8 && !standalone.matches(compact)
            if (incompletePrevious && shortContinuation) {
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
            if (values.isNotEmpty()) return stabilizeReplyBubbles(values).take(3)
        }

        return json.optString("text").replace("\r\n", "\n").trim()
            .takeIf(String::isNotBlank)?.let(::listOf)
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
