package com.jiacimu.lulu.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jiacimu.lulu.LuluVoiceCallSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The existing asynchronous phone search now survives interruption and returns real evidence. */
class PhoneXhsReadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        initializeBackgroundRuntime(applicationContext)
        val role = inputData.getString("characterId") ?: return Result.failure()
        val sourceId = inputData.getString("sourceId") ?: return Result.failure()
        val conversation = inputData.getString("conversationId") ?: return Result.failure()
        val source = SharedExperienceTimeline.eventsByIds(role, listOf(sourceId)).firstOrNull()
            ?.takeIf { it.evidenceKind == EventEvidenceKind.UserStatement && it.sessionId == conversation }
            ?: return Result.success()
        if (source.revision != inputData.getLong("revision", -1)) return Result.success()
        val requestId = "call-xhs-$sourceId"
        val raw = ToolRouter.execute(applicationContext, role, "read_xiaohongshu",
            JSONObject(inputData.getString("args") ?: "{}"), requestId, userRequested = true)
        val result = runCatching { JSONObject(raw) }.getOrNull()
        val receiptId = "tool-$role-$requestId"
        if (SharedExperienceTimeline.eventsByIds(role, listOf(sourceId)).firstOrNull() != source) {
            // An old request cannot reintroduce its reading after the user removes its basis.
            result?.optString("evidenceId")?.takeIf { it.isNotBlank() && it != receiptId }
                ?.let(SharedExperienceTimeline::deleteEvent)
            SharedExperienceTimeline.deleteEvent(receiptId)
            return Result.success()
        }
        if (MigratedDomainStores.chat.conversations.value.none { it.id == conversation }) return Result.success()
        val success = result?.optBoolean("success") == true
        val detail = result?.optString("summary").orEmpty().ifBlank { result?.optString("error").orEmpty() }
            .replace(Regex("\\s+"), " ").take(160)
        MigratedDomainStores.chat.appendSystemReceipt(conversation, "xhs-result-$sourceId",
            if (success) "小红书 · 实际已读：$detail" else "小红书 · 本次未读到：$detail")
        withContext(Dispatchers.Main) {
            LuluVoiceCallSession.onExternalToolResult(role, inputData.getString("callId").orEmpty(), receiptId)
        }
        // When the original call has ended, normal shared perception can use the result.
        ImportantEventBridge.wake(applicationContext, role, "重要事件·电话请求的小红书检索已有真实回执")
        return Result.success()
    }

    companion object {
        internal fun enqueue(context: Context, role: String, conversation: String, callId: String,
            sourceId: String, request: XhsExplicitReadRequest): Boolean {
            val source = SharedExperienceTimeline.eventsByIds(role, listOf(sourceId)).firstOrNull()
                ?.takeIf { it.evidenceKind == EventEvidenceKind.UserStatement && it.sessionId == conversation }
                ?: return false
            val args = JSONObject().apply {
                if (request.url.isNotBlank()) put("url", request.url) else put("keyword", request.keyword)
            }
            val work = OneTimeWorkRequestBuilder<PhoneXhsReadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(Data.Builder().putString("characterId", role).putString("conversationId", conversation)
                    .putString("callId", callId).putString("sourceId", sourceId).putLong("revision", source.revision)
                    .putString("args", args.toString()).build()).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "lulu-phone-xhs-$role-$sourceId", ExistingWorkPolicy.KEEP, work)
            return true
        }
    }
}
