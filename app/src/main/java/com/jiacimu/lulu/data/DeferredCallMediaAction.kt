package com.jiacimu.lulu.data

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jiacimu.lulu.system.LuluMediaPlayback
import com.jiacimu.lulu.system.LuluMediaVolume
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A character-authored deferred device action, not an automatic hang-up song.
 * The original telephone decision persists first; WorkManager survives UI exit.
 */
internal object DeferredCallMediaAction {
    private const val PREFS = "lulu_deferred_call_media_v1"
    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun validate(spec: JSONObject): String? {
        val trigger = spec.optString("trigger")
        val seconds = spec.optInt("delaySeconds", -1)
        if (trigger !in setOf("in_call", "call_end", "now")) return "没有可执行的音乐时间锚点"
        if (seconds !in 0..7200) return "音乐延时需要在0至7200秒之间"
        if (spec.optString("command", "play") != "play") return "延时音乐只允许播放已存在的媒体会话"
        val volume = spec.optInt("volumePercent", -1)
        if (volume !in -1..65) return "请求的音量超出安全范围"
        return null
    }

    @Synchronized fun register(
        context: Context, characterId: String, callId: String, turnId: String, spec: JSONObject,
    ): String {
        if (characterId.isBlank() || callId.isBlank() || turnId.isBlank())
            return "未安排播放：缺少真实通话或用户消息依据"
        validate(spec)?.let { return "未安排播放：$it" }
        if (!CapabilityRegistry.allows(context, characterId,
                requireNotNull(CapabilityRegistry.find("control_music")), false))
            return "未安排播放：没有给角色授权媒体控制"
        // A player has to expose a session before we can operate it. We do not
        // invent a streaming source or claim Lulu can launch a music catalog.
        val sessions = runCatching { JSONObject(LuluMediaPlayback.sessions(context)) }.getOrNull()
        if (sessions?.optBoolean("success") != true ||
            sessions.optInt("count", 0) == 0)
            return "未安排播放：未找到可控制的音乐播放器。先在音乐App播放一次并开启通知读取权限"
        val id = "$characterId:$callId:$turnId"
        if (prefs(context).contains(id)) return "已经登记这一次音乐安排，不重复创建"
        val row = JSONObject().put("id", id).put("characterId", characterId)
            .put("callId", callId).put("turnId", turnId)
            .put("trigger", spec.optString("trigger"))
            .put("delaySeconds", spec.optInt("delaySeconds"))
            .put("volumePercent", spec.optInt("volumePercent", -1))
            .put("status", "waiting")
            .put("createdAt", Instant.now().toString())
        if (!prefs(context).edit().putString(id, row.toString()).commit())
            return "未安排播放：手机本地保存失败"
        if (row.getString("trigger") != "call_end") enqueue(context, row)
        return "已登记真实媒体任务：${when (row.getString("trigger")) { "in_call" -> "本次通话仍连接时，从现在"; "call_end" -> "通话结束"; else -> "从现在" }}起约${row.getInt("delaySeconds")}秒后播放；实际播放器结果另行核验"
    }

    @Synchronized fun onCallEnded(context: Context, characterId: String, callId: String) {
        if (callId.isBlank()) return
        val store = prefs(context)
        store.all.values.filterIsInstance<String>().forEach { raw ->
            val row = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            if (row.optString("characterId") == characterId &&
                row.optString("callId") == callId &&
                row.optString("trigger") == "call_end" &&
                row.optString("status") == "waiting") enqueue(context, row)
        }
    }

