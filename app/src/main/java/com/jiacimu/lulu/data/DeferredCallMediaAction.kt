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
        if (trigger !in setOf("call_end", "now")) return "没有可执行的音乐时间锚点"
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
        if (row.getString("trigger") == "now") enqueue(context, row)
        return "已登记真实媒体任务：${if (row.getString("trigger") == "call_end") "通话结束" else "从现在"}后约${row.getInt("delaySeconds")}秒播放；不保证播放器届时仍然可用"
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

    @Synchronized fun execute(context: Context, id: String): Boolean {
        val store = prefs(context)
        val row = store.getString(id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return false
        if (row.optString("status") !in setOf("queued", "waiting")) return false
        val charId = row.optString("characterId")
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
