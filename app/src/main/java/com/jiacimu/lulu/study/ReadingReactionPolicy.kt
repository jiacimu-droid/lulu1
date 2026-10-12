package com.jiacimu.lulu.study

import org.json.JSONArray
import org.json.JSONObject

data class ReadingMoment(
    val quote: String, val reaction: String, val startOffset: Int, val endOffset: Int,
    val feeling: String = "",
)

internal data class ReadingReaction(
    val reflection: String, val moments: List<ReadingMoment>,
    val innerThought: String, val mood: String,
) {
    fun brief(): String = moments.firstOrNull()?.reaction ?: reflection
}

/** Exact source anchors only. Silence is a valid response; invented quotes are not saved. */
internal object ReadingReactionPolicy {
    fun responseObject(raw: String): JSONObject? {
        com.jiacimu.lulu.data.ModelStructuredOutput.objectOrNull(raw)?.let { return it }
        val text = raw.trim()
        // An ordinary short exclamation need not be wrapped in a book-review schema.
        if (text.length in 1..600 && text.none { it in "{}[]" } &&
            !text.contains("```") && !text.contains("<think", ignoreCase = true))
            return JSONObject().put("reflection", text)
        return null
    }
    val instruction = """
        你是在自己看书消遣，可以被某一小段打中，也可以没感觉；阅读不是交书评作业。
        跟随这次真正读到的文字产生反应，不用概括整章，不套“作者通过…展现…”或列分析要点。
        有时一句“卧槽”“这也行？”“神了”或停顿就够了；是否这样说由你的个性和具体情节决定，不强迫所有角色都惊叹或说粗口。
        可以只对一处台词/转折吐槽、笑、困惑、心疼或沉默；认真思考也可以，但要像你自己，允许改观、嫌弃、无感。
        返回简短JSON，字段都可省略：{"moments":[{"quote":"本段真正出现的原话","reaction":"读到这里的即时反应","feeling":"可选的感受"}],"reflection":"可选的余韵/一句话","innerThought":"可选尚未说出的念头","mood":"可选当下心情"}。
        moments可为空，每个quote必须逐字来自本次原文；同一句重复时可填写occurrence=1或2指定第几处。没有特别想说的返回{}也可以，照样完成真实阅读。
        不续写后文，不替人物补动机，不把小说情节当成你的生活；不能自称已经学会方法。不需要每段形成新心声、学习目标、愿望或公开分享。
    """.trimIndent()

    fun parse(response: JSONObject, text: String, baseOffset: Int): ReadingReaction {
        val array = response.optJSONArray("moments") ?: JSONArray()
        val moments = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val quote = item.optString("quote").trim()
                val reaction = item.optString("reaction").trim().take(400)
                if (quote.length !in 2..240 || reaction.isBlank()) continue
                val occurrence = item.optInt("occurrence", 1)
                if (occurrence !in 1..20) continue
                var offset = -1
                repeat(occurrence) {
                    if (it == 0 || offset >= 0) offset = text.indexOf(quote, offset + 1)
                }
                if (offset < 0) continue
                add(ReadingMoment(quote, reaction, baseOffset + offset,
                    baseOffset + offset + quote.length, item.optString("feeling").trim().take(80)))
                if (size == 4) break
            }
        }.distinctBy { it.startOffset to it.reaction }
        return ReadingReaction(response.optString("reflection").trim().take(1_200), moments,
            response.optString("innerThought").trim().take(220), response.optString("mood").trim().take(80))
    }

    fun encodeMoments(moments: List<ReadingMoment>): JSONArray = JSONArray().apply {
        moments.forEach { put(JSONObject().put("quote", it.quote).put("reaction", it.reaction)
            .put("startOffset", it.startOffset).put("endOffset", it.endOffset).put("feeling", it.feeling)) }
    }
    fun decodeMoments(array: JSONArray?): List<ReadingMoment> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { index -> runCatching {
            val item = array.getJSONObject(index)
            ReadingMoment(item.getString("quote"), item.getString("reaction"),
                item.getInt("startOffset"), item.getInt("endOffset"), item.optString("feeling"))
        }.getOrNull() }
}
