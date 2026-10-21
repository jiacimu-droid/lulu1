package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluVoiceCallSession
import com.jiacimu.lulu.system.LuluMediaPlayback

/** Optional, evidence-gated wake-up music. Never starts on an unanswered alarm. */
internal object WakeMusicCoordinator {
    private const val STORE = "lulu_wake_music_actions"
    private const val PENDING = "pending:"
    private var deferredCharacter: String? = null

    /** Model-authored wake task completion already verified actual user evidence. */
    @Synchronized fun onModelConfirmedWake(context: Context, characterId: String) {
        com.jiacimu.lulu.system.LuluMediaVolume.control(context, characterId, "restore")
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

    /**
     * Wake music can be an explicit character action while the call is live.
     * Never put a surprise PLAY into a generic on-hangup callback. A separate
     * model-authored call_end plan has its own executor and durable receipt.
     */
    @Synchronized fun onScheduledWake(context: Context, characterId: String, taskId: String, defer: Boolean): String {
        val permission = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        if (!permission.getBoolean("$characterId:wake_music_enabled", false) ||
            !permission.getBoolean("$characterId:media", false)) return "未授权音乐叫醒"
        val callActive = LuluVoiceCallSession.state.value.connected
        if (defer || callActive) {
            // The live call's model may choose music, volume or silence in
            // its next genuine observation. End-call is not such a choice.
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .remove(PENDING + characterId).commit()
            return "本次电话不会在挂断时自动续播；接通后由角色自主决定音乐行动"
        }
        return play(context, taskId)
    }

    @Synchronized fun shouldSwitchAfterSpeech(context: Context?, characterId: String, taskId: String): Boolean = false

    @Synchronized fun onCallEnded(context: Context?, characterId: String) {
        // An old pending key from previous versions is NOT fresh permission.
        context?.getSharedPreferences(STORE, Context.MODE_PRIVATE)?.edit()
            ?.remove(PENDING + characterId)?.commit()
        deferredCharacter = null
    }

    @Synchronized fun onMissedCall(context: Context?, characterId: String, taskId: String?) {
        // An unanswered call is not authorization to start music after hang-up.
        onCallEnded(context, characterId)
    }

    @Synchronized fun onDeclinedCall(context: Context?, characterId: String, taskId: String?) {
        onCallEnded(context, characterId)
    }

    @Synchronized fun cancelPending(context: Context, characterId: String) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .remove(PENDING + characterId).commit()
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
