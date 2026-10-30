package com.jiacimu.lulu

/**
 * Short-lived PCM16 mono loudness timeline for one streamed TTS utterance.
 * The provider appends bytes when AudioTrack accepts them, but the avatar
 * samples them only when AudioTrack reports frames ACTUALLY consumed.
 * It is neither a new speech clock nor an emotional/motion decision.
 */
internal class CallSpeechPcmEnvelope(sampleRate: Int) {
    private val periodMs = 40L
    private val bucketBytes = ((sampleRate * periodMs / 1000L).toInt().coerceAtLeast(1)) * 2
    private val bucket = ByteArray(bucketBytes)
    private val completed = ArrayList<Float>()
    private var filled = 0

    @Synchronized
    fun append(pcm: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length <= 0 || offset.toLong() + length > pcm.size) return
        var index = offset
        val end = offset + length
        while (index < end) {
            val count = minOf(end - index, bucket.size - filled)
            pcm.copyInto(bucket, filled, index, index + count)
            filled += count
            index += count
            if (filled == bucket.size) {
                completed.add(CallSpeechPcmLevel.measure(bucket, 0, bucket.size))
                filled = 0
            }
        }
    }

    @Synchronized
    fun levelAt(realPlaybackMs: Long): Float? {
        if (realPlaybackMs < 0) return null
        val bucketIndex = realPlaybackMs / periodMs
        if (bucketIndex > Int.MAX_VALUE) return null
        val index = bucketIndex.toInt()
        return when {
            index < completed.size -> completed[index]
            index == completed.size && filled >= 2 ->
                CallSpeechPcmLevel.measure(bucket, 0, filled - (filled % 2))
            else -> null
        }
    }
}
