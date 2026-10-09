package com.jiacimu.lulu.data

import android.content.Context

/** Durable chat turns wait for the last user bubble without cancelling already-running replies. */
internal object OnlineChatBatchStore {
    const val QUIET_MILLIS = 6_000L
    data class Batch(val revision: Long, val dueAtMillis: Long)
    private val reading = mutableSetOf<Pair<String, Long>>()
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("lulu_online_chat_batches", Context.MODE_PRIVATE)

    @Synchronized fun next(context: Context, characterId: String, collectMessages: Boolean, now: Long = System.currentTimeMillis()): Batch {
        val p = prefs(context)
        // The last user bubble determines when a queued turn can start.
        // Waking or autonomous ticks must NOT shorten/reset a user's deadline.
        // A message during a claimed batch belongs to a NEW reply revision.
        val pendingRevision = p.getLong("revision:$characterId", 0)
        if (p.contains("due:$characterId") && (characterId to pendingRevision) !in reading) {
            val hasUserBubbles = p.getBoolean("messageWindow:$characterId", false)
            if (collectMessages && !hasUserBubbles) {
                // An autonomous background tick may have left an immediate
                // non-message batch pending. The FIRST chat bubble MUST turn
                // that into a real six-second waiting window; never reuse the 0ms due.
                val due = now + QUIET_MILLIS
                check(p.edit().putLong("due:$characterId", due)
                    .putBoolean("messageWindow:$characterId", true).commit()) { "在线首条消息等待期保存失败" }
                return Batch(pendingRevision, due)
            }
            return Batch(pendingRevision, p.getLong("due:$characterId", now))
        }
        val revision = pendingRevision + 1
        val due = if (collectMessages) now + QUIET_MILLIS else now
        check(p.edit().putLong("revision:$characterId", revision).putLong("due:$characterId", due)
            .putBoolean("messageWindow:$characterId", collectMessages).commit()) { "在线消息批次保存失败" }
        return Batch(revision, due)
    }

    /** Extend the pending turn by six seconds from each newly persisted user bubble. */
    @Synchronized fun onUserBubble(
        context: Context, characterId: String, atMillis: Long = System.currentTimeMillis(),
    ): Batch {
        val p = prefs(context)
        val revision = p.getLong("revision:$characterId", 0L)
        if (p.contains("due:$characterId") && (characterId to revision) !in reading) {
            // This worker checks dueAt after every wait: a newly persisted
            // bubble restarts its silence clock even if the old due just passed.
            val due = atMillis + QUIET_MILLIS
            check(p.edit().putLong("due:$characterId", due)
                .putBoolean("messageWindow:$characterId", true).commit()) {
                "延长本轮消息静默等待失败"
            }
            return Batch(revision, due)
        }
        // If an earlier reply is already reading, the next bubble must not
        // be swallowed by that reader's unread cursor.
        return next(context, characterId, collectMessages = true, now = atMillis)
    }

    /** Claim under the perception mutex; pending messages during this reply open the next window. */
    @Synchronized fun claim(
        context: Context,
        characterId: String,
        revision: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val p = prefs(context)
        if (!isCurrent(context, characterId, revision) || !p.contains("due:$characterId")) return false
        // Closing the race between a sleeping worker and another incoming
        // bubble: even a worker with the correct revision cannot read early.
        if (now < p.getLong("due:$characterId", Long.MAX_VALUE)) return false
        return reading.add(characterId to revision)
    }

    /** A cancelled worker retains its durable deadline so WorkManager can resume the unread batch. */
    @Synchronized fun finish(context: Context, characterId: String, revision: Long, completed: Boolean = true) {
        reading.remove(characterId to revision)
        if (completed && isCurrent(context, characterId, revision)) {
            check(prefs(context).edit().remove("due:$characterId")
                .remove("messageWindow:$characterId").commit()) { "在线消息读取状态保存失败" }
        }
    }

    fun isCurrent(context: Context, characterId: String, revision: Long) =
        prefs(context).getLong("revision:$characterId", 0) == revision

    /** Independent self-reflection waits for an unfinished user-message turn. */
    @Synchronized fun pendingDueAt(context: Context, characterId: String): Long? =
        prefs(context).getLong("due:$characterId", 0L).takeIf { it > 0L }

    fun dueAt(context: Context, characterId: String, revision: Long): Long? =
        if (isCurrent(context, characterId, revision)) prefs(context).getLong("due:$characterId", 0L).takeIf { it > 0L }
        else null

    @Synchronized fun cancel(context: Context, characterId: String) {
        val p = prefs(context)
        reading.removeAll { it.first == characterId }
        check(p.edit().putLong("revision:$characterId", p.getLong("revision:$characterId", 0) + 1)
            .remove("due:$characterId").remove("messageWindow:$characterId").commit()) { "取消在线消息失败" }
    }
}
