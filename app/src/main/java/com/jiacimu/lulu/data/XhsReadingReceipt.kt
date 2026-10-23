package com.jiacimu.lulu.data

import org.json.JSONObject
import java.time.Instant

/**
 * UI-only view of canonical Xiaohongshu reading evidence.
 * Never infer a successful image understanding from URL count or model claims.
 * Older records may not contain detailed counts: show an unknown status instead.
 */
internal data class XhsReadingReceipt(
    val evidenceId: String,
    val characterId: String,
    val occurredAt: Instant,
    val title: String,
    val noteUrl: String,
    val bodyVerified: Boolean,
    val commentsLoaded: Int?,
    val imagesLinked: Int?,
    val imagesDownloaded: Int?,
    val imagesAnalyzed: Int?,
    val imageStatus: String,
) {
    val bodyLabel: String get() = if (bodyVerified) "正文已读" else "正文未核验"
    val commentsLabel: String get() =
        commentsLoaded?.let { "评论 ${it} 条" } ?: "评论未记录"
    val imagesLabel: String get() = when {
        imagesLinked == 0 -> "无图片"
        imagesLinked == null -> "图片未记录"
        imagesAnalyzed != null -> "视觉 ${imagesAnalyzed}/${imagesLinked}"
        imagesDownloaded != null -> "下载 ${imagesDownloaded}/${imagesLinked}"
        else -> "图片 ${imagesLinked} 张"
    }

    companion object {
        fun from(event: SharedTimelineEvent): XhsReadingReceipt? {
            if (event.channel != "小红书阅读" ||
                event.source !in setOf("xiaohongshu-mcp", "xiaohongshu-webview") ||
                event.evidenceKind != EventEvidenceKind.Observation) return null
            val note = runCatching { JSONObject(event.content) }.getOrNull() ?: return null
            val title = note.optString("title").trim()
            if (title.isBlank()) return null
            fun count(key: String): Int? =
                if (note.has(key) && !note.isNull(key)) note.optInt(key, -1).takeIf { it >= 0 } else null
            val images = count("imageCount")
            val downloaded = count("imageDownloadedCount")?.let { n ->
                images?.let { n.coerceAtMost(it) } ?: n
            }
            val analyzed = count("imageAnalyzedCount")?.let { n ->
                downloaded?.let { n.coerceAtMost(it) } ?: images?.let { n.coerceAtMost(it) } ?: n
            }
            val bodyLoaded = if (note.has("bodyActuallyLoaded")) note.optBoolean("bodyActuallyLoaded")
                else note.optString("text").trim().let { it.isNotBlank() && !it.startsWith("[图片理解") }
            return XhsReadingReceipt(
                evidenceId = event.id,
                characterId = event.characterId,
                occurredAt = event.occurredAt,
                title = title,
                noteUrl = note.optString("url"),
                bodyVerified = bodyLoaded,
                commentsLoaded = count("commentCountActuallyLoaded"),
                imagesLinked = images,
                imagesDownloaded = downloaded,
                imagesAnalyzed = analyzed,
                imageStatus = note.optString("imageReadingStatus").take(160),
            )
        }
    }
}
