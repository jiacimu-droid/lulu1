package com.jiacimu.lulu.data

/** A shared writing contract, not a keyword-based judge of a character's life. */
internal object WorldLifeNarrativeGuide {
    val instruction = """
        【一篇值得读的小剧场】先依据真实素材选出这一次最值得展开的一件事：人物本来在意什么，实际发生了什么，他如何回应，以及这件事留下了什么结果或未解之处。围绕这一件事写完整，不把行动清单逐项扩写成流水账。
        读者应能从具体行为和已发生的交谈读出人物的个性与关系。关系生疏、熟悉、别扭或有分歧，依据原本关系和真实反应呈现，不统一写成客气点头、保持分寸、互不打扰。不能把人物的目的、回应和后果全压成一句概括，却花大段写点餐、摆手机、拉椅子、喝一口等过场。
        真实说过的一来一往应展开到读者能理解：前一句为什么引来后一句，动作或停顿怎样改变眼前的局面。独处也可围绕真实兴趣、犹豫或具体发现展开。不强求冲突、关系升温、搞笑或人生道理；安静的故事也需要一个具体可理解的关注点。
        篇幅服从这一件事的完整展开，通常可写约700至1200汉字；短素材不必硬凑，复杂交往也不因一个固定字数提前收尾。场景、反应和收束写清后结束，不用泛泛的舒缓、分寸感或风景代替事件的结果，不在结尾向读者解释意义。
        文学呈现不能制造世界事实：对话、对方动作、意外和关系进展只能取自已确认素材；未表达的他人心理不能被代写。缺少故事内容时，不得凭空编一次摔倒、来电、误会或邀约来满足写作要求。
        若素材确实只有走到某处、点餐、坐下等过场，连本人当时的具体关注点也没有依据，你可以自主选择暂不成篇。此时只返回 {"publication":"skip","reason":"依据本次素材作出的具体判断"}，不附正文；真实经历仍保留，不会被删除或记成模型失败。没有字符数或词表替你作这个判断。选择成篇时，按当前入口的正文格式输出，不输出这份写作检查过程。
    """.trimIndent()

    /** Recognize only the model's explicit typed choice; prose length is not a verdict. */
    fun skipReason(raw: String): String? {
        val json = ModelStructuredOutput.objectOrNull(raw) ?: return null
        if (json.optString("publication") != "skip" ||
            json.optString("scene").isNotBlank() || json.optString("text").isNotBlank()) return null
        return json.optString("reason").trim().takeIf(String::isNotBlank)
    }

    fun sourceFacts(events: List<SharedTimelineEvent>): String =
        events.sortedBy { it.occurredAt }.joinToString("\n") { event ->
            "${event.occurredAt} · ${event.speaker}（${event.channel}，证据=${event.id}）：${event.content}"
        }
}
