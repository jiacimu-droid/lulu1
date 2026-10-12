package com.jiacimu.lulu

/**
 * A transcript is NOT proof that the human spoke: loudspeaker TTS can enter
 * AudioRecord and cloud STT can return paraphrased / imperfect playback echoes.
 *
 * We cannot reliably separate speakers from text alone.  On loudspeaker use
 * half-duplex capture while character audio plays; true barge-in is permitted
 * on a private output (headset / receiver), then screened for obvious echoes.
 */
internal object PhoneBargeInPolicy {
    private val separators = Regex("""[\s，。！？!?、；;：:“”‘’…~～—_"'（）()\[\]【】]+""")

    private fun normalize(text: String): String =
        text.lowercase().replace(separators, "").trim()

    fun shouldCapture(microphoneMuted: Boolean, playbackBusy: Boolean, privateOutput: Boolean): Boolean =
        !microphoneMuted && (!playbackBusy || privateOutput)

    /** TTS synthesis is interruptible; once audio is audible, keep its turn. */
    fun shouldSupersedeUnplayedReply(thinking: Boolean, playing: Boolean, pendingAudio: Boolean): Boolean =
        !playing && (thinking || pendingAudio)

    /**
     * This must be called BEFORE cancelling agent speech or saving a message.
     * Match short verbatim fragments too, and tolerate dropped/misheard syllables
     * in longer STT output. A fuzzy score is only meaningful during/recently
     * after actual output; never filter ordinary later conversation.
     */
    fun isLikelyPlaybackEcho(
        candidate: String,
        generated: String,
        playing: String,
        playbackActive: Boolean,
    ): Boolean {
        if (!playbackActive) return false
        val heard = normalize(candidate).takeLast(220)
        if (heard.isBlank()) return false
        val sources = listOf(playing, generated).map(::normalize).filter(String::isNotBlank)
        return sources.any { spoken ->
            if (spoken.contains(heard)) return@any true
            // If the recognizer paraphrases the long playback by one or two
            // characters, exact substring checks won't catch the feedback loop.
            // Require a substantial run of overlapping pairs; shared generic
            // words ("我", "你好") by themselves aren't sufficient.
            if (heard.length < 6) return@any false
            val pairs = heard.windowed(2).toSet()
            val spokenPairs = spoken.windowed(2).toSet()
            val overlap = pairs.count { it in spokenPairs }
            val score = overlap.toDouble() / pairs.size.coerceAtLeast(1)
            score >= 0.70 && overlap >= 4
        }
    }

    /**
     * Don't interrupt with an uncommitted 1-2-character guess while TTS plays.
     * A short direct interjection can still be submitted as committed speech.
     */
    fun safePartialBargeIn(text: String, privateOutput: Boolean, playbackBusy: Boolean): Boolean =
        (!playbackBusy || privateOutput) &&
            (!playbackBusy || normalize(text).length >= 4)
}
