package com.jiacimu.lulu.data

/**
 * System reading receipts stay small in the conversation. The actual book cursor, source text,
 * chapter reflection and life timeline are stored separately by the reading executor.
 *
 * This also normalizes old verbose receipts without rewriting historical evidence.
 */
internal object ReadingChatReceiptFormatter {
    private val receipt = Regex("""^\[阅读记录\|([^\]]+)]\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val chapter = Regex("""第[零〇一二三四五六七八九十百千万0-9]+[章节回卷]""")
    private val batch = Regex("""连续阅读\s*(\d+)\s*段""")
    private const val openLabel = "查看感想"

    data class Preview(val recordId: String, val text: String, val linkStart: Int, val linkEnd: Int)

    fun isReadingNotice(message: String): Boolean =
        unwrap(message).startsWith("[阅读记录|")

    fun consecutiveCount(message: String): Int =
        batch.find(unwrap(message))?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 30) ?: 1

    fun create(
        recordId: String,
        bookTitle: String,
        chapterTitle: String,
        reflection: String,
        consecutiveReads: Int = 1,
    ): String {
        val cleanTitle = bookTitle.trim().removePrefix("《").removeSuffix("》").take(54)
        val cleanChapter = chapter.find(chapterTitle)?.value.orEmpty()
        val note = reflection.replace(Regex("""\s+"""), " ").trim().take(90)
        val prefix = if (consecutiveReads > 1) "连续阅读 ${consecutiveReads.coerceAtMost(30)} 段 · " else ""
        return buildString {
            append("[阅读记录|").append(recordId).append("]")
            append(prefix).append("《").append(cleanTitle).append("》")
            if (cleanChapter.isNotEmpty()) append(" · ").append(cleanChapter)
            if (note.isNotBlank()) append("\n读后感：").append(note)
            append("\n").append(openLabel)
        }
    }

    fun preview(message: String): Preview? {
        val match = receipt.find(unwrap(message)) ?: return null
        val raw = match.groupValues[2].trim()
        val count = consecutiveCount(message)
        val bookName = Regex("""《([^》]+)》""").find(raw)?.groupValues?.get(1).orEmpty()
            .ifBlank { "阅读记录" }.take(35)
        val chapterName = chapter.find(raw)?.value.orEmpty()
        val summary = raw.substringAfter("读后感：", "")
            .substringBefore("\n点开查看")
            .substringBefore("\n查看")
            .substringBefore("点开查看")
            .substringBefore(openLabel)
            .replace(Regex("""\s+"""), " ")
            .trim().take(52)
        val compact = buildString {
            if (count > 1) append("连读${count}段 · ")
            else append("读了 ")
            append("《").append(bookName).append("》")
            if (chapterName.isNotEmpty()) append(" · ").append(chapterName)
            if (summary.isNotBlank()) append("\n").append(summary).append(if (summary.length == 52) "…" else "")
            append("\n").append(openLabel)
        }
        val start = compact.lastIndexOf(openLabel)
        return Preview(match.groupValues[1], compact, start, start + openLabel.length)
    }

    private fun unwrap(message: String): String = message.trim()
        .removePrefix("[共同活动]").trimStart()
}
