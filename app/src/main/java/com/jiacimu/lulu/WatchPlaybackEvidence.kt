package com.jiacimu.lulu

/** Playback evidence, never another character memory/personality source. */
internal data class WatchedRange(val fromMs: Long, val toMs: Long) {
    init { require(fromMs >= 0L && toMs >= fromMs) }
}

internal class WatchPlaybackEvidence private constructor(private val ranges: MutableList<WatchedRange>) {
    private var anchor: Long? = null

    fun begin(atMs: Long) { anchor = atMs.coerceAtLeast(0L) }
    fun pause() { anchor = null }

    /** A seek, backwards jump, stall, or resume gap cannot fill unwatched footage. */
    fun advance(toMs: Long, isPlaying: Boolean) {
        val at = toMs.coerceAtLeast(0L)
        val previous = anchor
        anchor = if (isPlaying) at else null
        if (!isPlaying || previous == null || at <= previous || at - previous > 2_500L) return
        val latest = ranges.lastOrNull()
        if (latest != null && previous >= latest.fromMs && previous <= latest.toMs + 1_000L) {
            ranges[ranges.lastIndex] = latest.copy(toMs = maxOf(latest.toMs, at))
        } else ranges += WatchedRange(previous, at)
    }

    fun watchedThrough(playheadMs: Long): List<WatchedRange> {
        val now = playheadMs.coerceAtLeast(0L)
        return ranges.mapNotNull { range ->
            if (range.fromMs >= now) null
            else WatchedRange(range.fromMs, minOf(range.toMs, now)).takeIf { it.toMs > it.fromMs }
        }
    }

    /** Local evidence survives dialog closures, process death and phone restarts. */
    fun encode(): String = ranges.joinToString(";") { it.fromMs.toString() + "," + it.toMs.toString() }
    fun observedMs(atMs: Long): Long = watchedThrough(atMs).sumOf { it.toMs - it.fromMs }

    fun frameTimes(atMs: Long): List<Long> {
        val bounded = watchedThrough(atMs)
        if (bounded.isEmpty()) return emptyList()
        val edge = (atMs - 120_000L).coerceAtLeast(0L)
        val recent = bounded.mapNotNull {
            WatchedRange(maxOf(it.fromMs, edge), it.toMs).takeIf { span -> span.toMs > span.fromMs }
        }
        if (recent.isEmpty()) return emptyList()
        val covered = recent.sumOf { it.toMs - it.fromMs }
        if (covered == 0L) return emptyList()
        val count = if (covered < 2_500L) 1 else if (covered < 6_000L) 2 else 5
        return (0 until count).map { index ->
            val offset = if (count == 1) covered - 1L else (covered - 1L) * index / (count - 1)
            var remainder = offset
            var selected = recent.last().toMs - 1L
            for (span in recent) {
                val length = span.toMs - span.fromMs
                if (remainder < length) {
                    selected = span.fromMs + remainder
                    break
                }
                remainder -= length
            }
            selected.coerceAtLeast(0L).coerceAtMost(atMs)
        }.distinct()
    }

    companion object {
        fun empty(): WatchPlaybackEvidence = WatchPlaybackEvidence(mutableListOf())
        fun restore(encoded: String): WatchPlaybackEvidence {
            val list = encoded.split(';').mapNotNull { item ->
                val parts = item.split(',')
                if (parts.size != 2) null else runCatching {
                    WatchedRange(parts[0].toLong(), parts[1].toLong())
                }.getOrNull()
            }.toMutableList()
            return WatchPlaybackEvidence(list)
        }
    }
}

internal fun watchedSubtitles(cues: List<WatchSubtitleCue>, evidence: WatchPlaybackEvidence, playheadMs: Long): String {
    val spans = evidence.watchedThrough(playheadMs)
    val recent = (playheadMs - 180_000L).coerceAtLeast(0L)
    return cues.asSequence().filter { cue ->
        cue.endMs <= playheadMs && cue.startMs >= recent &&
            spans.any { it.fromMs <= cue.startMs && it.toMs >= cue.endMs }
    }.toList().takeLast(28).joinToString("\n") { "[" + it.startMs / 1000 + "s] " + it.text }
}
