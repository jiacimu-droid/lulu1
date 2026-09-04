package com.jiacimu.lulu.games

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

internal enum class GameSoundEffect {
    Move,
    Bump,
    Interact,
    Collect,
    Objective,
}

/** Short synthesized one-shots. They carry no asset or network cost and are never queued. */
internal object GameSoundEffects {
    private const val SAMPLE_RATE = 22_050
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lulu-game-sfx").apply { isDaemon = true }
    }
    private val lastPlayedAt = AtomicLong(0L)

    fun play(effect: GameSoundEffect) {
        val now = System.currentTimeMillis()
        val minimumGap = if (effect == GameSoundEffect.Bump) 180L else 65L
        val previous = lastPlayedAt.get()
        if (now - previous < minimumGap || !lastPlayedAt.compareAndSet(previous, now)) return
        executor.execute { render(effect) }
    }

    private fun render(effect: GameSoundEffect) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val duration = when (effect) {
            GameSoundEffect.Move -> .10
            GameSoundEffect.Bump -> .08
            GameSoundEffect.Interact -> .16
            GameSoundEffect.Collect -> .27
            GameSoundEffect.Objective -> .42
        }
        val sampleCount = (SAMPLE_RATE * duration).toInt()
        val samples = ShortArray(sampleCount)
        for (index in samples.indices) {
            val t = index.toDouble() / SAMPLE_RATE
            val progress = (t / duration).coerceIn(0.0, 1.0)
            val envelope = (1.0 - progress).let { it * it }
            val signal = when (effect) {
                GameSoundEffect.Move -> sin(2.0 * PI * (180.0 - progress * 55.0) * t) * .10
                GameSoundEffect.Bump -> (sin(2.0 * PI * 72.0 * t) + sin(2.0 * PI * 119.0 * t) * .25) * .14
                GameSoundEffect.Interact -> sin(2.0 * PI * (420.0 + progress * 210.0) * t) * .12
                GameSoundEffect.Collect -> {
                    val hz = if (progress < .48) 523.25 else 783.99
                    (sin(2.0 * PI * hz * t) + sin(2.0 * PI * hz * 2.0 * t) * .18) * .12
                }
                GameSoundEffect.Objective -> {
                    val hz = when {
                        progress < .32 -> 392.0
                        progress < .64 -> 523.25
                        else -> 659.25
                    }
                    sin(2.0 * PI * hz * t) * .12
                }
            }
            samples[index] = (signal * envelope * Short.MAX_VALUE).toInt().toShort()
        }

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return
        runCatching {
            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep((duration * 1_000).toLong() + 35L)
        }
        track.release()
    }
}
