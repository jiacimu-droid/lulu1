package com.jiacimu.lulu.study

/**
 * Human-readable export for readers who want to discuss story continuity.
 * The export intentionally omits the internal story bible, planning sheets,
 * ledger, world-book rules and any other private application data.
 */
internal object StarWishTheaterStoryExport {
    fun render(
        title: String,
        chapters: List<StarWishTheaterChapter>,
        storyGuide: String,
        bible: StarWishStoryBible?,
        seedIntro: String = "",
    ): String {
        val overview = bible?.overview.orEmpty().trim().ifBlank {
            section(storyGuide, "故事核心", "故事总纲", "故事简介")
        }.ifBlank {
            storyGuide.trim().takeIf { "【" !in it }.orEmpty().ifBlank { seedIntro.trim() }
        }
        val highlights = bible?.highlights.orEmpty().trim().ifBlank {
            section(storyGuide, "核心看点", "看点")
        }
        val hook = bible?.hook.orEmpty().trim().ifBlank {
            section(storyGuide, "开篇钩子", "核心钩子")
        }
        return buildString {
            appendLine("《${title.trim()}》")
            appendLine()
            if (overview.isNotBlank()) {
                appendLine("【故事简介】")
                appendLine(overview)
                appendLine()
            }
            if (highlights.isNotBlank()) {
                appendLine("【核心看点】")
                appendLine(highlights)
                appendLine()
            }
            if (hook.isNotBlank()) {
                appendLine("【开篇钩子】")
                appendLine(hook)
                appendLine()
            }
            appendLine("========== 小说正文（共${chapters.size}章） ==========")
            if (chapters.isEmpty()) {
                appendLine("（尚未生成章节）")
            } else {
                chapters.sortedBy { it.chapter }.forEach { chapter ->
                    appendLine()
                    appendLine("第${chapter.chapter}章 · ${chapter.title.trim().ifBlank { "未命名" }}")
                    appendLine()
                    appendLine(chapter.content.trim())
                    appendLine()
                }
            }
        }.trimEnd() + "\n"
    }

    private fun section(guide: String, vararg names: String): String {
        for (name in names) {
            val marker = "【$name】"
            val start = guide.indexOf(marker)
            if (start < 0) continue
            val from = start + marker.length
            val end = guide.indexOf("【", from).let { if (it < 0) guide.length else it }
            val value = guide.substring(from, end).trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    fun suggestedFileName(title: String): String {
        val safeName = title.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .trim('.', ' ')
            .take(70)
            .ifBlank { "未命名小说" }
        return "${safeName}_全部章节.txt"
    }
}
