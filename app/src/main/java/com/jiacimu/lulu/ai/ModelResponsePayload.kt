package com.jiacimu.lulu.ai

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Some compatible relays send SSE even when a normal JSON response was requested. */
internal fun modelResponseObject(raw: String): JSONObject {
    val clean = raw.trim().removePrefix("\uFEFF")
    if (Regex("(?m)^data:").containsMatchIn(clean)) {
        val text = StringBuilder()
        val reasoning = StringBuilder()
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
            fun content(value: Any?): String = when (value) {
                is String -> value
                is JSONArray -> (0 until value.length()).joinToString("") { content(value.opt(it)) }
                is JSONObject -> content(value.opt("text")).ifBlank { content(value.opt("content")) }
                else -> ""
            }
            if (delta != null) {
                text.append(content(delta.opt("content")))
                reasoning.append(content(delta.opt("reasoning_content")))
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
        if (text.isEmpty() && reasoning.isEmpty()) return finalObject ?: error("模型流式响应没有返回正文")
        val spoken = text.toString().ifBlank { reasoning.toString() }
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
