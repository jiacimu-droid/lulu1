package com.jiacimu.lulu

/**
 * Measures decoded frames arriving in the Android UI, NOT the GPU ticker FPS.
 *
 * Distinguishes average received FPS, p95 inter-frame jitter, JPEG bandwidth,
 * and decoder work. Uses a bounded window; never stores individual bitmaps or
 * any user audio/character data.
 */
internal class Live2DFrameStats(
    private val reportIntervalMs: Long = 4_000L,
) {
    data class Sample(
        val fps: Float,
        val p95GapMs: Long,
        val averageDecodeMs: Float,
        val averageFrameKiB: Float,
        val width: Int,
        val height: Int,
        val mode: String,
    ) {
        fun label(): String =
            "%s · %.1f FPS · %d×%dpx · p95 %dms · 解码 %.0fms".format(
                java.util.Locale.ROOT, mode, fps, width, height, p95GapMs,
                averageDecodeMs)
    }

    private var startedAt = -1L
    private var lastAt = -1L
    private val gaps = ArrayList<Long>(256)
    private var frames = 0
    private var decodeTimeTotal = 0L
    private var bytesTotal = 0L

    fun frame(
        nowMs: Long,
        encodedBytes: Int,
        decodeMs: Long,
        width: Int,
        height: Int,
        mode: String,
    ): Sample? {
        if (startedAt < 0 || nowMs < startedAt) {
            startedAt = nowMs
            lastAt = -1L
            gaps.clear()
            frames = 0
            decodeTimeTotal = 0
            bytesTotal = 0
        }
        if (lastAt >= 0) {
            if (gaps.size >= 256) gaps.removeAt(0)
            gaps.add((nowMs - lastAt).coerceAtLeast(0))
        }
        lastAt = nowMs
        frames++
        decodeTimeTotal += decodeMs.coerceAtLeast(0)
        bytesTotal += encodedBytes.coerceAtLeast(0).toLong()
        val span = nowMs - startedAt
        if (span < reportIntervalMs) return null
        val sorted = gaps.sorted()
        val p95 = if (sorted.isEmpty()) 0 else
            sorted[((sorted.size - 1) * .95).toInt()]
        val result = Sample(
            fps = frames * 1000f / span.coerceAtLeast(1),
            p95GapMs = p95,
            averageDecodeMs = decodeTimeTotal.toFloat() / frames,
            averageFrameKiB = bytesTotal.toFloat() / frames / 1024f,
            width = width,
            height = height,
            mode = mode,
        )
        startedAt = nowMs
        lastAt = -1L
        gaps.clear()
        frames = 0
        decodeTimeTotal = 0
        bytesTotal = 0
        return result
    }
}
