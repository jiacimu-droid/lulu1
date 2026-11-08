package com.jiacimu.lulu.data

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Single paid-request authority for low-priority autonomous maintenance.
 *
 * The main character decision, genuine user turn, resident's own planning
 * and appointments are NOT background housekeeping and are never throttled
 * here. A deferred extraction must retain its original source/checkpoint.
 *
 * Limits count HTTP *attempts*, not successful generations: ambiguous timeouts
 * may already be charged by a relay. No user text, prompts, or API keys persist.
 */
internal object BackgroundModelRequestBudget {
    private const val PREFS = "lulu_background_model_requests_v1"
    internal const val DAILY_TOTAL_LIMIT = 8
    private const val COUNT = "count:"
    private const val DAY = "day:"

    /** Only sources with a durable recovery route or optional semantic
     * housekeeping belong here. An ordinary chat/phone/world decision does not. */
    internal fun category(source: String, title: String): String? = when {
        source == "记忆" && title == "重要信息即时记忆" -> "priority_memory"
        source == "记忆" && title == "连续记忆提取" -> "batch_memory"
        source == "生活记录" -> "life_records"
        source == "承诺任务" -> "commitments"
        source == "增量成长反思" -> "growth"
        source == "记忆有效性" -> "supersession"
        source == "记忆维护" -> "weekly_memory"
        source == "记忆" && title == "语义召回核验" -> "semantic_check"
        else -> null
    }

    internal fun categoryLimit(category: String): Int = when (category) {
        "priority_memory" -> 3
        "batch_memory" -> 4
        "life_records" -> 2
        "commitments" -> 2
        "growth" -> 1
        "supersession" -> 2
        "weekly_memory" -> 2
        "semantic_check" -> 2
        else -> 0
    }

    private fun localDay(now: Instant): String =
        now.atZone(ZoneId.systemDefault()).toLocalDate().toString()

    internal fun nextDay(now: Instant): Instant =
        now.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun used(context: Context, role: String, category: String, now: Instant): Int {
        val storage = prefs(context)
        val date = localDay(now)
        if (storage.getString(DAY + role, "") != date) return 0
        return storage.getInt(COUNT + role + ":" + category, 0).coerceAtLeast(0)
    }

    @Synchronized
    fun allowed(context: Context, role: String, source: String, title: String,
        now: Instant = Instant.now()): Boolean {
        val category = category(source, title) ?: return true
        if (role.isBlank()) return false
        val storage = prefs(context)
        val date = localDay(now)
        if (storage.getString(DAY + role, "") != date) return true
        val total = storage.getInt(COUNT + role + ":total", 0).coerceAtLeast(0)
        return total < DAILY_TOTAL_LIMIT &&
            used(context, role, category, now) < categoryLimit(category)
    }

    /** Atomic claim immediately before the first HTTP handoff. No reset of
     * pending work or semantic decision is implied if this returns false. */
    @Synchronized
    fun claim(context: Context, role: String, source: String, title: String,
        now: Instant = Instant.now()): Boolean {
        val category = category(source, title) ?: return true
        if (!allowed(context, role, source, title, now)) return false
        val storage = prefs(context)
        val date = localDay(now)
        val freshDay = storage.getString(DAY + role, "") != date
        val total = if (freshDay) 0 else storage.getInt(COUNT + role + ":total", 0)
        val categoryCount = if (freshDay) 0 else
            storage.getInt(COUNT + role + ":" + category, 0)
        val editor = storage.edit()
        if (freshDay) {
            // Last-day counts must not leak into a new day. Store only bounded
            // category keys, never enumerate user content or conversation IDs.
            for (old in listOf("priority_memory", "batch_memory", "life_records",
                "commitments", "growth", "supersession", "weekly_memory", "semantic_check")) {
                editor.remove(COUNT + role + ":" + old)
            }
        }
        return editor.putString(DAY + role, date)
            .putInt(COUNT + role + ":total", total + 1)
            .putInt(COUNT + role + ":" + category, categoryCount + 1)
            .commit()
    }
}

internal class BackgroundModelBudgetDeferred(val resumeAt: Instant) :
    IllegalStateException("后台模型额外请求达到当日限额，原始证据与任务保留，下一预算日继续")
