package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluVoiceCallSession
import com.jiacimu.lulu.system.LuluMediaPlayback
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Optional, evidence-gated wake-up music. Never starts on an unanswered alarm. */
internal object WakeMusicCoordinator {
    private const val STORE = "lulu_wake_music_actions"
    private const val PENDING = "pending:"
    private var deferredCharacter: String? = null
    internal data class ArmedWakeMusic(val volumePercent: Int?)

    /** Model-authored wake task completion already verified actual user evidence. */
    @Synchronized fun onModelConfirmedWake(context: Context, characterId: String) {
        com.jiacimu.lulu.system.LuluMediaVolume.control(context, characterId, "restore")
    }

    /** Immediate music is only called for a verified character-selected action. */
    suspend fun playImmediately(context: Context, characterId: String, taskId: String): Boolean {
        val permission = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        if (!permission.getBoolean("$characterId:wake_music_enabled", false) ||
            !permission.getBoolean("$characterId:media", false)) return false
        val result = runCatching { JSONObject(LuluMediaPlayback.control(context, "play")) }
            .getOrNull() ?: return false
        if (!result.optBoolean("accepted") && !result.optBoolean("success")) return false

        // MediaController dispatch is asynchronous. Re-read the EXACT session
        // instead of treating the original, possibly stale state as a failure
        // or claiming that an accepted command means music is audible.
        val packageName = result.optString("packageName")
        if (!result.optBoolean("success")) delay(850L)
        val sessions = runCatching { JSONObject(LuluMediaPlayback.sessions(context)) }.getOrNull()
        val array = sessions?.optJSONArray("sessions")
        val verified = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
            .any { it.optString("packageName") == packageName &&
                packageName.isNotBlank() && it.optBoolean("playing") }
        if (!verified) return false
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putBoolean("requested:$taskId", true).commit()
        return true
    }

    /** A role-selected music wake survives the incoming call UI and app restart,
     * but it is valid only for the actual answered call with this task ID. */
    @Synchronized fun takeAnsweredMusicPlan(
        context: Context, characterId: String, taskId: String?,
    ): ArmedWakeMusic? {
        if (taskId.isNullOrBlank()) return null
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val raw = prefs.getString(PENDING + characterId, "").orEmpty()
        val plan = runCatching { JSONObject(raw) }.getOrNull()
        val matches = (plan?.optString("taskId") ?: raw) == taskId
        if (!matches) return null
        prefs.edit().remove(PENDING + characterId).commit()
        return ArmedWakeMusic(plan?.optInt("volumePercent", -1)?.takeIf { it in 0..65 })
    }

    /**
     * Wake music can be an explicit character action while the call is live.
     * Never put a surprise PLAY into a generic on-hangup callback. A separate
     * model-authored call_end plan has its own executor and durable receipt.
     */
    @Synchronized fun onScheduledWake(
        context: Context, characterId: String, taskId: String, defer: Boolean,
        volumePercent: Int? = null,
    ): String {
        val permission = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        if (!permission.getBoolean("$characterId:wake_music_enabled", false) ||
            !permission.getBoolean("$characterId:media", false)) return "未授权音乐叫醒"
        val callActive = LuluVoiceCallSession.state.value.connected
        if (defer || callActive) {
            // Only an explicitly selected music cue can create this pending
            // handoff. Actual playback requires an answered call and current
            // permission, never a missed-call or hangup callback.
            val editor = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            if (defer && !callActive && taskId.isNotBlank()) {
                editor.putString(PENDING + characterId, JSONObject().put("taskId", taskId)
                    .put("volumePercent", volumePercent?.takeIf { it in 0..65 } ?: JSONObject.NULL).toString()).commit()
                return "角色选择音乐叫醒，接通后尝试播放现有播放器；未接听不播放"
            }
            editor.remove(PENDING + characterId).commit()
            return "当前电话内由已执行的唤醒动作负责，不在挂断时自动续播"
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
