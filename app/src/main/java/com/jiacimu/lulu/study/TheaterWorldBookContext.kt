package com.jiacimu.lulu.study

import com.jiacimu.lulu.core.WorldBookEntry

/** Explicit story selections are independent of character/global chat switches. */
internal data class TheaterWorldBookContext(val rules: List<Rule>) {
    internal data class Rule(val id: String, val title: String, val content: String)

    fun promptText(): String = if (rules.isEmpty()) "" else buildString {
        appendLine("以下为本书明确选用的最新世界书。未发生剧情以这些规则为准，旧总纲、旧幕后规划和旧章节规划冲突的部分必须调整；已经写出的事实不可无解释改写，必要变化须在新剧情中给出因果。")
        rules.forEach { appendLine("- ${it.title}：${it.content}") }
    }.trim()

    /** Single-story world rules are fixed at creation, not synced with later edits.
     * They guide the fiction only and never become a character's actual memory.
     */
    fun standalonePromptText(): String = rules.joinToString("\n") { "${it.title}：${it.content}" }

    fun requireUnchanged(ids: Set<String>, entries: List<WorldBookEntry>) {
        check(this == capture(ids, entries)) { "世界书已修改或删除，本次旧结果未保存。请重新生成。" }
    }

    companion object {
        /** Only strip the historical app-generated preface; keep every authored rule unchanged. */
        private val legacyStandalonePreface = listOf(
            "【本篇明确选中的世界书｜仅本篇虚构设定】",
            "以下条目作为本篇的环境、社会与故事规律，不代表角色在原世界的真实经历。",
            "保留本篇选定的原世界或平行世界模式及角色的稳定人格；遇到冲突，依用户本篇明确要求来安排剧情，不要擅自把另一个世界的身份和关系搬来。",
        ).joinToString("\n")

        fun storyFactsFromSnapshot(snapshot: String): String =
            snapshot.trim().removePrefix(legacyStandalonePreface).trim()

        fun capture(ids: Set<String>, entries: List<WorldBookEntry>) = TheaterWorldBookContext(
            entries.filter { it.id in ids }.sortedBy { it.id }
                .map { Rule(it.id, it.title, it.content) },
        )
    }
}
