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
    val pending: StateFlow<ProactiveIncomingCall?> = mutablePending.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutablePending.value = decode(prefs?.getString(KEY_PENDING, null))?.takeIf { it.active() }
        if (mutablePending.value == null) prefs?.edit()?.remove(KEY_PENDING)?.apply()
    }

    fun offer(
        context: Context,
        characterId: String,
        conversationId: String,
        reason: String,
        now: Instant = Instant.now(),
    ): ProactiveIncomingCall {
        initialize(context)
        val call = ProactiveIncomingCall(
            characterId = characterId,
            conversationId = conversationId,
            reason = reason.trim().take(300),
            createdAt = now,
            expiresAt = now.plus(lifetime),
        )
        mutablePending.value = call
        prefs?.edit()?.putString(KEY_PENDING, encode(call))?.apply()
        return call
    }

    fun activeFor(conversationId: String, now: Instant = Instant.now()): ProactiveIncomingCall? {
        val call = mutablePending.value ?: return null
        if (!call.active(now)) {
            clear()
            return null
        }
        return call.takeIf { it.conversationId == conversationId }
    }

    fun clear(call: ProactiveIncomingCall? = null) {
        val current = mutablePending.value
        if (call != null && current != null && current != call) return
        mutablePending.value = null
        prefs?.edit()?.remove(KEY_PENDING)?.apply()
    }

    private fun encode(call: ProactiveIncomingCall): String = JSONObject()
        .put("characterId", call.characterId)
        .put("conversationId", call.conversationId)
        .put("reason", call.reason)
        .put("createdAt", call.createdAt.toEpochMilli())
        .put("expiresAt", call.expiresAt.toEpochMilli())
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
        )
    }.getOrNull()
}
