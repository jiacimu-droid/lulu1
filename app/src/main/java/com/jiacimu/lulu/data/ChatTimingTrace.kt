package com.jiacimu.lulu.data

import java.nio.charset.StandardCharsets
import java.util.UUID

/** Correlate phases of the same role-owned chat revision, without paid work. */
internal object ChatTimingTrace {
    fun forOnlineBatch(characterId: String, revision: Long): String =
        "chat-" + UUID.nameUUIDFromBytes(
            "lulu-chat-timing-v1:$characterId:$revision".toByteArray(StandardCharsets.UTF_8),
        ).toString()

    data class Timeline(val id: String, val records: List<PerformanceTimingRecord>) {
        val latestAt: Long get() = records.maxOfOrNull { it.recordedAtMillis } ?: 0L
        val firstBubbleMillis: Long? get() =
            records.filter { it.stage == "消息到首条回复入库" }
                .maxByOrNull { it.recordedAtMillis }?.durationMillis
    }

    fun keyOf(row: PerformanceTimingRecord): String = row.traceId.ifBlank {
        Regex("(?:^|;)trace=([^;\\s]+)").find(row.detail)?.groupValues?.getOrNull(1).orEmpty()
    }

    fun group(records: List<PerformanceTimingRecord>): List<Timeline> =
        records.filter { keyOf(it).isNotBlank() }
            .groupBy(::keyOf)
            .map { (key, stages) -> Timeline(key, stages.sortedBy { it.recordedAtMillis }) }
            .sortedByDescending { it.latestAt }

    fun legacy(records: List<PerformanceTimingRecord>): List<PerformanceTimingRecord> =
        records.filter { keyOf(it).isBlank() }.sortedByDescending { it.recordedAtMillis }

    fun duration(msInput: Long): String {
        val ms = msInput.coerceAtLeast(0L)
        return when {
            ms < 1_000 -> "$ms ms"
            ms < 60_000 -> (ms / 1_000).toString() + "." + ((ms % 1_000) / 100) + " 秒"
            else -> (ms / 60_000).toString() + " 分 " + ((ms % 60_000) / 1_000) + " 秒"
        }
    }
}
