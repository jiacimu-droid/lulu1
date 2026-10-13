package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluVoiceCallSession
import com.jiacimu.lulu.system.LuluMediaPlayback

/** Optional, evidence-gated wake-up music. Never starts on an unanswered alarm. */
internal object WakeMusicCoordinator {
    private var deferredCharacter: String? = null

    internal fun explicitlyAwake(text: String): Boolean {
        val clean = text.replace(Regex("\\s+"), "")
        if (Regex("还没醒|没醒|没起来|不想起|没起床|别放歌|不要放歌|不放音乐|不要音乐").containsMatchIn(clean)) return false
        return Regex("我醒了|我醒啦|已经醒了|睡醒了|我起来了|我起床了|起床啦|起来啦").containsMatchIn(clean)
    }

    @Synchronized fun onExplicitWakeConfirmation(
        context: Context,
        characterId: String,
        text: String,
        activeTasks: List<CommitmentTask>,
    ) {
        if (!explicitlyAwake(text)) return
        val task = activeTasks.firstOrNull {
            it.characterId == characterId && it.isWakeResponsibility() &&
                it.status == CommitmentTaskStatus.WaitingForFeedback
        } ?: return
        val policy = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        if (!policy.getBoolean("$characterId:wake_music_enabled", false) ||
            !policy.getBoolean("$characterId:media", false)) return
        val played = context.getSharedPreferences("lulu_wake_music_actions", Context.MODE_PRIVATE)
        if (played.getBoolean(task.id, false)) return
        if (!played.edit().putBoolean(task.id, true).commit()) return
        val call = LuluVoiceCallSession.state.value
        if (call.connected && call.characterId == characterId) {
            deferredCharacter = characterId
        } else {
            LuluMediaPlayback.control(context, "play")
        }
    }

    @Synchronized fun onCallEnded(context: Context?, characterId: String) {
        if (deferredCharacter != characterId) return
        deferredCharacter = null
        if (context == null) return
        // Dispatch only after call routing/audio focus is returned to Android.
        LuluMediaPlayback.control(context, "play")
    }
}
