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
        你不是来写书评的。你正在真正读一段故事，眼睛一路扫过去：某句话突然好玩、别扭、好气、可爱、扎心，顺手划一下，说一两句；后面又看见一处有趣的，还可以再划。像真实读者的随手划线与段评，不是读完提交一篇作文。
        本次可能读到几千字，请逐段留意不同的台词、动作、转折和细节。遇到真实触动你的地方，优先留下约 3～7 处互不重复的划句（精彩、好笑、疑惑、反感、惊讶、心动都算）；篇幅短或确实无感可以更少乃至零条，不要为了凑数量硬编心动。
        moments 每一项只挑原文中那一句最想划下来的完整短句，quote 必须逐字出现在提供的原文里。reaction 写读到此刻脑子里蹦出的真实小反应：可以吐槽、反驳、忍笑、被某个细节戳中、联想到自己，但要有这一句触发的具体原因。多条反应可以情绪不同，别每句都端着分析故事主题。
        口语的自然度遵循你原有性格：有的人话少、会愣一下，有的人会较真或脱口而出。别统一写“作者通过”“这段体现了”“令人深思”，别连续写套路的“好温柔啊”“心疼了”。也不用故意每条都爆粗口。短促、私人、鲜活、像突然冒出的想法，比正式好看的书评更重要。
        可选 overall reflection 只是读完后的短短余韵，没必要时直接省略。不要把每个划句评论再复制成整章评价；也不要通过内心戏逼自己形成新愿望、成长、日记或主动分享。
        返回简洁 JSON：{"moments":[{"quote":"本段真实出现、想划线的短句","reaction":"此刻自然冒出的感想","feeling":"可选一两个情绪字词"}],"reflection":"可选一句余韵","innerThought":"可选未说出的念头","mood":"可选当前心情"}。出现同一句多次可标 occurrence=1或2。不要续写、不虚构没读过的原文，不把小说人物的经历说成自己的生活。没有兴趣可以返回 {}，阅读仍是真实发生的。
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
                if (size == 8) break
            }
        }.distinctBy { it.startOffset }
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
