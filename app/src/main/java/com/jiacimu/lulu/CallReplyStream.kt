package com.jiacimu.lulu

/** Reads only the reply text string of the ordered envelope; tools and private fields never speak. */
internal class CallReplyStream {
    private var emitted = 0
    private var snapshot = ""
    private var finished = false
    val isFinished: Boolean get() = finished
    fun cancel() { finished = true }

    fun update(envelope: String): List<String> {
        if (finished) return emptyList()
        val text = replyTextPrefix(envelope) ?: return emptyList()
        if (!text.startsWith(snapshot)) return emptyList()
        snapshot = text
        return drain(final = false)
    }

    /** Keep subtitle streaming, but do not split the emotional context sent to expressive TTS. */
    fun updateForSpeech(envelope: String, wholeTurn: Boolean): List<String> =
        if (wholeTurn) emptyList() else update(envelope)

    /** A whole-turn performance is one TTS request; legacy models retain incremental chunks. */
    fun finishForSpeech(text: String, wholeTurn: Boolean): List<String> {
        val chunks = finish(text)
        return if (wholeTurn) listOf(text) else chunks
    }

    fun finish(text: String): List<String> {
        check(!text.trimStart().startsWith("{") && !text.trimStart().startsWith("```")) {
            "模型未返回可朗读正文，内部内容未播放"
        }
        check(text.startsWith(snapshot.take(emitted))) { "流式回复与最终内容不一致，请重试" }
        snapshot = text; finished = true
        return drain(final = true)
    }

    private fun drain(final: Boolean): List<String> {
        val chunks = mutableListOf<String>()
        var start = emitted
        var inTag = false
        for (i in start until snapshot.length) {
            if (snapshot[i] == '[') inTag = true
            if (inTag) {
                if (snapshot[i] == ']') inTag = false
                continue
            }
            val count = i - start + 1
            val boundary = snapshot[i] in "。！？!?；;\n" ||
                (snapshot[i] in "，, " && count >= 70)
            if (boundary && count >= 4) {
                chunks.add(snapshot.substring(start, i + 1)); start = i + 1
            }
        }
        if (final && start < snapshot.length) { chunks.add(snapshot.substring(start)); start = snapshot.length }
        emitted = start
        return chunks
    }

    companion object {
        private val opening = Regex("^\\s*\\{\\s*\"action\"\\s*:\\s*\"reply\"\\s*,\\s*\"text\"\\s*:\\s*\"")
        fun replyTextPrefix(envelope: String): String? = decodeReplyText(envelope, requireComplete = false)

        /** A closed spoken field is usable even if later optional metadata is truncated. */
        fun completeReplyText(envelope: String): String? {
            val clean = envelope.trim().removePrefix("```json").removePrefix("```").trimStart()
            return decodeReplyText(clean, requireComplete = true)
        }

        private fun decodeReplyText(envelope: String, requireComplete: Boolean): String? {
            val match = opening.find(envelope) ?: return null
            val out = StringBuilder()
            var i = match.range.last + 1
            while (i < envelope.length) {
                val c = envelope[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= envelope.length) break
                        when (val escape = envelope[i++]) {
                            '"', '\\', '/' -> out.append(escape)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b', 'f' -> out.append(' ')
                            'u' -> {
                                if (i + 4 > envelope.length) break
                                val code = envelope.substring(i, i + 4).toIntOrNull(16) ?: return null
                                out.append(code.toChar()); i += 4
                            }
                            else -> return null
                        }
                    }
                    c.code < 32 -> return null
                    else -> out.append(c)
                }
            }
            if (requireComplete) return null
            // An incomplete Unicode surrogate must not be spoken by itself.
            if (out.isNotEmpty() && out.last().isHighSurrogate()) out.setLength(out.length - 1)
            return out.toString()
        }
    }
}
