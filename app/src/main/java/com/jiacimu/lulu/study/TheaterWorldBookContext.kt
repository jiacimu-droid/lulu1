package com.jiacimu.lulu.study

import com.jiacimu.lulu.core.WorldBookEntry

/** Explicit story selections are independent of character/global chat switches. */
internal data class TheaterWorldBookContext(val rules: List<Rule>) {
    internal data class Rule(val id: String, val title: String, val content: String)

    fun promptText(): String = if (rules.isEmpty()) "" else buildString {
        appendLine("以下为本书明确选用的最新世界书。未发生剧情以这些规则为准，旧总纲、旧幕后规划和旧章节规划冲突的部分必须调整；已经写出的事实不可无解释改写，必要变化须在新剧情中给出因果。")
        rules.forEach { appendLine("- ${it.title}：${it.content}") }
    }.trim()

    fun requireUnchanged(ids: Set<String>, entries: List<WorldBookEntry>) {
        check(this == capture(ids, entries)) { "世界书已修改或删除，本次旧结果未保存。请重新生成。" }
    }

    companion object {
        fun capture(ids: Set<String>, entries: List<WorldBookEntry>) = TheaterWorldBookContext(
            entries.filter { it.id in ids }.sortedBy { it.id }
                .map { Rule(it.id, it.title, it.content) },
        )
    }
}
