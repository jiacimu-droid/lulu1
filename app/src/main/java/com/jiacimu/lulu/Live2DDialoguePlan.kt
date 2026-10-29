package com.jiacimu.lulu

import org.json.JSONArray
import org.json.JSONObject

/** One transient performance: words, captions and motion share the same part identity. */
internal object Live2DDialoguePlan {
    data class Part(val id: String, val text: String, val motion: Pair<String, Int>,
        val expression: String? = null, val transition: String = "blend")

    /** Recover speakable words even when optional motion metadata needs correction. */
    fun spokenText(envelope: JSONObject): String? = envelope.optJSONArray("speech")?.let { speech ->
        (0 until speech.length()).joinToString("") {
            speech.optJSONObject(it)?.opt("text")?.takeIf { value -> value is String } as? String ?: ""
        }.takeIf(String::isNotBlank)
    }

    fun visual(envelope: JSONObject): JSONObject? = envelope.optJSONArray("speech")?.let {
        JSONObject().put("speech", it)
    } ?: envelope.optJSONObject("live2d")

    fun parts(visual: JSONObject?): List<Part>? {
        val array = visual?.optJSONArray("speech") ?: return null
        // Preserve the existing 16-beat turn contract; never silently truncate.
        if (array.length() !in 1..16) return null
        val seen = mutableSetOf<String>()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: return null
                val id = item.opt("id") as? String ?: return null
                val text = item.opt("text") as? String ?: return null
                val motion = item.optJSONObject("motion") ?: return null
                val transition = item.optString("transition", "blend")
                if (id.isBlank() || id.length > 80 || !seen.add(id) || text.isBlank() ||
                    transition !in setOf("blend", "continue")) return null
                add(Part(id, text, motion.optString("group") to motion.optInt("index", -1),
                    item.optString("expression").takeIf(String::isNotBlank), transition))
            }
        }
    }

    fun errors(text: String, visual: JSONObject, motions: List<Pair<String, Int>>): List<String> {
        val parts = parts(visual) ?: return listOf("speech 须有1至16个编号唯一、台词非空且带真实 motion 的片段；transition=blend|continue")
        if (parts.joinToString("") { it.text } != text)
            return listOf("speech 拼接结果与最终可朗读正文不一致，不得把旧表演套给改写后的台词")
        return parts.filter { it.motion !in motions }.map { "${it.id} 的 Motion3 不在当前模型目录中" }
    }

    fun beats(parts: List<Part>): JSONArray = JSONArray().also { beats ->
        parts.forEachIndexed { index, part ->
            beats.put(JSONObject().put("atMs", 0).put("segmentIndex", index)
                .put("segmentId", part.id).put("transition", part.transition)
                .put("motion", JSONObject().put("group", part.motion.first).put("index", part.motion.second))
                .also { beat -> part.expression?.let { beat.put("expression", it) } })
        }
    }

    /** Exact equality is an integrity check, never a text search/anchor matcher. */
    fun queuedParts(visual: List<Part>, spoken: String): List<Part>? =
        visual.takeIf { it.isNotEmpty() && it.joinToString("") { part -> part.text } == spoken }
}
