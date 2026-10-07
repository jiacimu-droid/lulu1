package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** The same persisted routing and evidence path is used by foreground, voice and background. */
object ToolRouter {
    private val mutex = Mutex()

    suspend fun execute(context: Context, characterId: String, name: String, args: JSONObject,
        requestId: String = UUID.randomUUID().toString(), userRequested: Boolean = false): String = mutex.withLock {
        fun rejected(message: String, status: String = "failed"): String {
            CharacterLifeStore.recordOutcome(characterId, "denied-$characterId-$requestId", name, false, message)
            return failure(message, status)
        }
        val cap = CapabilityRegistry.find(name) ?: return@withLock rejected("未知能力：$name")
        if (!CapabilityRegistry.allows(context, characterId, cap, userRequested)) return@withLock rejected("此角色尚未获准主动使用 ${cap.permission}", "waiting_user")
        val prefs = context.getSharedPreferences("lulu_tool_executions", Context.MODE_PRIVATE)
        val key = "$characterId:$requestId"
        val fingerprint = "$name:${canonical(args)}"
        val old = prefs.getString(key, null)?.let(::JSONObject)
        if (old != null) {
            if (old.optString("fingerprint") != fingerprint) return@withLock failure("同一执行ID的参数发生变化，请重新确认", "waiting_user")
            if (old.optString("status") != "running" || !cap.retrySafe) {
                return@withLock old.optString("result").ifBlank { failure("上次执行中断，结果未知；为避免重复动作，需要核实", "waiting_user") }
            }
        }
        val execution = JSONObject().put("characterId", characterId).put("requestId", requestId)
            .put("tool", name).put("fingerprint", fingerprint).put("status", "running").put("startedAt", Instant.now().toString())
        check(prefs.edit().putString(key, execution.toString()).commit())
        // Once accepted, complete and persist the actual effect even if speech is interrupted.
        withContext(NonCancellable) {
            val result = runCatching {
                if (name == "cloud_task") {
                    val job = CloudTaskBridge.submit(characterId, args.getString("kind"),
                        JSONObject().put("request", args.optString("request")).put("sources", args.optJSONArray("sources") ?: org.json.JSONArray()).put("notes", args.optString("notes")), requestId)
                    JSONObject().put("success", true).put("status", "pending").put("requestId", job.getString("requestId"))
                        .put("summary", "已保存云端任务请求，尚未生成文件").toString()
                } else if (cap.domain == "phone") {
                    LuluDeviceToolBridge.executeRegistered(context, characterId, name, args, requestId)
                } else CompanionActionRuntime.execute(context, characterId, name, args).asJson()
            }.getOrElse { failure(it.message.orEmpty()) }
            val parsed = runCatching { JSONObject(result) }.getOrDefault(JSONObject())
            execution.put("status", parsed.optString("status").ifBlank { if (parsed.optBoolean("success")) "succeeded" else "failed" })
                .put("result", result).put("finishedAt", Instant.now().toString())
            check(prefs.edit().putString(key, execution.toString()).commit())
            SharedExperienceTimeline.record("tool-$characterId-$requestId", characterId, "实际工具执行", "执行器",
                execution.toString(), source = "tool-router", taskId = requestId, evidenceKind = EventEvidenceKind.ToolResult)
            val outcome = runCatching { JSONObject(result) }.getOrDefault(JSONObject())
            CharacterLifeStore.recordOutcome(characterId, "tool-$characterId-$requestId", name, outcome.optBoolean("success"),
                outcome.optString("summary").ifBlank { outcome.optString("error").ifBlank { result } })
            result
        }
    }

    private fun failure(message: String, status: String = "failed"): String = JSONObject().put("success", false).put("status", status).put("error", message).toString()
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
