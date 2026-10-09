package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

data class ProactiveIncomingCall(
    val characterId: String,
    val conversationId: String,
    val reason: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val commitmentTaskId: String? = null,
) {
    fun active(now: Instant = Instant.now()): Boolean = expiresAt.isAfter(now)
}

/** Durable hand-off between background autonomous actions, notifications and the chat/call UI. */
object ProactiveIncomingCallStore {
    private const val PREFS_NAME = "lulu_incoming_call_v1"
    private const val KEY_PENDING = "pending"
    private val lifetime = Duration.ofMinutes(2)
    private var prefs: android.content.SharedPreferences? = null
    private val mutablePending = MutableStateFlow<ProactiveIncomingCall?>(null)
    private val resolvedTaskCalls = mutableMapOf<String, Boolean>()
    val pending: StateFlow<ProactiveIncomingCall?> = mutablePending.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutablePending.value = decode(prefs?.getString(KEY_PENDING, null))
        if (mutablePending.value?.active() == false) reconcileExpired()
        mutablePending.value?.let { call -> com.jiacimu.lulu.LuluCallRingtone.startIncoming(context, call.conversationId + ":" + call.createdAt.toEpochMilli(), java.time.Duration.between(java.time.Instant.now(), call.expiresAt).toMillis()) }
        if (mutablePending.value == null) prefs?.edit()?.remove(KEY_PENDING)?.apply()
    }

    fun offer(
        context: Context,
        characterId: String,
        conversationId: String,
        reason: String,
        now: Instant = Instant.now(),
        commitmentTaskId: String? = null,
    ): ProactiveIncomingCall {
        initialize(context)
        reconcileExpired(now)
        commitmentTaskId?.let(resolvedTaskCalls::remove)
        val call = ProactiveIncomingCall(
            characterId = characterId,
            conversationId = conversationId,
            reason = reason.trim().take(300),
            createdAt = now,
            expiresAt = now.plus(lifetime),
            commitmentTaskId = commitmentTaskId,
        )
        mutablePending.value = call
        prefs?.edit()?.putString(KEY_PENDING, encode(call))?.apply()
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
        call.commitmentTaskId?.let { resolvedTaskCalls[it] = answered }
        clear(call)
        call.commitmentTaskId?.let { CommitmentCallFeedback.onResponse(it, answered) }
    }

    @Synchronized fun responseForTask(taskId: String): Boolean? = resolvedTaskCalls[taskId]

    /** Reconciled both by the visible UI and when a background alarm fires. */
    @Synchronized fun reconcileExpired(now: Instant = Instant.now()) {
        val call = mutablePending.value?.takeIf { !it.active(now) } ?: return
        clear(call)
        call.commitmentTaskId?.let(CommitmentCallFeedback::onMissed)
    }

    fun clear(call: ProactiveIncomingCall? = null) {
        val current = mutablePending.value
        if (call != null && current != null && current != call) return
        mutablePending.value = null
        prefs?.edit()?.remove(KEY_PENDING)?.apply()
        com.jiacimu.lulu.LuluCallRingtone.stopIncoming()
    }

    private fun encode(call: ProactiveIncomingCall): String = JSONObject()
        .put("characterId", call.characterId)
        .put("conversationId", call.conversationId)
        .put("reason", call.reason)
        .put("createdAt", call.createdAt.toEpochMilli())
        .put("expiresAt", call.expiresAt.toEpochMilli())
        .put("commitmentTaskId", call.commitmentTaskId ?: JSONObject.NULL)
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
        )
    }.getOrNull()
}
