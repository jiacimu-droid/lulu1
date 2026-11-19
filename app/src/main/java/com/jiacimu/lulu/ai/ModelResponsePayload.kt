package com.jiacimu.lulu.ai

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener


/** Diagnose a billed-but-unreadable reply from transport metadata only.
 * Never include private reasoning, prompt text, refusal details or provider raw bodies. */
internal fun modelEmptyOutputDiagnostic(
    source: String,
    finishReason: String?,
    outputTokens: Int?,
    reasoningTokens: Int?,
    reasoningFieldSeen: Boolean,
    refusalFieldSeen: Boolean = false,
    transportState: String = "",
): String {
    // Relay-provided stop reasons are untrusted: keep only short protocol identifiers.
    val reason = finishReason?.trim()?.takeIf {
        it.matches(Regex("[A-Za-z0-9_.-]{1,64}"))
    }
    val category = when (reason?.lowercase()) {
        "content_filter", "content_blocked", "prohibited_content", "safety", "blocked" ->
            "上游标记内容过滤，正文可能被拦截"
        "length", "max_tokens", "max_output_tokens" ->
            "输出达到长度上限，尚无可显示正文"
        else -> when {
            refusalFieldSeen -> "上游返回拒绝标记，但没有可显示正文"
            reasoningFieldSeen || (reasoningTokens ?: 0) > 0 ->
                "有思考输出记录，但没有可显示正文"
            else -> "上游未提供可显示正文，或响应格式未被识别"
        }
    }
    val metadata = listOf(
        "来源=" + source,
        "原因=" + category,
        "finish_reason=" + (reason ?: "未提供"),
        "正文字符=0",
        "输出Token=" + (outputTokens?.toString() ?: "未提供"),
        "思考Token=" + (reasoningTokens?.toString() ?: "未提供"),
        "收到思考字段=" + if (reasoningFieldSeen) "是" else "否",
    ) + listOfNotNull(transportState.takeIf(String::isNotBlank))
    return "模型没有返回可显示的正文（" + metadata.joinToString("；") +
        "）。输出Token不等于正文Token；本次可能已计费，未自动重新请求"
}

/** Text-bearing content blocks only. Thinking/reasoning blocks may also have
 * a `text` property; their transport shape does not make them public speech. */
internal fun visibleModelText(value: Any?): String = when (value) {
    is String -> value
    is JSONArray -> (0 until value.length()).joinToString("") { visibleModelText(value.opt(it)) }
    is JSONObject -> if (value.optString("type").let { it.isNotBlank() &&
            it !in setOf("text", "output_text", "message") }) ""
        else sequenceOf("text", "content", "output_text")
            .map { visibleModelText(value.opt(it)) }.firstOrNull(String::isNotBlank).orEmpty()
    else -> ""
}

/** Some compatible relays send SSE even when a normal JSON response was requested. */
internal fun modelResponseObject(raw: String): JSONObject {
    val clean = raw.trim().removePrefix("\uFEFF")
    if (Regex("(?m)^data:").containsMatchIn(clean)) {
        val text = StringBuilder()
        var usage = JSONObject()
        var finalObject: JSONObject? = null
        val pending = StringBuilder()
        fun consume(payload: String): Boolean {
            if (payload.trim() == "[DONE]") return true
            val chunk = runCatching { JSONTokener(payload).nextValue() as? JSONObject }.getOrNull() ?: return false
            if (chunk.has("error")) error(chunk.optJSONObject("error")?.optString("message")
                .orEmpty().ifBlank { "模型接口返回流式错误" })
            chunk.optJSONObject("usage")?.let { incoming -> incoming.keys().forEach { key -> usage.put(key, incoming.opt(key)) } }
            val choice = chunk.optJSONArray("choices")?.optJSONObject(0)
            val delta = choice?.optJSONObject("delta")
            if (delta != null) {
                text.append(visibleModelText(delta.opt("content")))
            } else if (chunk.optString("type") == "content_block_delta") {
                val block = chunk.optJSONObject("delta")
                if (block?.optString("type") == "text_delta") text.append(block.optString("text"))
            } else if (chunk.optString("type") == "message_start") {
                chunk.optJSONObject("message")?.optJSONObject("usage")?.let { incoming ->
                    incoming.keys().forEach { key -> usage.put(key, incoming.opt(key)) }
                }
            } else if (choice?.optJSONObject("message") != null || choice?.has("text") == true || chunk.has("output_text") || chunk.has("content")) {
                finalObject = chunk
            }
            return true
        }
        clean.lineSequence().forEach { line ->
            if (line.startsWith("data:")) {
                val part = line.removePrefix("data:").trimStart()
                if (pending.isNotEmpty()) pending.append('\n')
                pending.append(part)
                if (consume(pending.toString())) pending.clear()
            } else if (line.isBlank() && pending.isNotEmpty()) {
                check(consume(pending.toString())) { "接口返回的流式片段不完整，请重试" }
                pending.clear()
            }
        }
        check(pending.isEmpty() || consume(pending.toString())) { "接口返回的流式片段不完整，请重试" }
        // Provider reasoning is private transport metadata, never a substitute
        // for authored speech or a structured action decision.
        if (text.isEmpty()) return finalObject ?: error("模型流式响应没有返回正文")
        val spoken = text.toString()
        val message = JSONObject().put("content", spoken)
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message)))
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", spoken))).put("usage", usage)
    }
    var value: Any? = runCatching { JSONTokener(clean).nextValue() }.getOrNull()
    repeat(3) {
        if (value is String) value = runCatching { JSONTokener(value as String).nextValue() }.getOrNull()
    }
    return when (value) {
        is JSONObject -> value as JSONObject
        is JSONArray -> JSONObject().put("data", value)
        else -> error("接口返回的内容不是可读取的 JSON 或流式响应，请检查模型接口配置")
    }
}
