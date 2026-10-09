package com.jiacimu.lulu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Device-local ringtone only. No TTS generation, network API or ElevenLabs credits.
 * Incoming ringing and outgoing ringback are independent; both stop at answer/reject/end.
 */
internal object LuluCallRingtone {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var incoming: Ringtone? = null
    private var outgoing: Ringtone? = null
    private var incomingKey: String? = null

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
            .getBoolean("voice_call_ringtone_enabled", true)

    @Synchronized fun startIncoming(context: Context, key: String, lifetimeMs: Long) {
        if (key.isBlank()) return
        if (incomingKey == key && incoming?.isPlaying == true) return
        stopIncoming()
        if (!enabled(context)) return
        incomingKey = key
        incoming = play(context)
        scope.launch {
            delay(lifetimeMs.coerceIn(1000L, 120_000L))
            synchronized(this@LuluCallRingtone) {
                if (incomingKey == key) stopIncoming()
            }
        }
    }

    @Synchronized fun startOutgoing(context: Context) {
        stopOutgoing()
        if (!enabled(context)) return
        // Incoming ringing stops as soon as the call is accepted.
        stopIncoming()
        outgoing = play(context)
    }

    @Synchronized fun stopIncoming() {
        runCatching { incoming?.stop() }
        incoming = null
        incomingKey = null
    }

    @Synchronized fun stopOutgoing() {
        runCatching { outgoing?.stop() }
        outgoing = null
    }

    @Synchronized fun stopAll() {
        stopIncoming()
        stopOutgoing()
    }

    private fun play(context: Context): Ringtone? = runCatching {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // Respect phone silent/DND: never force volume or use media stream as a workaround.
        if (manager?.ringerMode == AudioManager.RINGER_MODE_SILENT ||
            manager?.ringerMode == AudioManager.RINGER_MODE_VIBRATE) return@runCatching null
        val ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(
            context, RingtoneManager.TYPE_RINGTONE,
        ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        RingtoneManager.getRingtone(context.applicationContext, ringtoneUri)?.also { ringtone ->
            if (Build.VERSION.SDK_INT >= 28) ringtone.isLooping = true
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
        }
    }.getOrNull()
}
