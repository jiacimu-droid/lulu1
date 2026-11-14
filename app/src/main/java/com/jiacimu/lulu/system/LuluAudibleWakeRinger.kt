package com.jiacimu.lulu.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A real, bounded device alarm cue. Posting an Android notification is not
 * evidence that its channel made a sound (users can change channel settings).
 *
 * Alarm audio is independent of the character model, TTS, chat, and any
 * currently connected call. It respects the device's configured alarm volume
 * and DND policy; it never forces media/ring volume or creates a new call.
 */
internal object LuluAudibleWakeRinger {
    private const val MAX_RING_MS = 30_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeTask: String? = null
    private var deadlineElapsed: Long = 0L
    private var ringtone: Ringtone? = null

    @Synchronized
    fun start(context: Context, taskId: String): Boolean {
        if (taskId.isBlank()) return false
        if (activeTask == taskId && ringtone?.isPlaying == true) return true
        stop()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio?.getStreamVolume(AudioManager.STREAM_ALARM) == 0) return false
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val alarm = runCatching {
            RingtoneManager.getRingtone(context.applicationContext, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (android.os.Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }.getOrNull()
        if (alarm?.isPlaying != true) {
            runCatching { alarm?.stop() }
            return false
        }
        activeTask = taskId
        deadlineElapsed = SystemClock.elapsedRealtime() + MAX_RING_MS
        ringtone = alarm
        scope.launch {
            delay(MAX_RING_MS)
            stop(taskId)
        }
        return true
    }

    @Synchronized
    fun remainingMs(taskId: String): Long =
        if (activeTask == taskId && ringtone?.isPlaying == true)
            (deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        else 0L

    @Synchronized
    fun stop(taskId: String? = null) {
        if (taskId != null && taskId != activeTask) return
        runCatching { ringtone?.stop() }
        ringtone = null
        activeTask = null
        deadlineElapsed = 0L
    }
}