    private fun enqueue(context: Context, row: JSONObject) {
        val id = row.getString("id")
        // Never mark enqueued until WorkManager accepted the concrete request.
        runCatching {
            val work = OneTimeWorkRequestBuilder<DeferredCallMediaWorker>()
                .setInitialDelay(row.optLong("delaySeconds", 0).coerceIn(0, 7200), TimeUnit.SECONDS)
                .setInputData(workDataOf("taskId" to id))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "lulu-deferred-media-$id", ExistingWorkPolicy.KEEP, work)
            prefs(context).edit().putString(id, row.put("status", "queued")
                .put("queuedAt", Instant.now().toString()).toString()).commit()
        }.onFailure {
            prefs(context).edit().putString(id, row.put("status", "failed")
                .put("result", "音乐任务调度失败：${it.message.orEmpty().take(120)}").toString()).commit()
        }
    }

    @Synchronized fun cancelCall(context: Context, characterId: String, callId: String) {
        val store = prefs(context)
        store.all.values.filterIsInstance<String>().forEach { raw ->
            val row = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            if (row.optString("characterId") == characterId &&
                row.optString("callId") == callId &&
                row.optString("trigger") == "in_call" &&
                row.optString("status") in setOf("queued", "waiting")) {
                store.edit().putString(row.optString("id"),
                    row.put("status", "cancelled").put("result", "通话结束；只限本次通话的音乐安排已取消").toString()).commit()
                WorkManager.getInstance(context).cancelUniqueWork("lulu-deferred-media-${row.optString("id")}")
            }
        }
    }

    private var activeMusicCallId: String? = null
    private var activeMusicCharacterId: String? = null
    private var interruptedByRoleSpeech: Boolean = false
    private var resumeApprovedByRole: Boolean = false
    private var activeMusicPackage: String = ""
    private var userPausedCallId: String? = null
    private var userPausedCharacterId: String? = null
    private var userPausedMusicPackage: String = ""

    /** One last check after Android returns audio focus to external players. */
    @Synchronized fun enforceStoppedAfterCall(context: Context, characterId: String, callId: String) {
        if (userPausedCallId != callId || userPausedCharacterId != characterId) return
        val selected = userPausedMusicPackage
        if (selected.isNotBlank()) {
            val snapshot = runCatching { JSONObject(LuluMediaPlayback.sessions(context)) }.getOrNull()
            val entries = snapshot?.optJSONArray("sessions")
            val resumed = (0 until (entries?.length() ?: 0)).mapNotNull { entries?.optJSONObject(it) }
                .any { it.optString("packageName") == selected && it.optBoolean("playing") }
            if (resumed) {
                // With the already-at-target guard a stale PAUSE cannot toggle
                // the session back on if it has stopped in the meantime.
                runCatching { LuluMediaPlayback.control(context, "pause", selected) }
            }
        }
        userPausedCallId = null
        userPausedCharacterId = null
        userPausedMusicPackage = ""
    }


    @Synchronized fun mayStartMusic(characterId: String, callId: String): Boolean =
        userPausedCallId != callId || userPausedCharacterId != characterId

    @Synchronized fun onWakeConfirmed(context: Context, characterId: String, callId: String) {
        val playerAtConfirmation = activeMusicPackage
        if (activeMusicCharacterId == characterId && activeMusicCallId == callId) {
            if (activeMusicPackage.isNotBlank())
                runCatching { LuluMediaPlayback.control(context, "pause", activeMusicPackage) }
            activeMusicCallId = null
            activeMusicCharacterId = null
            activeMusicPackage = ""
            interruptedByRoleSpeech = false
            resumeApprovedByRole = false
        }
        userPausedCallId = callId
        userPausedCharacterId = characterId
        userPausedMusicPackage = playerAtConfirmation.ifBlank { userPausedMusicPackage }
        cancelPlannedPlayback(context, characterId, callId)
        LuluMediaVolume.control(context, characterId, "restore")
    }

    @Synchronized fun cancelPlannedPlayback(context: Context, characterId: String, callId: String) {
        val store = prefs(context)
        store.all.values.filterIsInstance<String>().forEach { raw ->
            val row = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            if (row.optString("characterId") != characterId ||
                row.optString("callId") != callId ||
                row.optString("status") !in setOf("queued", "waiting")) return@forEach
            val id = row.optString("id")
            store.edit().putString(id, row.put("status", "cancelled")
                .put("result", "用户停止或结束本次叫醒；旧音乐任务已取消").toString()).commit()
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork("lulu-deferred-media-$id")
            }
        }
    }


    /**
     * Read playback state only. Understanding spoken wishes belongs to the
     * character's normal model turn, never a list of Chinese keywords here.
     */
    @Synchronized fun observeBeforeUserTurn(context: Context, characterId: String, callId: String) {
        if (activeMusicCharacterId != characterId || activeMusicCallId != callId) return
        resumeApprovedByRole = false
        // A pause made by our own TTS does not prove the user rejected music.
        if (interruptedByRoleSpeech) return
        val sessions = runCatching { JSONObject(LuluMediaPlayback.sessions(context)) }.getOrNull()
        if (sessions?.optBoolean("success") != true) return
        val entries = sessions.optJSONArray("sessions")
        val target = (0 until (entries?.length() ?: 0)).mapNotNull { entries?.optJSONObject(it) }
            .firstOrNull { it.optString("packageName") == activeMusicPackage }
        if (target?.optBoolean("playing") == true) return
        userPausedCallId = callId
        userPausedCharacterId = characterId
        userPausedMusicPackage = activeMusicPackage
        activeMusicCallId = null
        activeMusicCharacterId = null
        activeMusicPackage = ""
        interruptedByRoleSpeech = false
        cancelPlannedPlayback(context, characterId, callId)
    }

    /**
     * This is the structured choice from ONE genuinely new user turn.
     * "stop"/"play" is model interpretation, not lexical matching.
     * The actual player/permissions are still checked by their executors.
     */
    @Synchronized fun applyUserMediaUnderstanding(
        context: Context, characterId: String, callId: String, intent: String,
    ) {
        when (intent) {
            "stop" -> {
                userPausedCallId = callId
                userPausedCharacterId = characterId
                userPausedMusicPackage = activeMusicPackage.ifBlank { userPausedMusicPackage }
                resumeApprovedByRole = false
                cancelPlannedPlayback(context, characterId, callId)
                // If our character currently owns the active music, stop it;
                // mentioning a past pause does NOT reach this branch.
                if (activeMusicCharacterId == characterId && activeMusicCallId == callId) {
                    activeMusicPackage.takeIf(String::isNotBlank)?.let { target ->
                        runCatching { LuluMediaPlayback.control(context, "pause", target) }
                    }
                    activeMusicCallId = null
                    activeMusicCharacterId = null
                    activeMusicPackage = ""
                    interruptedByRoleSpeech = false
                }
            }
            "play" -> {
                // Only a *fresh* user utterance assessed in this role model
                // can lift the lock; an old stored tool decision cannot.
                userPausedCallId = null
                userPausedCharacterId = null
                userPausedMusicPackage = ""
            }
        }
    }

    /** Immediate phone media commands use the same ownership/cleanup as delayed music. */
    @Synchronized fun notePhoneMediaControl(
        characterId: String, callId: String, command: String, result: JSONObject,
    ) {
        if (!result.optBoolean("accepted") && !result.optBoolean("success")) return
        when (result.optString("command").ifBlank { command }.lowercase()) {
            "play" -> {
                activeMusicCallId = callId
                activeMusicCharacterId = characterId
                activeMusicPackage = result.optString("packageName")
                interruptedByRoleSpeech = false
                resumeApprovedByRole = false
            }
            "pause" -> if (activeMusicCallId == callId &&
                activeMusicCharacterId == characterId) {
                activeMusicCallId = null
                activeMusicCharacterId = null
                activeMusicPackage = ""
                interruptedByRoleSpeech = false
                resumeApprovedByRole = false
            }
        }
    }


    @Synchronized fun musicContext(characterId: String, callId: String): String = when {
        userPausedCallId == callId && userPausedCharacterId == characterId ->
            "本通电话的音乐已观测到暂停或用户决定停止。旧命令不能自动恢复；若有新的真实发言，请自行理解是否明确要求重新播放"
        activeMusicCharacterId == characterId && activeMusicCallId == callId ->
            "本通电话已有角色请求的音乐。若要继续或暂停，必须尊重当前播放器状态与对方新回应"
        else -> "本通电话尚无本系统成功请求的音乐"
    }

    @Synchronized fun chooseAfterSpeech(characterId: String, callId: String, resume: Boolean) {
        if (activeMusicCharacterId == characterId && activeMusicCallId == callId)
            resumeApprovedByRole = resume
    }

    @Synchronized fun stopAutoResume(characterId: String, callId: String) {
        if (activeMusicCharacterId == characterId && activeMusicCallId == callId) {
            interruptedByRoleSpeech = false
            resumeApprovedByRole = false
        }
    }

    @Synchronized fun beforeRoleSpeech(context: Context, characterId: String, callId: String) {
        if (activeMusicCallId != callId || activeMusicCharacterId != characterId) return
        val sessions = runCatching { JSONObject(LuluMediaPlayback.sessions(context)) }.getOrNull()
        val entries = sessions?.optJSONArray("sessions")
        val playing = (0 until (entries?.length() ?: 0)).mapNotNull { entries?.optJSONObject(it) }
            .firstOrNull { it.optString("packageName") == activeMusicPackage }
            ?.optBoolean("playing") == true
        if (!playing) {
            // A phone TTS turn never has permission to revive user-paused music.
            interruptedByRoleSpeech = false
            resumeApprovedByRole = false
            activeMusicCallId = null
            activeMusicCharacterId = null
            activeMusicPackage = ""
            return
        }
        val result = runCatching {
            JSONObject(LuluMediaPlayback.control(context, "pause", activeMusicPackage))
        }.getOrNull()
        interruptedByRoleSpeech = result?.optBoolean("accepted") == true ||
            result?.optBoolean("success") == true
    }

    @Synchronized fun afterRoleSpeech(context: Context, characterId: String, callId: String) {
        if (activeMusicCallId != callId || activeMusicCharacterId != characterId ||
            !interruptedByRoleSpeech) return
        if (!resumeApprovedByRole) {
            interruptedByRoleSpeech = false
            return // The character did not decide to turn music back on.
        }
        val result = runCatching {
            JSONObject(LuluMediaPlayback.control(context, "play", activeMusicPackage))
        }.getOrNull()
        interruptedByRoleSpeech = false
        resumeApprovedByRole = false // One approval cannot leak into another turn.
        if (result?.optBoolean("accepted") != true && result?.optBoolean("success") != true) {
            activeMusicCallId = null
        }
    }

    @Synchronized fun onCallDisconnected(context: Context, characterId: String, callId: String) {
        cancelCall(context, characterId, callId)
        if (activeMusicCallId == callId && activeMusicCharacterId == characterId) {
            // A song explicitly scoped to this call should not be left playing
            // after disconnect unless the role registered a separate post-call act.
            if (activeMusicPackage.isNotBlank()) {
                runCatching { LuluMediaPlayback.control(context, "pause", activeMusicPackage) }
            }
            LuluMediaVolume.control(context, characterId, "restore")
            activeMusicPackage = ""
            activeMusicCallId = null
            activeMusicCharacterId = null
            interruptedByRoleSpeech = false
            resumeApprovedByRole = false
        }
        // Keep the user's explicit stop through audio-focus restoration,
        // which finishes shortly AFTER the disconnection callback.
    }

    @Synchronized fun execute(context: Context, id: String): Boolean {
        val store = prefs(context)
        val row = store.getString(id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return false
        if (row.optString("status") !in setOf("queued", "waiting")) return false
        val charId = row.optString("characterId")
        if (row.optString("trigger") == "in_call") {
            val call = com.jiacimu.lulu.LuluVoiceCallSession.state.value
            if (!call.connected || call.characterId != charId ||
                call.callExperienceId != row.optString("callId")) {
                store.edit().putString(id,
                    row.put("status", "cancelled")
                        .put("result", "本次电话已经结束，未越过通话边界播放音乐").toString()).commit()
                return false
            }
        }
        if (row.optString("trigger") == "in_call" &&
            !mayStartMusic(charId, row.optString("callId"))) {
            store.edit().putString(id, row.put("status", "cancelled")
                .put("result", "用户已停止音乐，旧的延时播放不再执行").toString()).commit()
            return false
        }
        val volume = row.optInt("volumePercent", -1)
        if (volume >= 0) LuluMediaVolume.control(context, charId, "set", volume)
        val result = runCatching { JSONObject(LuluMediaPlayback.control(context, "play")) }
            .getOrElse { JSONObject().put("success", false).put("error", it.message) }
        val accepted = result.optBoolean("accepted") || result.optBoolean("success")
        // Asynchronous player callbacks are not a proof of music actually playing.
        val verified = if (accepted && !result.optBoolean("success")) {
            Thread.sleep(1000)
            val sessions = runCatching { JSONObject(LuluMediaPlayback.sessions(context)) }.getOrNull()
            val items = sessions?.optJSONArray("sessions")
            (0 until (items?.length() ?: 0)).any { n ->
                items?.optJSONObject(n)?.optBoolean("playing") == true &&
                    (result.optString("packageName").isBlank() ||
                        items.optJSONObject(n).optString("packageName") == result.optString("packageName"))
            }
        } else result.optBoolean("success")
        val actual = when {
            verified -> "播放器反馈正在播放；未直接检测扬声器是否有声"
            accepted -> "播放请求已发出，但播放器尚未确认播放"
            else -> "音乐没有播放：${result.optString("error", "找不到可控制的会话")}"
        }
        if (!accepted && volume >= 0) LuluMediaVolume.control(context, charId, "restore")
        if (row.optString("trigger") == "in_call" && accepted) {
            activeMusicCallId = row.optString("callId")
            activeMusicCharacterId = charId
            activeMusicPackage = result.optString("packageName")
            interruptedByRoleSpeech = false
            resumeApprovedByRole = false
        }
        val updated = row.put("status", if (verified) "verified" else if (accepted) "unverified" else "failed")
            .put("finishedAt", Instant.now().toString()).put("result", actual)
        store.edit().putString(id, updated.toString()).commit()
        runCatching {
            SharedExperienceTimeline.record(
                eventId = "deferred-media:$id", characterId = charId,
                channel = "手机音乐行动", speaker = "设备执行器",
                content = actual, source = "deferred-call-media",
                evidenceKind = EventEvidenceKind.ToolResult, triggerExtraction = false)
        }
        return verified
    }
}

class DeferredCallMediaWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result {
        val id = inputData.getString("taskId") ?: return Result.failure()
        DeferredCallMediaAction.execute(applicationContext, id)
        // A verified=false response has already been persisted. Do not
        // blind-retry and unexpectedly play music later.
        return Result.success()
    }
}
