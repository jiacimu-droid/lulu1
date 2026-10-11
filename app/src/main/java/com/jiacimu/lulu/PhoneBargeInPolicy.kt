package com.jiacimu.lulu

/**
 * Cloud ASR can keep listening during TTS. Check only conspicuous verbatim
 * playback echoes; do NOT reject short user interjections like "不对" or "等等".
 *
 * This is a conservative guard, not speaker identification. For a loudspeaker,
 * Android acoustic echo cancellation still determines recognition quality.
 */
internal object PhoneBargeInPolicy {
    private val separators = Regex("""[\s，。！？!?、；;：:“”‘’…~～—_"'（）()\[\]【】]+""")

    private fun normalize(text: String): String =
        text.lowercase().replace(separators, "").trim()

    fun isLikelyPlaybackEcho(
        candidate: String,
        generated: String,
        playing: String,
        playbackActive: Boolean,
    ): Boolean {
        if (!playbackActive) return false
        val heard = normalize(candidate)
        // A few overlapping Chinese characters can be an ordinary human answer.
        if (heard.length < 10) return false
        return sequenceOf(playing, generated).map(::normalize).any { output ->
            output.length >= heard.length && output.contains(heard) &&
                heard.length >= minOf(12, output.length)
        }
    }
}
