package com.jiacimu.lulu

import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/** AudioTrack's consumed frames, never HTTP arrival time or bytes merely queued. */
internal class CallAudioPlaybackClock(private val track: AudioTrack, private val rate: Int,
    private val isCurrent: () -> Boolean, private val onStarted: () -> Unit,
    private val onPosition: (Long) -> Unit,
) : AutoCloseable {
    private var closed = false
    private var started = false
    private var lastReported = -100L

    init {
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) = sample()
            override fun onPeriodicNotification(track: AudioTrack) = sample()
        }, Handler(Looper.getMainLooper()))
        // Detect first consumed audio within 20ms; bridge progress at 10Hz.
        track.setPositionNotificationPeriod(maxOf(1, rate / 50))
    }

    @Synchronized fun sample() {
        if (closed || !isCurrent()) return
        val frames = runCatching { track.playbackHeadPosition.toLong() and 0xffffffffL }.getOrNull() ?: return
        if (frames <= 0) return
        if (!started) { started = true; runCatching(onStarted) }
        val elapsed = frames * 1000 / rate
        if (elapsed - lastReported >= 100) {
            lastReported = elapsed
            runCatching { onPosition(elapsed) }
        }
    }

    @Synchronized override fun close() {
        closed = true
        runCatching { track.setPlaybackPositionUpdateListener(null) }
    }
}
