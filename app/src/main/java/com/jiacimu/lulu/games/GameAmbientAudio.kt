package com.jiacimu.lulu.games

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Tiny procedural soundtrack engine shared by the three game-like experiences.
 *
 * Music is synthesized on-device instead of shipping a large looping audio asset. Each screen owns
 * a playback token, so navigation can never stop a newer screen's soundtrack by accident.
 */
internal enum class GameSoundscape { Meeting, Apocalypse, Arcade }

internal object GameAmbientAudio {
    private const val PREFS = "lulu_game_ambient_audio"
    private const val KEY_ENABLED = "enabled"
    private const val SAMPLE_RATE = 22_050
    private const val CHANNELS = 2
    private val enabledState = MutableStateFlow(true)
    private var initialized = false
    private var activeOwner: Any? = null
    private var stopSignal: AtomicBoolean? = null
    private var playbackThread: Thread? = null

    @Synchronized
    fun enabled(context: Context): StateFlow<Boolean> {
        if (!initialized) {
            enabledState.value = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
            initialized = true
        }
        return enabledState
    }

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        enabled(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        enabledState.value = enabled
        if (!enabled) stopLocked()
    }

    @Synchronized
    fun start(context: Context, owner: Any, soundscape: GameSoundscape) {
        enabled(context)
        if (!enabledState.value) return
        if (activeOwner === owner && playbackThread?.isAlive == true) return
        stopLocked()
        val signal = AtomicBoolean(false)
        activeOwner = owner
        stopSignal = signal
        playbackThread = Thread(
            { streamSoundscape(soundscape, signal) },
            "lulu-" + soundscape.name.lowercase() + "-bgm",
        ).also { thread ->
            thread.isDaemon = true
            thread.start()
        }
    }

    @Synchronized
    fun stop(owner: Any) {
        if (activeOwner !== owner) return
        stopLocked()
    }

    private fun stopLocked() {
        stopSignal?.set(true)
        playbackThread?.interrupt()
        stopSignal = null
        playbackThread = null
        activeOwner = null
    }

    private fun streamSoundscape(soundscape: GameSoundscape, stop: AtomicBoolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(8_192)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(minimum * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return

        val frames = 2_048
        val buffer = ShortArray(frames * CHANNELS)
        var sampleIndex = 0L
        var noiseSeed = soundscape.ordinal * 1_103_515_245 + 12_345
        runCatching {
            track.play()
            while (!stop.get()) {
                for (frame in 0 until frames) {
                    val time = sampleIndex.toDouble() / SAMPLE_RATE
                    noiseSeed = noiseSeed * 1_103_515_245 + 12_345
                    val noise = ((noiseSeed ushr 16) and 0x7FFF) / 16_383.5 - 1.0
                    val (left, right) = synthFrame(soundscape, time, noise)
                    buffer[frame * 2] = (left.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                    buffer[frame * 2 + 1] = (right.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                    sampleIndex++
                }
                track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            }
        }
        runCatching { track.pause() }
        runCatching { track.flush() }
        track.release()
    }

    private fun synthFrame(soundscape: GameSoundscape, t: Double, noise: Double): Pair<Double, Double> =
        when (soundscape) {
            GameSoundscape.Meeting -> {
                val bar = (t / 4.8).toInt()
                val chords = arrayOf(
                    doubleArrayOf(130.81, 164.81, 196.00, 246.94),
                    doubleArrayOf(110.00, 146.83, 174.61, 220.00),
                    doubleArrayOf(98.00, 130.81, 164.81, 196.00),
                    doubleArrayOf(116.54, 146.83, 196.00, 233.08),
                )
                val chord = chords[bar % chords.size]
                val breathe = .72 + .28 * sin(t * PI / 4.8)
                val pad = chord.take(3).mapIndexed { index, hz ->
                    sin(2.0 * PI * hz * t + index * .7) * (.013 - index * .002)
                }.sum() * breathe
                val noteWindow = (t % 1.2) / 1.2
                val note = chord[((t / 1.2).toInt() + bar) % chord.size] * 2.0
                val bell = sin(2.0 * PI * note * t) * (.018 * (1.0 - noteWindow).let { it * it })
                val air = noise * .0022
                (pad + bell + air) to (pad * .94 + bell * .82 - air)
            }
            GameSoundscape.Apocalypse -> {
                val drone = sin(2.0 * PI * 55.0 * t) * .018 +
                    sin(2.0 * PI * 73.42 * t + .6) * .012 +
                    sin(2.0 * PI * 82.41 * t + 1.4) * .009
                val pulsePosition = t % 2.15
                val pulse = when {
                    pulsePosition < .13 -> (1.0 - pulsePosition / .13) * .026
                    pulsePosition in .24..0.34 -> (1.0 - (pulsePosition - .24) / .10) * .018
                    else -> 0.0
                }
                val heartbeat = sin(2.0 * PI * 48.0 * t) * pulse
                val wind = noise * (.003 + abs(sin(t * .17)) * .003)
                (drone + heartbeat + wind) to (drone * .91 + heartbeat * .84 - wind)
            }
            GameSoundscape.Arcade -> {
                val beat = t % .5
                val envelope = (1.0 - beat / .5).let { it * it }
                val scale = doubleArrayOf(261.63, 329.63, 392.00, 493.88, 440.00, 392.00, 329.63, 293.66)
                val step = (t / .5).toInt()
                val hz = scale[step % scale.size]
                val lead = (sin(2.0 * PI * hz * t) + .32 * sin(2.0 * PI * hz * 2.0 * t)) * .018 * envelope
                val bass = sin(2.0 * PI * (if ((step / 4) % 2 == 0) 65.41 else 73.42) * t) * .012
                val sparkle = if (beat < .035) noise * .009 * (1.0 - beat / .035) else 0.0
                (lead + bass + sparkle) to (lead * .82 + bass - sparkle)
            }
        }
}

@Composable
internal fun GameAmbientSoundscape(soundscape: GameSoundscape): Boolean {
    val context = LocalContext.current
    val enabled by GameAmbientAudio.enabled(context).collectAsState()
    val owner = remember { Any() }
    DisposableEffect(context, soundscape, enabled, owner) {
        if (enabled) GameAmbientAudio.start(context, owner, soundscape)
        onDispose { GameAmbientAudio.stop(owner) }
    }
    return enabled
}

@Composable
internal fun GameAmbientAudioButton(
    enabled: Boolean,
    tint: Color,
) {
    val context = LocalContext.current
    IconButton(onClick = { GameAmbientAudio.setEnabled(context, !enabled) }) {
        Icon(
            if (enabled) Icons.Outlined.MusicNote else Icons.Outlined.MusicOff,
            if (enabled) "关闭背景音乐" else "开启背景音乐",
            tint = tint,
        )
    }
}
