package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ProactiveIncomingCall(
    val characterId: String,
    val conversationId: String,
    val reason: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val commitmentTaskId: String? = null,
    /** Explicit character choice. Existing saved invitations remain voice calls. */
    val mediaMode: String = "voice",
) {
    init { require(mediaMode == "voice" || mediaMode == "video") }
    fun active(now: Instant = Instant.now()): Boolean = expiresAt.isAfter(now)
}

/** Durable hand-off between background autonomous actions, notifications and the chat/call UI. */
object ProactiveIncomingCallStore {
    private const val PREFS_NAME = "lulu_incoming_call_v1"
    private const val KEY_PENDING = "pending"
    private val lifetime = Duration.ofMinutes(2)
    private var prefs: android.content.SharedPreferences? = null
    private var application: Context? = null
    private val mutablePending = MutableStateFlow<ProactiveIncomingCall?>(null)
    private val resolvedTaskCalls = mutableMapOf<String, Boolean>()
    val pending: StateFlow<ProactiveIncomingCall?> = mutablePending.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        application = context.applicationContext
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutablePending.value = decode(prefs?.getString(KEY_PENDING, null))
        if (mutablePending.value?.active() == false) reconcileExpired()
        mutablePending.value?.let { call -> com.jiacimu.lulu.LuluCallRingtone.startIncoming(context, call.conversationId + ":" + call.createdAt.toEpochMilli(), java.time.Duration.between(java.time.Instant.now(), call.expiresAt).toMillis()) }
        if (mutablePending.value == null) prefs?.edit()?.remove(KEY_PENDING)?.apply()
    }

    @Synchronized fun offer(
        context: Context,
        characterId: String,
        conversationId: String,
        reason: String,
        now: Instant = Instant.now(),
        commitmentTaskId: String? = null,
        mediaMode: String = "voice",
    ): ProactiveIncomingCall {
        require(mediaMode == "voice" || mediaMode == "video") { "来电类型必须是 voice 或 video" }
        initialize(context)
        reconcileExpired(now)
        // Never silently erase a previous invitation when two calls overlap.
        mutablePending.value?.let { previous ->
            check(previous.active(now)) { "过期来电未能保存回执，不能覆盖原始记录" }
            recordUnconnectedCall(previous, "replaced")
            clear(previous)
            previous.commitmentTaskId?.let(CommitmentCallFeedback::onMissed)
        }
        commitmentTaskId?.let(resolvedTaskCalls::remove)
        val call = ProactiveIncomingCall(
            characterId = characterId,
            conversationId = conversationId,
            reason = reason.trim().take(300),
            createdAt = now,
            expiresAt = now.plus(lifetime),
            commitmentTaskId = commitmentTaskId,
            mediaMode = mediaMode,
        )
        check(prefs?.edit()?.putString(KEY_PENDING, encode(call))?.commit() == true) { "来电状态未能保存" }
        mutablePending.value = call
        com.jiacimu.lulu.LuluCallRingtone.startIncoming(context, call.conversationId + ":" + call.createdAt.toEpochMilli(), lifetime.toMillis())
        return call
    }

    fun activeFor(conversationId: String, now: Instant = Instant.now()): ProactiveIncomingCall? {
        val call = mutablePending.value ?: return null
        if (!call.active(now)) {
            reconcileExpired(now)
            return null
        }
        return call.takeIf { it.conversationId == conversationId }
    }

    @Synchronized fun respond(call: ProactiveIncomingCall, answered: Boolean) {
        if (mutablePending.value != call) return
        // User refusal is not a timeout, nor a successfully connected call.
        // A storage error must never trap the owner behind an unclosable
        // incoming-call overlay. Other outcomes retry before clearing instead.
        if (!answered) runCatching { recordUnconnectedCall(call, "declined") }
            .onFailure { android.util.Log.w("LuluIncomingCall", "未能保存拒接回执", it) }
        call.commitmentTaskId?.let { resolvedTaskCalls[it] = answered }
        clear(call)
        call.commitmentTaskId?.let { CommitmentCallFeedback.onResponse(it, answered) }
        if (!answered) WakeMusicCoordinator.onDeclinedCall(
            application, call.characterId, call.commitmentTaskId,
        )
    }

    @Synchronized fun responseForTask(taskId: String): Boolean? = resolvedTaskCalls[taskId]

    /** Reconciled both by the visible UI and when a background alarm fires. */
    @Synchronized fun reconcileExpired(now: Instant = Instant.now()) {
        val call = mutablePending.value?.takeIf { !it.active(now) } ?: return
        // Persist a deduplicated real outcome BEFORE deleting the only pending
        // snapshot. If chat storage fails, leave the expired offer for retry.
        if (runCatching { recordUnconnectedCall(call, "expired") }
                .onFailure { android.util.Log.w("LuluIncomingCall", "未能保存未接来电，保留待恢复", it) }
                .isFailure) return
        clear(call)
        call.commitmentTaskId?.let(CommitmentCallFeedback::onMissed)
        WakeMusicCoordinator.onMissedCall(application, call.characterId, call.commitmentTaskId)
    }

    @Synchronized fun clear(call: ProactiveIncomingCall? = null) {
        val current = mutablePending.value
        if (call != null && current != null && current != call) return
        mutablePending.value = null
        prefs?.edit()?.remove(KEY_PENDING)?.commit()
        current?.let { active -> application?.let { ProactivePerceptionRuntime.cancelCallNotification(it, active.conversationId) } }
        com.jiacimu.lulu.LuluCallRingtone.stopIncoming()
    }

    /** A phone offer never proves the Android banner or ringtone was delivered.
     * A system receipt enters the existing chat + shared experience timeline,
     * without paying for another autonomous model turn.
     */
    private fun recordUnconnectedCall(call: ProactiveIncomingCall, outcome: String) {
        val context = application ?: error("主动来电上下文未初始化")
        MigratedDomainStores.chat.initialize(context)
        val conversation = MigratedDomainStores.chat.conversations.value.firstOrNull {
            it.id == call.conversationId && it.groupChat == null && it.characterId == call.characterId
        } ?: return // Do not resurrect a deleted conversation.
        val time = DateTimeFormatter.ofPattern("M月d日 HH:mm")
            .withZone(ZoneId.systemDefault()).format(call.createdAt)
        val media = if (call.mediaMode == "video") "视频" else "语音"
        val detail = when (outcome) {
            "declined" -> "已拒接（用户选择拒接，未建立通话）"
            "replaced" -> "未接通（被下一通来电替换）"
            else -> "未接通（来电邀请超时，无接听记录）"
        }
        val fact = "[共同活动] $time ${conversation.title}发起的${media}来电${detail}；手机来电通知实际显示与否尚未确认"
        MigratedDomainStores.chat.appendSystemReceipt(
            call.conversationId,
            "incoming-call-unconnected-${call.characterId}-${call.createdAt.toEpochMilli()}",
            fact,
        )
    }

    private fun encode(call: ProactiveIncomingCall): String = JSONObject()
        .put("characterId", call.characterId)
        .put("conversationId", call.conversationId)
        .put("reason", call.reason)
        .put("createdAt", call.createdAt.toEpochMilli())
        .put("expiresAt", call.expiresAt.toEpochMilli())
        .put("commitmentTaskId", call.commitmentTaskId ?: JSONObject.NULL)
        .put("mediaMode", call.mediaMode)
        .toString()

    private fun decode(raw: String?): ProactiveIncomingCall? = runCatching {
        val json = JSONObject(raw ?: return@runCatching null)
        val characterId = json.optString("characterId").trim()
        val conversationId = json.optString("conversationId").trim()
        if (characterId.isBlank() || conversationId.isBlank()) return@runCatching null
        ProactiveIncomingCall(
            characterId = characterId,
            conversationId = conversationId,
            reason = json.optString("reason").trim(),
            createdAt = Instant.ofEpochMilli(json.optLong("createdAt")),
            expiresAt = Instant.ofEpochMilli(json.optLong("expiresAt")),
            commitmentTaskId = if (json.isNull("commitmentTaskId")) null
                else json.optString("commitmentTaskId").takeIf(String::isNotBlank),
            mediaMode = json.optString("mediaMode", "voice").takeIf { it == "video" } ?: "voice",
        )
    }.getOrNull()
}
