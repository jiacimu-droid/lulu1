package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import com.jiacimu.lulu.system.LuluScreenAwareness
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** The same persisted routing and evidence path is used by foreground, voice and background. */
object ToolRouter {
    private val mutex = KeyedWorkMutex()
    private val requests = KeyedWorkMutex()

    suspend fun execute(context: Context, characterId: String, name: String, args: JSONObject,
        requestId: String = UUID.randomUUID().toString(), userRequested: Boolean = false,
        intentionId: String = ""): String = requests.withLock("$characterId:$requestId") {
        // A slow read-only website request must not hold up an in-call favorite
        // or another short action. Exact request ids still serialize across tools.
        mutex.withLock("$characterId:" + when (name) {
            "read_xiaohongshu" -> "xhs"
            "web_search" -> "web"
            "image_search" -> "images"
            else -> "actions"
        }) {
            fun rejected(message: String, status: String = "failed"): String {
                // A rejected command did not run; do not mark any unrelated desire complete.
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
                    val saved = old.optString("result")
                    val receiptId = "tool-$characterId-$requestId"
                    if (old.optString("status") != "running" && saved.isNotBlank()) {
                        // A crash after effect persistence must not erase the receipt
                        // or cause a repeated side-effect when this request is retried.
                        if (SharedExperienceTimeline.eventsByIds(characterId, listOf(receiptId)).isEmpty()) {
                            SharedExperienceTimeline.record(receiptId, characterId,
                                "实际工具执行", "执行器", old.toString(),
                                triggerExtraction = false, source = "tool-router", taskId = requestId,
                                evidenceKind = EventEvidenceKind.ToolResult)
                        }
                        if (SharedExperienceTimeline.eventsByIds(characterId, listOf(receiptId)).isNotEmpty()) {
                            runCatching { JSONObject(saved) }.getOrNull()?.let { result ->
                                CharacterLifeStore.recordLinkedOutcome(characterId, intentionId, receiptId,
                                    name, result.optBoolean("success"),
                                    result.optString("summary").ifBlank { result.optString("error") })
                                CharacterInnerLifeStore.resolveActivityExpectation(characterId, receiptId,
                                    result.optString("evidenceId").ifBlank { receiptId }, result.optBoolean("success"),
                                    result.optString("summary").ifBlank { result.optString("error") })
                            }
                        }
                    }
                    return@withLock saved.ifBlank {
                        failure("上次执行中断，结果未知；为避免重复动作，需要核实", "waiting_user")
                    }
                }
            }
            // A future plan does not grant extra permissions; reject the wrong role, time
            // or tool before producing side effects, including Android phone tools.
            val scheduledPlanId = args.optString("planId")
            if (scheduledPlanId.isNotBlank() &&
                name !in setOf("reschedule_personal_activity", "cancel_personal_activity") &&
                !RoleDayScheduleStore.canBegin(context, characterId, scheduledPlanId, name, args))
                return@withLock rejected("这项日程已取消、未到期或所选工具与原计划不符")
            val goalReceiptId = "tool-$characterId-$requestId"
            CharacterLifeStore.markExecuting(characterId, intentionId, goalReceiptId, name)
            val execution = JSONObject().put("characterId", characterId).put("requestId", requestId)
                .put("tool", name).put("fingerprint", fingerprint).put("status", "running")
                .put("planId", scheduledPlanId).put("startedAt", Instant.now().toString())
            CharacterInnerLifeStore.initialize(context)
            CharacterInnerLifeStore.beginActivityExpectation(characterId, goalReceiptId, name,
                args.optJSONObject("expectation"))
            check(prefs.edit().putString(key, execution.toString()).commit())
            // Once accepted, complete and persist the actual effect even if speech is interrupted.
            withContext(NonCancellable) {
                val result = runCatching {
                    if (name == "cloud_task") {
                        val job = CloudTaskBridge.submit(characterId, args.getString("kind"),
                            JSONObject().put("request", args.optString("request")).put("sources", args.optJSONArray("sources") ?: org.json.JSONArray()).put("notes", args.optString("notes")), requestId)
                        JSONObject().put("success", true).put("status", "pending").put("requestId", job.getString("requestId"))
                            .put("summary", "已保存云端任务请求，尚未生成文件").toString()
                    } else if (name == "capture_screen") {
                        LuluScreenAwareness.capture(context, userRequested, characterId, requestId)
                    } else if (name == "summon_lulu") {
                        LuluScreenAwareness.summon(context, characterId)
                    } else if (cap.domain == "phone") {
                        LuluDeviceToolBridge.executeRegistered(context, characterId, name, args, requestId)
                    } else CompanionActionRuntime.execute(context, characterId, name, args).asJson()
                }.getOrElse { failure(it.message.orEmpty()) }
                val parsed = runCatching { JSONObject(result) }.getOrElse {
                    JSONObject().put("success", false).put("error", result.take(400))
                }
                if (!parsed.optBoolean("success") &&
                    parsed.optString("error").isBlank() && parsed.optString("summary").isBlank()) {
                    parsed.put("error", failureDetail(name, parsed))
                }
                val receiptId = "tool-$characterId-$requestId"
                // Prefer the actual outbound message/post. All other operations use
                // the authoritative tool receipt rather than a model description.
                if (parsed.optString("evidenceId").isBlank()) parsed.put("evidenceId", receiptId)
                val confirmedResult = parsed.toString()
                execution.put("status", parsed.optString("status").ifBlank { if (parsed.optBoolean("success")) "succeeded" else "failed" })
                    .put("result", confirmedResult).put("finishedAt", Instant.now().toString())
                check(prefs.edit().putString(key, execution.toString()).commit())
                if (!parsed.optBoolean("success")) {
                    com.jiacimu.lulu.LuluRepositories.performance.recordError(
                        source = "角色行动",
                        title = "${MigratedDomainStores.characters.get(characterId).displayName} · $name",
                        message = "角色ID=$characterId；执行ID=$requestId\n" +
                            parsed.optString("summary").ifBlank { parsed.optString("error").ifBlank { "执行器未确认成功" } },
                    )
                }
                SharedExperienceTimeline.record("tool-$characterId-$requestId", characterId, "实际工具执行", "执行器",
                    execution.toString(), source = "tool-router", taskId = requestId, evidenceKind = EventEvidenceKind.ToolResult)
                // Only the actual executor receipt can settle a plan. Pending consent,
                // screenshot requests and cloud jobs must NOT count as completion.
                if (parsed.optBoolean("success") &&
                    execution.optString("status") !in setOf("pending", "waiting_user", "requested", "running") &&
                    scheduledPlanId.isNotBlank()) {
                    runCatching {
                        RoleDayScheduleStore.completeFromReceipt(
                            context, characterId, scheduledPlanId, name,
                            parsed.optString("evidenceId"), parsed.optString("summary"),
                            Instant.now(), args)
                    }.onFailure { android.util.Log.w("RoleDaySchedule", "Plan receipt projection failed", it) }
                }
                CharacterInnerLifeStore.resolveActivityExpectation(characterId, receiptId,
                    parsed.optString("evidenceId").ifBlank { receiptId },
                    parsed.optBoolean("success"), parsed.optString("summary").ifBlank { parsed.optString("error") })
                val outcome = parsed
                // A delivered chat bubble does not automatically buy another five-minute
                // online lease. Only the character's next expiry decision can do that.
                val chatDelivery = name in setOf(
                    "send_private_message", "send_group_message",
                    "send_private_sticker", "send_group_sticker",
                )
                // An autonomous tool that finished while the role explicitly
                // chose offline cannot implicitly wake it for another five minutes.
                // During a real online window, activities may still refresh it.
                if (outcome.optBoolean("success") && !chatDelivery &&
                    CompanionOnlineStore.isOnline(characterId))
                    CompanionOnlineStore.recordActivity(characterId)
                CharacterLifeStore.recordLinkedOutcome(characterId, intentionId, goalReceiptId, name,
                    outcome.optBoolean("success"),
                    outcome.optString("summary").ifBlank { outcome.optString("error").ifBlank { result } })
                confirmedResult
            }
        }
    }

    /** Human-readable diagnostics even when native tools return status/verification only. */
    internal fun failureDetail(name: String, result: JSONObject): String {
        val available = listOf("error", "summary", "verification", "message",
            "detail", "reason", "status").firstNotNullOfOrNull { key ->
            result.optString(key).takeIf { it.isNotBlank() }
        }
        return (available ?: "工具${name}执行未成功，且执行器没有提供诊断字段").take(250)
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
