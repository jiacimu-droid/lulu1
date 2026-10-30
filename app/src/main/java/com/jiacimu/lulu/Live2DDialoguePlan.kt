package com.jiacimu.lulu

import org.json.JSONArray
import org.json.JSONObject

/** One transient performance: words, captions and motion share the same part identity. */
internal object Live2DDialoguePlan {
    data class FollowUp(val motion: Pair<String, Int>, val transition: String = "blend")
    data class Part(val id: String, val text: String, val motion: Pair<String, Int>,
        val expression: String? = null, val transition: String = "blend",
        val followUps: List<FollowUp> = emptyList())

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
                // Optional subsequent clips are selected by the SAME character
                // for this one uninterrupted TTS fragment. The renderer starts
                // the next clip only when the previous real Motion3 finishes.
                val followUps = item.optJSONArray("followUps")?.let { extras ->
                    if (extras.length() !in 1..5) return null
                    buildList {
                        for (j in 0 until extras.length()) {
                            val extra = extras.optJSONObject(j) ?: return null
                            val selected = extra.optJSONObject("motion") ?: return null
                            val mode = extra.optString("transition", "blend")
                            if (mode !in setOf("blend", "continue")) return null
                            add(FollowUp(selected.optString("group") to
                                selected.optInt("index", -1), mode))
                        }
                    }
                }.orEmpty()
                add(Part(id, text, motion.optString("group") to motion.optInt("index", -1),
                    item.optString("expression").takeIf(String::isNotBlank), transition, followUps))
            }
        }
    }

    fun errors(text: String, visual: JSONObject, motions: List<Pair<String, Int>>): List<String> {
        val parts = parts(visual) ?: return listOf("speech 须有1至16个编号唯一、台词非空且带真实 motion 的片段；transition=blend|continue")
        if (parts.joinToString("") { it.text } != text)
            return listOf("speech 拼接结果与最终可朗读正文不一致，不得把旧表演套给改写后的台词")
        return parts.flatMap { part ->
            buildList {
                if (part.motion !in motions) add("${part.id} 的 Motion3 不在当前模型目录中")
                part.followUps.forEachIndexed { index, extra ->
                    if (extra.motion !in motions)
                        add("${part.id} 的后续动作${index + 1} 不在当前模型目录中")
                }
            }
        }
    }

    fun beats(parts: List<Part>): JSONArray = JSONArray().also { beats ->
        parts.forEachIndexed { index, part ->
            beats.put(JSONObject().put("atMs", 0).put("segmentIndex", index)
                .put("segmentId", part.id).put("transition", part.transition)
                .put("motion", JSONObject().put("group", part.motion.first).put("index", part.motion.second))
                .also { beat ->
                    part.expression?.let { beat.put("expression", it) }
                    if (part.followUps.isNotEmpty()) beat.put("followUps", JSONArray().also { sequence ->
                        part.followUps.forEach { extra ->
                            sequence.put(JSONObject()
                                .put("motion", JSONObject().put("group", extra.motion.first)
                                    .put("index", extra.motion.second))
                                .put("transition", extra.transition))
                        }
                    })
                })
        }
    }

    /** Exact equality is an integrity check, never a text search/anchor matcher. */
    fun queuedParts(visual: List<Part>, spoken: String): List<Part>? =
        visual.takeIf { it.isNotEmpty() && it.joinToString("") { part -> part.text } == spoken }
}
