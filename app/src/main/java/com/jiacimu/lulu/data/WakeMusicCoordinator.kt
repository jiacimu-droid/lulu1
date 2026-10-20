package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluVoiceCallSession
import com.jiacimu.lulu.system.LuluMediaPlayback

/** Optional, evidence-gated wake-up music. Never starts on an unanswered alarm. */
internal object WakeMusicCoordinator {
    private const val STORE = "lulu_wake_music_actions"
    private const val PENDING = "pending:"
    private var deferredCharacter: String? = null

    internal fun explicitlyAwake(text: String): Boolean {
        val clean = text.replace(Regex("\\s+"), "")
        if (Regex("还没醒|没醒|没起来|不想起|没起床|别放歌|不要放歌|不放音乐|不要音乐").containsMatchIn(clean)) return false
        return Regex("我醒了|我醒啦|已经醒了|睡醒了|我起来了|我起床了|起床啦|起来啦").containsMatchIn(clean)
    }

    /** Wake confirmation never automatically schedules a song. */
    @Synchronized fun onExplicitWakeConfirmation(
        context: Context,
        characterId: String,
        text: String,
        activeTasks: List<CommitmentTask>,
    ) {
        if (explicitlyAwake(text)) {
            com.jiacimu.lulu.system.LuluMediaVolume.control(context, characterId, "restore")
        }
    }

    /** Immediate music is only called for a verified character-selected action. */
    @Synchronized fun playImmediately(context: Context, characterId: String, taskId: String): Boolean {
        val permission = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        if (!permission.getBoolean("$characterId:wake_music_enabled", false) ||
            !permission.getBoolean("$characterId:media", false)) return false
        val result = runCatching {
            org.json.JSONObject(LuluMediaPlayback.control(context, "play"))
        }.getOrNull() ?: return false
        if (!result.optBoolean("success")) return false
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putBoolean("requested:$taskId", true).commit()
        return true
    }

    /** An opted-in song may be the alarm itself; no awake acknowledgement is required. */
    @Synchronized fun onScheduledWake(context: Context, characterId: String, taskId: String, defer: Boolean): String {
        val permission = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        if (!permission.getBoolean("$characterId:wake_music_enabled", false) ||
            !permission.getBoolean("$characterId:media", false)) return "未授权音乐叫醒"
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val waitForCall = defer || LuluVoiceCallSession.state.value.connected
        if (prefs.getBoolean("requested:$taskId", false)) {
            if (waitForCall) {
                // A finite call retry must not talk over music previously started
                // by this wake task. Resume the same media session afterward.
                LuluMediaPlayback.control(context, "pause")
                prefs.edit().remove("requested:$taskId")
                    .putString(PENDING + characterId, taskId).commit()
                return "已暂停音乐，等电话结束后续播"
            }
            return "本次音乐已请求"
        }
        if (waitForCall) {
            prefs.edit().putString(PENDING + characterId, taskId).commit()
            deferredCharacter = characterId
            return "音乐将在通话结束后开始"
        }
        return play(context, taskId)
    }

    @Synchronized fun shouldSwitchAfterSpeech(context: Context?, characterId: String, taskId: String): Boolean =
        context != null && context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getString(PENDING + characterId, null) == taskId

    @Synchronized fun onCallEnded(context: Context?, characterId: String) {
        if (context == null || LuluVoiceCallSession.state.value.connected) return
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val pending = prefs.getString(PENDING + characterId, null)
        deferredCharacter = null
        if (pending != null) {
            prefs.edit().remove(PENDING + characterId).commit()
            play(context, pending)
        }
    }

    @Synchronized fun onMissedCall(context: Context?, characterId: String, taskId: String?) {
        if (context == null || taskId == null) return
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (prefs.getString(PENDING + characterId, null) == taskId) onCallEnded(context, characterId)
    }

    @Synchronized fun onDeclinedCall(context: Context?, characterId: String, taskId: String?) {
        if (context == null || taskId == null) return
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (prefs.getString(PENDING + characterId, null) == taskId) {
            prefs.edit().remove(PENDING + characterId).commit()
        }
    }

    private fun play(context: Context, taskId: String): String {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (prefs.getBoolean("requested:$taskId", false)) return "已请求播放"
        val response = org.json.JSONObject(LuluMediaPlayback.control(context, "play"))
        return if (response.optBoolean("accepted") || response.optBoolean("success")) {
            prefs.edit().putBoolean("requested:$taskId", true).commit()
            if (response.optBoolean("success")) "音乐已开始播放"
            else "音乐播放请求已发出，等待播放器响应"
        } else "音乐未播放：" + response.optString("error", "尚未发现可控制的播放器")
    }
}
