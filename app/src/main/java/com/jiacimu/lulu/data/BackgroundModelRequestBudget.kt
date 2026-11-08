package com.jiacimu.lulu.data

import android.content.Context
import java.security.MessageDigest
import java.time.Instant

/**
 * Causal idempotence gate for OPTIONAL background model POST attempts.
 *
 * This is deliberately NOT a daily user/memory/call quota. Every different
 * evidence payload remains eligible, including dozens of important owner
 * requests in one day. Only a near-identical paid request is deferred briefly
 * after an ambiguous timeout or invalid reply to avoid a provider retry storm.
 * A successful decision's normal source checkpoint is owned by its caller.
 */
internal object BackgroundModelRequestBudget {
    internal const val SAME_EVIDENCE_RETRY_MS = 15L * 60_000L
    private const val KEEP_ATTEMPTS_MS = 2L * 24L * 60 * 60_000L
    private const val PREFS = "lulu_background_model_requests_v1"
    private const val PREFIX = "evidence-attempt:"

    private fun optionalSource(source: String, title: String): Boolean =
        (source == "记忆" && title in setOf("重要信息即时记忆",
            "连续记忆提取", "语义召回核验")) ||
        source in setOf("生活记录", "承诺任务", "增量成长反思",
            "记忆有效性", "记忆维护", "向量索引")

    private fun key(role: String, source: String, title: String,
        evidence: String): String {
        val bytes = "$role\u0000$source\u0000$title\u0000$evidence"
            .toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    /** Legacy source-only preflight cannot distinguish separate user facts.
     * It must never reject a legitimate new message based on aggregate calls. */
    fun allowed(context: Context, role: String, source: String, title: String,
        now: Instant = Instant.now()): Boolean = true

    @Synchronized
    fun claim(context: Context, role: String, source: String, title: String,
        now: Instant = Instant.now(), evidence: String = ""): Boolean {
        if (!optionalSource(source, title) || role.isBlank() || evidence.isBlank())
            return true
        val preferences = context.applicationContext.getSharedPreferences(
            PREFS, Context.MODE_PRIVATE)
        val nowMs = now.toEpochMilli()
        val k = key(role, source, title, evidence)
        val last = preferences.getLong(k, 0L)
        if (last > 0 && nowMs >= last && nowMs - last < SAME_EVIDENCE_RETRY_MS)
            return false
        val editor = preferences.edit()
        val lastCleanup = preferences.getLong("last-evidence-cleanup", 0L)
        if (lastCleanup <= 0 || nowMs < lastCleanup ||
            nowMs - lastCleanup > 24L * 60 * 60_000L) {
            preferences.all.forEach { (savedKey, value) ->
                if (savedKey.startsWith(PREFIX) && value is Long &&
                    (value > nowMs || nowMs - value >= KEEP_ATTEMPTS_MS))
                    editor.remove(savedKey)
            }
            editor.putLong("last-evidence-cleanup", nowMs)
        }
        // Claim BEFORE a possibly billed network handoff, not after success.
        // No body, prompt text, credential or character identity is persisted.
        return editor.putLong(k, nowMs).commit()
    }

    fun retryAt(now: Instant = Instant.now()): Instant =
        now.plusMillis(SAME_EVIDENCE_RETRY_MS)
}

internal class BackgroundModelBudgetDeferred(val resumeAt: Instant) :
    IllegalStateException(
        "相同来源的后台模型请求刚刚尝试，暂停重复收费；原始证据与任务仍保留")
